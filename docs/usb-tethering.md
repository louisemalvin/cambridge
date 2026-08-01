# USB tethering

USB tethering uses the same two application connections as Wi-Fi. The phone
does not send a custom USB protocol:

```text
HTTP control -> Linux receiver address:5001/TCP
MPEG-TS media -> Linux receiver address:5000/UDP
```

## Procedure

1. Start the receiver on the Linux host or stop it until the address is known.
2. Connect the Android phone with a USB cable.
3. Enable USB tethering in Android network settings.
4. On Linux, inspect all interfaces and routes:

   ```bash
   ip -br address
   ip route
   ```

5. Identify the address assigned to the USB-tethered Linux interface. Do not
   assume it is `usb0`, `rndis0`, or a particular `enp*` name.
6. Start the receiver bound to `0.0.0.0` or the selected host address.
7. Enter the Linux tethered address in the Android app and keep control port
   `5001`.
8. Confirm the session state and selected codec before measuring latency.

The exact address range and interface name are controlled by Android and the
Linux network manager. A route can exist while the firewall still blocks the
ports, so test both TCP control and UDP media. For a temporary trusted setup,
allow inbound TCP `5001` and UDP `5000` on the tethered interface only.

If USB tethering is not available, use Wi-Fi without changing the app or
receiver media implementation. Switching networks requires a new reachable
receiver address, not a different protocol.
