# HiddenCam

HiddenCam is an Android camera recorder with per-folder encrypted storage, private playback, calculator-based app authentication, and explicit recording controls.

## What changed

- The locked entry screen is a functional calculator, not a PIN-only keypad. It supports decimal arithmetic, parentheses, percentages, and the usual arithmetic operators.
- Tap `=` to calculate. Long-press `=` to submit the current entry as the configured app PIN. A PIN can be configured as 4–12 digits; no default PIN is embedded.
- A rejected PIN does not display a wrong-PIN message. The existing retry counter and timed lockout remain active.
- The interface palette is monochrome.
- Protected-folder entries include private video playback. The app authenticates the complete AES-GCM file before exposing a decrypted temporary cache file to the player; the file is removed when the playback screen closes.
- The dashboard includes an opt-in live Camera2 preview with front/rear selection. It requests camera permission when enabled, closes camera resources on pause, and releases the camera before the recording service starts.
- The first-run notice explicitly explains camera recording, the Android camera privacy indicator, and the ongoing recording notification.
- The existing visible camera foreground service, recording notification, encrypted-folder requirement, and recovery path are retained.

## Build

Requirements: Java 17, Gradle 8.13, Android SDK platform 36 and build-tools 36.0.0.

```sh
gradle --no-daemon clean testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. The release variant is unsigned until a release signing configuration is provided. There is no Gradle wrapper in this source tree.

## Recording transparency and Android restrictions

Recording starts only after a user action while the app is in the foreground, with camera permission granted and a protected folder unlocked and selected. While the camera is active, Android's camera privacy indicator and the ongoing HiddenCam recording notification remain enabled. HiddenCam does not attempt to bypass Android's indicator or start camera use through a hidden background trigger. Android and device firmware can restrict camera service continuation under some conditions.

## Storage and security

- Protected folders use PBKDF2-HMAC-SHA256 with 600,000 iterations and AES-256-GCM.
- Each protected folder's password-derived key is retained in memory only while that folder is unlocked.
- Legacy recording files use per-file Android Keystore AES-256-GCM keys.
- Playback decrypts only into the application's private cache and deletes the temporary file on exit. As with any app-private plaintext, process termination or power loss can interrupt cleanup; startup cleanup removes stale playback cache files.
- Exporting a protected-folder file is an explicit user action through the Android document picker.
- No microphone, location, contacts, or network permission is requested.

## Limits

The live preview and recording service use the camera sequentially: the preview releases the camera before recording starts, and is resumed after recording finalization when the user previously enabled it. Simultaneous preview and recording output is not implemented. The project should be built and exercised on the intended Android devices before distributing an APK.
