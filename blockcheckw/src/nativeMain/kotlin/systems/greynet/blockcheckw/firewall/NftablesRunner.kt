package systems.greynet.blockcheckw.firewall

import systems.greynet.blockcheckw.system.runProcess

private const val TABLE_NAME = "zapret"

fun prepareTcp(port: Int, qnum: Int, ips: List<String>) {
    val ipSet = ips.joinToString(", ")

    runProcess(listOf("nft", "add", "table", "inet", TABLE_NAME))

    runProcess(
        listOf(
            "nft", "add", "chain", "inet", TABLE_NAME, "postnat",
            "{ type filter hook postrouting priority 102; }"
        )
    )

    runProcess(
        listOf(
            "nft", "add", "rule", "inet", TABLE_NAME, "postnat",
            "meta", "nfproto", "ipv4",
            "tcp", "dport", port.toString(),
            "mark", "and", "0x10000000", "==", "0",
            "ip", "daddr", "{ $ipSet }",
            "ct", "mark", "set", "ct", "mark", "or", "0x10000000",
            "queue", "num", qnum.toString()
        )
    )
}

fun unprepare() {
    runProcess(listOf("nft", "delete", "table", "inet", TABLE_NAME))
}
