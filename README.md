<!-- PROJECT: PiDA -->
<!-- TITLE: PiDA App -->
<!-- KEYWORDS: Android, Device Controls, Widgets, Home Automation -->
<!-- LANGUAGES: Kotlin -->
<!-- TECHNOLOGY: Android SDK, REST API -->

<!-- LOGO -->
![PiDA](media/pida-logo-app.png)

[About](#about) | [Key Features](#key-features) | [Demo](#demo) | [Usage](#usage) | [Related](#related) | [License](#license) | [Contribution](#contribution)

## Status

<!-- STATUS -->
**Prototype**

## About
<!-- DESCRIPTION START -->
PiDA is an Android companion for controlling devices exposed by a PiDA server. It provides Android Device Controls, configurable device controls in the app, and home-screen widgets.
<!-- DESCRIPTION END -->

### Why
<!-- WHY START -->
PiDA provides a lightweight Android interface for actions exposed by a compatible server. It gives users direct control of server-managed devices without requiring a broader automation suite, while leaving execution and device integration with the server.
<!-- WHY END -->

## Key Features

- Android Device Controls for system-level access to supported controls.
- In-app device controls for schema-defined parameters, including sliders and switches.
- Home-screen widgets for pinned device actions.
- Server URL configuration in the app.
- State refresh on demand, with cached widget data for rendering.

## Demo

<!-- DEMO -->
No demo recording is included yet. Build and install the app on an Android device to try it.

## Usage

### Requirements

- Android 11 (API 30) or newer.
- JDK 17.
- Android SDK platform 34 and the SDK build tools required by the Android Gradle Plugin.
- A reachable action server that implements PiDA's expected API.

### Building

Build a debug APK with the Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Installing

Install the APK on a connected device with ADB:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Running

Open PiDA from the device's app launcher. To open it from ADB:

```sh
adb shell am start -n com.pida.app/.MainActivity
```

After configuring a server, add PiDA controls through Android's Device Controls interface or pin a supported widget from the launcher's widget picker.

### Configuration

Enter the base URL in the app, save it, and use **Test API** to check connectivity. The server must provide:

- `GET /api/health` for connection status.
- `GET /api/schema` for device groups, actions, and parameters.

Device Controls must be enabled and favorited through the Android system UI. Exact presentation and placement depend on the Android version and device manufacturer.

## Security

Keep the action server on a trusted private network or behind authentication. Do not expose an unauthenticated server to the public internet. For remote access, use a VPN or another secure, authenticated channel.

## Related

- [PiDA](https://github.com/LeHuman/PiDA) — the server this app controls.
- [Android Device Controls](https://developer.android.com/develop/ui/views/device-control)
- [Android App Widgets](https://developer.android.com/develop/ui/views/appwidgets)

## License

This project is licensed under the [MIT License](LICENSE).

## Contribution

Contributions are welcome. Keep changes focused, and do not include private server addresses, credentials, or device-specific details.
