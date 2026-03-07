package systems.greynet.blockcheckw

import arrow.core.Either
import systems.greynet.blockcheckw.config.configExists

fun main(args: Array<String>) {
    val healthcheck = "--healthcheck" in args

    if (healthcheck) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    } else {
        configExists()
            .map {  }
    }
}

sealed interface BlockcheckwError
object ZapretConfigMissing : BlockcheckwError

sealed interface BlockcheckSuccess
data class ZapretConfig(val domains: List<String>) : BlockcheckSuccess
