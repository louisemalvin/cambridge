# Troubleshooting

## The desktop does not find the phone

- Keep both apps open and confirm both devices are on the same local network.
- Allow TCP `53555` to the phone on the trusted interface.
- Check for access-point client isolation, which prevents local devices from
  reaching each other.
- Confirm the Linux interface has an IPv4 subnet no larger than 4096 addresses.
- Restart either app to refresh discovery state.
- The product has no manual IP fallback. A discovery failure should be fixed at
  the network or advertisement boundary.

## Control works but no video arrives

- Allow UDP `50000:50099` to the receiver from the trusted subnet.
- Read the session preparation response and confirm the phone uses its returned
  UDP port in that range.
- Inspect `udpsrc`, `tsparse`, `tsdemux`, and the selected parser with
  `gst-inspect-1.0`.
- Run the H.264 synthetic sender to separate Android camera issues from the
  receiver path.
- Check `timeoutCount` and the receiver log for a wrong codec.

## MPEG-TS continuity warnings or `not-negotiated`

Continuity warnings identify missing, reordered, or mixed MPEG-TS packets. They
are separate from raw-output caps negotiation. The receiver gives each session
its own UDP port, so an old or unselected phone does not normally share the
active media socket. PID `0x0020` is the normal first video PID used by the
current sender.

- Prefer a stable Wi-Fi connection or USB tethering and avoid competing high-
  bandwidth traffic.
- Restart the sender after the receiver has returned to `Idle` or
  `TimedOut`.
- Compare sender and receiver logs around the first discontinuity before
  changing socket or latency settings.

The receiver normalizes decoded frame rate with `videorate` before fixed
virtual-camera caps. A decoded 25 FPS stream no longer fails merely because the
selected output profile is 30 FPS.

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
