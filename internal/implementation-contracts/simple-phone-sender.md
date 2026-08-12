# CamBridge Simple Streaming Contract

Internal status: approved cross-platform sender implementation handoff.

## Authority

This is the normative sender product contract for Android and iOS.

The existing machine-readable
[`cambridge-stream-contract.json`](../../protocol/cambridge-stream-contract.json)
remains the authority for CamBridge v6 control and media transport. This
document does not change the wire protocol or the OBS receiver. It defines how
phone senders obtain and use the values sent over that protocol.

Existing sender code, tests, mode catalogs, and older documentation do not
override this contract. An implementation agent must update conflicting
behavior and documentation.

Where older internal Android or iOS sender contracts conflict with this
contract's product behavior, this contract takes precedence. The existing
machine-readable v6 wire contract remains authoritative for protocol behavior.

## Core behavior

```text
user chooses settings
    -> sender attempts those exact settings once
    -> sender sends those exact settings to the receiver
    -> the stream starts, or Start returns a clear error
```

The sender must not negotiate, infer, downgrade, or replace the user's stream
settings. Start is the authoritative camera and encoder compatibility test.

Android and iOS must present the same product concepts. Camera2, MediaCodec,
AVFoundation, and VideoToolbox details stay inside their platform adapters.

## Stream setup

The normal setup UI contains four independent controls:

1. receiver;
2. resolution;
3. frame rate; and
4. bitrate.

Resolution and frame rate must be separate controls. The UI must not present
combined modes such as `1080p30`, `1080p60`, or `2k30`.

Initial choices and defaults are:

| Setting | Choices | Fresh-install default |
| --- | --- | --- |
| Resolution | Full HD (`1920x1080`), 2K (`2560x1440`) | Full HD |
| Frame rate | `30 fps`, `60 fps` | `30 fps` |
| Bitrate | Editable Mbps value | `5 Mbps` |

### Bitrate defaults and override

The shared sender configuration contains this one defaults table:

| Resolution | 30 fps | 60 fps |
| --- | ---: | ---: |
| Full HD (`1920x1080`) | `5 Mbps` | `10 Mbps` |
| 2K (`2560x1440`) | `9 Mbps` | `18 Mbps` |

Changing resolution or frame rate replaces the bitrate field with the matching
default. The user may then enter another bitrate.

Example:

```text
select Full HD + 60 fps -> bitrate becomes 10 Mbps
enter 1 Mbps manually   -> bitrate remains 1 Mbps
press Start             -> sender requests and announces 1 Mbps
```

Opening the screen, connecting a receiver, or pressing Start must not replace
a manual bitrate. Changing resolution or frame rate again applies the new
default again.

The bitrate input accepts base-10 whole Mbps from `1` through `100`. `N Mbps`
means exactly `N * 1,000,000` bits per second. Fractions, exponents, grouping,
rounding, and clamping are not accepted. Invalid or partial text is not
committed and disables Start without opening camera, encoder, or network
resources.

A committed manual bitrate persists across screen reopen, app restart, failed
Start, and Stop. Only an actual resolution or FPS value change replaces it
with a suggestion. Upgrade migration maps the prior combined mode to its
resolution and FPS and preserves a prior whole-Mbps bitrate when it remains
within the new input bounds.

The default table is convenience, not a hardware limit. A valid manual value
must not be intersected with or rejected because of an advertised encoder
bitrate range.

Bitrate is fixed during a stream. Changing it requires Stop and another Start.
There is no adaptive bitrate in this contract.

### One shared settings source

There must be one shared source of truth for:

- resolution choices and dimensions;
- frame-rate choices;
- fresh-install defaults; and
- the bitrate-default table.

Android and iOS consume generated or otherwise mechanically shared values from
that source. They must not contain independent copies.

The product settings model contains independent resolution, FPS, and bitrate
values. A final request object may group them for Start and transport, but that
object is not a combined product mode and must not drive a combined picker.

The existing combined sender mode catalog must be replaced or reduced to a
generated compatibility artifact that has no UI or capability authority.

## Camera experience

Camera controls belong on the live camera screen, not in stream setup or
general settings.

### Front and back

The sender starts with the back camera. The live camera screen provides one
standard flip-camera button to switch between back and front.

The normal UI must not expose physical or framework camera names or IDs, such
as `Back Ultra Wide Camera`, `Back Dual Wide Camera`, Camera2 IDs, or
AVFoundation unique IDs.

