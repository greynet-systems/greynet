@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package systems.greynet.blockcheckw.pipeline

import arrow.core.Either
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.*
import platform.posix.*
import systems.greynet.blockcheckw.firewall.*
import systems.greynet.blockcheckw.network.*

data class ParallelConfig(
    val workerCount: Int = 8,
    val baseQnum: Int = 200,
    val baseLocalPort: Int = 30000,
)

data class StrategyResult(
    val strategyArgs: List<String>,
    val result: Either<StrategyTestError, StrategyTestResult>,
)

private class SharedContext(
    val domain: String,
    val protocol: Protocol,
    val strategies: List<List<String>>,
    val ips: List<String>,
    val nextIndex: AtomicInt,
    val results: Array<StrategyResult?>,
    val printMutex: CPointer<pthread_mutex_t>,
)

private class ThreadArg(
    val slot: WorkerSlot,
    val shared: SharedContext,
)

private fun printResult(r: StrategyResult, mutex: CPointer<pthread_mutex_t>) {
    pthread_mutex_lock(mutex)
    try {
        println("  [worker] ${r.strategyArgs.joinToString(" ")}")
        r.result.fold(
            ifLeft = { error -> println("    ERROR: ${strategyTestErrorToString(error)}") },
            ifRight = { testResult ->
                println("    ${strategyTestResultToString(testResult)}")
            },
        )
    } finally {
        pthread_mutex_unlock(mutex)
    }
}

private val threadEntry: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>> =
    staticCFunction { arg: COpaquePointer? ->
        val ref = arg!!.asStableRef<ThreadArg>()
        val threadArg = ref.get()
        val slot = threadArg.slot
        val ctx = threadArg.shared

        while (true) {
            val idx = ctx.nextIndex.fetchAndAdd(1)
            if (idx >= ctx.strategies.size) break

            val strategyArgs = ctx.strategies[idx]

            val task = WorkerTask(
                slot = slot,
                domain = ctx.domain,
                strategyArgs = strategyArgs,
                protocol = ctx.protocol,
                ips = ctx.ips,
            )

            val result = StrategyResult(strategyArgs, executeWorker(task))
            ctx.results[idx] = result
            printResult(result, ctx.printMutex)
        }

        null
    }

fun runParallel(
    domain: String,
    protocol: Protocol,
    strategies: List<List<String>>,
    ips: List<String>,
    config: ParallelConfig = ParallelConfig(),
): List<StrategyResult> {
    val slots = (0 until config.workerCount).map { i ->
        WorkerSlot(
            id = i,
            qnum = config.baseQnum + i,
            localPort = config.baseLocalPort + i,
        )
    }

    // Создаём таблицу один раз
    prepareTable().onLeft { err ->
        return strategies.map { args ->
            StrategyResult(args, Either.Left(StrategyTestError.Nftables(err)))
        }
    }

    val results = arrayOfNulls<StrategyResult>(strategies.size)

    try {
        memScoped {
            val mutex = alloc<pthread_mutex_t>()
            pthread_mutex_init(mutex.ptr, null)

            val shared = SharedContext(
                domain = domain,
                protocol = protocol,
                strategies = strategies,
                ips = ips,
                nextIndex = AtomicInt(0),
                results = results,
                printMutex = mutex.ptr,
            )

            val threadCount = config.workerCount
            val stableRefs = slots.map { slot ->
                StableRef.create(ThreadArg(slot, shared))
            }
            val threads = allocArray<ULongVar>(threadCount)

            for (i in 0 until threadCount) {
                val threadPtr = (threads + i)!!
                pthread_create(threadPtr, null, threadEntry, stableRefs[i].asCPointer())
            }

            for (i in 0 until threadCount) {
                pthread_join(threads[i], null)
            }

            stableRefs.forEach { it.dispose() }
            pthread_mutex_destroy(mutex.ptr)
        }
    } finally {
        dropTable()
    }

    return results.map { it ?: error("unreachable: result slot not filled") }
}
