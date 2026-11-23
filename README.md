# HiddenCam

HiddenCam is an Android camera recorder with an application-private encrypted recording vault.

## Included in this build

- A mandatory first-launch ethical-use acknowledgement.
- Six-digit PIN setup and unlock through an in-app numeric keypad.
- Rear-camera and front-camera selection.
- Camera2 video capture through a camera foreground service.
- An ongoing notification with actions to secure-and-stop or discard a recording.
- Per-recording AES-256-GCM encryption using non-exportable Android Keystore keys.
- Encrypted recordings stored under the application's private `files/vault` directory.
- Private playback through a temporary decrypted file in the application's cache directory.
- Cryptographic deletion by deleting the recording's Android Keystore key before removing its encrypted file.
- No microphone permission, network permission, analytics SDK or application-level logging calls.
- A GitHub Actions workflow that builds and uploads debug and unsigned release APK artifacts.

## Build

The GitHub Actions workflow is located at `.github/workflows/build-apk.yml`. It installs Java 17, Gradle 8.13 and Android SDK 36, builds both debug and release variants, verifies the APK outputs, and uploads the debug APK and unsigned release APK as workflow artifacts. The release APK is intentionally unsigned; configure a repository-managed signing process before distributing a production release.

The project uses Android Gradle Plugin 8.13.2, Gradle 8.13, Java 17, compile/target SDK 36 and minimum SDK 26.

With Gradle 8.13 and Android SDK 36 installed locally, run:

```sh
gradle --no-daemon clean :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The unsigned release APK is written to `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Android camera behavior

Start recording while HiddenCam is open and in the foreground, after granting camera and notification permissions. The camera foreground service then owns the camera session. On compatible devices, recording can continue while another application is in front or while the display is locked. Device firmware, camera hardware, Android version and system policy can still affect continuity.

Android's camera privacy indicator and HiddenCam's ongoing recording notification are intentionally preserved. Android restricts starting camera foreground services from the background, so HiddenCam does not try to launch a camera session from a hidden background trigger.

## Security notes and current scope

- The six-digit PIN verifier uses PBKDF2-HMAC-SHA256 with a random salt and a local retry delay. The PIN is an application access gate; it is not used as the media encryption key. A six-digit PIN remains a relatively low-entropy credential; use a strong device lock as well.
- Each encrypted recording receives an independent AES-256-GCM key generated in Android Keystore. The key is non-exportable through the ordinary application API. Deleting the key provides cryptographic erasure of that encrypted recording.
- Flash storage, filesystems and storage controllers may retain old physical blocks. Rewriting a file with random bytes three times cannot be guaranteed to erase flash media. HiddenCam therefore deletes the per-recording encryption key rather than claiming a reliable multi-pass overwrite.
- During capture, MediaRecorder writes a temporary file inside the app's private cache. When a recording is saved, that file is encrypted and then removed. A process crash or power loss during capture can leave a temporary file until the app next starts and performs stale-cache cleanup.
- Private playback temporarily decrypts the selected recording into the app's private cache. The temporary copy is deleted when the playback screen closes and stale playback files are cleaned at the next launch.
- Front and rear capture are supported as separate choices. Simultaneous dual-camera capture is not included in this initial build.
- A global volume-button trigger is not included. The notification's secure-and-stop action is the user-visible stop control available while recording, subject to lock-screen notification settings.
- A calculator launcher disguise, concealment of camera use, and suppression of Android privacy indicators are not included.

This is an initial source build. Validate recording, screen-lock continuation, playback and deletion on the target devices before relying on it for important footage.
