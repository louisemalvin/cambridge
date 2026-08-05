# Troubleshooting

## The phone cannot discover or reach the receiver

- Confirm the receiver is running and visible on the same local network. It
  advertises `_mobile-webcam._tcp.local.` for automatic discovery.
- If multicast discovery is blocked, open the manual connection fallback and
  enter the receiver's reachable host and HTTP control port.
- Check that TCP `5001` and the SRT listener on port `5000` are allowed on the
  trusted interface. SRT uses UDP transport.
- If UFW is active, configure `MOBILE_WEBCAM_TRUSTED_SUBNET` before running the
  installer instead of relying on automatic subnet detection.
- For an emulator, use `10.0.2.2` in the sender's manual origin fallback. The
  receiver derives the SRT host from that control origin automatically.

## Control works but no video arrives

- Allow SRT port `5000` over UDP to the receiver from the trusted subnet.
- Read the v2 session response and confirm the phone uses its returned SRT host,
  stream ID, latency, key length, and passphrase.
- Inspect `srtsrc`, `tsparse`, `tsdemux`, and the selected parser with
  `gst-inspect-1.0`.
- Run the H.264 synthetic sender to separate Android camera issues from the
  receiver path.
- Check `timeoutCount` and the receiver log for a wrong codec.

## MPEG-TS continuity warnings or `not-negotiated`

Continuity warnings identify missing, reordered, or mixed MPEG-TS packets. They
are separate from raw-output caps negotiation. SRT keeps one listener while
per-session stream IDs prevent an old or unselected phone from feeding the
active session. PID `0x0020` is the normal first video PID used by the current
sender.

- Prefer a stable Wi-Fi connection or USB tethering and avoid competing high-
  bandwidth traffic.
- Restart the sender after the receiver has returned to `Idle` or
  `Reconnecting`.
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

The v2 receiver keeps the virtual camera open during SRT reconnects and sends
standby frames after the inactivity timeout. Run
`scripts/linux/test-srt-receiver.sh` for authentication and reconnect checks,
`scripts/linux/test-srt-lifecycle.sh` for repeated cleanup, and
`scripts/linux/test-srt-sustained.sh` for the sustained-run gate.
