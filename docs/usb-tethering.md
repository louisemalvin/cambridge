# USB tethering

USB tethering uses the same application contracts as Wi-Fi. The phone
does not send a custom USB protocol:

```text
local subnet probe -> Android sender:53555/TCP
reverse control -> Android sender:53555/TCP
HTTP control -> Linux receiver address:5001/TCP
MPEG-TS media -> Linux receiver UDP 50000-50099
```

## Procedure

1. Open the Android app.
2. Connect the Android phone with a USB cable.
3. Enable USB tethering in Android network settings.
4. On Linux, inspect all interfaces and routes:

   ```bash
   ip -br address
   ip route
   ```

5. Start the desktop receiver bound to `0.0.0.0`.
6. Select the phone if more than one sender is discovered.
7. Approve the desktop on the phone the first time.
8. Confirm the session state and selected codec before measuring latency.

The exact address range and interface name are controlled by Android and the
Linux network manager. A route can exist while the firewall still blocks the
services, so test sender TCP control, receiver TCP control, and the negotiated
UDP media port in `50000-50099`. Limit firewall access to the tethered
interface.

If USB tethering is not available, use Wi-Fi without changing the app or
receiver media implementation. The apps rediscover each other after switching
networks.
