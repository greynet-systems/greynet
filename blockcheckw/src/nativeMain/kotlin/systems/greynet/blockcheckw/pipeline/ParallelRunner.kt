package systems.greynet.blockcheckw.pipeline

import arrow.core.Either
import kotlinx.coroutines.*
import systems.greynet.blockcheckw.firewall.*
import systems.greynet.blockcheckw.network.*
import systems.greynet.blockcheckw.system.ProgressBar

private const val PORTS_PER_WORKER = 10

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
    testFunc: String = protocol.toTestFunc(),
): List<StrategyResult> {
    val slots = (0 until config.workerCount).map { i ->
        val portStart = config.baseLocalPort + i * PORTS_PER_WORKER
        WorkerSlot(
            id = i,
            qnum = config.baseQnum + i,
            localPortRange = portStart..(portStart + PORTS_PER_WORKER - 1),
        )
    }

    // Создаём таблицу один раз
    prepareTable().onLeft { err ->
        return strategies.map { args ->
            StrategyResult(args, Either.Left(StrategyTestError.Nftables(err)))
        }
    }

    val results = mutableListOf<StrategyResult>()

    val progressBar = ProgressBar(strategies.size)

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

            progressBar.clear()
            for (r in batchResults) {
                results.add(r)
                println("- $testFunc ipv4 $domain : nfqws2 ${r.strategyArgs.joinToString(" ")}")
                r.result.fold(
                    ifLeft = { error -> println("ERROR: ${strategyTestErrorToString(error)}") },
                    ifRight = { testResult ->
                        println(strategyTestResultToString(testResult))
                    },
                )
            }
            progressBar.update(batchResults.size)
            progressBar.render()
        }

        progressBar.clear()
    } finally {
        dropTable()
    }

    return results
}
