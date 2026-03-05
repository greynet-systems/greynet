package systems.greynet.firmware.model

data class Strategy(
    val filterTcp: String? = "443",
    val filterUdp: String? = null,
    val dpiDesync: String,
    val ttl: Int? = null,
    val splitPos: String? = null,
    val fooling: String? = null,
    val origTtl: Int? = null,
    val origModStart: String? = null,
    val origModCutoff: String? = null,
    val extra: List<String> = emptyList(),
) {
    fun toNfqwsArgs(): String = buildString {
        filterTcp?.let { append(" --filter-tcp=$it") }
        filterUdp?.let { append(" --filter-udp=$it") }
        append(" --dpi-desync=$dpiDesync")
        ttl?.let { append(" --dpi-desync-ttl=$it") }
        splitPos?.let { append(" --dpi-desync-split-pos=$it") }
        fooling?.let { append(" --dpi-desync-fooling=$it") }
        origTtl?.let { append(" --orig-ttl=$it") }
        origModStart?.let { append(" --orig-mod-start=$it") }
        origModCutoff?.let { append(" --orig-mod-cutoff=$it") }
        extra.forEach { append(" $it") }
    }.trim()

    companion object {
        fun parse(line: String): Strategy {
            val args = line.trim().split(Regex("\\s+"))
            var filterTcp: String? = "443"
            var filterUdp: String? = null
            var dpiDesync: String? = null
            var ttl: Int? = null
            var splitPos: String? = null
            var fooling: String? = null
            var origTtl: Int? = null
            var origModStart: String? = null
            var origModCutoff: String? = null
            val extra = mutableListOf<String>()

            for (arg in args) {
                when {
                    arg.startsWith("--filter-tcp=") -> filterTcp = arg.substringAfter("=")
                    arg.startsWith("--filter-udp=") -> filterUdp = arg.substringAfter("=")
                    arg.startsWith("--dpi-desync=") -> dpiDesync = arg.substringAfter("=")
                    arg.startsWith("--dpi-desync-ttl=") -> ttl = arg.substringAfter("=").toIntOrNull()
                    arg.startsWith("--dpi-desync-split-pos=") -> splitPos = arg.substringAfter("=")
                    arg.startsWith("--dpi-desync-fooling=") -> fooling = arg.substringAfter("=")
                    arg.startsWith("--orig-ttl=") -> origTtl = arg.substringAfter("=").toIntOrNull()
                    arg.startsWith("--orig-mod-start=") -> origModStart = arg.substringAfter("=")
                    arg.startsWith("--orig-mod-cutoff=") -> origModCutoff = arg.substringAfter("=")
                    else -> extra.add(arg)
                }
            }

            requireNotNull(dpiDesync) { "Missing --dpi-desync in: $line" }

            return Strategy(
                filterTcp = filterTcp,
                filterUdp = filterUdp,
                dpiDesync = dpiDesync,
                ttl = ttl,
                splitPos = splitPos,
                fooling = fooling,
                origTtl = origTtl,
                origModStart = origModStart,
                origModCutoff = origModCutoff,
                extra = extra,
            )
        }
    }
}
