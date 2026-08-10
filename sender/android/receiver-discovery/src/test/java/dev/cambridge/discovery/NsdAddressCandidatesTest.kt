package dev.cambridge.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class NsdAddressCandidatesTest {
    @Test
    fun combinesResolvedAndContractAdvertisedAddressesInIndexOrder() {
        val addresses = resolvedAddressCandidates(
            resolvedHosts = listOf(LAN_ADDRESS),
            attributes = mapOf(
                "address1" to SECOND_VPN_ADDRESS.toByteArray(),
                "address0" to VPN_ADDRESS.toByteArray(),
                "name" to "OBS receiver".toByteArray(),
            ),
            addressAttributePrefix = ADDRESS_PREFIX,
            maximumAddressAttributeCount = MAXIMUM_ADDRESS_COUNT,
            addressFamily = ReceiverDiscoveryAddressFamily.ANY,
        )

        assertEquals(listOf(LAN_ADDRESS, VPN_ADDRESS, SECOND_VPN_ADDRESS), addresses)
    }

    @Test
    fun ignoresMalformedOutOfRangeAndDuplicateAddressAttributes() {
        val addresses = resolvedAddressCandidates(
            resolvedHosts = listOf(LAN_ADDRESS),
            attributes = mapOf(
                "address0" to LAN_ADDRESS.toByteArray(),
                "address2" to VPN_ADDRESS.toByteArray(),
                "address-not-an-index" to SECOND_VPN_ADDRESS.toByteArray(),
                "address16" to SECOND_VPN_ADDRESS.toByteArray(),
            ),
            addressAttributePrefix = ADDRESS_PREFIX,
            maximumAddressAttributeCount = MAXIMUM_ADDRESS_COUNT,
            addressFamily = ReceiverDiscoveryAddressFamily.ANY,
        )

        assertEquals(listOf(LAN_ADDRESS, VPN_ADDRESS), addresses)
    }

    @Test
    fun filtersCandidatesToTheConfiguredAddressFamily() {
        val addresses = resolvedAddressCandidates(
            resolvedHosts = listOf(LAN_ADDRESS, IPV6_ADDRESS),
            attributes = mapOf(
                "address0" to VPN_ADDRESS.toByteArray(),
                "address1" to HOSTNAME.toByteArray(),
            ),
            addressAttributePrefix = ADDRESS_PREFIX,
            maximumAddressAttributeCount = MAXIMUM_ADDRESS_COUNT,
            addressFamily = ReceiverDiscoveryAddressFamily.IPV4,
        )

        assertEquals(listOf(LAN_ADDRESS, VPN_ADDRESS), addresses)
    }

    private companion object {
        const val ADDRESS_PREFIX = "address"
        const val MAXIMUM_ADDRESS_COUNT = 16
        const val LAN_ADDRESS = "192.168.1.10"
        const val VPN_ADDRESS = "100.64.0.10"
        const val SECOND_VPN_ADDRESS = "100.64.0.11"
        const val IPV6_ADDRESS = "2001:db8::10"
        const val HOSTNAME = "receiver.example"
    }
}
