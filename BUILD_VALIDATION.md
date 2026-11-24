# Validation record

## Passed in the available environment

- Java syntax parsing passed for all 13 application Java source files using the JDK compiler parser (Java 17 source mode).
- `CalculatorExpression.java` compiled with JDK 21.
- Ten calculator smoke checks passed, covering precedence, parentheses, decimals, percentage, PIN conversion, fractional PIN rejection, range rejection, and division-by-zero rejection.
- All six Android XML resource/manifest files parsed successfully.
- Required project files and source folders were checked for presence.

## Not run

- Android Gradle unit-test task and APK assembly were not run because this environment has no installed Gradle executable or Android SDK.
- Camera preview, recording, encrypted-folder playback, and device lifecycle behavior still require validation on an Android device.

Do not treat this source archive as a verified APK release until the Android build and device checks have passed.
