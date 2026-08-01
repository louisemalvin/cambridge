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

## Codec negotiation fails

- Use Auto with `1080p30` first.
- Forced H.265 is expected to fail when either endpoint lacks H.265 support.
- A codec can support 1080p30 and reject 1440p30 or 4K30.
- A successful capability advertisement does not replace actual RootEncoder
  preparation; the preparation result is authoritative.

## No virtual-camera device

Run:

```bash
scripts/linux/inspect-video-devices.sh
scripts/linux/setup-v4l2loopback.sh 10
```

The receiver never runs `sudo`, loads modules, unloads modules, or changes
Secure Boot. Review the printed `modprobe` command manually. Check that the
device driver is `v4l2loopback` and that the receiver user can open it.

## `v4l2sink` or caps negotiation fails

- Verify `gst-inspect-1.0 v4l2sink`.
- Start with 1080p30 and output format Auto.
- Try explicit `--output-format yuy2` for broad consumer compatibility.
- Use NV12 for high-resolution tests only when the consumer accepts it.
- Run `scripts/linux/test-virtual-camera.sh /dev/video10` before involving
  the phone.

## H.265 synthetic test is skipped

Install the GStreamer package containing `x265enc`. The receiver only needs
an H.265 parser and decoder, while the synthetic sender additionally needs a
software H.265 encoder.

## The stream stops or the phone heats up

The app keeps a foreground notification and uses short-lived wake and Wi-Fi
locks only while streaming. Sustained camera encoding produces heat and
battery drain. Check the stability run metrics and stop the session before
the device enters thermal throttling.
