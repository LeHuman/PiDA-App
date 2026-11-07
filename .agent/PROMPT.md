# PiDA Android Companion

## Purpose

PiDA exposes devices managed by a private action server through native Android Device Controls and home-screen widgets. Users should be able to change device parameters, see confirmed device state, and trigger existing actions without opening a browser.

## Product Principles

- The server and controlled device are the source of truth; do not present cached or inferred values as live state.
- Use explicit, typed read and write operations for device state. Never accept arbitrary shell text or interpolate untrusted values into commands.
- Fetch state when Android or the user requests it. Avoid periodic polling and idle background work.
- Preserve existing action and shortcut workflows while new controls are validated.
- Keep control IDs stable. Do not promise a particular system-panel placement; Android UI behavior varies by device and OS version.

## Privacy And Security

- Keep the server on a private network or VPN. Do not expose an unauthenticated API to the public internet.
- Keep endpoint addresses, SSH configuration, keys, tokens, and device-specific values out of tracked files.
- Prefer HTTPS. If private-network HTTP is required, scope cleartext access as narrowly as the configurable endpoint design permits.

## Project Conventions

- Android package: `com.pida.app`; implementation is Kotlin with Gradle.
- Keep app behavior compatible with the existing server schema and stable Android control IDs.
- Consult `TODO.MD` for outstanding work and `NOTES.md` for implementation constraints and research.
