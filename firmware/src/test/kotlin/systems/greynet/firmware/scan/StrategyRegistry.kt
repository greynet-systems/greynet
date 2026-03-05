package systems.greynet.firmware.scan

import systems.greynet.firmware.model.Strategy

object StrategyRegistry {

    fun loadFrom(resourcePath: String): List<Strategy> {
        val stream = StrategyRegistry::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("Resource not found: $resourcePath")

        return stream.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { Strategy.parse(it) }
    }
}
