package com.fiospace.tivochannelshome

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

/** Minimal HTTP/1.0 client over a plain socket, so no cleartext-traffic configuration is needed for the LAN-only Channels / Home Assistant endpoints. */
object ChannelsHttp {
    /** Body of a 2xx response, or null on any failure. [body] (with [contentType]) is sent as the request body. */
    fun request(
        host: String,
        port: Int,
        method: String,
        path: String,
        connectTimeoutMs: Int = 1_500,
        readTimeoutMs: Int = 8_000,
        body: String? = null,
        contentType: String? = null,
    ): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            val payload = body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder("$method $path HTTP/1.0\r\nHost: $host\r\nContent-Length: ${payload?.size ?: 0}\r\n")
            if (payload != null && contentType != null) head.append("Content-Type: $contentType\r\n")
            head.append("Connection: close\r\n\r\n")
            s.getOutputStream().apply {
                write(head.toString().toByteArray())
                if (payload != null) write(payload)
                flush()
            }
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            if (!resp.startsWith("HTTP/1.") || resp.length < 10 || resp[9] != '2') null else resp.substringAfter("\r\n\r\n", "")
        }
    } catch (e: Exception) {
        null
    }
}

/** The channel the remote's LIVE button tunes. Chosen in [AccessibilitySettingsActivity] or from Home Assistant (see [LiveSync]); stored in the app's own preferences. */
object LiveChannelPrefs {
    private const val PREFS = "tivo_prefs"   // same file AccessibilitySettingsActivity / UsageMonitorService already use
    private const val KEY_CHANNEL = "live_channel"
    private const val KEY_PENDING_SINCE = "live_pending_since"
    private const val KEY_DVR_HOST = "dvr_host"
    private const val SETTINGS_GLOBAL_KEY = "tivo_live_channel"   // adb fallback: `adb shell settings put global tivo_live_channel 6073`
    const val DEFAULT_DVR_HOST = "192.168.1.61"
    const val DVR_PORT = 8089
    private val VALID = Regex("""\d{1,6}(\.\d{1,3})?""")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen channel number; null = none chosen (the LIVE button just opens the live player). An explicit choice in the app wins over the adb setting. */
    fun get(context: Context): String? {
        val prefs = prefs(context)
        val value = if (prefs.contains(KEY_CHANNEL)) {
            prefs.getString(KEY_CHANNEL, null)
        } else {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, SETTINGS_GLOBAL_KEY)
            } catch (e: Exception) {
                null
            }
        }
        return value?.trim()?.takeIf { VALID.matches(it) }
    }

    /** Stores the choice locally only; null or blank = none. */
    fun set(context: Context, channel: String?) {
        prefs(context).edit().putString(KEY_CHANNEL, channel?.trim().orEmpty()).apply()
    }

    /** A choice made on this TV: store it and have [LiveSync] send it to Home Assistant (kept as pending until HA confirms it). */
    fun setFromUser(context: Context, channel: String?) {
        set(context, channel)
        if (LiveSync.enabled) {
            prefs(context).edit().putLong(KEY_PENDING_SINCE, System.currentTimeMillis()).apply()
            LiveSync.pushNow()
        }
    }

    /** When the not-yet-confirmed local change was made, or 0 when there is none. */
    fun pendingSince(context: Context): Long = prefs(context).getLong(KEY_PENDING_SINCE, 0L)

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_SINCE).apply()
    }

    fun isValid(channel: String) = VALID.matches(channel.trim())

    fun dvrHost(context: Context): String =
        prefs(context).getString(KEY_DVR_HOST, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DVR_HOST
}
