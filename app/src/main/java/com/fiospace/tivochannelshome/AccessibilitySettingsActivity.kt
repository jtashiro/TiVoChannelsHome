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
import android.text.InputType
import android.util.Log
import android.widget.EditText
import org.json.JSONArray

class AccessibilitySettingsActivity : Activity() {
    private val PREFS_NAME = "tivo_prefs"

    // True while the Live-button channel picker (which replaces this dialog) is showing, so dismissing the menu does not finish the activity.
    private var pickerActive = false

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
        val tivo = ResumePrefs.isTiVoDevice()
        val options = mutableListOf(
            "Open Usage Access Settings",
            "Uninstall OEM Apps (best-effort)",
            "Launch Channels Live",
            "Live button channel: ${LiveChannelPrefs.get(this) ?: "none (opens live player)"}${if (LiveChannelPrefs.pendingSince(this) != 0L) "  (syncing with Home Assistant…)" else ""}",
            "Power on: ${powerOnLabel(ResumePrefs.powerOnMode(this))}",
        )
        if (tivo) options.add("Power off: ${powerOffLabel(ResumePrefs.powerOffMode(this))}")
        options.add("Close")

        val builder = AlertDialog.Builder(this)
            .setTitle("TiVo Channels Home — Configuration")
            .setItems(options.toTypedArray()) { dialog: DialogInterface, which: Int ->
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
                    3 -> { // Live button channel
                        pickerActive = true
                        showLiveChannelPicker()
                    }
                    4 -> { // Power on
                        pickerActive = true
                        showChoice("Power on", POWER_ON_MODES.map { powerOnLabel(it) }, POWER_ON_MODES.indexOf(ResumePrefs.powerOnMode(this))) {
                            ResumePrefs.setPowerFromUser(this, on = POWER_ON_MODES[it])
                            Toast.makeText(this, "Power on: ${powerOnLabel(POWER_ON_MODES[it])}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> if (tivo) { // Power off (TiVo boxes only)
                        pickerActive = true
                        showChoice("Power off", POWER_OFF_MODES.map { powerOffLabel(it) }, POWER_OFF_MODES.indexOf(ResumePrefs.powerOffMode(this))) {
                            ResumePrefs.setPowerFromUser(this, off = POWER_OFF_MODES[it])
                            Toast.makeText(this, "Power off: ${powerOffLabel(POWER_OFF_MODES[it])}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> { /* Close */ }
                }
            }
            .setOnDismissListener { if (!pickerActive) finish() }

        // Show the dialog immediately; it will finish the activity when dismissed
        builder.show()

        // Ensure suppression flag is cleared when the activity finishes (onDismiss will call finish())
        // but also clear in onDestroy as a safety net.
    }

    private val POWER_ON_MODES = listOf(ResumePrefs.ON_NONE, ResumePrefs.ON_CHANNELS, ResumePrefs.ON_TUNE)
    private val POWER_OFF_MODES = listOf(ResumePrefs.OFF_PAUSE, ResumePrefs.OFF_CONTINUE, ResumePrefs.OFF_EXIT)

    private fun powerOnLabel(mode: String) = when (mode) {
        ResumePrefs.ON_NONE -> "No action"
        ResumePrefs.ON_CHANNELS -> "Channels"
        else -> "Channels and tune to last state (else Live channel)"
    }

    private fun powerOffLabel(mode: String) = when (mode) {
        ResumePrefs.OFF_PAUSE -> "Pause"
        ResumePrefs.OFF_CONTINUE -> "Continue playing"
        else -> "Exit Channels"
    }

    /** One-of-N choice list (replaces the main dialog); [onPick] gets the chosen index. Finishes the activity when dismissed. */
    private fun showChoice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                onPick(which)
                dialog.dismiss()
            }
            .setOnDismissListener { finish() }
            .show()
    }

    /** Loads the DVR's channel list and lets the user pick the channel the remote's LIVE button tunes; falls back to typing a number if the DVR cannot be reached. */
    private fun showLiveChannelPicker() {
        val host = LiveChannelPrefs.dvrHost(this)
        val loading = AlertDialog.Builder(this)
            .setTitle("Live button channel")
            .setMessage("Loading channels from $host …")
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        Thread {
            val channels = loadDvrChannels(host)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                loading.dismiss()
                if (channels.isNullOrEmpty()) showManualChannelEntry(host) else showChannelList(channels)
            }
        }.start()
    }

    /** (number, name) for every channel on the DVR, in channel order, or null when the DVR does not answer. */
    private fun loadDvrChannels(host: String): List<Pair<String, String>>? {
        val body = ChannelsHttp.request(host, LiveChannelPrefs.DVR_PORT, "GET", "/api/v1/channels", readTimeoutMs = 15_000) ?: return null
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val number = o.optString("number").trim()
                if (number.isEmpty()) null else Pair(number, o.optString("name").trim())
            }.distinctBy { it.first }.sortedBy { it.first.toDoubleOrNull() ?: Double.MAX_VALUE }
        } catch (e: Exception) {
            Log.w("AccessibilitySettings", "Could not parse the DVR channel list", e)
            null
        }
    }

    private fun showChannelList(channels: List<Pair<String, String>>) {
        val current = LiveChannelPrefs.get(this)
        val labels = ArrayList<String>()
        labels.add((if (current == null) "✓ " else "") + "None — just open the live player")
        channels.forEach { (number, name) -> labels.add((if (number == current) "✓ " else "") + "$number  $name") }
        AlertDialog.Builder(this)
            .setTitle("Live button channel")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = if (which == 0) null else channels[which - 1].first
                LiveChannelPrefs.setFromUser(this, chosen)
                Toast.makeText(this, "Live button: ${chosen ?: "opens the live player"}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun showManualChannelEntry(host: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Channel number, e.g. 6073"
            LiveChannelPrefs.get(this@AccessibilitySettingsActivity)?.let { setText(it) }
        }
        AlertDialog.Builder(this)
            .setTitle("Live button channel")
            .setMessage("Could not load the channel list from $host. Type a channel number (blank = just open the live player).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty() || LiveChannelPrefs.isValid(text)) {
                    LiveChannelPrefs.setFromUser(this, text)
                    Toast.makeText(this, "Live button: ${text.ifEmpty { "opens the live player" }}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Not a channel number: $text", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .show()
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
