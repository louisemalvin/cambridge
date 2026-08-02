# Android preview-first MVVM UI rehaul

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Replace the Android configuration-first screen with a preview-first Material 3
surface while preserving the existing RootEncoder camera and streaming
contracts. Establish a strict MVVM boundary with a presentation-only screen
state before changing the visual composition.

## Context

- This branch was isolated from the performance-observability worktree.
- The current `SenderUiState` leaked domain types directly into Compose.
- `StreamState`, `CameraInteractionState`, negotiation models, and controller
  interfaces remain below the ViewModel boundary.
- The current engine creates the camera source during negotiated session
  preparation. Before a receiver connects, show a waiting state rather than
  adding a second local camera lifecycle.
- Preview geometry must preserve the selected profile aspect ratio in portrait
  and landscape. Encoded profile dimensions remain unchanged.

## Decisions

- Use `SenderScreenState`, `SenderScreenAction`, and `SenderUiEffect` as the
  screen contract. Compose does not receive domain state or access controllers.
- `SenderViewModel` combines domain flows and maps them through a pure mapper.
- Main-surface state, including dimmed, settings open, and zoom-tray open,
  is ViewModel-owned. Only internal dropdown expansion may be local component
  state.
- Use a full-screen fitted preview shell with black letterbox space and
  Material 3 overlay actions.
- Use a full-screen `Scaffold` settings destination with `TopAppBar`,
  `LazyColumn`, `ListItem`, `Switch`, `Slider`, and standard dropdown menus.
  Avoid a bottom sheet so settings remain usable in landscape.
- Use a reversible black scrim for dimming with
  `Icons.Outlined.BrightnessLow` and `BrightnessHigh`; do not change window
  hardware brightness in this pass.
- Keep pinch zoom and add a compact Material 3 zoom tray; keep the full slider
  and reset action available in settings.
- Do not change the receiver protocol, RootEncoder ownership, or session
  interfaces.

## Acceptance Criteria

- Compose consumes only immutable `SenderScreenState` and callbacks/effects.
- A pure state mapper covers waiting, approval, connecting, streaming,
  stopping, failure, camera permission, zoom, lens, stabilization, settings,
  dimming, and dialog presentation.
- The main screen is preview-first and has no permanent codec/profile/lens/OIS
  form or screen-level vertical scroll.
- Portrait and landscape preview layout preserves the selected profile aspect
  ratio without stretching or overflow.
- Settings exposes codec/profile, lens, stabilization, full zoom, connection,
  and diagnostics through Material 3 components.
- Pending approval and camera permission use standard Material 3 dialogs.
- Existing zoom bounds/reset, camera surface lifecycle, and stream behavior
  remain intact.
- Android unit tests, Compose instrumentation compilation, lint, and debug
  assembly pass.

## Implementation Plan

- Add presentation-only screen models, actions, effects, and a pure mapper with
  JVM tests.
- Refactor `SenderViewModel` to expose one mapped screen `StateFlow` while
  retaining existing controller delegation.
- Replace the root screen composition with a Material 3 preview shell that
  calculates a fitted oriented preview rectangle.
- Add state-driven overlay actions, dim scrim, zoom tray, waiting/status
  treatments, permission/approval dialogs, and snackbar/effect handling.
- Move camera and stream controls into a scrollable Material 3 settings list.
- Update strings, icons dependency if required, Compose tests, and durable
  Android UI documentation.
- Run Android gates and inspect changed production literals before handoff.

## Task Contract

- Scope: Android UI presentation models, ViewModel mapping/actions, preview
  layout, Material 3 screen/list/dialog components, tests, and UI docs.
- Out of scope: receiver changes, performance instrumentation, protocol/media
  changes, hardware brightness control, pre-connection camera ownership, and
  new camera sessions.
- Files or areas likely to change: `android/app/src/main/java/.../ui`,
  `android/app/src/main/res/values/strings.xml`, Compose dependencies/version
  catalog if icons require it, Android unit/instrumentation tests, and Android
  documentation.
- Interfaces or behavior contracts: domain and camera/session interfaces remain
  unchanged; UI consumes only the new presentation contract.
- Risks and edge cases: SurfaceView destruction/recreation, display rotation,
  profile changes before the next session, unsupported camera controls,
  pending approval during waiting, failures while settings are open, and a later
  rebase onto performance changes.
- Open questions: N/A.

## Verification Plan

- `git diff --check`.
- `./android/gradlew -p android test lint assembleDebug`.
- `./android/gradlew -p android assembleDebugAndroidTest`.
- JVM tests for the screen mapper and fitted preview geometry.
- Compose tests for waiting, streaming controls, dim icon/state, settings
  visibility, dialogs, and existing zoom controls.
- Physical-device follow-up for portrait/landscape rotation, surface
  recreation, dim behavior, and stream continuity.

## Status

The preview-first implementation and adaptive-toolbar/settings-list refinement
are complete on `main` in commits `37c0578` and `cefb450`. The merged tree also
contains the completed performance-observability commit. A JSON serialization
compatibility fix was recorded as `9ab9dd9`.

## Handoff Notes

- Next exact step: unlock/wake the connected device, then validate rotation,
  preview surface recreation, waiting/approval flow, zoom, dimming, settings,
  and stream continuity.
- ADB currently detects `vivo V2413` as `10DECJ0JKG0003C`.
- Files changed: Android presentation models/mapper, ViewModel, preview-first
  Compose screen, Material icon dependency, resources, tests, Android docs,
  and this task artifact.
- Commands run: `work-context`, `task-init`, `task-ready`,
  `work-context <task-file>`, `git diff --check`, the merged-tree Android
  test/lint/debug and instrumentation APK gates, and
  `connectedDebugAndroidTest`.
- Errors encountered: Initial Gradle checks lacked `JAVA_HOME` and Android SDK
  environment variables; rerun with the installed JDK 17 and SDK paths. One
  lint pass caught Compose resource lookup inside a coroutine and was fixed.
  The build still reports existing SDK XML compatibility and native-library
  strip warnings without failing.
- Verification evidence: JVM tests, lint, debug APK assembly, and
  instrumentation APK assembly pass on the merged tree. The device runner
  now resolves the Compose test activity, but the phone was screen-off/locked,
  so physical camera and visual validation remain pending.
