package systems.greynet.blockcheckw.network

import systems.greynet.blockcheckw.system.runProcess

data class IpInfo(
    val ip: String,
    val org: String,
    val city: String,
    val region: String,
    val country: String,
)

// TODO: переехать на kotlinx.serialization
private fun extractField(json: String, field: String): String? {
    val regex = Regex(""""$field"\s*:\s*"([^"]+)"""")
    return regex.find(json)?.groupValues?.get(1)
}

fun detectIpInfo(): IpInfo? {
    val result = runProcess(listOf("curl", "-s", "--max-time", "3", "ipinfo.io"))
    if (result.exitCode != 0) return null
    val json = result.stdout
    return IpInfo(
        ip = extractField(json, "ip") ?: return null,
        org = extractField(json, "org") ?: "unknown",
        city = extractField(json, "city") ?: "unknown",
        region = extractField(json, "region") ?: "unknown",
        country = extractField(json, "country") ?: "unknown",
    )
}

// TODO: рендерить "красивую" отформатированную таблицу
fun ipInfoToString(info: IpInfo): String =
    "${info.org} | ${info.ip} | ${info.city}, ${info.region}, ${info.country}"
