package systems.greynet.blockcheckw.pipeline

import arrow.core.Either
import kotlinx.coroutines.*
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

    val results = mutableListOf<StrategyResult>()

    try {
        // Разбиваем стратегии на batch-и по workerCount
        val batches = strategies.chunked(config.workerCount)

        for (batch in batches) {
            val batchResults = runBlocking {
                batch.mapIndexed { index, strategyArgs ->
                    val slot = slots[index]
                    val task = WorkerTask(
                        slot = slot,
                        domain = domain,
                        strategyArgs = strategyArgs,
                        protocol = protocol,
                        ips = ips,
                    )
                    async(Dispatchers.Default) {
                        StrategyResult(strategyArgs, executeWorker(task))
                    }
                }.awaitAll()
            }

            for (r in batchResults) {
                results.add(r)
                println("  [worker] ${r.strategyArgs.joinToString(" ")}")
                r.result.fold(
                    ifLeft = { error -> println("    ERROR: ${strategyTestErrorToString(error)}") },
                    ifRight = { testResult ->
                        println("    ${strategyTestResultToString(testResult)}")
                    },
                )
            }
        }
    } finally {
        dropTable()
    }

    return results
}
