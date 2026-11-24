# Changelog

## 1.2.0
- Replaced the PIN-only unlock keypad with a working decimal calculator.
- `=` tap evaluates; long-press `=` submits the current expression/value for authentication.
- Kept configurable 4–12 digit PINs, salted PBKDF2 verification, duress behavior, and retry lockout.
- Removed visible incorrect-PIN feedback while preserving retry accounting and lockout.
- Applied a monochrome interface palette to primary app screens.
- Added private playback for video files stored in encrypted protected folders.
- Authenticated complete AES-GCM content in app-private cache before passing it to Media3 playback.
- Expanded the first-launch notice to explain camera permission and visible Android recording indicators.

## 1.1.0
- Added password-protected encrypted folders and per-folder keys.
- Added camera foreground-service recording and protected-folder encryption.
- Added recovery flow for interrupted or failed saves.
- Added optional Duress Code and emergency lock.
