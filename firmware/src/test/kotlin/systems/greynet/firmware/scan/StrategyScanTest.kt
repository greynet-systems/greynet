package systems.greynet.firmware.scan

import kotlinx.coroutines.runBlocking
import systems.greynet.firmware.infra.NfqwsRunner
import systems.greynet.firmware.infra.Probe
import systems.greynet.firmware.infra.SshClient
import systems.greynet.firmware.model.Strategy
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class StrategyScanTest {

    private val ssh = SshClient()
    private val runner = NfqwsRunner(ssh)
    private val probe = Probe(ssh)

    @Test
    fun `scan strategies for blocked domains`() = runBlocking {
        val strategies = StrategyRegistry.loadFrom("strategies/base.txt")
        val domain = "rutracker.org"

        scanAndReport(domain, strategies)
    }

    @Test
    fun `scan strategies for youtube`() = runBlocking {
        val strategies = StrategyRegistry.loadFrom("strategies/youtube.txt")
        val domain = "youtube.com"

        scanAndReport(domain, strategies)
    }

    private suspend fun scanAndReport(domain: String, strategies: List<Strategy>) {
        // Baseline без nfqws
        runner.stop()
        val baseline = probe.checkAvailability(domain, timeout = 5.seconds)
        println("=== Baseline ($domain): ${statusStr(baseline.available)} httpCode=${baseline.httpCode} time=${baseline.totalTime} ===")
        println()

        // Перебор стратегий
        val results = mutableListOf<Pair<Strategy, systems.greynet.firmware.model.ProbeResult>>()

        for ((i, strategy) in strategies.withIndex()) {
            print("[${i + 1}/${strategies.size}] ${strategy.toNfqwsArgs()} ... ")

            try {
                runner.start(strategy)
                val result = probe.checkAvailability(domain, timeout = 7.seconds)
                results.add(strategy to result)
                println("${statusStr(result.available)} httpCode=${result.httpCode} time=${result.totalTime}")
            } catch (e: Exception) {
                println("ERROR: ${e.message}")
                results.add(strategy to systems.greynet.firmware.model.ProbeResult(available = false, error = e.message))
            } finally {
                runner.stop()
            }
        }

        // Итоговая таблица
        println()
        println("=== Results for $domain ===")
        println("%-6s | %-12s | %s".format("Status", "Time", "Strategy"))
        println("-".repeat(80))

        results.sortedByDescending { it.second.available }
            .forEach { (strategy, result) ->
                println(
                    "%-6s | %-12s | %s".format(
                        statusStr(result.available),
                        result.totalTime?.toString() ?: "-",
                        strategy.toNfqwsArgs()
                    )
                )
            }

        val okCount = results.count { it.second.available }
        println()
        println("Total: ${results.size} strategies, $okCount available, ${results.size - okCount} failed")
    }

    private fun statusStr(available: Boolean) = if (available) "OK" else "FAIL"
}
