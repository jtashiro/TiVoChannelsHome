package com.fiospace.tivochannelshome

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.app.AlertDialog
import android.content.DialogInterface
import android.net.Uri
import android.util.Log

class AccessibilitySettingsActivity : Activity() {
    private val PREFS_NAME = "tivo_prefs"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Indicate to UsageMonitorService that the configuration UI is visible; this suppresses auto-launch behavior
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("suppress_launch_for_config", true).apply()
        } catch (e: Exception) {
            Log.w("AccessibilitySettings", "Failed to set suppression pref", e)
        }

        // Present an in-app configuration dialog that the Accessibility settings UI can open.
        val options = arrayOf(
            "Open Usage Access Settings",
            "Uninstall OEM Apps (best-effort)",
            "Launch Channels Live",
            "Close"
        )

        val builder = AlertDialog.Builder(this)
            .setTitle("TiVo Channels Home — Configuration")
            .setItems(options) { dialog: DialogInterface, which: Int ->
                when (which) {
                    0 -> { // Open Usage Access
                        try {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Unable to open Usage Access settings.", Toast.LENGTH_LONG).show()
                        }
                    }
                    1 -> { // Uninstall OEM Apps (best-effort)
                        // Mirror MainActivity's uninstall behavior (silent attempt then interactive)
                        val targets = listOf(
                            "com.tivo.atom",
                            "com.amazon.freevee",
                            "com.sling",
                            "com.amazon.amazonvideo.livingroom",
                            "com.amazon.avod",
                            "com.google.android.play.games",
                            "com.google.android.videos",
                            "com.bydeluxe.d3.android.program.starz",
                            "com.netflix.ninja"
                        )

                        Thread {
                            for (target in targets) {
                                try {
                                    var removed = false
                                    try {
                                        val proc = Runtime.getRuntime().exec(arrayOf("pm", "uninstall", "--user", "0", target))
                                        val exit = proc.waitFor()
                                        if (exit == 0) {
                                            runOnUiThread { Toast.makeText(this, "Uninstalled $target (silent)", Toast.LENGTH_SHORT).show() }
                                            removed = true
                                        } else {
                                            Log.w("AccessibilitySettings", "Silent uninstall exit=$exit for $target; falling back to interactive")
                                        }
                                    } catch (e: Exception) {
                                        Log.w("AccessibilitySettings", "Silent uninstall failed for $target", e)
                                    }

                                    if (!removed) {
                                        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$target")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        startActivity(intent)
                                        runOnUiThread { Toast.makeText(this, "Launched uninstall UI for $target", Toast.LENGTH_SHORT).show() }
                                    }

                                    // Poll for package removal up to 60s
                                    val timeoutMs = 60_000L
                                    val start = System.currentTimeMillis()
                                    while (System.currentTimeMillis() - start < timeoutMs) {
                                        val installed = try {
                                            packageManager.getPackageInfo(target, 0)
                                            true
                                        } catch (e: Exception) {
                                            false
                                        }
                                        if (!installed) {
                                            removed = true
                                            runOnUiThread { Toast.makeText(this, "$target removed", Toast.LENGTH_SHORT).show() }
                                            break
                                        }
                                        Thread.sleep(1000)
                                    }

                                    if (!removed) {
                                        Log.w("AccessibilitySettings", "Timeout waiting for removal of $target")
                                        runOnUiThread { Toast.makeText(this, "Timeout waiting for removal of $target", Toast.LENGTH_SHORT).show() }
                                    }

                                    Thread.sleep(500)
                                } catch (e: Exception) {
                                    Log.w("AccessibilitySettings", "Failed to request uninstall for $target", e)
                                    runOnUiThread { Toast.makeText(this, "Failed to request uninstall for $target", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }.start()
                    }
                    2 -> { // Launch Channels Live
                        try {
                            val pm = packageManager
                            val pkg = "com.getchannels.dvr.app"
                            val launch = pm.getLaunchIntentForPackage(pkg)
                            if (launch != null) {
                                launch.putExtra("open_live", true)
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(launch)
                            } else {
                                Toast.makeText(this, "Channels app not installed", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.w("AccessibilitySettings", "Failed to launch Channels Live", e)
                            Toast.makeText(this, "Failed to launch Channels Live", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> { /* Close */ }
                }
            }
            .setOnDismissListener { finish() }

        // Show the dialog immediately; it will finish the activity when dismissed
        builder.show()

        // Ensure suppression flag is cleared when the activity finishes (onDismiss will call finish())
        // but also clear in onDestroy as a safety net.
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("suppress_launch_for_config", false).apply()
        } catch (e: Exception) {
            Log.w("AccessibilitySettings", "Failed to clear suppression pref", e)
        }
    }
}
