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
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = flags or
                        AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            Log.i(TAG, "Requested key event and window content filtering")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set serviceInfo flags", e)
        }
        createNotification()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyEvent: action=${event.action}, keyCode=${event.keyCode}, scanCode=${event.scanCode}")

        return when {
            // Map ESCAPE to perform a global BACK action.
            event.keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.i(TAG, "Escape key up, performing GLOBAL_ACTION_BACK.")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                true // Consume both down and up events.
            }

            // Map PAUSE/BREAK button to clicking the on-screen Play/Pause UI.
            event.keyCode == KeyEvent.KEYCODE_BREAK || event.scanCode == 119 -> {
                handlePauseKeyPress(event)
                true // Always consume this key.
            }

            // Handle app launching shortcuts on key down.
            event.action == KeyEvent.ACTION_DOWN -> handleLaunchShortcuts(event)

            // For all other keys and actions, do not consume the event.
            else -> false
        }
    }

    private fun handlePauseKeyPress(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isBreakPressed || System.currentTimeMillis() - lastBreakEmitTime < DEBOUNCE_MS) {
                return // Debounce or ignore repeats
            }
            isBreakPressed = true
            lastBreakEmitTime = System.currentTimeMillis()

            Log.i(TAG, "PAUSE/BREAK detected, attempting to click on-screen Play/Pause button.")
            if (findAndClickNodeByText("pause", "play")) {
                Log.i(TAG, "Successfully clicked Play/Pause button.")
            } else {
                Log.w(TAG, "Failed to find and click Play/Pause button.")
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            isBreakPressed = false
        }
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
            }
            KeyEvent.KEYCODE_GUIDE -> {
                Log.i(TAG, "GUIDE button pressed, launching Channels guide.")
                launchChannelsMainActivity(extras = mapOf("tab" to "guide"))
            }
            KeyEvent.KEYCODE_DVR -> launchDvrAction()
            KeyEvent.KEYCODE_HOME -> {
                Log.i(TAG, "HOME button pressed, launching Channels DVR.")
                launchTargetApp()
                true
            }
            KeyEvent.KEYCODE_UNKNOWN -> if (event.scanCode == 240) launchDvrAction() else false
            else -> false
        }
    }

    private fun findAndClickNodeByText(vararg keywords: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "findAndClickNodeByText: rootInActiveWindow is null.")
            return false
        }

        val queue: java.util.Queue<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        try {
            while (queue.isNotEmpty()) {
                val node = queue.poll() ?: continue

                val matches = keywords.any { keyword ->
                    node.contentDescription?.contains(keyword, ignoreCase = true) == true ||
                            node.text?.contains(keyword, ignoreCase = true) == true
                }

                if (matches) {
                    var clickableNode: AccessibilityNodeInfo? = node
                    while (clickableNode != null && !clickableNode.isClickable) {
                        clickableNode = clickableNode.parent
                    }

                    if (clickableNode != null) {
                        Log.i(TAG, "Found clickable node for '${keywords.joinToString()}': ${clickableNode.className}")
                        return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        Log.w(TAG, "Found a node with matching text but it was not clickable: ${node.text}")
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } finally {
            root.recycle()
        }

        Log.w(TAG, "Could not find any clickable node for: ${keywords.joinToString()}")
        return false
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
