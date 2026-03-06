package systems.greynet.blockcheckw

fun main(args: Array<String>) {
    val healthcheck = "--healthcheck" in args

    if (healthcheck) {
        println("blockcheckw: ${getPlatform()} - OK")
        return
    }
}