For the selected facing, the platform adapter chooses the platform-default
logical or virtual camera, with a default physical-camera fallback when the
platform has no logical camera. The product does not rank or ask the user to
select physical lenses.

A live front/back switch keeps the same resolution, FPS, bitrate, receiver,
wire rotation, and wire session. It resets zoom to `1x` and recomputes the
available pills. If the other facing cannot supply the active settings, the
sender makes one best-effort restoration of the current camera and reports a
camera-switch error. If restoration itself fails, the sender stops cleanly.
It never changes the stream settings.

The front preview is mirrored in the familiar camera style. Transmitted pixels
are never mirrored; v6 has no mirroring field.

### Zoom

The live camera screen provides:

- a `1x` pill;
- exactly `0.5x` and `2x` pills when those user-facing targets are supported
  by the active logical or virtual camera; and
- pinch-to-zoom within the supported user-facing range.

The pills are zoom targets, not physical-lens selectors. The platform camera
system decides whether to use or switch physical lenses.

The UI must not present raw framework values such as a `123.75x` maximum as a
normal zoom choice. Camera-facing and zoom changes are not sent to the
receiver.

### Orientation and stabilization

The normal UI does not expose wire-rotation choices or framework stabilization
modes.

The sender snapshots the active interface/display orientation at Start, maps
it to one clockwise v6 quarter-turn, and keeps that wire rotation for the
session. An unknown orientation uses the last valid interface orientation, or
landscape-right when none exists. Mid-stream device rotation does not change
the wire value. A new orientation takes effect after Stop and a new Start.

On iOS, the capture connection requests AVFoundation `.auto` once while the
capture graph is configured and again after a facing switch. On Android, each
initial capture and facing switch requests one advertised mode in this order:
preview stabilization, video stabilization, then optical stabilization. The
Android request explicitly disables the other stabilization family.

Stabilization may resolve to Off for the selected camera, resolution, or FPS.
That outcome does not disable Start, retry another mode, or change stream
settings. Requested and applied stabilization are diagnostics only. The
user-facing promise is "automatically when supported," not "always enabled."

## Start

Start performs one direct attempt:

1. Read the selected width, height, FPS, and bitrate.
2. Validate their syntax and the hard v6 bounds.
3. Select the platform-default camera for the current facing.
4. Configure camera output for the selected dimensions and FPS.
5. Create and configure the real hardware H.264 encoder for the selected
   dimensions, FPS, and bitrate target.
6. Send one v6 `hello` with those exact values.
7. Require the matching receiver `accepted` response.
8. Send H.264 RTP to the accepted UDP media port.

Setup, Settings, diagnostics, startup, and prior failures must not build or
retain a camera-by-FPS-by-encoder capability matrix, instantiate a throwaway
encoder, query or intersect an encoder bitrate range, filter or disable a
product choice, or rewrite a selection. The v6 probe remains solely for
receiver reachability and identity.

The sender must not gate Start on:

- encoder-advertised minimum or maximum bitrate;
- a camera x FPS x encoder capability matrix;
- a temporary encoder used only for setup;
- a physical-lens choice; or
- an optional capability report.

Missing or differently shaped encoder metadata is not proof that H.264 or a
bitrate is unavailable. Creation and configuration of the real encoder is the
test.

Start may inspect camera formats only to choose the one deterministic source
for that attempt. It creates and configures exactly one real hardware encoder.

The configured bitrate is an encoder target. The sender announces the exact
target selected by the user; instantaneous encoded output may naturally vary.

### Camera source and coded output

The selected resolution is the H.264 coded output resolution. The underlying
camera source need not have the same dimensions when the platform can safely
produce the requested output from a compatible source.

The platform adapter may use a same-aspect-ratio source that is at least as
large as the requested output and use normal platform output scaling. Encoder
input and H.264 coded dimensions must still equal the user's selection.

In particular, a sender must not reject 2K merely because the camera lacks an
exact `2560x1440` source format when a larger 16:9 source can produce
`2560x1440`. It must not upscale a smaller source or silently change aspect
ratio.

Source-format selection is deterministic platform plumbing and does not appear
in the normal UI.

On the iOS 17.4+ sender, the native adapter may select the smallest compatible larger 16:9
active format and ask `AVCaptureVideoDataOutput` for explicit NV12 target
buffer dimensions with automatic preview sizing disabled. Every delivered
pixel buffer is checked against the selected dimensions before VideoToolbox.
VideoToolbox is never relied on to resize. Different-aspect sources and
upscaling are rejected.

