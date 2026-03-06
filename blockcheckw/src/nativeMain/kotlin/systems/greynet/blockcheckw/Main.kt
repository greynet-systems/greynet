package systems.greynet.blockcheckw

import arrow.core.Either

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

sealed interface BlockcheckSuccess
data class ZapretConfig(val domains: List<String>) : BlockcheckSuccess

fun configExists(): Either<BlockcheckwError, ZapretConfig> {
    TODO("")
}
