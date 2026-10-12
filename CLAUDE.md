# TiVoChannelsHome - context for Claude Code

On-device Android TV app that turns a TiVo Stream 4K into a "Channels DVR appliance". Package `com.fiospace.tivochannelshome`, minSdk 21, targetSdk 36.
The TVs run Android 12 (SDK 31; TiVo Stream 4K, Amlogic). One client is a Chromecast with Google TV (Android 14).

## What lives where
- `TiVoButtonService.kt` - AccessibilityService that filters the TiVo remote's keys: launches Channels (`com.getchannels.dvr.app`) for HOME/GUIDE/DVR, swallows the power key, and tunes a channel on the LIVE key.
- `AccessibilitySettingsActivity.kt` - the "TiVo Channels Home - Configuration" dialog (Usage Access, OEM cleanup, Launch Channels Live, **Live button channel** picker). `MainActivity` (the app's tile) just opens this dialog; it no longer launches Channels.
- `ChannelsHttp.kt` - socket HTTP helper (no cleartext config needed) + `LiveChannelPrefs` (the chosen Live channel, in `tivo_prefs`). `LiveSync.kt` - keeps that channel in step with Home Assistant (below).
- `UsageMonitorService.kt` - foreground service that sends the TiVo UI back to Channels. `UsageAccessActivity` - helper.
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
  DVR=173/scan 240, GUIDE=172/scan 362, **LIVE=5124/scan 240**, one unidentified app button 272/scan 240, OK=23/353, arrows 19-22, power=85/116.
- The **Netflix button never reaches the service**: the system handles it first (`WindowManager: netflix key do one touch play`, then
  `START act=com.netflix.action.NETFLIX_KEY_START pkg=com.netflix.ninja`, `ActivityNotFoundException` when Netflix is absent). It cannot be remapped from the app. It is **left to the real
  Netflix app** (TVs that have it launch it; the host service `tivo_powerkey.py` no longer touches it - an earlier host hook that opened Channels on a channel was removed, it was confusing
  and its deep-link tune was unreliable).
- Android 11+ package visibility: without `<queries><package android:name="com.getchannels.dvr.app"/>` (now in the manifest) `getLaunchIntentForPackage` returned null
  ("Launch intent not found") and GUIDE/DVR/HOME did nothing. Channels has LAUNCHER and LEANBACK_LAUNCHER activities.
- Channels resumes live TV (its own `PlayerActivity`) on launch, so the DVR button lands on live TV even though the `tab=dvr_library` extra switches the sidebar.

## Power button handling (version 1.1)
`TiVoButtonService.onKeyEvent` swallows scanCode 116 / keyCode 85 unless `Settings.Global tivo_swallow_power == 0` (default 1). The host writes that flag from the Home Assistant
dashboard switch ("TiVo remote buttons"). The IR power signal to the TV is separate and unaffected. If the app is not rebuilt on a TV, the host service undoes the pause with an
explicit play/pause key instead. Keep the `Log.d(... onKeyEvent ...)` line: the host service reads it from logcat (`TiVoButtonService:D`).

## LIVE button (version 1.2+) - runs on the TV; its channel is synced with Home Assistant (1.5+)
`TiVoButtonService.onKeyEvent` handles keyCode 5124 / scanCode 240: swallows DOWN and UP, acts on the first DOWN only. The channel is `LiveChannelPrefs.get()`: the choice in the app's own prefs
(`tivo_prefs`, set in the Configuration dialog's "Live button channel" picker - fed from the DVR's `/api/v1/channels` at `LiveChannelPrefs.DEFAULT_DVR_HOST` 192.168.1.61:8089, typed entry if the
DVR is unreachable - or adopted from HA), else the adb fallback `adb shell settings put global tivo_live_channel <n>`; none = just open the Channels live player. How it tunes (`tuneLive`):
Channels API at 127.0.0.1:57000 (`POST /api/play/channel/<n>`) **only when the app reports `playing`/`paused`** (retunes inside the running player); otherwise the deep link
`channels://com.getchannels.dvr.app/play/channel/<n>`. Reasons, measured on the devices: the API also answers when Channels is closed or on a menu but then a tune POST returns 200 and does nothing
(cost ~16 s), and the deep link over a running player starts a second PlayerActivity on top of it (was followed by a spinner / "Connection lost" on channel 6073 - cause not proven). The API
reports `playing` as soon as a tune is requested, so success needs 2 s of stable `playing`. Already playing the target: just bring Channels to the front. One press at a time. Typical ~3-4 s.

**Screensaver (Ambient mode) rule - do not swallow the LIVE key-up.** The remote's Live key is a TiVo shortcut: TiVo's system app `com.nes.tvglobalkeyhandler` maps 5124 = `KEYCODE_NES_SHORTCUT_10`
to `com.tivo.atom` / `com.tivo.live` (not installed on these TVs, so that part is a no-op) and, on seeing the key-up, also ends the screensaver (`SystemUtils: stop dream by a global key pressed`). The service
therefore swallows only the DOWN and returns false for the UP. When both were swallowed the screensaver stayed on top of Channels and the button seemed dead (Channels never reached `playing`).
With the fix: screensaver on, Live -> screensaver ends after ~0.4 s, playing in ~6.4 s (measured on .137).

### Home Assistant sync (`LiveSync.kt`, started by `TiVoButtonService.onServiceConnected`)
HA is the source of truth, the TV keeps a local copy (works with HA down). HA side lives in the ha-docker repo (`tools/gen_tivo_dashboard.py`, deployed with `--deploy` on hp.local): per TV an
`input_text.tivo_<ip>_live_channel`, a "Live button tunes to:" pick on its dashboard tab (first entry "(none - just open the live player)"), an automation that publishes
`config/www/tivo-live.txt` (`ip=channel;ip=channel;`, blank = none; served without login at `http://192.168.1.96:8123/local/tivo-live.txt`), and a webhook automation
(`config/tivo/live-webhook-id.txt` on hp.local) that takes `{"ip","channel"}` POSTs from the TVs and raises an HA bell notification ("Live button changed on the TV") when the value changed.
- HA -> TV: the TV polls the file every 45 s (and at service start) and adopts its own entry (seen 25-32 s after a change).
- TV -> HA: a choice made in the dialog is stored and POSTed to the webhook; it stays "pending" (dialog shows "syncing...") and is re-sent until the file shows it, never overwritten by HA's older value; gives up after 10 min.
- A TV is identified by its LAN IP (192.168.x.x). No entry for a TV in the file = leave the local value alone. Use HA's IP (192.168.1.96), not `hp.local` (does not resolve reliably from the TVs).
- **Build prerequisites**: `local.properties` (gitignored; the repo is public) needs `ha.host=192.168.1.96`, `ha.port=8123`, `ha.live.webhook=<id from hp.local ~/ha-docker/config/tivo/live-webhook-id.txt>`; they are baked
  into `BuildConfig`. Without them the app builds and works but the sync is off.
- Do NOT simulate "HA changed it" by POSTing to the webhook while testing: HA cannot tell that from a TV-side change and raises the bell notification. Change the value in HA's UI instead.
- **No TV-side notification on purpose.** Google TV's Notifications panel (`com.google.android.tvrecommendations`) only lists notifications from an allowlist of packages
  (`tvlauncher:notifications_whitelisted_packages`, a Google/partner-controlled flag); ordinary app notifications - even ones posted from the adb shell - never show up there, and adb cannot
  change the flag (`GSERVICES_OVERRIDE` broadcast is refused: Permission Denial, uid 2000). A toast was not wanted. The Channels app's own `POST http://<tv>:57000/api/notify`
  (`{"title","message","icon"}`) does draw a top-right banner, but only while Channels is playing; it was tested (works) and not built in.

## App tile (launcher entry) can be found disabled
On .98, .115 and .192 `MainActivity` (the Installed Apps tile) was found in `disabledComponents` (cause unknown - none of our scripts/code does it); the system then does not resolve the
LEANBACK_LAUNCHER entry and the tile is missing. adb cannot fix it (`pm enable`/`default-state`: "Shell cannot change component state"), but the app can: `TiVoButtonService.ensureLauncherEntryEnabled()`
re-enables it every time the service starts (so also after an update). Check: `adb -s $S shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p com.fiospace.tivochannelshome`.

## Do not break
- `onServiceConnected` restricts `packageNames` to this app, sets `eventTypes = 0`, `feedbackType = 0`, `FLAG_REQUEST_FILTER_KEY_EVENTS` only. This fixed Google TV "single click acts as
  long press" side effects. Leave it.
- Version convention: **bump `versionCode`/`versionName` in `app/build.gradle.kts` for every build you install.** The HA dashboard shows `v<name> (<code>) - updated <time>` and a red
  "service off" per TV. 1.12 (13) = re-enables a disabled launcher tile; 1.11 (12) = Live key-up passes through (ends the screensaver); 1.10 (11) = HA sync of the Live channel (1.5-1.9 were its development steps incl. TV-side notification experiments, all removed); 1.4 (5) = the app tile opens the Configuration dialog; 1.3 (4) = new icon/banner; 1.2 (3) = LIVE button + channel picker; 1.1 (2) = power-key swallow +
  package-visibility fix; 1.0 (1) = older builds.

## Build, install, check (macOS)
- `export ANDROID_SERIAL=192.168.1.137:5555` then `./gradlew :app:assembleDebug` (JDK 17; see README for the JDK 25 gradle issue). Android Studio must be allowed under
  System Settings > Privacy & Security > **Local Network** or `adb connect` fails with "No route to host" while ping works; run `adb kill-server` after granting.
- **Installing can switch the accessibility service off** (seen on .137 and .133): `accessibility_enabled` goes back to 0 and no service is bound. After every install run
  `adb -s $S shell settings put secure accessibility_enabled 1`, wait a few seconds and check `dumpsys accessibility | grep "label=TiVo Channels Button Service"`. The Google TV launcher also keeps
  the old banner cached until it restarts: `adb -s $S shell am force-stop com.google.android.tvlauncher`. (Google TV's long-press tile menu cannot show app shortcuts - tried, not supported.)
- After installing, confirm the service is still on: `adb -s $S shell settings get secure enabled_accessibility_services` (should contain `com.fiospace.tivochannelshome/...TiVoButtonService`).
- Watch it: `adb -s $S logcat -v time -s TiVoButtonService:V WindowManager:I`. Press the remote's power button (log shows scanCode=116, playback must not change), GUIDE and DVR (Channels opens).
- If Android Studio will not start with "Plugin 'Python Community Edition' requires plugin 'intellij.libraries.jcef'": add `PythonCore` to `disabled_plugins.txt` in
  `~/Library/Application Support/Google/AndroidStudio<version>/`.

## Known gaps / ideas (not done)
- `handleLaunchShortcuts` returns `true` even when the launch fails, so a failed launch also swallows the key.
- Keys consumed on DOWN let the matching UP through (orphan UP); only the power key is swallowed on both.
- Identify key 272 (scan 240) and decide an action; HOME is intercepted and sent to Channels.
- The LIVE channel list comes from a hard-coded DVR address (`DEFAULT_DVR_HOST`; a `dvr_host` pref overrides it, no UI for that yet).
- Make the DVR button land on the DVR library (Channels opens the live player first).
