/*
 * Derived from RootEncoder 2.8.0, Copyright (C) 2024 pedroSG94.
 * Licensed under the Apache License, Version 2.0.
 */
package com.pedro.udp

internal const val MPEG_TS_PACKET_BYTES = 188
internal const val MPEG_TS_PACKETS_PER_DATAGRAM = 6
internal const val MPEG_TS_UDP_PAYLOAD_BYTES =
    MPEG_TS_PACKET_BYTES * MPEG_TS_PACKETS_PER_DATAGRAM
internal const val IPV6_MINIMUM_MTU_BYTES = 1_280
internal const val IPV6_AND_UDP_HEADER_BYTES = 48
internal const val IPV6_MINIMUM_UDP_PAYLOAD_BYTES =
    IPV6_MINIMUM_MTU_BYTES - IPV6_AND_UDP_HEADER_BYTES
