# Technical Notes

## Build And Configuration

- Use JDK 17 and the repository Gradle wrapper: `gradlew.bat assembleDebug`.
- Configure the Android SDK through the local development environment. `local.properties` is machine-specific and must remain untracked.
- Do not add host paths, private IP addresses, SSH aliases, credentials, or device-specific values to tracked configuration or examples.

## Server Contract

The Android app talks to a separate Pi-hosted action server; server implementation files are not part of this Android repository.

- Read-only routes include health, action/schema listing, endpoint listing, and icons.
- `POST /api/<group>/<action>` dispatches an action. Some existing servers also dispatch on `GET /api/<group>/<action>`; never use mutating GET requests because browsers and intermediaries may prefetch them.
- Device state changes should use typed operations with bounded input validation and normalized responses containing confirmed post-write state.
- The current server is intended for private-network/VPN use and has no authentication. Do not port-forward it to the public internet.

## Android Device Controls

- Device Controls are available from Android 11 / API 30. `RangeTemplate` provides a slider; `ToggleRangeTemplate` combines a toggle and range.
- A `ControlsProviderService` publishes controls and responds to system state/action requests. Query device state on request and publish the confirmed result.
- Quick Settings `TileService` is a separate API for tap-oriented tiles; it does not provide a general inline slider beside system brightness controls.
- OEMs choose the presentation and placement. Validate on the target device rather than assuming a particular One UI surface.
- Keep control IDs stable. Changing IDs can invalidate existing favorites and require users to add controls again.

## Current Schema Mapping

- The first boolean parameter is used as the control toggle; the first numeric parameter is paired with it as the primary range when available.
- Additional numeric parameters can be surfaced as separate controls. Additional booleans are currently page-only.
- `ControlCatalog` currently selects the first parameterized action per group. Do not add multi-action or shared-parameter schema concepts until a concrete device requires them.
- If schema-level UI composition is later needed, define it in the server schema and validate references during schema loading. Avoid device-specific UI code.
- Monitor brightness backed only by a local cache is not confirmed live state.

## Private Connectivity

- The simplest remote path is a user-configured WireGuard tunnel with routes limited to the required private network. Keep all endpoint and peer settings local.
- An app-owned tunnel requires Android's VPN permission and cannot coexist with another active `VpnService` in the same profile. It cannot silently activate another VPN app's tunnel.
- Do not build an app-owned tunnel or expose the server directly until the target connectivity requirement is confirmed. Never include a private key in the repository.