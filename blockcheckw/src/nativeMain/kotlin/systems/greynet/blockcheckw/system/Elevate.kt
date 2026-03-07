@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.system

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.system.exitProcess

/**
 * Перезапуск процесса с предоставлением `sudo` прав, если таких не было
 */
fun requireRoot(argv: Array<String>) {
    if (geteuid() == 0u) return

    println()
    println("* checking privileges")
    println("root is required")

    // попробовать sudo — как в оригинальном blockcheck2.sh
    val exePath = readSelfExe()
    if (exePath == null) {
        println("cannot determine own executable path")
        exitProcess(2)
    }

    if (commandExists("sudo")) {
        println("elevating with sudo")
        val cmd = listOf("sudo", "--", exePath) + argv.toList()
        execCommand(cmd)
        // execCommand не возвращается при успехе
    }

    if (commandExists("su")) {
        println("elevating with su")
        val quoted = (listOf(exePath) + argv.toList()).joinToString(" ") { "\"$it\"" }
        execCommand(listOf("su", "--preserve-environment", "root", "-c", quoted))
    }

    println("sudo or su not found")
    exitProcess(2)
}

private fun readSelfExe(): String? = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val len = readlink("/proc/self/exe", buf, 4095u)
    if (len > 0) buf.toKString().take(len.toInt()) else null
}

private fun commandExists(name: String): Boolean {
    val result = runProcess(listOf("which", name))
    return result.exitCode == 0
}

private fun execCommand(command: List<String>): Nothing {
    memScoped {
        val argv = allocArrayOf(
            *command.map { it.cstr.ptr }.toTypedArray(),
            null
        )
        execvp(command[0], argv)
    }
    // execvp вернулся — значит ошибка
    perror("execvp")
    exitProcess(2)
}
