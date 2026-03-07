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

    val strategies = mapOf(
        Protocol.HTTP to listOf(
            listOf("--payload=http_req", "--lua-desync=hostfakesplit:ip_ttl=7:repeats=1"),
            listOf("--payload=http_req", "--lua-desync=hostfakesplit:tcp_md5:repeats=1"),
            listOf("--payload=http_req", "--lua-desync=fake:blob=fake_default_http:tcp_md5:repeats=1",
                "--payload=empty", "--out-range=<s1", "--lua-desync=send:tcp_md5"),
            listOf("--in-range=-s1", "--lua-desync=oob:urp=b"),
        ),
        Protocol.HTTPS_TLS12 to listOf(
            listOf("--payload=tls_client_hello",
                "--lua-desync=fake:blob=fake_default_tls:tcp_md5:tls_mod=rnd,dupsid,padencap:repeats=1"),
            listOf("--payload=tls_client_hello",
                "--lua-desync=hostfakesplit:midhost=midsld:tcp_md5:repeats=1"),
            listOf("--payload=tls_client_hello",
                "--lua-desync=fake:blob=fake_default_tls:tcp_ts=-1000:repeats=1"),
        ),
        Protocol.HTTPS_TLS13 to listOf(
            listOf("--payload=tls_client_hello",
                "--lua-desync=fake:blob=fake_default_tls:tcp_md5:tls_mod=rnd,dupsid,padencap:repeats=1"),
            listOf("--payload=tls_client_hello",
                "--lua-desync=hostfakesplit:midhost=midsld:tcp_md5:repeats=1"),
            listOf("--payload=tls_client_hello",
                "--lua-desync=fake:blob=fake_default_tls:tcp_ts=-1000:repeats=1"),
        ),
    )

    val parallelConfig = ParallelConfig(workerCount = 8)

    for ((name, protocol, _) in blockedProtocols) {
        val candidates = strategies[protocol] ?: continue
        println()
        println("=== bypass test: $name (parallel, ${minOf(candidates.size, parallelConfig.workerCount)} workers) ===")

        val results = runParallel(domain, protocol, candidates, ips, parallelConfig)

        val success = results.firstOrNull { r ->
            r.result.getOrElse { null } is StrategyTestResult.Success
        }

        if (success != null) {
            println("  => working strategy for $name: ${success.strategyArgs.joinToString(" ")}")
        } else {
            println("  no working strategy found for $name")
        }
    }
}
