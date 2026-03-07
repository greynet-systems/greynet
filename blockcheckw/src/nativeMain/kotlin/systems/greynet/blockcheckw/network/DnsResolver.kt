package systems.greynet.blockcheckw.network

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import systems.greynet.blockcheckw.system.runProcess

sealed interface DnsError {
    data class ResolveFailed(val domain: String, val reason: String) : DnsError
    data class NoAddresses(val domain: String) : DnsError
}

private val cache = mutableMapOf<String, List<String>>()

private val IPV4_REGEX = Regex("""\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b""")

// TODO: resolve должен на вход получать уже стратегию: либо работаем с getent, либо с nslookup
fun resolveIpv4(domain: String): Either<DnsError, List<String>> {
    cache[domain]?.let { return it.right() }

    val ips = resolveWithGetent(domain) ?: resolveWithNslookup(domain)

    if (ips == null) {
        return DnsError.ResolveFailed(domain, "getent and nslookup both failed").left()
    }

    if (ips.isEmpty()) {
        return DnsError.NoAddresses(domain).left()
    }

    cache[domain] = ips
    return ips.right()
}

// FIXME: вынести наверх логику про выбор инструмента nslookup/getent
private fun resolveWithGetent(domain: String): List<String>? {
    val result = runProcess(listOf("getent", "ahostsv4", domain))
    if (result.exitCode != 0) return null

    val ips = result.stdout.lineSequence()
        .map { it.split("\\s+".toRegex()).firstOrNull().orEmpty() }
        .filter { it.isNotEmpty() && it[0].isDigit() }
        .distinct()
        .toList()

    return ips
}

// FIXME: вынести наверх логику про выбор инструмента nslookup/getent
private fun resolveWithNslookup(domain: String): List<String>? {
    val result = runProcess(listOf("nslookup", domain))
    if (result.exitCode != 0) return null

    // nslookup выводит сначала сервер, потом ответ — парсим только после "Name:"
    val answerSection = result.stdout.substringAfter("Name:", "")
    if (answerSection.isEmpty()) return null
    val ips = IPV4_REGEX.findAll(answerSection)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    return ips
}

fun dnsErrorToString(error: DnsError): String = when (error) {
    is DnsError.ResolveFailed -> "DNS resolve failed for ${error.domain}: ${error.reason}"
    is DnsError.NoAddresses -> "No IPv4 addresses found for ${error.domain}"
}
