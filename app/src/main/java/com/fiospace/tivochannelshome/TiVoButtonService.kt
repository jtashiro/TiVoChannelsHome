package com.fiospace.tivochannelshome

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.util.Log
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.core.net.toUri
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.os.Build
import android.content.Context
import android.os.Handler
import android.os.Looper

class TiVoButtonService : AccessibilityService() {
    private val NOTIF_ID = 1001
    private val NOTIF_CHANNEL_ID = "tivo_channels_service"
    // Handler to schedule delayed boot-time actions on the main thread
    private lateinit var handler: Handler

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for key events
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("TiVoButtonService", "Accessibility service connected")

        // initialize handler tied to main looper
        handler = Handler(Looper.getMainLooper())

        // Programmatically request key event filtering and basic info to ensure we receive key events
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            // Not retrieving window content — this is a key-event-only service
            info.packageNames = null
            setServiceInfo(info)
            Log.i("TiVoButtonService", "Requested key event filtering via serviceInfo flags")
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Failed to set serviceInfo flags", e)
        }

        // Post a visible notification so it's easy to confirm the service is running
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "TiVo Channels Service",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Shows when the TiVo key interception service is active"
                    }
                    nm.createNotificationChannel(ch)
                }

                val intent = Intent(this, AccessibilitySettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("TiVo Channels Service")
                    .setContentText("Accessibility service is active — intercepting TiVo remote keys")
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .build()

                nm.notify(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Failed to post diagnostic notification", e)
        }

        // Safe per-boot launch: attempt to bring Channels DVR to the foreground once per device boot.
        try {
            val prefs = getSharedPreferences("tivo_prefs", Context.MODE_PRIVATE)
            val currentBootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            val lastBootLaunched = prefs.getLong("boot_launch_boot_time", 0L)

            if (lastBootLaunched != currentBootTime) {
                // schedule a short delayed launch so the system finishes boot tasks and our service is fully bound
                handler.postDelayed({
                    try {
                        // Double-check Channels is installed before trying to launch
                        val pm = packageManager
                        val pkg = "com.getchannels.dvr.app"
                        val launch = pm.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            Log.i("TiVoButtonService", "Boot-time launch: launching Channels DVR (delayed)")
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(launch)
                            // persist that we've launched during this boot
                            prefs.edit().putLong("boot_launch_boot_time", currentBootTime).apply()
                        } else {
                            Log.i("TiVoButtonService", "Boot-time launch: Channels DVR not installed, skipping")
                        }
                    } catch (e: Exception) {
                        Log.w("TiVoButtonService", "Error during boot-time Channels launch", e)
                    }
                }, 3000L) // 3s delay
            } else {
                Log.d("TiVoButtonService", "Channels already launched for current boot; skipping automatic boot launch")
            }
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Failed to schedule boot-time launch", e)
        }
    }

    override fun onDestroy() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIF_ID)
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Failed to cancel notification", e)
        }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Log every key event we receive for debugging
        Log.d("TiVoButtonService", "onKeyEvent action=${event.action} keyCode=${event.keyCode} keyString=${KeyEvent.keyCodeToString(event.keyCode)} repeat=${event.repeatCount} scanCode=${event.scanCode}")

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_TV,
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_DVR -> {
                    if (launchChannelsDvr()) {
                        Log.i("TiVoButtonService", "Intercepted ${KeyEvent.keyCodeToString(event.keyCode)} and launched Channels DVR")
                        return true
                    }
                }
                // Numeric / vendor fallback: some remotes send KEY_UNKNOWN with a vendor scan code.
                KeyEvent.KEYCODE_UNKNOWN -> {
                    // Observed MSC_SCAN value from getevent: 000c003d (hex). Lower 16 bits = 0x003d == 61.
                    // Some devices surface full vendor code as scanCode (0x000C003D == 786493).
                    // User's TiVo Stream remote sends scanCode 240 for the DVR button.
                    val scan = event.scanCode
                    if (scan == 61 || scan == 0x000C003D || scan == 240) {
                        Log.i("TiVoButtonService", "Intercepted KEY_UNKNOWN with scanCode=$scan — treating as TiVo button and launching Channels DVR")
                        if (launchChannelsDvr()) return true
                    } else {
                        Log.d("TiVoButtonService", "KEY_UNKNOWN with scanCode=$scan — not matched")
                    }
                }
                // Add numeric fallbacks here if getevent shows different codes (edit as needed)
            }
        }

        return false
    }

    private fun launchChannelsDvr(): Boolean {
        val pkg = "com.getchannels.dvr.app"
        // First try a direct explicit activity (keeps existing behavior)
        try {
            val explicit = Intent().apply {
                setClassName(pkg, "com.getchannels.android.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(explicit)
            return true
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Explicit activity launch failed, will try package launch", e)
        }

        // Next try the package launch intent (more robust across app versions)
        try {
            val pm = packageManager
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
                return true
            }
        } catch (e: Exception) {
            Log.w("TiVoButtonService", "Package launch failed", e)
        }

        // If Channels DVR isn't installed, open Play Store to the app page as a fallback
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(marketIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Play Store not installed — open web fallback
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
                return true
            } catch (ex: Exception) {
                Log.e("TiVoButtonService", "Failed to open Play Store or web page for Channels DVR", ex)
            }
        } catch (e: Exception) {
            Log.e("TiVoButtonService", "Failed to open Play Store for Channels DVR", e)
        }

        return false
    }
}