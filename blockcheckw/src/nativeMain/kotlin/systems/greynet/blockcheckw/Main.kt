package systems.greynet.blockcheckw

import arrow.core.getOrElse
import kotlin.time.measureTimedValue
import kotlin.time.DurationUnit
import systems.greynet.blockcheckw.network.*
import systems.greynet.blockcheckw.pipeline.*
import systems.greynet.blockcheckw.system.checkAlreadyRunning
import systems.greynet.blockcheckw.system.requireRoot

fun main(args: Array<String>) {
    if ("--healthcheck" in args) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    }

    if ("--count" in args) {
        for (p in Protocol.entries) {
            val strategies = generateStrategies(p)
            println("${p.name}: ${strategies.size} strategies")
        }
        return
    }

    val domain = "rutracker.org"

    // FIXME: перезапускает весь бинарь, поэтому bypass тесты прогоняются два раза
    requireRoot(args)
    checkAlreadyRunning()

    println()
    val ipInfo = detectIpInfo()
    println("=== blockcheckw: тестирование $domain ===")
    println("ISP: ${ipInfo?.let { ipInfoToString(it) } ?: "unknown"}")
    println()

    // --- DNS-резолв один раз ---
    val ips = resolveIpv4(domain).fold(
        ifLeft = { error ->
            println("DNS error: ${dnsErrorToString(error)}")
            return
        },
        ifRight = { it },
    )
    println("resolved $domain -> ${ips.joinToString(", ")}")
    println()

    // --- Проверка без bypass ---
    println("* curl_test_http ipv4 $domain")
    println("- checking without DPI bypass")
    val httpResult = curlTestHttp(domain)
    val httpVerdict = interpretCurlResult(httpResult, domain)
    println(verdictToString(httpVerdict))

    println()
    println("* curl_test_https_tls12 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls12Result = curlTestHttpsTls12(domain)
    val tls12Verdict = interpretCurlResult(tls12Result, domain)
    println(verdictToString(tls12Verdict))

    println()
    println("* curl_test_https_tls13 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls13Result = curlTestHttpsTls13(domain)
    val tls13Verdict = interpretCurlResult(tls13Result, domain)
    println(verdictToString(tls13Verdict))

    // --- Тест bypass-стратегии если заблокировано ---
    val protocols = listOf(
        Triple("HTTP", Protocol.HTTP, httpVerdict),
        Triple("HTTPS TLS1.2", Protocol.HTTPS_TLS12, tls12Verdict),
        Triple("HTTPS TLS1.3", Protocol.HTTPS_TLS13, tls13Verdict),
    )

    val blockedProtocols = protocols.filter { (_, _, verdict) -> verdict !is CurlVerdict.Available }

    if (blockedProtocols.isEmpty()) {
        println()
        println("Все протоколы доступны без bypass — стратегии не нужны.")
        return
    }

    val parallelConfig = ParallelConfig(workerCount = 8)

    for ((name, protocol, _) in blockedProtocols) {
        val candidates = generateStrategies(protocol)
        println()
        println("=== bypass test: $name (${candidates.size} strategies, ${parallelConfig.workerCount} workers) ===")

        val (results, duration) = measureTimedValue {
            runParallel(domain, protocol, candidates, ips, parallelConfig)
        }

        val successCount = results.count { r ->
            r.result.getOrElse { null } is StrategyTestResult.Success
        }
        val failedCount = results.count { r ->
            r.result.getOrElse { null } is StrategyTestResult.Failed
        }
        val errorCount = results.count { r ->
            r.result.isLeft() || r.result.getOrElse { null } is StrategyTestResult.Error
        }

        val totalSec = duration.toLong(DurationUnit.SECONDS)
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        val avgMs = if (results.isNotEmpty()) duration.toLong(DurationUnit.MILLISECONDS) / results.size else 0

        println()
        println("=== SUMMARY: $name ===")
        println("strategies: ${candidates.size}")
        println("success: $successCount")
        println("failed: $failedCount")
        println("errors: $errorCount")
        println("time: ${minutes}m ${seconds}s (avg ${avgMs}ms/strategy)")

        val firstSuccess = results.firstOrNull { r ->
            r.result.getOrElse { null } is StrategyTestResult.Success
        }
        if (firstSuccess != null) {
            println("  => working strategy for $name: ${firstSuccess.strategyArgs.joinToString(" ")}")
        } else {
            println("  no working strategy found for $name")
        }
    }
}
