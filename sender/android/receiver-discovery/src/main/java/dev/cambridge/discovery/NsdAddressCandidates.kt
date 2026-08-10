package dev.cambridge.discovery

internal fun resolvedAddressCandidates(
    resolvedHosts: List<String>,
    attributes: Map<String, ByteArray>,
    addressAttributePrefix: String?,
    maximumAddressAttributeCount: Int,
    addressFamily: ReceiverDiscoveryAddressFamily,
): List<String> {
    val advertisedHosts = if (addressAttributePrefix == null) {
        emptyList()
    } else {
        attributes.mapNotNull { (key, value) ->
            val index = key.removePrefix(addressAttributePrefix)
                .takeIf { key.startsWith(addressAttributePrefix) }
                ?.toIntOrNull()
                ?: return@mapNotNull null
            index to value.toString(Charsets.UTF_8).trim()
        }.filter { (index, host) ->
            index in NO_ADDRESS_INDEX until maximumAddressAttributeCount && host.isNotEmpty()
        }.sortedBy { (index, _) -> index }
            .map { (_, host) -> host }
    }
    return (resolvedHosts + advertisedHosts)
        .filter(String::isNotBlank)
        .filter(addressFamily::accepts)
        .distinct()
}

private fun ReceiverDiscoveryAddressFamily.accepts(host: String): Boolean = when (this) {
    ReceiverDiscoveryAddressFamily.ANY -> true
    ReceiverDiscoveryAddressFamily.IPV4 -> host.isIpv4Literal()
}

private fun String.isIpv4Literal(): Boolean {
    val octets = split(IPV4_OCTET_SEPARATOR)
    return octets.size == IPV4_OCTET_COUNT && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) &&
            octet.toIntOrNull()?.let { value ->
                value in MINIMUM_IPV4_OCTET..MAXIMUM_IPV4_OCTET
            } == true
    }
}

private const val NO_ADDRESS_INDEX = 0
private const val IPV4_OCTET_SEPARATOR = '.'
private const val IPV4_OCTET_COUNT = 4
private const val MINIMUM_IPV4_OCTET = 0
private const val MAXIMUM_IPV4_OCTET = 255
