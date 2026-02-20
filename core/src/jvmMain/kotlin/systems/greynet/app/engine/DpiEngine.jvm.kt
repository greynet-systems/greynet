package systems.greynet.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files

actual class DpiEngine actual constructor(private val config: DpiConfig) {

    private val _status = MutableStateFlow(EngineStatus.STOPPED)
    private var process: Process? = null

    actual fun start() {
        if (_status.value == EngineStatus.RUNNING) return
        _status.value = EngineStatus.STARTING
        try {
            val binary = resolveBinary()
            val command = buildList {
                add(binary.absolutePath)
                add("--port")
                add(config.listenPort.toString())
                if (config.strategy.isNotBlank()) {
                    addAll(config.strategy.split(" "))
                }
            }
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            _status.value = EngineStatus.RUNNING
        } catch (e: Exception) {
            _status.value = EngineStatus.ERROR
        }
    }

    actual fun stop() {
        process?.destroyForcibly()
        process = null
        _status.value = EngineStatus.STOPPED
    }

    actual fun statusFlow(): StateFlow<EngineStatus> = _status.asStateFlow()

    private fun resolveBinary(): File {
        // Try to find the binary next to the jar first
        val localBinary = File("ciadpi")
        if (localBinary.exists() && localBinary.canExecute()) return localBinary

        // Extract from resources
        val resourceStream = this::class.java.getResourceAsStream("/byedpi/ciadpi")
            ?: error("ByeDPI binary not found in resources. Run ./gradlew :core:downloadByedpi first.")

        val tempDir = Files.createTempDirectory("byedpi").toFile()
        tempDir.deleteOnExit()
        val tempBinary = File(tempDir, "ciadpi")
        resourceStream.use { input ->
            tempBinary.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempBinary.setExecutable(true)
        return tempBinary
    }
}
