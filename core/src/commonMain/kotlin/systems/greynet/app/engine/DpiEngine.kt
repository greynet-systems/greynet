package systems.greynet.app.engine

import kotlinx.coroutines.flow.StateFlow

expect class DpiEngine(config: DpiConfig) {
    fun start()
    fun stop()
    fun statusFlow(): StateFlow<EngineStatus>
}
