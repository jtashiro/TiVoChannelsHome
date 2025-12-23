TiVo Channels Home — TV publishing notes

This project has been configured to be published to Google Play as an Android TV (Leanback) app.

What's changed
- `AndroidManifest.xml` now declares `android.software.leanback` and `android.hardware.type.television` as required so Play recognizes this as a TV app.
- `MainActivity` includes a `LEANBACK_LAUNCHER` intent-filter so the app appears in the Android TV launcher.
- `application` has a `android:banner` resource (`@drawable/tv_banner`) and `ic_tv_launcher` adaptive icon; replace with branded assets (banner recommended 320x180 px).
- `MainActivity` will hide its launcher component at runtime after performing a quick UI dialog. This permits publishing as a LEANBACK_LAUNCHER app while keeping the launcher icon hidden by default.

Play Store requirements and checklist
- Target API: compileSdk/targetSdk are 36 in `app/build.gradle.kts`. Update to the latest stable Android API if required by Play.
- TV assets: provide a proper 320x180 px banner (`@drawable/tv_banner`) and high-quality launcher icons (mipmap) for TV.
- Leanback UI: Google strongly recommends providing a Leanback-optimized experience for TV. If your app is primarily an utility/config app (this app is a helper that launches Channels), consider whether the app should be visible in the main launcher.
- Accessibility: This app exposes an AccessibilityService. Ensure you meet Play policies around accessibility services (do not use accessibility to change settings without explicit user consent). The manifest includes the accessibility service metadata.
- Testing: Test the APK on multiple Android TV devices and emulator images (Android TV emulator images) and confirm the app is listed in the Play Console for TV devices.

How to test locally
- Build a release-signed APK/AAB and install on a device/emulator that runs Android TV. The app will appear in the TV launcher. After first run the app hides its launcher icon; to re-enable it use:

  adb shell pm enable com.fiospace.tivochannelshome/.MainActivity

How to adjust behavior
- To keep the app visible in the TV launcher by default, remove the runtime component-disable block in `MainActivity` or change the intent-filter in the manifest.
- Replace `@drawable/tv_banner` and `@mipmap/ic_tv_launcher` with your branding before publishing.

Security & policy notes
- Accessibility usage: ensure your app's description in the Play Console and in the Settings make clear why the accessibility service is required. Users must enable it manually and consent. Avoid using accessibility to do anything that violates user privacy.
- Uninstall behavior: silent uninstall via `pm uninstall --user 0` only works for device owner/root or via adb; fallback is interactive uninstall dialog.

If you want, I can:
- Add proper launcher and banner artwork files (placeholders or templates).
- Convert the helper dialog into a small Leanback-friendly fragment/activity.
- Run a Gradle assembleDebug to ensure the project builds locally.

