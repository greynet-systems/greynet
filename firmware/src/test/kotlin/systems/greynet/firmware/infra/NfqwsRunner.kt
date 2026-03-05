package systems.greynet.firmware.infra

import systems.greynet.firmware.model.Strategy
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class NfqwsRunner(private val ssh: SshClient) {

    companion object {
        private const val QNUM = 200
        private const val NFQWS_PATH = "/opt/zapret/nfq/nfqws"
    }

    suspend fun start(strategy: Strategy) {
        stop()

        val nfqwsArgs = strategy.toNfqwsArgs()

        // Добавляем nftables-правила для перехвата TCP/UDP трафика
        val nftCmds = buildList {
            strategy.filterTcp?.let { ports ->
                add("nft add table inet greynet_test")
                add("nft add chain inet greynet_test output '{ type filter hook output priority 0; }'")
                add("nft add rule inet greynet_test output tcp dport { $ports } ct original packets 1-6 queue num $QNUM bypass")
            }
            strategy.filterUdp?.let { ports ->
                add("nft add table inet greynet_test 2>/dev/null || true")
                add("nft add chain inet greynet_test output '{ type filter hook output priority 0; }' 2>/dev/null || true")
                add("nft add rule inet greynet_test output udp dport { $ports } queue num $QNUM bypass")
            }
        }

        for (cmd in nftCmds) {
            ssh.exec(cmd)
        }

        // Запускаем nfqws в фоне
        ssh.exec("$NFQWS_PATH --qnum=$QNUM $nfqwsArgs </dev/null >/dev/null 2>&1 &")

        // Даём nfqws время запуститься
        delay(300.milliseconds)
    }

    suspend fun stop() {
        ssh.exec("killall nfqws 2>/dev/null || true")
        ssh.exec("nft delete table inet greynet_test 2>/dev/null || true")
        delay(100.milliseconds)
    }

    suspend fun isRunning(): Boolean {
        val result = ssh.exec("pidof nfqws")
        return result.success && result.stdout.isNotBlank()
    }
}
