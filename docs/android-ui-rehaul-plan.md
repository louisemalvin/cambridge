# Android preview-first UI rehaul

Status: complete. The feature-oriented presentation architecture, Navigation 3, destination ViewModels, destination-owned immutable state and mappers, Hilt DI, grouped infrastructure packages, persisted sender settings, discovered/manual receiver-origin presentation, and RootEncoder Camera2Source migration are implemented and verified.

## Direction

Make the Android app a preview-first surface:

- Keep one full-screen preview shell for every app state.
- Show `Waiting for connection` in the shell while no receiver session exists.
- Keep receiver-origin entry, stream defaults, lens selection, stabilization, and diagnostics out of the main surface.
- Expose a small set of overlay actions using Material 3 components: screen dim, zoom, and settings.
- Keep the action toolbar horizontal in portrait and vertically aligned on the
  right in landscape.
- Preserve the existing RootEncoder camera boundary, negotiated profile dimensions, rotation handling, and stable Compose BOM.

The implemented RootEncoder engine creates one `Camera2Source` during session preparation and keeps its lifecycle operations on the main dispatcher. Therefore the UI shows connected standby before demand starts a session rather than adding a second local-preview camera lifecycle. A pre-connection live camera preview remains a separate media-lifecycle change.

## Proposed Material 3 composition

`SenderApp` should become a `Scaffold` with a `SnackbarHost`, containing a preview stage that owns the screen geometry and overlays:

- `BoxWithConstraints` plus a pure fit calculation keeps the selected profile aspect ratio inside the available window in portrait and landscape. Letterbox space remains black and the preview is never stretched.
- The Android `SurfaceView` remains the narrow `AndroidView` adapter. It receives the fitted surface dimensions and current display orientation; it does not own connection or settings UI.
- `IconButton` or `FilledTonalIconButton` provides dim and settings actions. Use Material icons from the Compose icon artifact rather than custom drawn glyphs.
- A compact Material 3 zoom affordance keeps pinch-to-zoom on the preview and opens a standard `Slider` in a small overlay/tray. The slider remains bounded by the camera-reported range and keeps reset at 1x.
- A full-screen settings destination uses `TopAppBar`, `ListItem`, `Switch`,
  `Slider`, `DropdownMenu`, and standard dividers/typography. This keeps the
  settings list usable in landscape without a modal bottom-sheet constraint.
- `AlertDialog` handles camera permission when required. A `SnackbarHost` handles transient failures; the existing copy-diagnostics action remains available from the failure surface.

## Main surface states

| State | Main surface | Secondary surface |
| --- | --- | --- |
| Idle or no receiver | Black fitted stage with `Waiting for connection` | Settings button; permission dialog only when needed |
| Receiver origin missing | Waiting stage remains visible | Material 3 connection form |
| Checking, negotiating, preparing, starting | Fitted preview shell or neutral waiting stage with progress/status | Settings remains available where safe |
| Streaming | Fitted live preview with minimal status and adaptive action toolbar | Zoom tray, settings screen, snackbar |
| Failed | Fitted stage with concise error state | Snackbar or dialog with copy diagnostics |
| Stopping | Preview shell with stopping status | Actions disabled until idle |

The main screen should not expose codec, profile, bitrate, receiver details, lens controls, or stabilization controls as a permanent vertical form.

## Settings screen sections

1. Camera: physical lens options when supported, stabilization switch when supported, and the full zoom slider/reset.
2. Stream defaults: codec preference and video profile selectors. Changes continue to affect the next receiver session, not an active stream.
3. Connection: active receiver name and a concise stream status.
4. Diagnostics: failure explanation and copy action only when a failure exists.

The settings destination should be scrollable as one `LazyColumn`. It should
not introduce another screen-level scroll owner around the preview.

## MVVM and state boundaries

The implementation keeps infrastructure state out of Compose. Destination-scoped
`PairingViewModel`, `WebcamViewModel`, and `SettingsViewModel` map coordinator,
camera, and settings state into immutable presentation models and handle screen
actions. Navigation 3 entry decorators scope those ViewModels to their
destinations.

Each destination keeps its route, screen, immutable state, and mapper together:

- `feature/pairing`: `PairingRoute`, `PairingScreen`, `PairingUiState`, and `PairingUiStateMapper`;
- `feature/webcam`: `WebcamRoute`, `WebcamScreen`, `WebcamUiState`, and `WebcamUiStateMapper`;
- `feature/settings`: `SettingsRoute`, `SettingsScreen`, `SettingsUiState`, and `SettingsUiStateMapper`.

