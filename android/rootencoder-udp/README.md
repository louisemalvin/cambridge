# RootEncoder UDP transport

This module is a scoped fork of RootEncoder 2.8.0's Apache-2.0 UDP module. The
published module fixes MPEG-TS datagrams at seven 188-byte packets (1316 bytes)
and provides no payload-size API. That exceeds the safe UDP payload on a
1280-byte IPv6 or VPN path.

This fork preserves RootEncoder's public UDP API and sends six MPEG-TS packets
per datagram (1128 bytes). Remove it when upstream exposes equivalent packet
sizing and the physical VPN-path regression passes against the published
artifact.
