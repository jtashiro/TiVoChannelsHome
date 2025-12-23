package com.fiospace.tivochannelshome

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.os.Handler
import android.os.HandlerThread
import android.content.Context
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.os.Build

class UsageMonitorService : Service() {
    private val TAG = "UsageMonitorService"
    private var running = false
    private lateinit var handler: Handler
    private val PREFS_NAME = "tivo_prefs"

    // When true (set by AccessibilitySettingsActivity while the configuration UI is visible)
    // the UsageMonitorService should not schedule auto-launches of Channels DVR.
    private fun isLaunchSuppressed(): Boolean {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean("suppress_launch_for_config", false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read suppression pref", e)
            false
        }
    }

    // Debounce / persistence window (ms) before we react to a foreground switch
    private val PERSISTENCE_MS = 1500L
    // Minimum time between launching Channels to avoid restart loops
    private val LAUNCH_COOLDOWN_MS = 5000L

    // If no usage events are observed for this long, stop the service (ms)
    private val NO_USAGE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    // Foreground notification config
    private val NOTIF_CHANNEL_ID = "usage_monitor_channel"
    private val NOTIF_ID = 2001

    private var lastForegroundPackage: String? = null
    // The timestamp (ms) when we first observed the current lastForegroundPackage
    private var lastForegroundFirstSeen: Long = 0L
    private var lastLaunchTime: Long = 0L

    // Scheduled delayed launch runnable and which package it's for
    private var scheduledLaunchRunnable: Runnable? = null
    private var scheduledForPackage: String? = null

    // Track last time we saw any usage events (for NO_USAGE_TIMEOUT_MS)
    private var lastUsageEventSeenAt: Long = 0L

    // Target package to launch (Google TV Launcher) when TiVo Home is detected
    private val TARGET_PKG = "com.google.android.apps.tv.launcherx"

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("usage-monitor")
        thread.start()
        handler = Handler(thread.looper)

