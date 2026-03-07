package systems.greynet.blockcheckw

import systems.greynet.blockcheckw.network.*
import systems.greynet.blockcheckw.pipeline.*
import systems.greynet.blockcheckw.system.requireRoot

fun main(args: Array<String>) {
    if ("--healthcheck" in args) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    }

    val domain = "rutracker.ru"

    // FIXME: перезапускает весь бинарь, поэтому bypass тесты прогоняются два раза
    requireRoot(args)

    println()
    val ipInfo = detectIpInfo()
    println("=== blockcheckw: тестирование $domain ===")
    println("ISP: ${ipInfo?.let { ipInfoToString(it) } ?: "unknown"}")
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

    val strategy = listOf("--dpi-desync=split2")

    for ((name, protocol, _) in blockedProtocols) {
        println()
        println("=== bypass test: $name с ${strategy.joinToString(" ")} ===")

        val params = StrategyTestParams(
            domain = domain,
            strategyArgs = strategy,
            protocol = protocol,
        )

        val result = testStrategy(params)

        result.fold(
            ifLeft = { error -> println("ERROR: ${strategyTestErrorToString(error)}") },
            ifRight = { testResult -> println(strategyTestResultToString(testResult)) },
        )
    }
}
