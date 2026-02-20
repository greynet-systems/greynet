package systems.greynet.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class DpiEngine actual constructor(private val config: DpiConfig) {

    private val _status = MutableStateFlow(EngineStatus.STOPPED)

    actual fun start() {
        _status.value = EngineStatus.ERROR
    }

    actual fun stop() {
        _status.value = EngineStatus.STOPPED
    }

    actual fun statusFlow(): StateFlow<EngineStatus> = _status.asStateFlow()
}
