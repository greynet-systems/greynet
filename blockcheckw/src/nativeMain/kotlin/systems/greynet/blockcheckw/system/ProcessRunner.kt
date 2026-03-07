@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.system

import kotlinx.cinterop.*
import platform.posix.*

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class BackgroundProcess(val pid: Int) {

    fun kill(): Boolean {
        if (pid <= 0) return false
        val killed = kill(pid, SIGKILL) == 0
        if (killed) {
            // Собираем зомби-процесс, чтобы не забивать таблицу процессов
            waitpid(pid, null, 0)
        }
        return killed
    }

    fun isAlive(): Boolean {
        if (pid <= 0) return false
        val ret = waitpid(pid, null, WNOHANG)
        return ret == 0
    }

    fun waitFor(): Int = memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        (status.value shr 8) and 0xFF
    }
}

private fun setCloexec(fd: Int) {
    fcntl(fd, F_SETFD, FD_CLOEXEC)
}

private fun readFdToString(fd: Int): String {
    val sb = StringBuilder()
    val buf = ByteArray(4096)
    while (true) {
        val n = buf.usePinned { pinned ->
            read(fd, pinned.addressOf(0), buf.size.toULong())
        }
        if (n <= 0) break
        sb.append(buf.decodeToString(0, n.toInt()))
    }
    return sb.toString()
}

// TODO: убедиться, что мы не изобрели велосипед
fun runProcess(command: List<String>): ProcessResult {
    val stdoutPipe = IntArray(2)
    val stderrPipe = IntArray(2)

    stdoutPipe.usePinned { pinned ->
        if (pipe(pinned.addressOf(0)) != 0) {
            return ProcessResult(exitCode = -1, stdout = "", stderr = "pipe() failed for stdout")
        }
    }
    setCloexec(stdoutPipe[0])
    setCloexec(stdoutPipe[1])

    stderrPipe.usePinned { pinned ->
        if (pipe(pinned.addressOf(0)) != 0) {
            close(stdoutPipe[0]); close(stdoutPipe[1])
            return ProcessResult(exitCode = -1, stdout = "", stderr = "pipe() failed for stderr")
        }
    }
    setCloexec(stderrPipe[0])
    setCloexec(stderrPipe[1])

    val pid = fork()

    if (pid < 0) {
        close(stdoutPipe[0]); close(stdoutPipe[1])
        close(stderrPipe[0]); close(stderrPipe[1])
        return ProcessResult(exitCode = -1, stdout = "", stderr = "fork() failed")
    }

    if (pid == 0) {
        // child
        close(stdoutPipe[0])
        close(stderrPipe[0])
        dup2(stdoutPipe[1], STDOUT_FILENO)
        dup2(stderrPipe[1], STDERR_FILENO)
        close(stdoutPipe[1])
        close(stderrPipe[1])

        memScoped {
            val argv = allocArrayOf(
                *command.map { it.cstr.ptr }.toTypedArray(),
                null
            )
            execvp(command[0], argv)
        }
        _exit(127)
    }

    // parent
    close(stdoutPipe[1])
    close(stderrPipe[1])

    val stdout = readFdToString(stdoutPipe[0])
    val stderr = readFdToString(stderrPipe[0])
    close(stdoutPipe[0])
    close(stderrPipe[0])

    return memScoped {
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val exitCode = (status.value shr 8) and 0xFF
        ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }
}

fun startBackground(command: List<String>): BackgroundProcess {
    val pid = fork()

    if (pid < 0) return BackgroundProcess(pid = -1)

    if (pid == 0) {
        // child: redirect stdout/stderr to /dev/null
        val devNull = open("/dev/null", O_WRONLY)
        if (devNull >= 0) {
            dup2(devNull, STDOUT_FILENO)
            dup2(devNull, STDERR_FILENO)
            close(devNull)
        }

        memScoped {
            val argv = allocArrayOf(
                *command.map { it.cstr.ptr }.toTypedArray(),
                null
            )
            execvp(command[0], argv)
        }
        _exit(127)
    }

    return BackgroundProcess(pid = pid)
}
