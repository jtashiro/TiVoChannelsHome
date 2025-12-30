package com.fiospace.tivochannelshome

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque

class TiVoButtonService : AccessibilityService() {

    companion object {
        private const val TAG = "TiVoButtonService"
        private const val TARGET_PKG = "com.getchannels.dvr.app"
        private const val CHANNELS_PKG = "com.getchannels.dvr.app"
        private const val NOTIF_ID = 1001
        private const val NOTIF_CHANNEL_ID = "tivo_channels_service"
        private const val DEBOUNCE_MS = 300L
    }

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
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyEvent: action=${event.action}, keyCode=${event.keyCode}, scanCode=${event.scanCode}")

        // We only care about key down events for launching apps.
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        return handleLaunchShortcuts(event)
    }

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
            val intent = packageManager.getLaunchIntentForPackage(CHANNELS_PKG)?.apply {
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
            val intent = packageManager.getLaunchIntentForPackage(TARGET_PKG)?.apply {
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
        super.onDestroy()
    }
}
