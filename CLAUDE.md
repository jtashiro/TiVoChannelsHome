# TiVoChannelsHome - context for Claude Code

On-device Android TV app that turns a TiVo Stream 4K into a "Channels DVR appliance". Package `com.fiospace.tivochannelshome`, minSdk 21, targetSdk 36.
The TVs run Android 12 (SDK 31; TiVo Stream 4K, Amlogic). One client is a Chromecast with Google TV (Android 14).

## What lives where
- `TiVoButtonService.kt` - AccessibilityService that filters the TiVo remote's keys: launches Channels (`com.getchannels.dvr.app`) for HOME/GUIDE/DVR, swallows the power key, and tunes a channel on the LIVE key.
- `AccessibilitySettingsActivity.kt` - the "TiVo Channels Home - Configuration" dialog (Usage Access, OEM cleanup, Launch Channels Live, **Live button channel** picker). `MainActivity` (the app's tile) just opens this dialog; it no longer launches Channels.
- `ChannelsHttp.kt` - socket HTTP helper (no cleartext config needed) + `LiveChannelPrefs` (the chosen Live channel, in `tivo_prefs`). `LiveSync.kt` - keeps that channel in step with Home Assistant (below).
- `UsageMonitorService.kt` - foreground service that sends the TiVo UI back to Channels. `UsageAccessActivity` - helper.
- `provisioning/scripts/provision_tivo.sh`, `scripts/uninstall_oem_packages.sh` - strip OEM apps, install a Google TV launcher, enable the service.
- `app/build.gradle.kts` - NOTE: `assembleDebug` is `finalizedBy("installDebug")` and then `uninstallOemApps` removes ~13 packages (Amazon, Sling, Plex, ...) with `pm uninstall --user 0`.
  **Netflix (`com.netflix.ninja`) is deliberately NOT in any removal list** (Gradle task, `scripts/uninstall_oem_packages.sh`, `provisioning/scripts/provision_tivo.sh`, the app's OEM cleanup in the Configuration dialog). It acts on whatever device adb selects. **Always `export ANDROID_SERIAL=<ip>:5555` first.**
- A second repo, `~/ha-docker` (GitHub jtashiro/ha-docker, runs on the Ubuntu server hp.local), holds Home Assistant plus host services that reach these TVs over adb:
  collector (status + the dashboard's "TiVoChannelsHome app" column), reboot scheduler, channel schedule, auto cache trim, sluggish watchdog. Do not duplicate that here (a short summary is under "Host side").

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
  Netflix app** (TVs that have it launch it; an earlier host hook that opened Channels on a channel was removed, it was confusing
  and its deep-link tune was unreliable).
- Android 11+ package visibility: without `<queries><package android:name="com.getchannels.dvr.app"/>` (now in the manifest) `getLaunchIntentForPackage` returned null
  ("Launch intent not found") and GUIDE/DVR/HOME did nothing. Channels has LAUNCHER and LEANBACK_LAUNCHER activities.
- Channels resumes live TV (its own `PlayerActivity`) on launch, so the DVR button lands on live TV even though the `tab=dvr_library` extra switches the sidebar.

## Power button handling
`TiVoButtonService.onKeyEvent` always swallows scanCode 116 / keyCode 85 (the remote's power key arrives as play/pause), so Channels never toggles pause by itself; what a press does is set by the
Power on / Power off options (see "Restore TV to last state" below). The IR power signal to the TV is separate and unaffected. Keep the `Log.d(... onKeyEvent ...)` line (handy for debugging).
Removed in 1.21: the `Settings.Global tivo_swallow_power` flag, the Home Assistant switch "Power button: keep playing" and the host service `tivo_powerkey.py` (it un-paused after a press, which would undo the Pause option).

## LIVE button (version 1.2+) - runs on the TV; its channel is synced with Home Assistant (1.5+)
`TiVoButtonService.onKeyEvent` handles keyCode 5124 / scanCode 240: swallows DOWN and UP, acts on the first DOWN only. The channel is `LiveChannelPrefs.get()`: the choice in the app's own prefs
(`tivo_prefs`, set in the Configuration dialog's "Live button channel" picker - fed from the DVR's `/api/v1/channels` at `LiveChannelPrefs.DEFAULT_DVR_HOST` 192.168.1.61:8089, typed entry if the
DVR is unreachable - or adopted from HA), else the adb fallback `adb shell settings put global tivo_live_channel <n>`; none = just open the Channels live player. How it tunes (`tuneLive`):
Channels API at 127.0.0.1:57000 (`POST /api/play/channel/<n>`) **only when the app reports `playing`/`paused`** (retunes inside the running player); otherwise the deep link
`channels://com.getchannels.dvr.app/play/channel/<n>`. Reasons, measured on the devices: the API also answers when Channels is closed or on a menu but then a tune POST returns 200 and does nothing
(cost ~16 s), and the deep link over a running player starts a second PlayerActivity on top of it (was followed by a spinner / "Connection lost" on channel 6073). Measured on .137 2026-10-15: there is NO second PlayerActivity (only one ever existed, and after `/api/stop` it is gone). The failure is a player **behind another app** (status `paused`, PlayerActivity alive): a deep link sent then can leave it `stopped` (4 of 4 in a scripted run; a real LIVE press from a similar `stopped` state deep-linked fine in 2.9 s, so not deterministic) with the PlayerActivity still alive, and a second deep link or a tune POST do nothing (a POST to a player behind another app also does nothing). Bringing Channels to the front first (launch intent, wait ~1.5 s; this may flip `paused` to `stopped`) and then `POST /api/play/channel/<n>` recovers it (3 of 3 runs); a force-stop + deep link also recovers (~10 s). 1.23 does front -> API, then deep link, then front -> API again, last resort `killBackgroundProcesses` + deep link (the kill step is untested). The API
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
- A TV is identified by its LAN IP (192.168.x.x). No entry for a TV in the file = leave the local value alone. Use HA's IP (192.168.1.96); it works even if DNS is down. `hp.local` used to resolve wrongly from the TVs because dnsmasq on hp served hp's `/etc/hosts` line `127.0.1.1 hp.local` to every client; fixed 2026-10-16 (dnsmasq now serves `/etc/dnsmasq.hosts` with `no-hosts`: `hp.local` 192.168.1.96, `dvr.local` .61, `nas.local` .30; checked on all six TiVo/Google TV boxes). The TVs use hp (192.168.1.96) as DNS, then the router.
- **Build prerequisites**: `local.properties` (gitignored; the repo is public) needs `ha.host=192.168.1.96`, `ha.port=8123`, `ha.live.webhook=<id from hp.local ~/ha-docker/config/tivo/live-webhook-id.txt>`; they are baked
  into `BuildConfig`. Without them the app builds and works but the sync is off.
- Do NOT simulate "HA changed it" by POSTing to the webhook while testing: HA cannot tell that from a TV-side change and raises the bell notification. Change the value in HA's UI instead.
- **No TV-side notification on purpose.** Google TV's Notifications panel (`com.google.android.tvrecommendations`) only lists notifications from an allowlist of packages
  (`tvlauncher:notifications_whitelisted_packages`, a Google/partner-controlled flag); ordinary app notifications - even ones posted from the adb shell - never show up there, and adb cannot
  change the flag (`GSERVICES_OVERRIDE` broadcast is refused: Permission Denial, uid 2000). A toast was not wanted. The Channels app's own `POST http://<tv>:57000/api/notify`
  (`{"title","message","icon"}`) does draw a top-right banner, but only while Channels is playing; it was tested (works) and not built in.

## Restore TV to last state (version 1.17) - power off/on
Option in the Configuration dialog ("Power on: ... Channels and tune to last state" = this feature, the default; see 1.19 note; `ResumePrefs` in `ResumeState.kt`, same `tivo_prefs`). **Power off** = `ACTION_SCREEN_OFF` (TVs that sleep: .102, .63) or the
first press of the TiVo remote's power key (TiVo boxes stay awake and the key is a toggle, so the service tracks an "off" flag, `ResumePrefs.isOff`, stale after 12 h). It saves what Channels was playing:
live TV (`channel.number` in `/api/status`) or a recording (no `channel`; the file id is parsed from `now_playing.thumb_url` `/dvr/files/<id>/`). **Power on** (`ACTION_SCREEN_ON`, or the next TiVo power press) resumes it,
overriding the Live channel (`tuneLive`, or `POST /api/play/recording/<id>` / deep link `channels://com.getchannels.dvr.app/play/recording/<id>`), once, if it is under 12 h old; with nothing saved, or the option off, it does what the LIVE button does.
Channels' status drops to "stopped" within ~0.4 s of sleep (measured on .102), so the service polls `/api/status` every 4 s while the screen is on (`lastSnap`) and uses that if the status at power off is already stopped (max 10 s old).
Channels restarts a recording from its own saved resume point, so the playback position at power off is saved too and, after the resume, the service seeks by the difference (`POST /api/seek/<seconds>` is a RELATIVE seek, negative = back, fractions ok; measured on .137).

## Host side (ha-docker repo on hp.local): cache trim, sluggish watchdog, dashboard (2026-10-12/13)
Not part of this app, but it acts on the same TVs; the details are in the ha-docker repo (`ssh hp.local`, `~/ha-docker`; the Mac's `~/ha-docker` is a stale copy without git or `tools/`).
- **Channel schedule** `tools/tivo_channel_schedule.py` (2026-10-15): a tune counts only after 3 s of stable `playing`; API only while the app is playing/paused, else force-stop + deep link (twice), then `tivo-reboot.sh --launch-only`; a failure raises a bell notification (reboot-event `kind: schedule`).
  Also in ha-docker: X10/WM100 dashboard (`config/x10_dashboard.yaml`, generated by the separate `wm100-api` repo, not TiVo related) and `tools/router_daily_report.py` (emails yesterday's router usage + plan-size advice; timer `router-report.timer` fires 8am 2026-10-16..18).
- **Auto cache trim** `tools/tivo_autotrim.py` (timer every 10 min): trims app caches (`pm trim-caches`) when a TV's internal free space is under its **cache floor**, at most once per **check duration**
  (cooldown). Both are per TV on its dashboard page ("Automatic cache trim" card; files `config/tivo/trim-floor-gb-<ip>.txt`, `trim-cooldown-h-<ip>.txt`), fall back to the old global files, then to the defaults 1 GB / 2 hrs.
  **USB cache limit** (added 2026-10-14): when Channels runs from an adopted USB drive/SSD its cache grows there (about 1.5 GB/h seen on the Samsung's SSD) and the internal floor never notices. The same job now also trims the
  drive: if the drive's *used* space is over the TV's **USB cache limit** (card setting, file `trim-usb-gb-<ip>.txt`, default 8 GB, 0 = never), it runs `pm trim-caches <free space> <uuid>` (the command takes an optional `internal|UUID`)
  until the drive uses about half the limit, at most once per check duration (`trim-usb-last-<ip>.txt`). Tested on the Samsung's SSD: 4.7 GB used -> about 0.9 GB in 29 s (playback not checked). `tivo_clear_cache.run(..., volume=<uuid>, target=<bytes>)`.
- **Sluggish watchdog** `tools/tivo_sluggish.py` (service `tivo-sluggish`, samples every 15 s): the TVs' kernel is 4.9 (no PSI), so it uses `/proc/vmstat` deltas (swap in/out, workingset refaults, allocation stalls)
  with MemAvailable. HOT = RAM under the limit and swap/refault/stalls over theirs, or SEVERE (>=20 stalls, or swap>=1500 and refault>=3000 /s: the RAM gate is ignored); **3 hot of the last 5** samples = **emergency (adhoc) clear**:
  (1) kill Android Studio's screen-sharing agent if running, (2) cache trim + `am kill-all`, (3) bell notification ("ADHOC emergency cache clear", reboot-event file with kind `adhoc`). Before that a **gentle stage**: free RAM under 400 MB
  for 2 samples = `am kill-all` only, no bell, at most every 10 min. After an emergency clear, **opt-in clean reboot**: still sluggish N min later (card setting, 0 = never, default) = `tivo-reboot.sh` in a transient unit (reopens Channels, re-tunes),
  at most once per 60 min, bell kind `adhoc_reboot`. Limits per TV on the dashboard's Watchdog card (`config/tivo/slug-<key>-<ip>.txt`; defaults 350 MB / 200 swap / 1500 refault / 3 stalls / 3 samples / 10 min / gentle 400 MB / reboot 0; on/off switch).
  **Root cause found 2026-10-13: Android Studio's Running Devices screen mirroring** runs a software VP8 encoder (1280x720, 5 Mbps) on the TV that crash-loops (libvpx assertion in `vp8_pick_frame_size`, `media.swcodec` tombstones every 1-2 s) and thrashes the 2 GB boxes
  (Dining room 10:38-11:00 and Dad's TV 08:26-08:47, matching the emergency clears; tombstone counts: .137 502, .192 195, .107 131, .98 76, .102 66, .133/.63 0). Do not mirror these TVs from Studio; look for it with `adb shell dumpsys dropbox | grep SYSTEM_TOMBSTONE` and
  `ps -A | grep screensharing`. Two unexplained hard resets on .115 (09:16, 09:36 on 2026-10-13, boot reason `reboot,14200704xx` = clock not set) predate any tombstone.
  Log: `config/tivo/sluggish.log`; `python3 tivo_sluggish.py --watch --dry-run [--only <ip>]` shows live numbers. Idle TVs measure ~0 swap and <2 refaults/s; the thresholds are untuned guesses until a real freeze is logged.
  The cache trim frees disk, not RAM (`ram_after` barely moves); the `am kill-all` is what frees memory.
- **Dashboard** (`tools/gen_tivo_dashboard.py --deploy`): each TV page is a 3-column *sections* view; each column ends with one chart (storage, RAM, load) with its own timeframe drop-down
  (`input_select.tivo_<k>_chart_<kind>`: 24 hours / 7 / 30 / 60 / 90 days; up to 7 days raw history, longer = statistics). The fleet charts were removed from the Channels Clients tab.
- **Router / internet usage** (added 2026-10-14; ha-docker `tools/router_monitor.py`, `tools/gen_router_dashboard.py`): the home router is a TP-Link Archer AX20 (HA's UPnP/IGD integration names it "Archer AX20"; there are also other Wi-Fi access points
  - the TiVos sit on three different BSSIDs) on a Verizon "Fios 1 Gig" plan. Ookla from a wired Mac measured 926 down / 858 up; Cloudflare's browser test and `speedtest-cli` on hp/nas/dvr under-reported (200-350 up), so trust Ookla.
  HA's own UPnP speed sensors glitch (the router's byte counters are 32-bit and wrap every 4.3 GB; false ~290 Mbit/s upload spikes), so `router-monitor` (systemd user service) reads `GetTotalBytesReceived/Sent` over UPnP every 5 s, handles the wrap, drops samples over 1.5 Gbit/s
  and writes `config/tivo/router/state.json` (HA command_line sensor `Router Monitor` -> template sensors `sensor.router_download_mbps` etc.), `usage.csv` (one row a minute, for percentile sizing of the plan) and `peaks.csv` (one row a day: peak down/up and when; the peaks reset at local midnight).
  Checked against a known 1 GB download: the router counted 93% of download + background, so the counters are accurate. The router has no uptime over UPnP (`GetStatusInfo` returns 500); "uptime" = time since miniupnpd last restarted, detected by its random URL path (`/usbkt/`, `/ntthg/`) changing.
  Dashboard "Router" (`/router-usage`): speeds, today's peaks with time, uptime, a 1 hour .. 30 days drop-down. Public iperf servers were busy; the Cloudflare `__down?bytes=` endpoint 403s above 25 MB and rate-limits repeats.
- **Gotchas learned**: HA derives an entity_id from the entity's *name*, not its `unique_id` (twice a card pointed at a wrong id); new selects/options need Developer Tools > YAML > *Template entities* reload,
  new input_selects need *Input select* reload, new shell_command/command_line sensors need an HA restart. `config/scripts/` is root-owned: deploy a changed script with `docker cp tools/<f>.py homeassistant:/config/scripts/`
  (the systemd timers/services run that copy, not `tools/`). The collector's `apps-<ip>.json` can be stale (check `pm list packages`); some TiVo system apps are "core" and cannot be uninstalled.
- **USB storage (adopted as internal), measured 2026-10-13 on Dad's TV (.137)**: tried on every TiVo and removed (about 4x slower than internal); trim + daily reboot is the chosen approach. The TiVo Stream 4K's USB-C port
  is **USB 2.0** (~35-40 MB/s real, no UASP, so one command at a time: small random I/O and create/delete suffer most), which caps any drive, so a faster SSD would not help. The adb shell cannot write to an adopted volume
  (`/mnt/expand/<uuid>` is root-owned; no `dd`), so benchmark with `adb shell sm benchmark private` (internal) / `sm benchmark private:8,2` (the drive). It prints create/run/drop/destroy times (lower is better); ignore `ident`.
  **Benchmark results** (ms from `sm benchmark`, lower is better; every drive direct on the USB 2.0 port of Dad's TV, 2026-10-13; sorted best to worst by the core test `run`):

  | # | Drive | Type | run (core test) | create | destroy | run vs internal |
  |---|---|---|---|---|---|---|
  | 1 | Internal flash (baseline, typical) | Internal flash | 468 | 4,570 | 50 | 1.0x |
  | 2 | OCZ-VERT EX3 120 GB, second unit | SSD | 677 | 3,974 | 504 | 1.4x |
  | 3 | OCZ-VERT EX3 120 GB, third unit | SSD | 729 | 4,064 | 537 | 1.6x |
  | 4 | OCZ Solid 3 120 GB, second unit | SSD | 740 | 4,134 | 549 | 1.6x |
  | 5 | OCZ-VERT EX3 120 GB, first unit | SSD | 756 | 4,019 | 559 | 1.6x |
  | 6 | OCZ Solid 3 120 GB, first unit | SSD | 768 | 4,046 | 570 | 1.6x |
  | 7 | Sabrent 240 GB #2 (second unit) | SSD | 782 | 4,192 | 622 | 1.7x |
  | 8 | Sabrent 240 GB #1 | SSD | 809 | 3,999 | 640 | 1.7x |
  | 9 | Crucial CT960BX5 960 GB | SSD | 907 | 4,124 | 812 | 1.9x |
  | 10 | Earlier USB SSD (110 GB, first session; probably one of the OCZs, not confirmed) | SSD | 977 | 4,564 | 1,076 | 2.1x |
  | 11 | Kingston DataTraveler 70 ~128 GB (118 GB usable) | USB stick / flash | 1,534 | 13,691 | 2,013 | 3.3x |
  | 12 | Transcend TS256GSS 256 GB | SSD | 1,603 | 5,605 | 2,024 | 3.4x |
  | 13 | SanDisk "3.2Gen1" ~128 GB (117 GB usable) | USB stick / flash | 1,749 | 6,756 | 1,082 | 3.7x |
  | 14 | PNY 128 GB | USB stick / flash | 2,005 | 14,183 | 4,731 | 4.3x |
  | 15 | "USB DISK 3.0" 64 GB | USB stick / flash | 2,253 | 20,425 | 3,830 | 4.8x |
  | 16 | ADATA 8 GB | USB stick / flash | 7,990 | 22,375 | 3,703 | 17x |
  | 17 | WDC WD10SPZX 1 TB (2.5" spinning disk) | HDD | 9,328 | 16,615 | 11,613 | 20x |
  | 18 | "USB DISK 2.0" 4 GB | USB stick / flash | 18,266 | 21,787 | 3,326 | 39x |
  | 19 | SanDisk 4 GB "U3 Cruzer Micro" | USB stick / flash | never completes | >37,000 | n/a | n/a |
  | 20 | "USB Disk 20" 126 GB (label "VendorC", size exactly 120,000 MiB) | USB stick / flash | never completes | 44,500-73,000 (avg ~55,000) | n/a | n/a |

  Notes: internal is the typical figure of the later sessions (428-687 over the day, odd outliers 950+). Runs completed: all three OCZ-VERT EX3 units and both Solid 3 units, both Sabrents, Crucial, Transcend and ADATA 4 of 4 (within 2-5%), 64 GB and the WD hard disk 3 of 4, "USB DISK 2.0" 2 of 4 (the rest
  were abandoned ~22-24 s into the create phase), SanDisk 0 of 5 and "USB Disk 20" 0 of 4 (vold's create phase ran ~37 s for the SanDisk and 44-73 s for "USB Disk 20" before the benchmark was abandoned, and the failed runs slowed the internal create step to 6-14 s; "USB Disk 20" reports an exactly round 120,000 MiB with generic names, so it may be a counterfeit/relabeled drive, not verified; read phase times from `adb logcat -d | grep vold`, the "Creating ..." to
  "Destroying ..." lines). The first `sm benchmark` after a pause often returns no timings on the sticks and the hard disk (PNY twice, 64 GB once, WD10SPZX once; the ADATA and the SSDs never): just rerun it. A failed run on the HDD (and the
  M.2 enclosure earlier) also stalled the next internal benchmark (create 68 s), so a hiccuping drive can drag the whole system. The WD10SPZX is a bus-powered 2.5" hard disk: it worked here but is the worst of all on delete.
  The two Sabrent 240 GB SSDs (identical model) ran at the same time without a TV reset (benchmarked one at a time). The Kingston DT70 (4 of 4 runs, run 1,449-1,745, create ~13.7 s, destroy ~2.0 s) is the fastest stick on `run` and the SanDisk 3.2Gen1 (4 of 4 runs, run 1,461-2,053, create ~6.8 s, destroy ~1.1 s) the best at create/delete; every SSD beats every stick on the core test, and USB 2.0 caps
  the SSDs so the SSD-to-SSD gaps are modest; only an SSD could plausibly host Channels' cache (the Transcend is the slowest SSD, reason unknown). Dining room (.115) runs Channels from its USB SSD: its free space fell ~3.4 GB
  net in ~2 h (about 30 MB/min of net writes; `/proc/diskstats` is blocked, so reads cannot be seen).
  A 4-slot USB 2.0 multi-card reader ("Generic MultiCard Device" 1307:0361) with two 16 GB cards exposed 4 disks but only one card was detected, and its volume kept vanishing mid-benchmark (reader re-enumerating,
  `usb_device_open failed`), so no card numbers exist.
  All the USB drives are mounted `dirsync`, which makes deletes much slower than on internal. Use a drive as a capacity safety net, not for Channels' hot data.
  Android moves apps back to internal storage when an adopted drive is removed (Channels was back under `/data/app` after the swap); formatting a new drive creates a new volume UUID.
  **Channels load tests (2026-10-14; ha-docker `tools/tivo_load_test.py <ip> --label X [--rec ID] [--steps ...]`, run on hp.local).** Per TV, sampled every 5 s over adb (`/proc/stat`, vmstat, meminfo, Channels' own CPU, `/proc/net/dev`, `df` of /data and the drive): idle on Home, live TV (deep link, ch 6073), a recorded show played **from the start with the Channels cache cleared first**
  (`pm trim-caches <total bytes> <uuid>` + force-stop, then `POST /api/play/recording/<id>` and `POST /api/seek/-100000`), the same show with a seek (+30/-25 s) every 8 s, guide and DVR-library browsing (adb keys). Recording 15510 is on DVR .61; the Treadmill TV uses DVR .30 (recording 12393), so a wrong id = nothing plays (`stopped`, Channels ~1% CPU).
  Without clearing the cache first Channels replays a recording from its cache and nothing is downloaded (0.06 Mbit/s, no writes), so that run is not a test. All runs: zero allocation stalls, no reboot, free RAM never under 614 MB.

  | Storage under Channels (TV) | Idle CPU | Live TV CPU / write | Recorded-show burst: CPU / I/O wait / net / written / peak load | Seek step |
  |---|---|---|---|---|
  | Internal (Dad's .137) | 15.7% | 29.7% / +90 MB | 41% / 8.6% / 41 Mbit/s / 776 MB (163 s) / 7.8 | 31.8%, +70 MB |
  | OCZ-VERT EX3 SSD (Bedroom .133, bus-powered, TV USB power) | 18.3% | 33.6% / +106 MB | 57% / 10.4% / 74 Mbit/s / 1,368 MB (162 s) / 9.6 | 32.8%, 0 |
  | OCZ-VERT EX3 SSD (Bedroom, 2 A wall adapter, cache cleared) | 14.9% | 35.4% / +101 MB | 56% / 10.0% / 76 Mbit/s / 1,642 MB (186 s, 8.8 MB/s) / 9.3 | 32.2%, 0 |
  | Kingston DT70 stick (Dad's .137) | 14.4% | 30.9% / +42 MB | 43% / **28.2%** / 45 Mbit/s / 965 MB (5.3 MB/s) / 9.4 | 43%, I/O wait **29.5%**, +570 MB, peak load 10.0 |
  | SanDisk 3.2Gen1 stick (Treadmill .107, DVR .30) | 12.0% | 28.3% / +97 MB | 49% / 21.4% / 64 Mbit/s / 1,357 MB (7.5 MB/s) / 8.2 | 34%, I/O wait 5.5%, +209 MB, peak load 8.2 |

  Findings: the heavy case is a **recorded show starting** (Channels downloads it as fast as the network allows into its cache: 0.8-1.6 GB in ~3 min), live TV writes ~0.6-0.7 MB/s (~2.3 GB/h), seeking/guide/library are cheap once buffered (guide and library are overlays on the player, which keeps `playing`).
  The drive sets the pace: SSD I/O wait ~10% and the burst ends within one step; sticks wait 21-30% and the Kingston was still writing 570 MB in the next step (total about 1.5 GB for both sticks). Price seen: Kingston $17.92, SanDisk $27.99. Both sticks survived; for a heavily used box prefer the SSD, then the SanDisk.
  The Treadmill/Bedroom/Dad's runs are one run each on different TVs, so the stick-vs-stick gap is indicative, not proven. Dad's TV had lost the TiVoChannelsHome app (an adopted drive takes its apps with it); a fresh `adb install -r` put it on the Kingston (default install location), so pulling the stick drops the button service.
- **UNPOWERED USB hubs and an NVMe enclosure did not work on the TiVo Stream 4K (tried 2026-10-13 on Dad's TV); a powered hub/dock is the open question.** The hub used was NOT externally powered, so its failure is consistent with a power shortfall, not proof that hubs cannot work. A 4-port USB-C hub with two sticks and a Sabrent M.2 NVMe enclosure made the TV hard-reset on every plug-in
  (boot reason `reboot,14200704xx` = clock not set = power loss/reset; logged at 12:14, ~12:39 and ~12:43). Behind the hub only the enclosure ever enumerated (sticks never appeared on the bus); its `sm benchmark` stalled
  (run phase ~34 s vs ~0.5 s internal, create once >2 min, results often missing) and a stall also slowed internal storage. The enclosure plugged **directly** was not detected at all, even after a hard power cycle (bus shows only
  `usb1`/`usb2`). What did work on the direct port: a SATA USB SSD and the PNY 128 GB stick. **Never hot-plug USB on these boxes**: power the TV off, connect, then power on.
  The unexplained `reboot,14200704xx` hard resets on .115 (09:16, 09:36 2026-10-13) and .133 (2026-10-12) coincided with USB changes or thrash, so they are probably USB hot-plug resets (not proven); a third on .115 (~11:03)
  followed the encoder storm.
  **Samsung (.98) powered-dock trial, 2026-10-13/14:** attaching an adopted drive on the box's own port (the PNY stick, then an OCZ Solid 3 SSD) was followed by hard resets (23:03) and repeated reboots a few seconds after Channels started
  playing from the drive with HDMI plugged in and the TV on; the same OCZ SSD ran stable with HDMI unplugged for about 10 minutes. CEC (Anynet+) was off the whole time. Several 5 V AC-USB adapters, including the one TiVo supplies, still rebooted - but the TiVo's own adapter is only **5 V 1 A** and the others were probably 1 A too (not verified), so adapter capacity is NOT ruled out; the adapter was on the battery-backup side of an APC UPS (not ruled out). The TiVo is powered through a micro-USB input; the drive goes on the separate USB-C host port, whose current comes through the TiVo's board (limit unknown), so a bigger adapter may not raise what a bus-powered drive can draw.
  **Afternoon of 2026-10-14, 5 V 2 A adapters:** Dining room (wall adapter, bus-powered SSD, recording) and Samsung (bus-powered SSD on a USB-C to SATA connector, recording + skips) ran clean, but only ~40-55 min so far. The Dining room also reset once at 14:04 while idle when it was powered from the TCL TV's USB port (probably the TV cut the port in standby). To settle adapter-vs-other: A/B on one TV, same drive and recorded-show load, only the adapter changed (1 A vs 2 A, alternating, >=4 runs each), ideally with a USB inline voltage meter.
  **Counterexample, Bedroom (.133), 2026-10-14:** the TiVo is powered from a TV's USB port (a TCL; the port's rating was not read), an OCZ-VERT EX3 SSD is bus-powered on the USB-C host port through a **StarTech USB31CSAT3CB** (SATA to USB-C 10 Gbps cable, 2.5" SATA I/II/III; this is the USB-C to SATA cable used for the SSDs; its current draw was not checked) and Channels runs from the SSD: up 16+ h with no reset, recorded-show playback and skips fine (load peaked ~7.9 and recovered). **Then it hard-reset at 15:52:07 (`reboot,1420070416`) while idle, ~13 min after the last playback (stopped 15:38:50; load 3.0, RAM ~860 MB)** - the same pattern as the Dining room's 14:04 idle reset (paused 13:51, offline 14:03, ~12 min) while powered from the TCL's USB port. Both idle resets were TiVos powered from a TV's USB port, ~12-13 min after the last activity; suspected: the TV cuts the USB port in standby / on an idle timer (not proven; a hard reset leaves no log). The host's 'last playing N min before the boot' text uses a different tracker and was wrong both times. So a TV USB port plus a bus-powered SSD can be stable, which weakens 'power alone explains the resets'; per-box differences (the Dining room box was already freezing/rebooting on internal storage), cable, port or the TV keeping its USB port powered in standby are all still possible. The same SSD models ran bus-powered with no resets on .137 and .115. After the SSD was moved into a self-powered single-bay
  dock (JMicron bridge, one drive on the bus) with Channels on it, the box was up 11+ min with no reboot (too short to call it fixed). Treat power at the box as the leading suspect, unproven.
  **2026-10-15, user's conclusion: the 5 V 2 A adapter change is what made the boxes stable with external USB-C SSDs** (Dining room .115, Bedroom .133, Samsung .98 on SSDs). Not an A/B test, but consistent with the logs: at 07:27 they were up 16 h 57 m (.115), 14 h 35 m (.133) and 16 h 39 m (.98), no resets since the afternoon of 2026-10-14. The user has now **disabled the scheduled reboots** on these TVs to watch how they behave over time, The user confirmed the power setup (2026-10-15): the TiVo Stream 4Ks on the Samsung, Bedroom and Dining room TVs are each on a **5 V 2 A wall AC adapter that is not the TiVo-supplied one and not the TV's internal USB port** (so the Bedroom and Dining room boxes are no longer on a TV USB port, which was the suspect for their ~12-13 min idle resets). Adapter make/model and cable not recorded. So uptime is a real stability signal from here on (an unexplained `reboot,14200704xx` = hard reset again would mean the power theory is incomplete).
  **2026-10-15, Dining room (.115) offline 1:55 pm - 2:07 pm (12 min), planned:** the user swapped the USB cable and the SSD (SATA) cable on that box, so this outage is not a reset; ignore it when reading uptime. Cause of the swap not recorded (cable suspicion); watch .115 for hard resets (`reboot,14200704xx`) afterwards.
  **Same day, Dining room (.115) offline again 3:55 pm - 4:07 pm (12 min), planned:** the USB-C to SATA cable was replaced. Not a reset; ignore it when reading uptime.
  **2026-10-15, Treadmill TV (.107), 7:10 pm - 7:15 pm offline, planned:** a SATA USB-C SSD (OCZ-VERT EX3, adopted as `private:8,2`, 110 GB, 108 GB free) was installed; Channels is on it. Not a reset. Afterwards the user could not move TiVoChannelsHome to the drive in Settings ("not enough storage"; internal had 1.4 GB free, the SSD 108 GB, so the message does not match the numbers; cause not found). `adb shell pm move-package com.fiospace.tivochannelshome <volume uuid>` (uuid from `sm list-volumes all`) worked at 19:15 and the accessibility service stayed on (checked `enabled_accessibility_services` and `dumpsys accessibility`); the app is now under `/mnt/expand/<uuid>/app/`. Pulling that drive drops the button service (see the .137 note).
  **2026-10-15, Elliptical TV (.192), 7:25 pm - 7:28 pm powered off, planned:** its external storage was replaced with an SSD. Not a reset; ignore it when reading uptime. The drive is adopted (`private:8,2`, 110 GB, 108 GB free) and Channels was already on it. Moving our app: Settings says "not enough storage" (misleading); `pm move-package` failed with -6, logcat `Move only supported for modern cluster style installs` (no visible difference from .107/.137, whose moves worked; cause unknown). Fix that worked (19:36): `pm uninstall -k com.fiospace.tivochannelshome` (keeps data), `adb install` of the same APK - with installLocation=auto the fresh install landed directly on the drive. The uninstall clears `enabled_accessibility_services` (set it to `com.fiospace.tivochannelshome/com.fiospace.tivochannelshome.TiVoButtonService` again and `accessibility_enabled 1`); firstInstallTime/prefs survived. If a move fails with -6, try this.
  Moving an app off an adopted drive (`pm move-package <pkg> internal`) before removing the drive keeps it; moving *our* app switches the accessibility service off (re-set `enabled_accessibility_services`). Check the bus with `adb shell "ls /sys/bus/usb/devices; sm list-disks adoptable; dumpsys usb | grep product_name"` (the shell cannot read USB speed or power).

## App tile (launcher entry) can be found disabled
On .98, .115 and .192 `MainActivity` (the Installed Apps tile) was found in `disabledComponents` (cause unknown - none of our scripts/code does it); the system then does not resolve the
LEANBACK_LAUNCHER entry and the tile is missing. adb cannot fix it (`pm enable`/`default-state`: "Shell cannot change component state"), but the app can: `TiVoButtonService.ensureLauncherEntryEnabled()`
re-enables it every time the service starts (so also after an update). Check: `adb -s $S shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p com.fiospace.tivochannelshome`.

## Do not break
- `onServiceConnected` restricts `packageNames` to this app, sets `eventTypes = 0`, `feedbackType = 0`, `FLAG_REQUEST_FILTER_KEY_EVENTS` only. This fixed Google TV "single click acts as
  long press" side effects. Leave it.
- Version convention: **bump `versionCode`/`versionName` in `app/build.gradle.kts` for every build you install.** The HA dashboard shows `v<name> (<code>) - updated <time>` and a red
  "service off" per TV. 1.23 (24) = LIVE tune raises Channels before the API retune and has the fallback chain (see LIVE button); 1.22 (23) = installLocation=auto (app may live on an adopted drive); 1.21 (22) = the TiVo power key is always swallowed; the old HA switch + host service `tivo_powerkey.py` are gone; 1.20 (21) = HA sync of the Power on / Power off options (`PowerSync.kt`, `/local/tivo-power.txt`, webhook `ha.power.webhook` in local.properties; HA side in ha-docker `tools/gen_tivo_dashboard.py`: per-TV `input_select.tivo_<ip>_power_on/_power_off`, bell notification on a TV-side change); 1.19 (20) = power options as dropdowns in the Configuration dialog: Power on (all devices: No action / Channels / Channels and tune to last state else Live channel, default the last) and Power off (TiVo only: Pause / Continue playing / Exit Channels, default Exit; exit = `POST /api/stop`, Home, `killBackgroundProcesses`); 1.18 (19) = a resumed recording is seeked to where it was (`POST /api/seek/<delta seconds>` is relative); 1.17 (18) = "Restore TV to last state" (see below); 1.16 (17) = power key and TV wake (screen on) do what the LIVE button does (open Channels on the Live channel); 1.15 (16) = screen-on (TV woke) brings Channels to the front, for TVs that sleep (.102); 1.14 (15) = remote power key also brings Channels to the front; 1.13 (14) = SKIP button (272/scan 240) toggles closed captions via `POST /api/toggle_cc` (only while Channels is playing/paused); 1.12 (13) = re-enables a disabled launcher tile; 1.11 (12) = Live key-up passes through (ends the screensaver); 1.10 (11) = HA sync of the Live channel (1.5-1.9 were its development steps incl. TV-side notification experiments, all removed); 1.4 (5) = the app tile opens the Configuration dialog; 1.3 (4) = new icon/banner; 1.2 (3) = LIVE button + channel picker; 1.1 (2) = power-key swallow +
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
- HOME is intercepted and sent to Channels.
- The LIVE channel list comes from a hard-coded DVR address (`DEFAULT_DVR_HOST`; a `dvr_host` pref overrides it, no UI for that yet).
- Make the DVR button land on the DVR library (Channels opens the live player first).
