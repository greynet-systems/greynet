package systems.greynet.blockcheckw

import systems.greynet.blockcheckw.network.*

fun main(args: Array<String>) {
    if ("--healthcheck" in args) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    }

    val domain = "rutracker.ru"

    println("* curl_test_http ipv4 $domain")
    println("- checking without DPI bypass")
    val httpResult = curlTestHttp(domain)
    println(verdictToString(interpretCurlResult(httpResult, domain)))

    println()
    println("* curl_test_https_tls12 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls12Result = curlTestHttpsTls12(domain)
    println(verdictToString(interpretCurlResult(tls12Result, domain)))

    println()
    println("* curl_test_https_tls13 ipv4 $domain")
    println("- checking without DPI bypass")
    val tls13Result = curlTestHttpsTls13(domain)
    println(verdictToString(interpretCurlResult(tls13Result, domain)))
}