The `app/model` package contains only shared presentation values and common
stream/camera mapping helpers. It does not contain a monolithic state object for
all destinations, and feature mappers consume an immutable
`StreamPresentationSnapshot` rather than reaching into infrastructure state.

### Model and service state

- Keep `StreamState`, `NegotiatedSession`, `VideoProfile`, `CodecPreference`,
  `CameraInteractionState`, and controller interfaces below the UI package.
- These types describe streaming, camera, and negotiation behavior.
  They must not gain Compose annotations, UI labels, modal visibility, or
  Android view references.
- The coordinator and camera controller remain the sources of truth for their
  respective behavior.

### Destination state

Each destination exposes an immutable state containing only values its screen
can render. `WebcamUiState` contains, for example:

- `preview`: fitted aspect ratio, orientation, and whether a live surface is
  expected;
- `connection`: waiting, origin entry, connecting, streaming, stopping, or failed
  presentation state with display-ready text/resource IDs;
- `camera`: zoom display/range, lens options with display labels, and
  stabilization availability/state;
- `dialog`: camera-permission dialog state when one is active; receiver
  receiver-origin entry is owned by the Pairing destination;
- `isScreenDimmed`: the screen preference for this activity/session;
- `isZoomTrayOpen`: main-surface presentation state.

`SettingsUiState` owns codec/profile options, connection details, diagnostics,
and camera controls. `PairingUiState` owns receiver-origin entry and connection status.
Shared display values such as connection states, camera controls, and actions
remain in `app/model` because they are consumed by more than one destination.

Destination state must not contain `StreamState`, `CameraInteractionState`,
RootEncoder objects, protocol DTOs, or raw exception types. A pure mapper from
the shared infrastructure snapshot to each destination state should be unit
tested independently of Compose.

### ViewModel and UI contract

- `PairingViewModel`, `WebcamViewModel`, and `SettingsViewModel` expose the
  presentation state needed by their destinations and delegate actions to the
  coordinator or camera controller. The Navigation 3 entry decorators own the
  destination-scoped ViewModel lifecycles.
- Expose a small `SenderScreenAction` surface for settings, dimming, zoom,
  lens, stabilization, permission, receiver-origin, stop, and diagnostics actions.
- Expose one-shot `SenderUiEffect` values only for events the UI must perform,
  such as copying diagnostics or showing a transient message. The ViewModel
  does not access `Context`, `ClipboardManager`, `Toast`, or Compose APIs.
- `PairingScreen`, `WebcamScreen`, and `SettingsScreen` receive their
  destination state and callbacks only. They do not access the coordinator,
  RootEncoder, or Android camera APIs.
- Keep main-surface visibility state in `WebcamUiState` and change it only
  through ViewModel actions. Do not mix those booleans into the domain model.
  Internal dropdown expansion inside a settings component may remain local
  because it does not affect the screen projection or domain behavior.

This keeps MVVM meaningful: model state owns behavior, the ViewModel owns the
screen projection and intent handling, and Compose owns rendering plus narrow
ephemeral interaction state.

## Resolved implementation decisions

- Screen dim uses a reversible black scrim over the preview. It is lifecycle-safe and avoids window-brightness restoration concerns. The action uses the outlined brightness icons.
- Zoom uses pinch as the primary interaction plus a compact zoom chip/tray on the main surface. The slider is bounded by the camera-reported range and supports reset to 1x.
- The pre-connection surface remains a waiting state because the current engine opens its single camera source after negotiation. A live pre-connection preview would require a separate camera ownership decision.

## Acceptance criteria for implementation

- The app has one preview-first screen composition instead of separate connect and streaming columns.
- Portrait and landscape preview geometry preserve the selected profile aspect ratio without stretching or nested unbounded scrolling.
- The main surface contains only the preview, waiting/status treatment, dim action, zoom affordance, and settings action.
- Codec, profile, diagnostics, and connection details are reachable from Material 3 surfaces without being permanently visible. Physical lens choices are capability-gated by Android logical multi-camera metadata, while stabilization remains capability-gated because the RootEncoder `Camera2Source` adapter does not expose a supported control for it.
- Compose receives only destination-owned immutable state; domain state is translated by pure ViewModel mappers and is not passed directly into screen composables.
- Existing camera interaction and session contracts remain unchanged unless an explicit pre-connection preview decision is made.
- Compose tests cover waiting, streaming, settings visibility, dim state, rotation/aspect-ratio layout, and the existing zoom bounds/reset behavior.
- Android unit tests, instrumentation compilation, lint, and debug assembly remain green.
