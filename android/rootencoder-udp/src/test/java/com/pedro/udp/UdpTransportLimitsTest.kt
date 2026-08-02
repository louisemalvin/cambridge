package com.pedro.udp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpTransportLimitsTest {
    @Test
    fun payload_contains_whole_transport_packets_and_fits_minimum_ipv6_mtu() {
        assertEquals(0, MPEG_TS_UDP_PAYLOAD_BYTES % MPEG_TS_PACKET_BYTES)
        assertEquals(1_128, MPEG_TS_UDP_PAYLOAD_BYTES)
        assertTrue(MPEG_TS_UDP_PAYLOAD_BYTES <= IPV6_MINIMUM_UDP_PAYLOAD_BYTES)
    }
}
