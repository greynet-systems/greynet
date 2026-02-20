package systems.greynet.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class DpiEngine actual constructor(private val config: DpiConfig) {

    // TODO: implement backing fields, see: https://youtube.com/shorts/WInSa0Ks9EY?si=98NfkNIY2xlReYj7 + https://youtube.com/shorts/3E4HgWiWYpA?si=GtxyTDim7HB4xkVR
    private val _status = MutableStateFlow(EngineStatus.STOPPED)
    private var process: Process? = null

    actual fun start() {
        if (_status.value == EngineStatus.RUNNING) return
        _status.value = EngineStatus.STARTING
        try {
            val binaryPath = "/data/local/tmp/ciadpi" // TODO: copy from assets to app internal storage
            val command = buildList {
                add(binaryPath)
                add("--port")
                add(config.listenPort.toString())
                if (config.strategy.isNotBlank()) {
                    addAll(config.strategy.split(" "))
                }
            }
            process = Runtime.getRuntime().exec(command.toTypedArray())
            _status.value = EngineStatus.RUNNING
        } catch (e: Exception) {
            _status.value = EngineStatus.ERROR
        }
    }

    actual fun stop() {
        process?.destroy()
        process = null
        _status.value = EngineStatus.STOPPED
    }

    actual fun statusFlow(): StateFlow<EngineStatus> = _status.asStateFlow()
}
