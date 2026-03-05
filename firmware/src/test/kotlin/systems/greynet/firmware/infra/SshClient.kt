package systems.greynet.firmware.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class SshResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val success get() = exitCode == 0
}

class SshClient(private val host: String = "root@g-vpn") {

    suspend fun exec(command: String, timeout: Duration = 30.seconds): SshResult {
        return withContext(Dispatchers.IO) {
            val process = ProcessBuilder(
                "ssh",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=5",
                host,
                command
            ).start()

            val result = withTimeoutOrNull(timeout) {
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                process.waitFor()
                SshResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.trim(),
                    stderr = stderr.trim()
                )
            }

            if (result == null) {
                process.destroyForcibly()
                SshResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "SSH command timed out after $timeout"
                )
            } else {
                result
            }
        }
    }
}