        // Ensure the service runs in the foreground to avoid being stopped due to app idle
        createNotificationChannelIfNeeded()
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, UsageAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("TiVo Google TV — Usage Monitor")
            .setContentText("Monitoring foreground apps to intercept TiVo. Tap to open Usage Access settings.")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground notification", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            lastUsageEventSeenAt = System.currentTimeMillis()
            // If we don't have Usage Access, inform via log & keep running (accessibility fallback still works)
            if (!hasUsageStatsPermission()) {
                Log.w(TAG, "No Usage Access: UsageMonitorService may not see foreground app events. Please grant Usage Access to the app.")
                // Show notification action to open settings (already provided by foreground notification)
            }
            handler.post(monitorRunnable)
            Log.i(TAG, "UsageMonitorService started")
        }
        return START_STICKY
    }

    private fun createNotificationChannelIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "TiVo Channels: Usage Monitor",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Shows that TiVo Channels is monitoring foreground apps"
                    }
                    nm.createNotificationChannel(ch)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create notification channel", e)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check AppOps for GET_USAGE_STATS", e)
            return false
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            try {
                checkForegroundApp()
            } catch (e: Exception) {
                Log.w(TAG, "Error checking foreground app", e)
            }
            if (running) handler.postDelayed(this, 1000)
        }
    }

    private fun checkForegroundApp() {
        val pkgTiVoGuess = "com.tivostream.app" // fallback guess; we'll also match by substring 'tivo' in case package differs
        
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 5000 // look back several seconds to capture recent events
        val events = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()

        var lastPackageSeen: String? = null
        var lastEventTime: Long = 0L

        var anyEvent = false
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            anyEvent = true
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                // we want the most recent MOVE_TO_FOREGROUND
                if (ev.timeStamp >= lastEventTime) {
                    lastEventTime = ev.timeStamp
                    lastPackageSeen = ev.packageName
                }
            }
        }

        val now = System.currentTimeMillis()
        if (anyEvent) {
            lastUsageEventSeenAt = now
        } else {
            // If we've seen no usage events for a long time, shut down the service to avoid spinning
            if (now - lastUsageEventSeenAt >= NO_USAGE_TIMEOUT_MS) {
                Log.w(TAG, "No usage events for ${NO_USAGE_TIMEOUT_MS}ms — stopping UsageMonitorService to save resources. Open Usage Access settings or restart service when ready.")
                stopSelf()
                running = false
                return
            }
            // Quiet: skip noisy 'no usage events' log; user can check Usage Access in settings if needed.
            return
        }

        if (lastPackageSeen == null) {
            // no foreground move events in the window
            return
        }

        // If package changed, set first-seen timestamp to the event time we observed
        if (lastPackageSeen != lastForegroundPackage) {
            lastForegroundPackage = lastPackageSeen
            lastForegroundFirstSeen = lastEventTime
            Log.d(TAG, "Foreground package changed -> $lastForegroundPackage at ${lastForegroundFirstSeen}")

            // Cancel any previously scheduled launch if package changed
            cancelScheduledLaunch()
        } else {
            // same package; keep the existing first seen time so we can require persistence
        }

        val age = now - lastForegroundFirstSeen

        val ownPkg = packageName

        if ((lastForegroundPackage!!.contains("tivo", true) || lastForegroundPackage == pkgTiVoGuess) && lastForegroundPackage != TARGET_PKG) {
            // Exclude our own package from being treated as 'TiVo' so opening our config UI
            // doesn't trigger an automatic Channels launch.
            if (lastForegroundPackage == ownPkg) {
                Log.d(TAG, "Foreground package is our own package ($ownPkg) — do not auto-launch Google TV")
                cancelScheduledLaunch()
                return
            }
            // If the configuration UI is active, don't schedule or perform automated launches.
            if (isLaunchSuppressed()) {
                Log.d(TAG, "Auto-launch suppressed because configuration UI is active; skipping scheduled launch")
                cancelScheduledLaunch()
                return
            }
            // Use a scheduled delayed launch so the foreground package must remain TiVo for PERSISTENCE_MS
            if (now - lastLaunchTime < LAUNCH_COOLDOWN_MS) {
                Log.d(TAG, "Detected TiVo foreground but in cooldown (${now - lastLaunchTime}ms) — skipping launch")
                return
            }

            if (scheduledForPackage == lastForegroundPackage && scheduledLaunchRunnable != null) {
                // already scheduled for this package
                Log.d(TAG, "Launch already scheduled for $scheduledForPackage — waiting")
                return
            }

            // schedule a launch to run after PERSISTENCE_MS (or sooner if already older)
            val delay = if (age >= PERSISTENCE_MS) 0L else (PERSISTENCE_MS - age)
            Log.d(TAG, "Scheduling Google TV launch for $lastForegroundPackage in ${delay}ms")
            scheduledForPackage = lastForegroundPackage
            scheduledLaunchRunnable = Runnable {
                try {
                    // Re-check stability before launching
                    val stableNow = System.currentTimeMillis()
                    val stableAge = stableNow - lastForegroundFirstSeen
                    if (scheduledForPackage != lastForegroundPackage) {
                        Log.d(TAG, "Scheduled package ${scheduledForPackage} no longer matches current foreground $lastForegroundPackage — aborting launch")
                        return@Runnable
                    }
                    if (stableAge < PERSISTENCE_MS) {
                        Log.d(TAG, "Scheduled launch aborted: package not stable enough (${stableAge}ms)")
                        return@Runnable
                    }
                    Log.i(TAG, "Scheduled launch: Detected TiVo foreground package: $lastForegroundPackage (stable ${stableAge}ms) — launching Google TV")
                    val launched = launchTargetApp()
                    if (launched) {
                        lastLaunchTime = System.currentTimeMillis()
                    } else {
                        Log.w(TAG, "Scheduled launch attempted but launchTargetApp() returned false")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in scheduled launch", e)
                } finally {
                    // clear scheduled state
                    scheduledLaunchRunnable = null
                    scheduledForPackage = null
                }
            }
            handler.postDelayed(scheduledLaunchRunnable!!, delay)

        } else {
            // Not a TiVo package (or it's the target) — reset state appropriately
            if (lastForegroundPackage == TARGET_PKG) {
                // If target is foreground, reset lastLaunchTime to avoid blocking
                lastLaunchTime = 0L
            }
            // If package changed away from TiVo, cancel any scheduled launch
            cancelScheduledLaunch()
        }
    }

    private fun cancelScheduledLaunch() {
        try {
            if (scheduledLaunchRunnable != null) {
                handler.removeCallbacks(scheduledLaunchRunnable!!)
                scheduledLaunchRunnable = null
                scheduledForPackage = null
                Log.d(TAG, "Cancelled scheduled launch")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling scheduled launch", e)
        }
    }

    private fun launchTargetApp(): Boolean {
        try {
            val pm = packageManager
            val launch = pm.getLaunchIntentForPackage(TARGET_PKG)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target package $TARGET_PKG", e)
        }

        // fallback: open Play Store / web if not installed
        try {
            val market = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("market://details?id=$TARGET_PKG")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(market)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Play Store launch failed", e)
        }

        try {
            val web = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=$TARGET_PKG")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(web)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Play Store web page for $TARGET_PKG", e)
        }

        return false
    }

    override fun onDestroy() {
        running = false
        cancelScheduledLaunch()
        handler.removeCallbacksAndMessages(null)
        try {
            stopForeground(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
