package systems.greynet.blockcheckw

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

data class CurlResult(
    val exitCode: Int,
    val httpCode: Int?,
    val headers: String,
)

@OptIn(ExperimentalForeignApi::class)
fun curlTestHttp(domain: String): CurlResult {
    val cmd = "curl -SsD - -A \"Mozilla/5.0\" --max-time 2 -o /dev/null http://$domain 2>/dev/null"
    val fp = popen(cmd, "r") ?: return CurlResult(exitCode = -1, httpCode = null, headers = "")

    val buf = ByteArray(4096)
    val sb = StringBuilder()
    while (fgets(buf.refTo(0), buf.size, fp) != null) {
        sb.append(buf.toKString())
    }

    val status = pclose(fp)
    // pclose returns wait status; extract exit code via shift
    val exitCode = (status shr 8) and 0xFF

    val headers = sb.toString()
    val httpCode = headers.lineSequence()
        .firstOrNull { it.startsWith("HTTP/") }
        ?.split(" ")
        ?.getOrNull(1)
        ?.toIntOrNull()

    return CurlResult(exitCode = exitCode, httpCode = httpCode, headers = headers)
}

fun main(args: Array<String>) {
    if ("--healthcheck" in args) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    }

    val domain = "rutracker.ru"
    println("* curl_test_http ipv4 $domain")
    println("- checking without DPI bypass")

    val result = curlTestHttp(domain)

    when {
        result.exitCode != 0 -> {
            println("UNAVAILABLE (curl exit code=${result.exitCode})")
        }
        result.httpCode == 400 -> {
            println("http code ${result.httpCode}. likely the server receives fakes.")
        }
        result.httpCode in listOf(301, 302, 307, 308) -> {
            val location = result.headers.lineSequence()
                .firstOrNull { it.lowercase().startsWith("location:") }
                ?.substringAfter(":")?.trim()
            val domLower = domain.lowercase()
            val locLower = location?.lowercase() ?: ""
            if (locLower.contains(domLower)) {
                println("!!!!! AVAILABLE (redirect to self: ${result.httpCode} -> $location) !!!!!")
            } else {
                println("suspicious redirection ${result.httpCode} to: $location")
            }
        }
        result.httpCode != null -> {
            println("!!!!! AVAILABLE (http code ${result.httpCode}) !!!!!")
        }
        else -> {
            println("UNAVAILABLE (no http response)")
        }
    }
}
