TiVo Channels Home — README

What I changed
- `TiVoButtonService.kt`: intercepts HOME/TV/GUIDE key events and launches Channels DVR. Uses explicit activity -> package manager -> Play Store -> Play Store web fallback.
- `accessibility_config.xml`: requests key event filtering and sets flags for key-event-only behavior.
- `AccessibilitySettingsActivity.kt`: convenience Activity that opens Android Accessibility Settings so users can enable the accessibility service.
- `MainActivity.kt`: attempts to launch Channels DVR (package-launch) and falls back to opening the Accessibility Settings activity.
- `AndroidManifest.xml`: added `AccessibilitySettingsActivity` entry.

How to test on a device/emulator
1. Build & install the app (recommended on a real device or Android TV where you can enable Accessibility services):

```bash
# from project root (macOS, bash)
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the Gradle build fails with an error mentioning a Java version like `25.0.1`, see the "Troubleshooting: Java / Gradle build" section below.

2. Enable the Accessibility service:
- Settings → Accessibility → Installed services → "TiVo Channels Home" → Enable.
- Confirm any permission prompts to allow key event filtering.

3. Watch the service logcat to see events and redirection:

```bash
adb logcat -s TiVoButtonService
```

4. Press the TiVo remote HOME or GUIDE button. Expected behavior:
- The app will try to open Channels DVR (package `com.getchannels.dvr.app`).
- If Channels DVR is not installed it will open the Play Store to the app page (or a web fallback if Play Store is not available).
- If Channels DVR cannot be launched, `MainActivity` will open the Accessibility settings to help enable the service.

Troubleshooting: Java / Gradle build error ("25.0.1")
- If Gradle fails during script compilation with an exception about `JavaVersion.parse` or showing a number like `25.0.1`, this usually means your system JDK is a very new/unsupported version (e.g., JDK 25) and the Kotlin/Gradle plugin in this project doesn't handle that version string.
- Fix options (pick one):
  1) Use a JDK 17 (or JDK 11) when running Gradle. On macOS you can install Temurin 17 via Homebrew and then run gradle with that JVM:

```bash
brew install --cask temurin17
export JAVA_HOME=$(/usr/libexec/java_home -v17)
./gradlew :app:assembleDebug
```

  2) Use a JVM override for Gradle without changing your environment (replace the path with your JDK 11/17 installation path):

```bash
./gradlew :app:assembleDebug -Dorg.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
```

  3) Use SDKMAN or jenv to manage multiple Java versions and point to a supported one for this project.

- After switching Java versions to 11/17, re-run the build and install steps above.

Notes & privacy
- This app uses an AccessibilityService to receive hardware key events. That requires explicit user consent through the Accessibility settings. The service should only be enabled with informed consent.

Next improvements you might want
- Add a small in-app UI to explain why the Accessibility permission is needed and to deep-link into the Accessibility settings (we added `AccessibilitySettingsActivity` for this purpose).
- Add additional keycodes if your remote uses different codes (e.g., `KEYCODE_TV_HOME`) or map remote-specific buttons.
- Optionally, show a notification or settings toggle in-app to let the user enable/disable the redirection behavior.

If you'd like, I can: (pick one)
- Add the package-manager fallback to `MainActivity` (already done).
- Add an in-app settings screen to toggle interception on/off.
- Add unit tests and simple instrumentation tests (would need an emulator/device to run).


