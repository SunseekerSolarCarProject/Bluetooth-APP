# Android App

Native Kotlin Android implementation for the ESP32 BLE telemetry protocol.

## Current Status

This is an initial Android Studio project scaffold. It includes:

- Native Android BLE scanning and GATT connection code.
- Notify subscription for the telemetry characteristic.
- Command writes to the control characteristic with 20-byte chunking.
- A simple dashboard/log UI built in Kotlin.
- Kotlin parsing for `TEL`, `STATUS`, and `$LOG` records.

## Open In Android Studio

1. Open the `android/` folder in Android Studio.
2. Let Gradle sync.
3. Connect an Android phone with BLE support.
4. Run the `app` configuration.

This machine currently does not have `ANDROID_HOME`, Gradle, or `adb` configured,
so the app was scaffolded but not built locally by Codex.

## BLE Permissions

The app requests Android 12+ nearby-device permissions:

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`

Older Android versions use legacy Bluetooth/location permissions.
