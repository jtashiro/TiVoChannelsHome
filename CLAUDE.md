# TiVoChannelsHome - context for Claude Code

On-device Android TV app that turns a TiVo Stream 4K into a "Channels DVR appliance". Package `com.fiospace.tivochannelshome`, minSdk 21, targetSdk 36.
The TVs run Android 12 (SDK 31; TiVo Stream 4K, Amlogic). One client is a Chromecast with Google TV (Android 14).

## What lives where
- `TiVoButtonService.kt` - AccessibilityService that filters the TiVo remote's keys and launches Channels (`com.getchannels.dvr.app`).
- `UsageMonitorService.kt` - foreground service that sends the TiVo UI back to Channels. `MainActivity`/`AccessibilitySettingsActivity`/`UsageAccessActivity` - helpers.
- `provisioning/scripts/provision_tivo.sh`, `scripts/uninstall_oem_packages.sh` - strip OEM apps, install a Google TV launcher, enable the service.
- `app/build.gradle.kts` - NOTE: `assembleDebug` is `finalizedBy("installDebug")` and then `uninstallOemApps` removes ~14 packages (incl. `com.netflix.ninja`,
  Amazon, Sling, Plex) with `pm uninstall --user 0`. It acts on whatever device adb selects. **Always `export ANDROID_SERIAL=<ip>:5555` first.**
- A second repo, `~/ha-docker` (GitHub jtashiro/ha-docker, runs on the Ubuntu server hp.local), holds Home Assistant plus host services that reach these TVs over adb:
  collector (status + the dashboard's "TiVoChannelsHome app" column), `tivo_powerkey.py` (see below), reboot scheduler, channel schedule. Do not duplicate that here.

## The house
Cord-cut: Channels DVR on dvr.local (192.168.1.61, all TVs use it; a second DVR at .30), three HDHomeRun tuners, Xfinity via TV Everywhere. Clients (adb on :5555):

| IP | Name | Notes |
|---|---|---|
| .115 | Dining room TiVo stream | TiVo Stream 4K, TCL 32" TV, has Netflix |
| .98 | Samsung TV | TiVo Stream 4K, has Netflix |
| .133 | Bedroom TV | TiVo Stream 4K |
| .137 | Dad's TV | TiVo Stream 4K behind an HDMI capture adapter into the Mac Studio (QuickTime). Use this one for testing |
| .192 | Elliptical TV | TiVo Stream 4K, LG 24LB451B (no CEC signal) |
| .107 | Treadmill TV | TiVo Stream 4K, has Netflix |
| .102 | TCL Google TV | wired Ethernet, its own remote - no TiVo key service |
| .63 | Den TV | Chromecast with Google TV (Android 14), USB-connected to the Mac Studio; adb on :5555 is re-opened by a script on the Mac |

## Facts verified on the devices (logcat, 2026-10-12)
- The TiVo Remote is input device "TiVo Remote", key layout `/vendor/usr/keylayout/Vendor_0471_Product_1207.kl` (read-only). It has `key 116 MEDIA_PLAY_PAUSE`
  (Generic.kl would make 116 = POWER). So the remote's **power button arrives as keyCode 85, scanCode 116** and pauses/resumes whatever is playing.
- `onKeyEvent` sees every remote key before the foreground app. Returning `true` swallows it (do both DOWN and UP). Seen so far:
  DVR=173/scan 240, GUIDE=172/scan 362, two unidentified app buttons 272/scan 240 and 5124/scan 240, OK=23/353, arrows 19-22, power=85/116.
- The **Netflix button never reaches the service**: the system handles it first (`WindowManager: netflix key do one touch play`, then
  `START act=com.netflix.action.NETFLIX_KEY_START pkg=com.netflix.ninja`, `ActivityNotFoundException` when Netflix is absent). It cannot be remapped from the app; the host
  service `tivo_powerkey.py` watches that log line and opens Channels on a per-TV channel instead.
- Android 11+ package visibility: without `<queries><package android:name="com.getchannels.dvr.app"/>` (now in the manifest) `getLaunchIntentForPackage` returned null
  ("Launch intent not found") and GUIDE/DVR/HOME did nothing. Channels has LAUNCHER and LEANBACK_LAUNCHER activities.
- Channels resumes live TV (its own `PlayerActivity`) on launch, so the DVR button lands on live TV even though the `tab=dvr_library` extra switches the sidebar.

## Power button handling (version 1.1)
`TiVoButtonService.onKeyEvent` swallows scanCode 116 / keyCode 85 unless `Settings.Global tivo_swallow_power == 0` (default 1). The host writes that flag from the Home Assistant
dashboard switch ("TiVo remote buttons"). The IR power signal to the TV is separate and unaffected. If the app is not rebuilt on a TV, the host service undoes the pause with an
explicit play/pause key instead. Keep the `Log.d(... onKeyEvent ...)` line: the host service reads it from logcat (`TiVoButtonService:D`).

## Do not break
- `onServiceConnected` restricts `packageNames` to this app, sets `eventTypes = 0`, `feedbackType = 0`, `FLAG_REQUEST_FILTER_KEY_EVENTS` only. This fixed Google TV "single click acts as
  long press" side effects. Leave it.
- Version convention: **bump `versionCode`/`versionName` in `app/build.gradle.kts` for every build you install.** The HA dashboard shows `v<name> (<code>) - updated <time>` and a red
  "service off" per TV. 1.1 (2) = power-key swallow + package-visibility fix; 1.0 (1) = older builds.

## Build, install, check (macOS)
- `export ANDROID_SERIAL=192.168.1.137:5555` then `./gradlew :app:assembleDebug` (JDK 17; see README for the JDK 25 gradle issue). Android Studio must be allowed under
  System Settings > Privacy & Security > **Local Network** or `adb connect` fails with "No route to host" while ping works; run `adb kill-server` after granting.
- After installing, confirm the service is still on: `adb -s $S shell settings get secure enabled_accessibility_services` (should contain `com.fiospace.tivochannelshome/...TiVoButtonService`).
- Watch it: `adb -s $S logcat -v time -s TiVoButtonService:V WindowManager:I`. Press the remote's power button (log shows scanCode=116, playback must not change), GUIDE and DVR (Channels opens).
- If Android Studio will not start with "Plugin 'Python Community Edition' requires plugin 'intellij.libraries.jcef'": add `PythonCore` to `disabled_plugins.txt` in
  `~/Library/Application Support/Google/AndroidStudio<version>/`.

## Known gaps / ideas (not done)
- `handleLaunchShortcuts` returns `true` even when the launch fails, so a failed launch also swallows the key.
- Keys consumed on DOWN let the matching UP through (orphan UP); only the power key is swallowed on both.
- Identify keys 272 and 5124 (both scan 240) and decide actions; HOME is intercepted and sent to Channels.
- Make the DVR button land on the DVR library (Channels opens the live player first).
