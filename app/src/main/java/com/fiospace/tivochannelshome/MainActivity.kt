package com.fiospace.tivochannelshome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the UsageMonitorService is running so we can redirect TiVo Stream -> Channels DVR
        try {
            val svc = Intent(this, UsageMonitorService::class.java)
            startService(svc)
            Log.i("TiVoChannelsHome", "Started UsageMonitorService from MainActivity")
        } catch (e: Exception) {
            Log.w("TiVoChannelsHome", "Failed to start UsageMonitorService", e)
        }

        // The app's tile opens the Configuration dialog (Live button channel, usage access, ...). Channels itself is reached
        // through the remote's buttons (HOME / GUIDE / DVR / LIVE) and its own tile, and "Launch Channels Live" is in the dialog.
        try {
            startActivity(Intent(this, AccessibilitySettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e("TiVoChannelsHome", "Failed to open the Configuration dialog", e)
        }

        finish()
    }
}
