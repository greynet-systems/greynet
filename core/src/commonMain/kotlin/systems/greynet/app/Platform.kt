package systems.greynet.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform