# Fix Android Ktor request body serialization

Keep this artifact compact. Prefer short bullets. Omit or mark `N/A` for fields that do not apply. Do not paste long chat transcripts or full visual artifacts here.

## Goal

Make Android session preparation send the JSON request body successfully after health and capability checks pass.

## Context

The phone reaches `192.168.1.149:5001` and completes the earlier GET requests. Ktor 3.5.1 rejects `setBody(PrepareSessionRequestDto(...))` because the POST request has no `Content-Type`, so ContentNegotiation cannot select the JSON converter.

## Decisions

- Set `Content-Type: application/json` on the prepare-session POST request.
- Keep JSON serialization behind `HttpReceiverControlClient` and test the request through Ktor's mock engine.
- Add `ktor-client-mock` as a test-only dependency at the existing Ktor version. It owns in-memory HTTP request assertions, is Apache-2.0 licensed like Ktor, and has no APK runtime impact.
- Do not change the receiver protocol or add a manual JSON encoder.

## Acceptance Criteria

- The prepare-session request is serialized as JSON instead of failing before network transmission.
- The request includes `Content-Type: application/json`.
- A regression test verifies the JSON request body and response mapping.
- Android tests, lint, and debug APK assembly pass.

## Implementation Plan

- Add explicit JSON content type to the prepare-session request.
- Add a focused Ktor mock-engine test for the request body and headers.
- Format, test, lint, assemble, inspect the diff, and commit.

## Task Contract

- Scope: Android control-client request serialization.
- Out of scope: receiver behavior, codec negotiation, media streaming, and UI changes.
- Files or areas likely to change: `HttpReceiverControlClient`, Android test dependencies, and HTTP client tests.
- Interfaces or behavior contracts: `/v1/sessions/prepare` continues to use the existing versioned JSON schema.
- Risks and edge cases: Ktor 3 requires an explicit content type for object bodies; test both header and serialized JSON fields.
- Open questions: None.

## Verification Plan

- Run the Ktor mock-engine regression test and the Android unit test suite.
- Run Android lint and assemble the debug APK.
- Reinstall the APK and verify health, capabilities, and session preparation against the running receiver on a physical phone.

## Status

Complete.

## Handoff Notes

- Next exact step: install the rebuilt APK on the phone and retry connection. The receiver should now reach session preparation with a JSON body.
- Files changed: `HttpReceiverControlClient`, Android version catalog, app test dependencies, and `HttpReceiverControlClientTest`.
- Commands run: Gradle targeted regression test; Gradle `test lint assembleDebug`; `git diff --check`.
- Errors encountered: initial mock assertion checked the request builder header, but Ktor removes that header after converting it into `TextContent`; the regression now verifies the outgoing JSON content type and body instead.
- Verification evidence: the mock-engine regression test passes, Android tests pass, lint passes, and the debug APK assembles at `android/app/build/outputs/apk/debug/app-debug.apk`.
