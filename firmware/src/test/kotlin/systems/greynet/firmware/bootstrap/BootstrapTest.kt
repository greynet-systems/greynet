package systems.greynet.firmware.bootstrap

import kotlinx.coroutines.runBlocking
import systems.greynet.firmware.infra.SshClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Тесты для проверки состояния "голой" системы после установки OpenWRT на NanoPI R3S
 */
class BootstrapTest {

    private val ssh = SshClient()

    @Test
    fun `openwrt version is 25_12`() = runBlocking {
        val result = ssh.exec("cat /etc/openwrt_release")
        assertTrue(result.success, "Failed to read /etc/openwrt_release: ${result.stderr}")

        val distrib = result.stdout.lines()
            .firstOrNull { it.startsWith("DISTRIB_RELEASE=") }
            ?.substringAfter("=")?.trim('\'', '"')

        assertTrue(
            distrib != null && distrib.startsWith("25.12"),
            "Expected OpenWrt 25.12.x, got: $distrib"
        )
    }

    @Test
    fun `kernel version is 6_12+`() = runBlocking {
        val result = ssh.exec("uname -r")
        assertTrue(result.success, "Failed to get kernel version: ${result.stderr}")

        val version = result.stdout.trim()
        val major = version.substringBefore(".").toIntOrNull() ?: 0
        val minor = version.substringAfter(".").substringBefore(".").toIntOrNull() ?: 0

        assertTrue(
            major > 6 || (major == 6 && minor >= 12),
            "Expected kernel 6.12+, got: $version"
        )
    }

    @Test
    fun `architecture is aarch64`() = runBlocking {
        val result = ssh.exec("uname -m")
        assertTrue(result.success, "Failed to get architecture: ${result.stderr}")
        assertEquals(result.stdout.trim(), "aarch64", "Expected aarch64, got: ${result.stdout.trim()}")
    }

    @Test
    fun `wan interface has ip address`() = runBlocking {
        val result = ssh.exec("ip -4 addr show dev eth0")
        assertTrue(result.success, "Failed to get eth0 address: ${result.stderr}")
        assertTrue(
            result.stdout.contains("inet "),
            "WAN interface eth0 has no IPv4 address:\n${result.stdout}"
        )
    }

    @Test
    fun `default route exists via wan`() = runBlocking {
        val result = ssh.exec("ip route show default")
        assertTrue(result.success, "Failed to get default route: ${result.stderr}")
        assertTrue(
            result.stdout.contains("default") && result.stdout.contains("eth0"),
            "No default route via eth0:\n${result.stdout}"
        )
    }

    @Test
    fun `internet is reachable via http 204 check`() = runBlocking {
        val result = ssh.exec("curl -s --max-time 5 -o /dev/null -w '%{http_code}' http://cp.cloudflare.com/generate_204")
        assertTrue(result.success, "curl failed: ${result.stderr}")
        assertEquals(result.stdout.trim(), "204", "Expected HTTP 204, got: ${result.stdout.trim()}")
    }

    @Test
    fun `dns resolution works`() = runBlocking {
        val result = ssh.exec("nslookup google.com 2>&1")
        assertTrue(result.success, "DNS resolution failed: ${result.stderr}")
        assertTrue(
            result.stdout.contains("Address") && !result.stdout.contains("server can't find"),
            "DNS resolution failed:\n${result.stdout}"
        )
    }

    @Test
    fun `nfqws2 starts with lua without errors`() = runBlocking {
        ssh.exec("killall nfqws2 2>/dev/null || true")

        val result = ssh.exec(
            "/opt/zapret2/nfq2/nfqws2 --qnum=200" +
                " --lua-init=@/opt/zapret2/lua/zapret-lib.lua" +
                " --lua-init=@/opt/zapret2/lua/zapret-antidpi.lua" +
                " --payload=http_req --lua-desync=http_hostcase 2>&1 &" +
                " PID=\$!; sleep 2; kill \$PID 2>/dev/null; wait \$PID 2>/dev/null",
            timeout = 10.seconds
        )

        assertFalse(
            result.stdout.contains("LUA INIT ERROR"),
            "nfqws2 failed to initialize lua:\n${result.stdout}"
        )
        assertTrue(
            result.stdout.contains("initializing raw sockets"),
            "nfqws2 did not start properly:\n${result.stdout}"
        )
    }
}
