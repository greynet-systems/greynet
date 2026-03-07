package systems.greynet.blockcheckw.firewall

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import systems.greynet.blockcheckw.system.ProcessResult
import systems.greynet.blockcheckw.system.runProcess

private const val TABLE_NAME = "zapret"

sealed interface NftablesError {
    data class CommandFailed(val command: String, val result: ProcessResult) : NftablesError
}

private fun runNft(args: List<String>): Either<NftablesError, Unit> {
    val cmd = listOf("nft") + args
    val result = runProcess(cmd)
    return if (result.exitCode == 0) Unit.right()
    else NftablesError.CommandFailed(cmd.joinToString(" "), result).left()
}

fun prepareTcp(port: Int, qnum: Int, ips: List<String>): Either<NftablesError, Unit> {
    val ipSet = ips.joinToString(", ")

    runNft(listOf("add", "table", "inet", TABLE_NAME))
        .onLeft { return it.left() }

    runNft(
        listOf(
            "add", "chain", "inet", TABLE_NAME, "postnat",
            "{ type filter hook postrouting priority 102; }"
        )
    ).onLeft { return it.left() }

    return runNft(
        listOf(
            "add", "rule", "inet", TABLE_NAME, "postnat",
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
    runNft(listOf("delete", "table", "inet", TABLE_NAME))

fun nftablesErrorToString(error: NftablesError): String = when (error) {
    is NftablesError.CommandFailed ->
        "nftables command failed: ${error.command}\nstderr: ${error.result.stderr}"
}