## Wire values

The receiver receives final numeric settings, not camera capabilities or UI
modes. Full HD, 60 fps, and a manual 1 Mbps bitrate produces a v6 `hello`
equivalent to:

```json
{
  "protocolVersion": 6,
  "type": "hello",
  "sessionId": "<active-session-id>",
  "generation": 1,
  "profileId": "sender",
  "codec": "h264",
  "codedWidth": 1920,
  "codedHeight": 1080,
  "rotationDegrees": 0,
  "fps": 60,
  "bitrateBps": 1000000
}
```

The session ID, generation, and rotation vary. Width, height, FPS, and bitrate
must exactly match the user's settings.

Protocol v6 requires `profileId`, but it has no configuration meaning. All
production phone senders use the constant `sender`. No component may parse it
to obtain resolution, FPS, bitrate, facing, or another setting. Removing the
field requires a future protocol version.

The receiver continues to enforce protocol compatibility, hard geometry and
resource bounds, and one-active-session ownership. It does not choose presets,
negotiate settings, or interpret camera details.

The H.264 and RFC 6184 RTP/UDP path is unchanged.

## Failure and lifecycle

The sender must not retry Start with different settings or silently change:

- resolution;
- FPS;
- bitrate;
- camera facing; or
- hardware H.264 into a software encoder.

If the exact request fails, the sender releases all partial camera, encoder,
control, and media resources and retains the user's displayed settings. The
user-facing error identifies one stage:

- camera rejected the selected resolution or FPS;
- hardware H.264 rejected the selected settings;
- receiver rejected the stream;
- control connection failed; or
- media connection failed.

Platform error details belong in diagnostics, not in required setup choices.
There is no automatic reconnect; the user presses Start again.

Resolution, FPS, bitrate, and wire rotation are locked while streaming. Zoom
and camera facing may change because they do not alter those wire values.

Stop closes camera, encoder, RTP, and control resources through the existing
single lifecycle boundary. The next Start creates a new session and generation
using the settings currently displayed.

## Diagnostics

Diagnostics do not control availability or Start. They should record requested
and applied dimensions, FPS, bitrate target, facing, zoom, chosen camera source
format, hardware encoder result, receiver result, and the failing stage.

Unavailable optional metadata is recorded as unavailable, not converted into
an unsupported result. Receiver hosts remain redacted in copied reports.
Framework camera IDs may appear in explicitly labeled local diagnostics but
not in normal controls.

## Acceptance requirements

The implementation must include automated coverage proving:

1. Fresh install shows Full HD, 30 fps, and 5 Mbps.
2. Resolution and FPS are separate controls.
3. Full HD at 60 fps applies the 10 Mbps default.
4. 2K at 30 and 60 fps applies 9 and 18 Mbps respectively.
5. A manual 1 Mbps override survives Start.
6. Full HD, 60 fps, and 1 Mbps sends `1920`, `1080`, `60`, and `1000000` in
   the matching `hello` fields.
7. Missing encoder bitrate-range metadata does not disable Start.
8. Camera or encoder failure does not try another resolution, FPS, or bitrate.
9. A compatible larger camera source produces the exact selected coded output
   where the platform supports output scaling.
10. Camera flip and zoom do not change the active wire settings.
11. `profileId` is the constant `sender` and is never configuration input.
12. Android and iOS use the same settings/defaults source.
13. Stop releases the session; the next Start uses the displayed settings.

Physical validation must retain one successful OBS stream for each claimed
resolution/FPS combination on Android and iOS, plus the Full HD, 60 fps,
1 Mbps override. A failed combination returns a clear Start error and never
starts a different combination.

## Explicitly out of scope

- adaptive bitrate or congestion control;
- receiver-driven video negotiation;
- automatic quality selection;
- physical-lens selectors;
- combined product mode catalogs;
- mid-session resolution, FPS, bitrate, or rotation changes;
- automatic reconnect; and
- removal of the v6 `profileId` field.

## Implementation handoff

The implementation agent must apply this contract to Android and iOS, preserve
the v6 receiver and RTP/H.264 path, establish the one shared settings source,
replace old capability-matrix and combined-mode tests, and update all
architecture, installation, limitation, diagnostics, and physical-validation
documentation that describes the old behavior.

The work is complete only when both phone senders expose the same simple
behavior and no normal screen requires camera or encoder implementation
knowledge.
