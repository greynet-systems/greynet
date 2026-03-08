@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.system

import kotlinx.cinterop.*
import platform.posix.*

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

data class BackgroundProcess(val pid: Int) {

    fun kill(): Boolean {
        if (pid <= 0) return false
        val killed = kill(pid, SIGKILL) == 0
        if (killed) {
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

// Сериализует pipe()+setCloexec()+fork()+close(write-end) из всех потоков.
// Гарантия: на момент fork() ни один поток не держит write-end pipe без CLOEXEC.
private val forkMutex: CPointer<pthread_mutex_t> = nativeHeap.alloc<pthread_mutex_t>().apply {
    pthread_mutex_init(ptr, null)
}.ptr

private fun currentTimeMs(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC, ts.ptr)
    ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
}

// Мультиплексированное чтение stdout+stderr через один poll().
// Решает две проблемы:
// 1. Последовательное чтение (сначала stdout, потом stderr) → deadlock
//    если child заполнит 64KB stderr buffer пока мы ждём stdout.
// 2. Бесконечный цикл при poll()==0 без общего deadline.
//
// poll() с коротким таймаутом (200ms) — GC safe-point для K/N STW GC.
private fun drainPipes(
    stdoutFd: Int,
    stderrFd: Int,
    deadlineMs: Long,
): Pair<String, String> = memScoped {
    val stdoutSb = StringBuilder()
    val stderrSb = StringBuilder()
    val buf = ByteArray(4096)

    var stdoutOpen = true
    var stderrOpen = true

    val pfds = allocArray<pollfd>(2)

    while (stdoutOpen || stderrOpen) {
        val remaining = deadlineMs - currentTimeMs()
        if (remaining <= 0) break

        var nfds = 0
        if (stdoutOpen) {
            pfds[nfds].fd = stdoutFd
            pfds[nfds].events = POLLIN.toShort()
            pfds[nfds].revents = 0
            nfds++
        }
        if (stderrOpen) {
            pfds[nfds].fd = stderrFd
            pfds[nfds].events = POLLIN.toShort()
            pfds[nfds].revents = 0
            nfds++
        }

        val timeout = minOf(remaining, 200L).toInt() // 200ms — GC safe-point
        val ready = poll(pfds, nfds.toULong(), timeout)
        if (ready < 0) break

        for (i in 0 until nfds) {
            val revents = pfds[i].revents.toInt()
            val fd = pfds[i].fd

            if (revents and (POLLIN or POLLHUP) != 0) {
                val n = buf.usePinned { pinned ->
                    read(fd, pinned.addressOf(0), buf.size.toULong())
                }
                if (n <= 0) {
                    if (fd == stdoutFd) stdoutOpen = false
                    else stderrOpen = false
                } else {
                    val text = buf.decodeToString(0, n.toInt())
                    if (fd == stdoutFd) stdoutSb.append(text)
                    else stderrSb.append(text)
                }
            }
            if (revents and (POLLERR or POLLNVAL) != 0) {
                if (fd == stdoutFd) stdoutOpen = false
                else stderrOpen = false
            }
        }
    }

    Pair(stdoutSb.toString(), stderrSb.toString())
}

// Подготовка argv до fork(): все Kotlin-аллокации ДО fork(),
// в child после fork() — только POSIX-вызовы (dup2, close, execvp, _exit).
// Иначе Kotlin runtime (GC, locks) в невалидном состоянии после fork() → segfault.
private class PreparedArgv(command: List<String>) {
    val cmd: String = command[0]

    private val cStrings: List<CPointer<ByteVar>> = command.map { arg ->
        val bytes = arg.encodeToByteArray()
        val ptr = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
        bytes.forEachIndexed { i, b -> ptr[i] = b }
        ptr[bytes.size] = 0
        ptr
    }
    val argv: CArrayPointer<CPointerVar<ByteVar>> = nativeHeap.allocArray<CPointerVar<ByteVar>>(cStrings.size + 1).also { arr ->
        cStrings.forEachIndexed { i, ptr -> arr[i] = ptr }
        arr[cStrings.size] = null
    }

    fun dispose() {
        cStrings.forEach { nativeHeap.free(it) }
        nativeHeap.free(argv)
    }
}

// timeoutMs: общий таймаут на весь процесс (fork→read→waitpid).
// По умолчанию 10s — достаточно для curl --max-time 2 и nft-вызовов.
// При превышении: SIGKILL child, возврат ProcessResult с timedOut=true.
fun runProcess(command: List<String>, timeoutMs: Long = 10_000L): ProcessResult {
    val stdoutPipe = IntArray(2)
    val stderrPipe = IntArray(2)

    val prepared = PreparedArgv(command)
    val startMs = currentTimeMs()

    pthread_mutex_lock(forkMutex)

    var pid: Int = -1
    try {
        stdoutPipe.usePinned { pinned ->
            if (pipe(pinned.addressOf(0)) != 0) {
                prepared.dispose()
                return ProcessResult(exitCode = -1, stdout = "", stderr = "pipe() failed for stdout")
            }
        }
        setCloexec(stdoutPipe[0])
        setCloexec(stdoutPipe[1])

        stderrPipe.usePinned { pinned ->
            if (pipe(pinned.addressOf(0)) != 0) {
                close(stdoutPipe[0]); close(stdoutPipe[1])
                prepared.dispose()
                return ProcessResult(exitCode = -1, stdout = "", stderr = "pipe() failed for stderr")
            }
        }
        setCloexec(stderrPipe[0])
        setCloexec(stderrPipe[1])

        pid = fork()

        if (pid < 0) {
            close(stdoutPipe[0]); close(stdoutPipe[1])
            close(stderrPipe[0]); close(stderrPipe[1])
            prepared.dispose()
            return ProcessResult(exitCode = -1, stdout = "", stderr = "fork() failed")
        }

        if (pid == 0) {
            // child: только POSIX-вызовы, никаких Kotlin-аллокаций!
            dup2(stdoutPipe[1], STDOUT_FILENO)
            dup2(stderrPipe[1], STDERR_FILENO)
            var fd = 3; while (fd <= 1023) { close(fd); fd++ }
            execvp(prepared.cmd, prepared.argv)
            _exit(127)
        }

        // parent: закрыть write-end ДО unlock
        close(stdoutPipe[1])
        close(stderrPipe[1])
    } finally {
        pthread_mutex_unlock(forkMutex)
    }
    prepared.dispose()

    val deadlineMs = startMs + timeoutMs
    val (stdout, stderr) = drainPipes(stdoutFd = stdoutPipe[0], stderrFd = stderrPipe[0], deadlineMs = deadlineMs)
    close(stdoutPipe[0])
    close(stderrPipe[0])

    // Проверяем, завершился ли child. Если нет — таймаут, убиваем.
    return memScoped {
        val status = alloc<IntVar>()
        val ret = waitpid(pid, status.ptr, WNOHANG)

        if (ret == 0) {
            // child ещё жив → SIGKILL
            kill(pid, SIGKILL)
            waitpid(pid, status.ptr, 0)
            ProcessResult(
                exitCode = -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = true,
            )
        } else {
            val exitCode = (status.value shr 8) and 0xFF
            ProcessResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
        }
    }
}

fun startBackground(command: List<String>): BackgroundProcess {
    val prepared = PreparedArgv(command)

    val pid: Int
    pthread_mutex_lock(forkMutex)
    try {
        pid = fork()

        if (pid == 0) {
            // child: только POSIX-вызовы, никаких Kotlin-аллокаций!
            val devNull = open("/dev/null", O_WRONLY)
            if (devNull >= 0) {
                dup2(devNull, STDIN_FILENO)
                dup2(devNull, STDOUT_FILENO)
                dup2(devNull, STDERR_FILENO)
                close(devNull)
            }
            var fd = 3; while (fd <= 1023) { close(fd); fd++ }
            execvp(prepared.cmd, prepared.argv)
            _exit(127)
        }
    } finally {
        pthread_mutex_unlock(forkMutex)
    }

    prepared.dispose()

    if (pid < 0) {
        return BackgroundProcess(pid = -1)
    }

    return BackgroundProcess(pid = pid)
}
