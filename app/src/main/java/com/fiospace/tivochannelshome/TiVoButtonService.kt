package com.fiospace.tivochannelshome

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class TiVoButtonService : AccessibilityService() {

    companion object {
        private const val TAG = "TiVoButtonService"
        private const val TARGET_PKG = "com.getchannels.dvr.app"
        private const val CHANNELS_PKG = "com.getchannels.dvr.app"
        private const val NOTIF_ID = 1001
        private const val NOTIF_CHANNEL_ID = "tivo_channels_service"
        private const val DEBOUNCE_MS = 300L

        // The TiVo remote's POWER key is Linux scan code 116. The vendor key layout
        // (/vendor/usr/keylayout/Vendor_0471_Product_1207.kl) maps it to MEDIA_PLAY_PAUSE, so every press (TV off OR on)
        // toggled play/pause in Channels. The remote's IR power command to the TV is a separate signal and still works.
        private const val POWER_SCAN_CODE = 116

        // The remote's LIVE button arrives as keyCode 5124, scanCode 240 (seen in logcat; the service receives it, the system does not consume it first).
        private const val LIVE_KEYCODE = 5124
        private const val LIVE_SCAN_CODE = 240
        // The remote's SKIP button (keyCode 272 = KEYCODE_MEDIA_SKIP_FORWARD, scanCode 240) toggles closed captions via the Channels API.
        private const val SKIP_KEYCODE = 272
        private const val SKIP_SCAN_CODE = 240
        // The Channels app's own HTTP API (answers on the device itself).
        private const val CHANNELS_API_PORT = 57000
    }

    private val liveBusy = AtomicBoolean(false)
    private var isBreakPressed = false
    private var lastBreakEmitTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        try {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                // Limit the service to only listen to this app's own events. 
                // This prevents the system (and other apps like Google TV) from thinking we are 
                // actively exploring/reading their UI, which fixes the "single click acts as long press" issue.
                packageNames = arrayOf(packageName)
                
                // We don't need any standard events.
                eventTypes = 0
                
                // Setting feedbackType to 0 (or not setting it) effectively disables feedback, which
                // avoids triggering accessibility behavior in other apps.
                feedbackType = 0
                
                // IMPORTANT: Overwrite flags instead of OR-ing to ensure we don't accidentally enable touch exploration.
                flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
            Log.i(TAG, "Requested key event filtering with restricted package scope")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set serviceInfo flags", e)
        }
        createNotification()
        // Keep the LIVE-button channel in step with Home Assistant (no-op unless ha.host / ha.live.webhook were set at build time).
        LiveSync.start(applicationContext)
        PowerSync.start(applicationContext)
        ensureLauncherEntryEnabled()
        registerScreenOnReceiver()
        startSnapshotPoll()
    }

    private var screenOnReceiver: android.content.BroadcastReceiver? = null
    private var lastScreenOnLaunch = 0L

    /**
     * TVs that really sleep (the TCL Google TV, .102) get no power key we can see: the system handles it. Waking the TV is the "power on" event,
     * so ACTION_SCREEN_ON does what the LIVE button does (Channels on the Live channel). (The TiVo boxes stay awake when their TV goes off, so this stays quiet there.
     * Ending the screensaver does not send ACTION_SCREEN_ON.)
     */
    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    Log.i(TAG, "Screen off (TV went to sleep)")
                    handlePowerOff()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastScreenOnLaunch < 5_000) return
                lastScreenOnLaunch = now
                Log.i(TAG, "Screen on (TV woke)")
                handlePowerOn()
            }
        }
        try {
            registerReceiver(r, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            screenOnReceiver = r
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen-on receiver", e)
        }
    }

    @Volatile private var lastSnap: ResumeSnapshot? = null
    @Volatile private var pollRunning = false

    /** What Channels is playing right now (live channel or recording), or null. */
    private fun snapshotNow(): ResumeSnapshot? = ResumeSnapshot.fromStatus(channelsApi("GET", "/api/status"))

    /**
     * Channels' status turns to "stopped" within ~0.4 s of the TV going to sleep (measured on .102), which is before or around the screen-off
     * broadcast; so while the screen is on, remember what was playing every few seconds.
     */
    private fun startSnapshotPoll() {
        if (pollRunning) return
        pollRunning = true
        Thread {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            while (pollRunning) {
                try {
                    if (pm.isInteractive && ResumePrefs.isEnabled(this)) {
                        val s = snapshotNow()
                        if (pm.isInteractive) lastSnap = s   // a reply that raced the screen turning off is not trusted
                    }
                    Thread.sleep(4_000)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "Snapshot poll failed", e)
                }
            }
        }.start()
    }

    /** True once Channels has been "playing" [s] (the channel, or for a recording just playing) for 2 s in a row. */
    private fun waitPlayingSnap(s: ResumeSnapshot, timeoutMs: Long): Boolean {
        if (s.kind == ResumeSnapshot.LIVE) return waitPlaying(s.id, timeoutMs)
        val end = System.currentTimeMillis() + timeoutMs
        var since = 0L
        while (System.currentTimeMillis() < end) {
            if (channelsStatus().first == "playing") {
                if (since == 0L) since = System.currentTimeMillis()
                if (System.currentTimeMillis() - since >= 2_000) return true
            } else since = 0L
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Channels restarts a recording from its own saved resume point, not from where the TV was switched off. Its seek call is relative
     * (`POST /api/seek/<seconds>`, negative = back; measured on .137), so seek by the difference between the wanted position and the current one.
     */
    private fun seekRecording(wantedSec: Double) {
        if (wantedSec < 5.0) return
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            val body = channelsApi("GET", "/api/status")
            val cur = try { JSONObject(body ?: "").takeIf { it.optString("status") == "playing" && it.has("playback_time") }?.optDouble("playback_time") } catch (e: Exception) { null }
            if (cur != null) {
                val delta = wantedSec - cur
                if (Math.abs(delta) > 5.0) {
                    channelsApi("POST", "/api/seek/" + String.format(java.util.Locale.US, "%.1f", delta))
                    Log.i(TAG, "Power on: recording seeked from ${cur.toInt()} s to ${wantedSec.toInt()} s")
                }
                return
            }
            Thread.sleep(500)
        }
        Log.w(TAG, "Power on: could not seek the recording to ${wantedSec.toInt()} s (not playing)")
    }

    /** Power off (screen off, or the first press of the TiVo power key): remember what Channels was playing, for the next power on. */
    private fun handlePowerOff(offMode: String = ResumePrefs.OFF_CONTINUE) {
        if (!ResumePrefs.isEnabled(this) && offMode == ResumePrefs.OFF_CONTINUE) return
        Thread {
            try {
                if (ResumePrefs.isEnabled(this)) saveStateForPowerOn()
                when (offMode) {
                    ResumePrefs.OFF_PAUSE -> if (channelsStatus().first == "playing") {
                        channelsApi("POST", "/api/pause")
                        Log.i(TAG, "Power off: Channels paused")
                    }
                    ResumePrefs.OFF_EXIT -> stopAndExitChannels()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Power off failed", e)
            }
        }.start()
    }

    private fun saveStateForPowerOn() {
        val snap = snapshotNow() ?: lastSnap?.takeIf { System.currentTimeMillis() - it.at <= 10_000 }?.let {
            // The poll's snapshot is a few seconds old: a recording has moved on since.
            if (it.kind == ResumeSnapshot.RECORDING && it.playing) it.copy(posSec = it.posSec + (System.currentTimeMillis() - it.at) / 1000.0) else it
        }
        if (snap != null) {
            ResumePrefs.save(this, snap)
            Log.i(TAG, "Power off: remembered ${snap.kind} ${snap.id}")
        } else {
            ResumePrefs.clear(this)
            Log.i(TAG, "Power off: nothing was playing")
        }
    }

    /**
     * TiVo boxes only: stop what Channels is playing and exit it. /api/stop ends playback but leaves the app on its menu (Back does not leave it),
     * so go Home and then kill the now-background process.
     */
    private fun stopAndExitChannels() {
        val (status, _) = channelsStatus()
        if (status == "playing" || status == "paused") channelsApi("POST", "/api/stop")
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(1_500)
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(CHANNELS_PKG)
        Log.i(TAG, "Power off: Channels stopped and exited")
    }

    /**
     * Power on, by the "Power On" option: nothing; just Channels; or Channels tuned to what was playing at power off (else the Live channel).
     * A paused Channels (Power Off = pause) is resumed in the last two.
     */
    private fun handlePowerOn() {
        val mode = ResumePrefs.powerOnMode(this)
        if (mode == ResumePrefs.ON_NONE) return
        val snap = if (mode == ResumePrefs.ON_TUNE) ResumePrefs.take(this) else null
        if (mode == ResumePrefs.ON_TUNE && snap == null) {
            handleLiveButton()
            return
        }
        if (!liveBusy.compareAndSet(false, true)) {
            Log.i(TAG, "Power on: previous press still being handled: ignored")
            return
        }
        Thread {
            try {
                if (snap == null) {
                    Log.i(TAG, "Power on: bringing Channels to the front")
                    bringChannelsToFront()
                    if (channelsStatus().first == "paused") channelsApi("POST", "/api/resume")
                    return@Thread
                }
                Log.i(TAG, "Power on: resuming ${snap.kind} ${snap.id}")
                if (snap.kind == ResumeSnapshot.LIVE) {
                    tuneLive(snap.id)
                } else {
                    val (status, _) = channelsStatus()
                    if (status == "playing" || status == "paused") channelsApi("POST", "/api/play/recording/${snap.id}")
                    else launchChannelsDeepLink(snap.id, "recording")
                }
                // Right after a wake Channels' status can still say "playing" for the player that is being torn down (seen on .102: "already
                // playing 6007" and then it stopped), so check again and fall back to the deep link, which starts the app on it.
                Thread.sleep(3_000)
                if (!waitPlayingSnap(snap, 8_000)) {
                    Log.w(TAG, "Power on: ${snap.kind} ${snap.id} not playing, opening it with the deep link")
                    launchChannelsDeepLink(snap.id, if (snap.kind == ResumeSnapshot.LIVE) "channel" else "recording")
                    waitPlayingSnap(snap, 25_000)
                }
                if (snap.kind == ResumeSnapshot.RECORDING) seekRecording(snap.posSec)
                if (channelsStatus().first == "paused") channelsApi("POST", "/api/resume")
            } catch (e: Exception) {
                Log.w(TAG, "Power on: failed", e)
            } finally {
                liveBusy.set(false)
            }
        }.start()
    }

    /**
     * On some TVs the app's launcher activity (MainActivity, the tile in Installed Apps) was found disabled - adb cannot re-enable it
     * ("Shell cannot change component state") but the app can enable its own components. Runs every time the service starts, so it also
     * repairs the tile after an update.
     */
    private fun ensureLauncherEntryEnabled() {
        try {
            val cn = ComponentName(this, MainActivity::class.java)
            if (packageManager.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                packageManager.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "re-enabled the app's launcher entry (it was disabled, so the tile was missing from Installed Apps)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check/enable the launcher entry", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyEvent: action=${event.action}, keyCode=${event.keyCode}, scanCode=${event.scanCode}")

        // Swallow the remote's power key (down AND up) so Channels never sees it as play/pause.
        if (event.scanCode == POWER_SCAN_CODE && event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            // The key is a toggle (on and off look the same to us), so the service tracks it: first press = power off (remember what Channels was
            // playing), next press = power on (resume that, or else do what the LIVE button does).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (ResumePrefs.isOff(this)) {
                    ResumePrefs.setOff(this, false)
                    handlePowerOn()
                } else {
                    ResumePrefs.setOff(this, true)
                    handlePowerOff(ResumePrefs.powerOffMode(this))
                }
            }
            return true   // always swallowed: what the press does is set by the Power on / Power off options
        }

        // LIVE button: tune the configured channel. Swallow the DOWN so nothing else reacts to it, but let the UP through: TiVo's own system app
        // (com.nes.tvglobalkeyhandler) sees the key-up and ends the screensaver (Ambient mode); if the key were swallowed completely the
        // screensaver stayed on top of Channels and the button seemed dead. (Its own action for this key, launching com.tivo.atom, is a no-op
        // because that app is removed on these TVs.)
        if (event.keyCode == LIVE_KEYCODE && event.scanCode == LIVE_SCAN_CODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleLiveButton()
            }
            return event.action == KeyEvent.ACTION_DOWN
        }

        // SKIP button: toggle closed captions in the Channels player. Same DOWN-only swallow as LIVE (the UP must reach TiVo's screensaver handling).
        if (event.keyCode == SKIP_KEYCODE && event.scanCode == SKIP_SCAN_CODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                handleSkipButton()
            }
            return event.action == KeyEvent.ACTION_DOWN
        }

        // We only care about key down events for launching apps.
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        return handleLaunchShortcuts(event)
    }

    /** The channel the LIVE button tunes: chosen in the app's Configuration panel (see [LiveChannelPrefs]); null = none chosen. */
    private fun liveChannel(): String? = LiveChannelPrefs.get(this)

    /** LIVE button: tune [liveChannel] if one is set (everything runs on this TV, no host involved), else just open the live player. */
    private fun handleLiveButton() {
        val ch = liveChannel()
        if (ch == null) {
            Log.i(TAG, "LIVE button pressed, no Live channel chosen: launching Channels live player.")
            launchChannelsPlayerLive()
            return
        }
        if (!liveBusy.compareAndSet(false, true)) {
            Log.i(TAG, "LIVE button pressed, previous press still being handled: ignored")
            return
        }
        Thread {
            try {
                tuneLive(ch)
            } catch (e: Exception) {
                Log.w(TAG, "LIVE button: tune of $ch failed", e)
            } finally {
                liveBusy.set(false)
            }
        }.start()
    }

    /** SKIP button: flip closed captions. The API only acts while the player is up, so it is called only when Channels is playing/paused. */
    private fun handleSkipButton() {
        Thread {
            try {
                val status = channelsStatus().first
                if (status == "playing" || status == "paused") {
                    channelsApi("POST", "/api/toggle_cc")
                    Log.i(TAG, "SKIP button: toggled closed captions (status=$status)")
                } else {
                    Log.i(TAG, "SKIP button: Channels not playing (status=$status), ignored")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SKIP button: toggle_cc failed", e)
            }
        }.start()
    }

    /**
     * Channels already playing/paused: its API retunes inside that player (a deep link would start a second PlayerActivity over it).
     * Otherwise (closed, or open on a menu, where the API answers but a tune does nothing): the deep link, which starts the app on the channel.
     * Already playing [ch]: just bring the app to the front, do not restart the stream.
     */
    private fun tuneLive(ch: String) {
        val t0 = System.currentTimeMillis()
        val (status, current) = channelsStatus()
        if (status == "playing" && current == ch) {
            bringChannelsToFront()
            Log.i(TAG, "LIVE button: already playing $ch, not re-tuned")
            return
        }
        if (status == "playing" || status == "paused") {
            Log.i(TAG, "LIVE button: tuning $ch through the Channels API")
            if (channelsApi("POST", "/api/play/channel/$ch") != null && waitPlaying(ch, 10_000)) {
                Log.i(TAG, "LIVE button: playing $ch via api in ${System.currentTimeMillis() - t0} ms")
                return
            }
            Log.w(TAG, "LIVE button: API tune of $ch did not take, trying the deep link")
        } else {
            Log.i(TAG, "LIVE button: no player running (status=$status), opening Channels on $ch")
        }
        launchChannelsDeepLink(ch)
        if (waitPlaying(ch, 25_000)) {
            Log.i(TAG, "LIVE button: playing $ch via deeplink in ${System.currentTimeMillis() - t0} ms")
        } else {
            Log.w(TAG, "LIVE button: $ch not playing after ${System.currentTimeMillis() - t0} ms")
        }
    }

    private fun launchChannelsDeepLink(ch: String, kind: String = "channel") {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("channels://$CHANNELS_PKG/play/$kind/$ch")).apply {
                setPackage(CHANNELS_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open the Channels deep link for $ch", e)
        }
    }

    /** Brings the running Channels task to the front without CLEAR_TOP, so the player keeps playing. */
    private fun bringChannelsToFront() {
        try {
            val intent = (packageManager.getLaunchIntentForPackage(CHANNELS_PKG)
                ?: packageManager.getLeanbackLaunchIntentForPackage(CHANNELS_PKG))?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent != null) startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring Channels to the front", e)
        }
    }

    /** True once Channels reports "playing" on [ch] for 2 s in a row (it says "playing" as soon as a tune is requested, before the stream exists). */
    private fun waitPlaying(ch: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        var since = 0L
        while (System.currentTimeMillis() < end) {
            val (status, current) = channelsStatus()
            if (status == "playing" && current == ch) {
                if (since == 0L) since = System.currentTimeMillis()
                if (System.currentTimeMillis() - since >= 2_000) return true
            } else {
                since = 0L
            }
            Thread.sleep(250)
        }
        return false
    }

    /** (status, channel number) from the Channels app's API, or (null, null) when it does not answer. */
    private fun channelsStatus(): Pair<String?, String?> {
        val body = channelsApi("GET", "/api/status") ?: return Pair(null, null)
        return try {
            val json = JSONObject(body)
            Pair(json.optString("status").ifEmpty { null }, json.optJSONObject("channel")?.optString("number")?.ifEmpty { null })
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /** Call to the Channels API on this device; returns the body, or null on any failure. */
    private fun channelsApi(method: String, path: String): String? = ChannelsHttp.request("127.0.0.1", CHANNELS_API_PORT, method, path)

    private fun handleLaunchShortcuts(event: KeyEvent): Boolean {
        val launchDvrAction = {
            Log.i(TAG, "Launching Channels DVR library.")
            launchChannelsMainActivity(extras = mapOf("tab" to "dvr_library"))
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_TV -> {
                Log.i(TAG, "TV button pressed, launching Channels live player.")
                launchChannelsPlayerLive()
                true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                Log.i(TAG, "GUIDE button pressed, launching Channels guide.")
                launchChannelsMainActivity(extras = mapOf("tab" to "guide"))
                true
            }
            KeyEvent.KEYCODE_DVR -> {
                launchDvrAction()
                true
            }
            KeyEvent.KEYCODE_HOME -> {
                Log.i(TAG, "HOME button pressed, launching Channels DVR.")
                launchTargetApp()
                true
            }
            KeyEvent.KEYCODE_UNKNOWN -> if (event.scanCode == 240) {
                launchDvrAction()
                true
            } else false
            else -> false
        }
    }

    private fun launchChannelsMainActivity(extras: Map<String, String>? = null): Boolean {
        return try {
            val intent = (packageManager.getLaunchIntentForPackage(CHANNELS_PKG)
                ?: packageManager.getLeanbackLaunchIntentForPackage(CHANNELS_PKG))?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                extras?.forEach { (k, v) -> putExtra(k, v) }
            }
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                Log.w(TAG, "Launch intent not found for package: $CHANNELS_PKG")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch main activity for $CHANNELS_PKG", e)
            false
        }
    }

    private fun launchChannelsPlayerLive(channel: String? = null): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(CHANNELS_PKG, "com.getchannels.android.PlayerActivity")
                putExtra("open_live", true)
                channel?.let { putExtra("channel", it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch player activity for $CHANNELS_PKG", e)
            false
        }
    }

    private fun launchTargetApp(): Boolean {
        return try {
            val intent = (packageManager.getLaunchIntentForPackage(TARGET_PKG)
                ?: packageManager.getLeanbackLaunchIntentForPackage(TARGET_PKG))?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                Log.w(TAG, "Launch intent not found for package: $TARGET_PKG")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch main activity for $TARGET_PKG", e)
            false
        }
    }

    private fun createNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(NOTIF_CHANNEL_ID, "TiVo Channels Service", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows when the TiVo key interception service is active"
                }
                nm.createNotificationChannel(ch)
            }

            val intent = Intent(this, AccessibilitySettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("TiVo Channels Service")
                .setContentText("Accessibility service is active — intercepting TiVo remote keys")
                .setContentIntent(pending)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                nm.notify(NOTIF_ID, notif)
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping notification")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post diagnostic notification", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This method is called for all events that match the eventTypes, but we are only
        // interested in key events, which are handled by onKeyEvent. So we can leave this empty.
        // We now request only TYPE_VIEW_KEY_EVENT, so this will only be called for key events.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification on destroy", e)
        }
        LiveSync.stop()
        PowerSync.stop()
        try { screenOnReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        screenOnReceiver = null
        pollRunning = false
        super.onDestroy()
    }
}
