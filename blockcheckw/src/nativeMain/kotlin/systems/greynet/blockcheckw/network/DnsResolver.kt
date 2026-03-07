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

fun resolveIpv4(domain: String): Either<DnsError, List<String>> {
    cache[domain]?.let { return it.right() }

    val result = runProcess(listOf("getent", "ahostsv4", domain))

    if (result.exitCode != 0) {
        return DnsError.ResolveFailed(domain, "getent exit code=${result.exitCode}").left()
    }

    val ips = result.stdout.lineSequence()
        .map { it.split("\\s+".toRegex()).firstOrNull().orEmpty() }
        .filter { it.isNotEmpty() && it[0].isDigit() }
        .distinct()
        .toList()

    if (ips.isEmpty()) {
        return DnsError.NoAddresses(domain).left()
    }

    cache[domain] = ips
    return ips.right()
}

fun dnsErrorToString(error: DnsError): String = when (error) {
    is DnsError.ResolveFailed -> "DNS resolve failed for ${error.domain}: ${error.reason}"
    is DnsError.NoAddresses -> "No IPv4 addresses found for ${error.domain}"
}
