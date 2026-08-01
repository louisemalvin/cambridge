# Troubleshooting

## The phone cannot reach the receiver

- Confirm the receiver is running and the address is on the same network.
- Test the control port with `curl http://RECEIVER_IP:5001/v1/health`.
- Check the Linux address with `ip -br address` and routes with `ip route`.
- Allow TCP `5001` and UDP `5000` on the correct Wi-Fi or tethered interface.
- For IPv6, enter a literal address in brackets when a UI or URL requires it.

## Control works but no video arrives

- Confirm the media port is `5000` or use the port returned by preparation.
- Check that the phone is sending to the receiver address, not the phone's own address.
- Inspect `udpsrc`, `tsparse`, `tsdemux`, and the selected parser with
  `gst-inspect-1.0`.
- Run the H.264 synthetic sender to separate Android camera issues from the
  receiver path.
- Check `timeoutCount` and the receiver log for a wrong codec.

## MPEG-TS continuity warnings or `not-negotiated`

Continuity warnings identify missing or reordered UDP datagrams. A small number
can recover at the next keyframe, but a sustained burst can prevent GStreamer
from negotiating or decoding the stream. PID `0x0020` is the normal first video
PID used by the current RootEncoder MPEG-TS sender; it is not an error by
itself.

- Prefer a stable Wi-Fi connection or USB tethering and avoid competing high-
  bandwidth traffic.
- Restart the sender after the receiver has returned to `Idle` or
  `TimedOut`.
- Check the Linux UDP receive limits:

  ```bash
  sysctl net.core.rmem_default net.core.rmem_max
  ```

- If the maximum is unusually small, increase it temporarily before starting
  the receiver, for example:

  ```bash
  sudo sysctl -w net.core.rmem_max=4194304
  sudo sysctl -w net.core.rmem_default=4194304
  ```

The receiver declares the input as 188-byte MPEG-TS and requests a bounded
receive buffer. Linux may clamp that request to its configured maximum. UDP
has no retransmission, so persistent packet loss still requires improving the
network path.

## Codec negotiation fails

- Use Auto with `1080p30` first.
- Forced H.265 is expected to fail when either endpoint lacks H.265 support.
- A codec can support 1080p30 and reject 1440p30 or 4K30.
- A successful capability advertisement does not replace actual RootEncoder
  preparation; the preparation result is authoritative.

## No virtual-camera device

Run the one-time installer first:

```bash
./scripts/linux/install-receiver.sh
```

The receiver automatically selects the first device whose driver is
`v4l2loopback`, so daily startup does not require `/dev/video10` or any other
device path. If setup has already been attempted, inspect the current state:

```bash
scripts/linux/inspect-video-devices.sh
scripts/linux/setup-v4l2loopback.sh 10
```

The receiver never runs `sudo`, loads modules, unloads modules, or changes
Secure Boot. The installer also refuses to overwrite conflicting module
configuration. Review its error and any printed `modprobe` command manually.
Check that the receiver user can open the detected device.

## `v4l2sink` or caps negotiation fails

- Verify `gst-inspect-1.0 v4l2sink`.
- Start with 1080p30 and output format Auto.
- Try explicit `--output-format yuy2` for broad consumer compatibility.
- Use NV12 for high-resolution tests only when the consumer accepts it.
- Run `scripts/linux/test-virtual-camera.sh` before involving the phone.

## H.265 synthetic test is skipped

Install the GStreamer package containing `x265enc`. The receiver only needs
an H.265 parser and decoder, while the synthetic sender additionally needs a
software H.265 encoder.

## The stream stops or the phone heats up

The app keeps a foreground notification and uses short-lived wake and Wi-Fi
locks only while streaming. Sustained camera encoding produces heat and
battery drain. Check the stability run metrics and stop the session before
the device enters thermal throttling.
