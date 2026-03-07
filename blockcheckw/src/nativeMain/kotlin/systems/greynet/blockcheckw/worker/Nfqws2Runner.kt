@file:OptIn(ExperimentalForeignApi::class)

package systems.greynet.blockcheckw.worker

import kotlinx.cinterop.*
import platform.posix.uname
import platform.posix.utsname
import systems.greynet.blockcheckw.system.BackgroundProcess
import systems.greynet.blockcheckw.system.startBackground

private const val NFQWS2_ARM64 = "/opt/zapret2/binaries/linux-arm64/nfqws2"
private const val NFQWS2_X86_64 = "/opt/zapret2/binaries/linux-x86_64/nfqws2"

fun detectNfqws2Path(): String = memScoped {
    val info = alloc<utsname>()
    uname(info.ptr)
    val machine = info.machine.toKString()
    when {
        machine.contains("aarch64") || machine.contains("arm") -> NFQWS2_ARM64
        else -> NFQWS2_X86_64
    }
}

fun startNfqws2(qnum: Int, strategyArgs: List<String>): BackgroundProcess {
    val nfqws2 = detectNfqws2Path()
    val cmd = listOf(nfqws2, "--qnum=$qnum", "--fwmark=0x10000000") + strategyArgs
    return startBackground(cmd)
}
