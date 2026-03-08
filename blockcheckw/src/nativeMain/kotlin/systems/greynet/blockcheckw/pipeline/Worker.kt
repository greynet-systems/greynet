@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.pipeline

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import systems.greynet.blockcheckw.firewall.*
import systems.greynet.blockcheckw.network.*
import systems.greynet.blockcheckw.worker.*

data class WorkerSlot(
    val id: Int,
    val qnum: Int,
    val localPortRange: IntRange,
)

data class WorkerTask(
    val slot: WorkerSlot,
    val domain: String,
    val strategyArgs: List<String>,
    val protocol: Protocol,
    val ips: List<String>,
)

fun executeWorker(task: WorkerTask): Either<StrategyTestError, StrategyTestResult> = either {
    val handle = addWorkerRule(
        sportRange = task.slot.localPortRange,
        dport = task.protocol.port,
        qnum = task.slot.qnum,
        ips = task.ips,
    ).mapLeft { StrategyTestError.Nftables(it) }.bind()

    val nfqws2Process = startNfqws2(task.slot.qnum, task.strategyArgs)
        .mapLeft { StrategyTestError.Nfqws2(it) }
        .onLeft { removeRule(handle) }
        .bind()

    try {
        usleep(50_000u) // 50ms — дать nfqws2 время на инициализацию

        val portRange = task.slot.localPortRange
        val localPortArg = "${portRange.first}-${portRange.last}"

        val curlResult = when (task.protocol) {
            Protocol.HTTP -> curlTestHttp(task.domain, localPort = localPortArg, maxTime = "1")
            Protocol.HTTPS_TLS12 -> curlTestHttpsTls12(task.domain, localPort = localPortArg, maxTime = "1")
            Protocol.HTTPS_TLS13 -> curlTestHttpsTls13(task.domain, localPort = localPortArg, maxTime = "1")
        }

        val verdict = interpretCurlResult(curlResult, task.domain)

        when (verdict) {
            is CurlVerdict.Available ->
                StrategyTestResult.Success(verdict, task.strategyArgs)
            else ->
                StrategyTestResult.Failed(verdict)
        }
    } finally {
        nfqws2Process.kill()
        removeRule(handle)
    }
}
