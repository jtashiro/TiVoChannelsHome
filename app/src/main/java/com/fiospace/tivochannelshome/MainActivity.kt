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

        val pkg = "com.getchannels.dvr.app"
        try {
            // Try package launch intent first (more robust)
            val pm = packageManager
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
            } else {
                // If Channels not installed or no launch intent, open Accessibility settings to let user enable service
                Log.i("TiVoChannelsHome", "Channels DVR not installed or no launch intent — opening Accessibility Settings")
                val settings = Intent(this, AccessibilitySettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(settings)
            }
        } catch (e: Exception) {
            Log.e("TiVoChannelsHome", "Failed to launch Channels DVR or open settings", e)
        }

        finish()
    }
}
