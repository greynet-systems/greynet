@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.firewall

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import kotlinx.cinterop.*
import platform.posix.*
import systems.greynet.blockcheckw.system.ProcessResult
import systems.greynet.blockcheckw.system.runProcess

private const val TABLE_NAME = "zapret"
private const val CHAIN_NAME = "postnat"

// nftMutex сериализует nft-вызовы: параллельные nft могут конфликтовать в ядре (netlink).
// Порядок блокировки: nftMutex → forkMutex (внутри runProcess). Инверсии нет,
// т.к. curl/nfqws2-потоки не берут nftMutex.
private val nftMutex: CPointer<pthread_mutex_t> = nativeHeap.alloc<pthread_mutex_t>().apply {
    pthread_mutex_init(ptr, null)
}.ptr

sealed interface NftablesError {
    data class CommandFailed(val command: String, val result: ProcessResult) : NftablesError
}

data class RuleHandle(val handle: Int)

private fun runNft(args: List<String>): Either<NftablesError, ProcessResult> {
    val cmd = listOf("nft") + args
    pthread_mutex_lock(nftMutex)
    val result = try {
        runProcess(cmd)
    } finally {
        pthread_mutex_unlock(nftMutex)
    }
    return if (result.exitCode == 0) result.right()
    else NftablesError.CommandFailed(cmd.joinToString(" "), result).left()
}

private fun runNftUnit(args: List<String>): Either<NftablesError, Unit> =
    runNft(args).map { }

// --- Single-mode API (для обратной совместимости с StrategyTest) ---

fun prepareTcp(port: Int, qnum: Int, ips: List<String>): Either<NftablesError, Unit> {
    val ipSet = ips.joinToString(", ")

    runNftUnit(listOf("add", "table", "inet", TABLE_NAME))
        .onLeft { return it.left() }

    runNftUnit(
        listOf(
            "add", "chain", "inet", TABLE_NAME, CHAIN_NAME,
            "{ type filter hook postrouting priority 102; }"
        )
    ).onLeft { return it.left() }

    return runNftUnit(
        listOf(
            "add", "rule", "inet", TABLE_NAME, CHAIN_NAME,
            "meta", "nfproto", "ipv4",
            "tcp", "dport", port.toString(),
            "mark", "and", "0x10000000", "==", "0",
            "ip", "daddr", "{ $ipSet }",
            "ct", "mark", "set", "ct", "mark", "or", "0x10000000",
            "queue", "num", qnum.toString()
        )
    )
}

fun unprepare(): Either<NftablesError, Unit> =
    runNftUnit(listOf("delete", "table", "inet", TABLE_NAME))

// --- Parallel-mode API (per-worker правила) ---

fun prepareTable(): Either<NftablesError, Unit> {
    runNftUnit(listOf("add", "table", "inet", TABLE_NAME))
        .onLeft { return it.left() }

    return runNftUnit(
        listOf(
            "add", "chain", "inet", TABLE_NAME, CHAIN_NAME,
            "{ type filter hook postrouting priority 102; }"
        )
    )
}

private val HANDLE_REGEX = Regex("""# handle (\d+)""")

fun addWorkerRule(sport: Int, dport: Int, qnum: Int, ips: List<String>): Either<NftablesError, RuleHandle> {
    val ipSet = ips.joinToString(", ")

    val result = runNft(
        listOf(
            "--echo", "--handle",
            "add", "rule", "inet", TABLE_NAME, CHAIN_NAME,
            "meta", "nfproto", "ipv4",
            "tcp", "sport", sport.toString(),
            "tcp", "dport", dport.toString(),
            "mark", "and", "0x10000000", "==", "0",
            "ip", "daddr", "{ $ipSet }",
            "ct", "mark", "set", "ct", "mark", "or", "0x10000000",
            "queue", "num", qnum.toString()
        )
    ).getOrElse { return it.left() }

    val handle = HANDLE_REGEX.find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
        ?: return NftablesError.CommandFailed(
            "nft --echo --handle: cannot parse handle from output: ${result.stdout}",
            result
        ).left()

    return RuleHandle(handle).right()
}

fun removeRule(handle: RuleHandle): Either<NftablesError, Unit> =
    runNftUnit(
        listOf("delete", "rule", "inet", TABLE_NAME, CHAIN_NAME, "handle", handle.handle.toString())
    )

fun dropTable(): Either<NftablesError, Unit> =
    runNftUnit(listOf("delete", "table", "inet", TABLE_NAME))

fun nftablesErrorToString(error: NftablesError): String = when (error) {
    is NftablesError.CommandFailed ->
        "nftables command failed: ${error.command}\nstderr: ${error.result.stderr}"
}
