package systems.greynet.blockcheckw

import arrow.core.getOrElse
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

    if ("--test-parallel-real" in args) {
        val idx = args.indexOf("--test-parallel-real")
        val workers = args.getOrNull(idx + 1)?.toIntOrNull() ?: 4
        val maxStrategies = args.getOrNull(idx + 2)?.toIntOrNull() ?: 200
        val protocol = when (args.getOrNull(idx + 3)) {
            "tls12" -> Protocol.HTTPS_TLS12
            "tls13" -> Protocol.HTTPS_TLS13
            else -> Protocol.HTTP
        }
        requireRoot(args)
        checkAlreadyRunning()
        runStressTestReal(workers, maxStrategies, protocol)
        return
    }

    if ("--test-parallel" in args) {
        val idx = args.indexOf("--test-parallel")
        val workers = args.getOrNull(idx + 1)?.toIntOrNull() ?: 4
        val iterations = args.getOrNull(idx + 2)?.toIntOrNull() ?: 3
        requireRoot(args)
        checkAlreadyRunning()
        runStressTest(workers, iterations)
        return
    }

    val domain = "rutracker.org"

    // FIXME: перезапускает весь бинарь, поэтому bypass тесты прогоняются два раза
    requireRoot(args)
    checkAlreadyRunning()

    println()
    val ipInfo = detectIpInfo()
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
    println(baselineVerdictToString(httpVerdict))

    println()
    println("* curl_test_https_tls12 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls12Result = curlTestHttpsTls12(domain)
    val tls12Verdict = interpretCurlResult(tls12Result, domain)
    println(baselineVerdictToString(tls12Verdict))

    println()
    println("* curl_test_https_tls13 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls13Result = curlTestHttpsTls13(domain)
    val tls13Verdict = interpretCurlResult(tls13Result, domain)
    println(baselineVerdictToString(tls13Verdict))

    // --- Тест bypass-стратегии если заблокировано ---
    val protocols = listOf(
        Triple(Protocol.HTTP, Protocol.HTTP.toTestFunc(), httpVerdict),
        Triple(Protocol.HTTPS_TLS12, Protocol.HTTPS_TLS12.toTestFunc(), tls12Verdict),
        Triple(Protocol.HTTPS_TLS13, Protocol.HTTPS_TLS13.toTestFunc(), tls13Verdict),
    )

    val summaryReports = mutableListOf<String>()

    val blockedProtocols = protocols.filter { (_, _, verdict) -> verdict !is CurlVerdict.Available }
    val availableProtocols = protocols.filter { (_, _, verdict) -> verdict is CurlVerdict.Available }

    // Доступные без bypass — сразу добавляем в summary
    for ((_, testFunc, _) in availableProtocols) {
        summaryReports.add("$testFunc ipv4 $domain : working without bypass")
    }

    if (blockedProtocols.isEmpty()) {
        println()
        println("* SUMMARY")
        for (report in summaryReports) {
            println(report)
        }
        return
    }

    val parallelConfig = ParallelConfig(workerCount = 16) // TODO: поменять на 4 для aarch64

    for ((protocol, testFunc, _) in blockedProtocols) {
        val candidates = generateStrategies(protocol)
        println()
        println("preparing nfqws2 redirection")

        val results = runParallel(domain, protocol, candidates, ips, parallelConfig, testFunc)

        println("clearing nfqws2 redirection")

        val firstSuccess = results.firstOrNull { r ->
            r.result.getOrElse { null } is StrategyTestResult.Success
        }
        if (firstSuccess != null) {
            println()
            println("!!!!! $testFunc: working strategy found for ipv4 $domain : nfqws2 ${firstSuccess.strategyArgs.joinToString(" ")} !!!!!")
            println()
            summaryReports.add("$testFunc ipv4 $domain : nfqws2 ${firstSuccess.strategyArgs.joinToString(" ")}")
        } else {
            println()
            println("$testFunc: nfqws2 strategy for ipv4 $domain not found")
            println()
            summaryReports.add("$testFunc ipv4 $domain : nfqws2 not working")
        }
    }

    // --- Общая SUMMARY ---
    println()
    println("* SUMMARY")
    for (report in summaryReports) {
        println(report)
    }
    println()
    println("Please note this SUMMARY does not guarantee a magic pill for you to copy/paste and be happy.")
    println("Understanding how strategies work is very desirable.")
    println("This knowledge allows to understand better which strategies to prefer and which to avoid if possible, how to combine strategies.")
    println("Blockcheck does it's best to prioritize good strategies but it's not bullet-proof.")
    println("It was designed not as magic pill maker but as a DPI bypass test tool.")
}
