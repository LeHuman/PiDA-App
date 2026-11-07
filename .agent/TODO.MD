# TODO

## Current Status

- The Android companion app, dynamic device cards, Android Device Controls provider, and home-screen widget flow are implemented.
- The server URL is user-configured and starts blank; the app prompts for it instead of sending a request to an unset endpoint.
- `gradlew.bat assembleDebug` was verified successfully in this workspace. Run it again after source changes.
- Emulator verification has previously covered connection, state readback, and control actions. Recheck against the current app before relying on old device-state observations.

## Remaining Work

- [ ] Validate control discovery, favorite/pin flow, slider updates, and refresh behavior on a target Samsung device and current One UI release.
- [ ] Verify widget pinning, restored widget state, and offline behavior on a physical device.
- [ ] Address narrow-panel control-title truncation if it remains visible on target devices.
- [ ] Review the app's cleartext HTTP policy. Prefer HTTPS and restrict HTTP to private endpoints where the configurable-server design allows it.
- [ ] Keep the external action server private. Add authentication before supporting access outside a trusted private network or VPN.
- [ ] Before labeling any additional device state as live, verify that the server reads actual hardware state rather than a local cache.

## Acceptance Checks

- `gradlew.bat assembleDebug` completes successfully.
- A blank server setting causes no malformed request; a configured server can load the schema and state.
- Device Control and widget actions report unavailable/error states cleanly when the server cannot be reached.
- Writes are validated by the server and the app renders confirmed post-write state.