package systems.greynet.firmware.infra

import systems.greynet.firmware.model.ProbeResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class Probe(private val ssh: SshClient) {

    suspend fun checkAvailability(domain: String, timeout: Duration = 5.seconds): ProbeResult {
        val timeoutSec = timeout.inWholeSeconds
        val curlFormat = """%{http_code}\n%{time_connect}\n%{time_starttransfer}\n%{time_total}"""
        val result = ssh.exec(
            """curl -4sk --max-time $timeoutSec -o /dev/null -w '$curlFormat' https://$domain""",
            timeout = timeout + 5.seconds
        )

        if (!result.success && result.stdout.isBlank()) {
            return ProbeResult(
                available = false,
                error = result.stderr.ifBlank { "curl failed with exit code ${result.exitCode}" },
            )
        }

        val lines = result.stdout.lines()
        val httpCode = lines.getOrNull(0)?.toIntOrNull()
        val connectTime = lines.getOrNull(1)?.toDoubleOrNull()?.let { Duration.parse("${it}s") }
        val ttfb = lines.getOrNull(2)?.toDoubleOrNull()?.let { Duration.parse("${it}s") }
        val totalTime = lines.getOrNull(3)?.toDoubleOrNull()?.let { Duration.parse("${it}s") }

        return ProbeResult(
            available = httpCode != null && httpCode in 200..399,
            httpCode = httpCode,
            connectTime = connectTime,
            ttfb = ttfb,
            totalTime = totalTime,
            error = if (httpCode == null || httpCode !in 200..399) result.stderr.ifBlank { null } else null,
        )
    }

    suspend fun measureSpeed(domain: String, timeout: Duration = 10.seconds): ProbeResult {
        val timeoutSec = timeout.inWholeSeconds
        val curlFormat = """%{http_code}\n%{speed_download}\n%{time_total}"""
        val result = ssh.exec(
            """curl -4sk --max-time $timeoutSec -o /dev/null -w '$curlFormat' https://$domain""",
            timeout = timeout + 5.seconds
        )

        if (!result.success && result.stdout.isBlank()) {
            return ProbeResult(available = false, error = result.stderr.ifBlank { "curl failed" })
        }

        val lines = result.stdout.lines()
        val httpCode = lines.getOrNull(0)?.toIntOrNull()
        val speedBps = lines.getOrNull(1)?.toDoubleOrNull()?.toLong()
        val totalTime = lines.getOrNull(2)?.toDoubleOrNull()?.let { Duration.parse("${it}s") }

        return ProbeResult(
            available = httpCode != null && httpCode in 200..399,
            httpCode = httpCode,
            speedBytesPerSec = speedBps,
            totalTime = totalTime,
        )
    }
}
