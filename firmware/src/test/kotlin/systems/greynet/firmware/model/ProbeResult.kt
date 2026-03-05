package systems.greynet.firmware.model

import kotlin.time.Duration

data class ProbeResult(
    val available: Boolean,
    val httpCode: Int? = null,
    val speedBytesPerSec: Long? = null,
    val connectTime: Duration? = null,
    val ttfb: Duration? = null,
    val totalTime: Duration? = null,
    val error: String? = null,
)
