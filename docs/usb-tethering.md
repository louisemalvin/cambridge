# USB tethering

USB tethering uses the same application contracts as Wi-Fi. The phone
does not send a custom USB protocol. Configure the receiver origin using the
Linux address reachable over the tethered interface:

```text
Android sender -> receiver HTTP v2 origin:5001/TCP
Android SRT caller -> Linux receiver SRT listener:5000 (UDP transport)
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
6. Select the receiver from automatic discovery when it is visible. Enter the
   receiver origin manually if multicast discovery is unavailable.
7. Enter the optional bearer token when the receiver requires one.
8. Confirm the session state and selected codec before measuring latency.

The exact address range and interface name are controlled by Android and the
Linux network manager. A route can exist while the firewall still blocks the
services, so test the receiver TCP control port and SRT listener port. Limit
firewall access to the tethered interface.

If USB tethering is not available, use Wi-Fi without changing the app or
receiver media implementation. If discovery does not cross the tethered
interface, use the manual fallback after switching networks when the receiver
address changes.
