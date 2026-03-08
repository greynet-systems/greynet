@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.pipeline

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import systems.greynet.blockcheckw.firewall.*
import systems.greynet.blockcheckw.network.*
import systems.greynet.blockcheckw.worker.*

enum class Protocol(val port: Int) {
    HTTP(80),
    HTTPS_TLS12(443),
    HTTPS_TLS13(443),
}

data class StrategyTestParams(
    val domain: String,
    val strategyArgs: List<String>,
    val qnum: Int = 200,
    val protocol: Protocol,
)

sealed interface StrategyTestResult {
    data class Success(val verdict: CurlVerdict.Available, val strategy: List<String>) : StrategyTestResult
    data class Failed(val verdict: CurlVerdict) : StrategyTestResult
    data class Error(val reason: String) : StrategyTestResult
}

sealed interface StrategyTestError {
    data class Dns(val error: DnsError) : StrategyTestError
    data class Nftables(val error: NftablesError) : StrategyTestError
    data class Nfqws2(val error: Nfqws2Error) : StrategyTestError
}

fun strategyTestErrorToString(error: StrategyTestError): String = when (error) {
    is StrategyTestError.Dns -> dnsErrorToString(error.error)
    is StrategyTestError.Nftables -> nftablesErrorToString(error.error)
    is StrategyTestError.Nfqws2 -> nfqws2ErrorToString(error.error)
}

fun testStrategy(params: StrategyTestParams): Either<StrategyTestError, StrategyTestResult> = either {
    val ips = resolveIpv4(params.domain)
        .mapLeft { StrategyTestError.Dns(it) }
        .bind()

    prepareTcp(params.protocol.port, params.qnum, ips)
        .mapLeft { StrategyTestError.Nftables(it) }
        .bind()

    val nfqws2Process = startNfqws2(params.qnum, params.strategyArgs)
        .mapLeft { StrategyTestError.Nfqws2(it) }
        .onLeft { unprepare() }
        .bind()

    try {
        // дать nfqws2 время на инициализацию
        usleep(50_000u) // 50ms

        val curlResult = when (params.protocol) {
            Protocol.HTTP -> curlTestHttp(params.domain)
            Protocol.HTTPS_TLS12 -> curlTestHttpsTls12(params.domain)
            Protocol.HTTPS_TLS13 -> curlTestHttpsTls13(params.domain)
        }

        val verdict = interpretCurlResult(curlResult, params.domain)

        when (verdict) {
            is CurlVerdict.Available ->
                StrategyTestResult.Success(verdict, params.strategyArgs)
            else ->
                StrategyTestResult.Failed(verdict)
        }
    } finally {
        unprepare()
        nfqws2Process.kill()
    }
}

fun strategyTestResultToString(result: StrategyTestResult): String = when (result) {
    is StrategyTestResult.Success ->
        "!!!!! AVAILABLE with strategy: ${result.strategy.joinToString(" ")} !!!!!"
    is StrategyTestResult.Failed ->
        "FAILED: ${verdictToString(result.verdict)}"
    is StrategyTestResult.Error ->
        "ERROR: ${result.reason}"
}
