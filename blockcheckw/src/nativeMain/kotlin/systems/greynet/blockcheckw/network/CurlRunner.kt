package systems.greynet.blockcheckw.network

import systems.greynet.blockcheckw.system.ProcessResult
import systems.greynet.blockcheckw.system.runProcess

data class CurlResult(
    val exitCode: Int,
    val httpCode: Int?,
    val headers: String,
)

sealed interface CurlVerdict {
    data object Available : CurlVerdict
    data class SuspiciousRedirect(val code: Int, val location: String) : CurlVerdict
    data object ServerReceivesFakes : CurlVerdict
    data class Unavailable(val curlExitCode: Int) : CurlVerdict
}

private val REDIRECT_CODES = setOf(301, 302, 307, 308)

private fun toCurlResult(pr: ProcessResult): CurlResult {
    val httpCode = pr.stdout.lineSequence()
        .firstOrNull { it.startsWith("HTTP/") }
        ?.split(" ")
        ?.getOrNull(1)
        ?.toIntOrNull()
    return CurlResult(exitCode = pr.exitCode, httpCode = httpCode, headers = pr.stdout)
}

private fun localPortArgs(localPort: String?): List<String> =
    if (localPort != null) listOf("--local-port", localPort) else emptyList()

fun curlTestHttp(domain: String, localPort: String? = null, maxTime: String = "2"): CurlResult {
    val cmd = listOf(
        "curl", "-SsD", "-", "-A", "Mozilla/5.0",
        "--connect-timeout", maxTime, "--max-time", maxTime,
        "-o", "/dev/null",
    ) + localPortArgs(localPort) + listOf("http://$domain")
    return toCurlResult(runProcess(cmd))
}

fun curlTestHttpsTls12(domain: String, localPort: String? = null, maxTime: String = "2"): CurlResult {
    val cmd = listOf(
        "curl", "-Ss", "-A", "Mozilla/5.0",
        "--connect-timeout", maxTime, "--max-time", maxTime,
        "--tlsv1.2", "--tls-max", "1.2",
        "-o", "/dev/null",
    ) + localPortArgs(localPort) + listOf("https://$domain")
    return toCurlResult(runProcess(cmd))
}

fun curlTestHttpsTls13(domain: String, localPort: String? = null, maxTime: String = "2"): CurlResult {
    val cmd = listOf(
        "curl", "-Ss", "-A", "Mozilla/5.0",
        "--connect-timeout", maxTime, "--max-time", maxTime,
        "--tlsv1.3", "--tls-max", "1.3",
        "-o", "/dev/null",
    ) + localPortArgs(localPort) + listOf("https://$domain")
    return toCurlResult(runProcess(cmd))
}

fun interpretCurlResult(result: CurlResult, domain: String): CurlVerdict = when {
    result.exitCode != 0 ->
        CurlVerdict.Unavailable(result.exitCode)

    result.httpCode == 400 ->
        CurlVerdict.ServerReceivesFakes

    result.httpCode in REDIRECT_CODES -> {
        val location = result.headers.lineSequence()
            .firstOrNull { it.lowercase().startsWith("location:") }
            ?.substringAfter(":")?.trim()
            ?: ""
        if (location.lowercase().contains(domain.lowercase())) {
            CurlVerdict.Available
        } else {
            CurlVerdict.SuspiciousRedirect(result.httpCode!!, location)
        }
    }

    result.httpCode != null ->
        CurlVerdict.Available

    // HTTPS без -D: exitCode=0, httpCode=null → успешное подключение
    result.exitCode == 0 ->
        CurlVerdict.Available

    else ->
        CurlVerdict.Unavailable(result.exitCode)
}

fun verdictToString(verdict: CurlVerdict): String = when (verdict) {
    is CurlVerdict.Available -> "!!!!! AVAILABLE !!!!!"
    is CurlVerdict.SuspiciousRedirect -> "suspicious redirection ${verdict.code} to : ${verdict.location}"
    is CurlVerdict.ServerReceivesFakes -> "http code 400. likely the server receives fakes."
    is CurlVerdict.Unavailable -> "UNAVAILABLE code=${verdict.curlExitCode}"
}

fun baselineVerdictToString(verdict: CurlVerdict): String = when (verdict) {
    is CurlVerdict.Available -> "!!!!! AVAILABLE !!!!!"
    is CurlVerdict.SuspiciousRedirect ->
        "suspicious redirection ${verdict.code} to : ${verdict.location}\nUNAVAILABLE"
    is CurlVerdict.ServerReceivesFakes ->
        "http code 400. likely the server receives fakes.\nUNAVAILABLE"
    is CurlVerdict.Unavailable -> "UNAVAILABLE code=${verdict.curlExitCode}"
}
