package com.fiospace.tivochannelshome

import android.content.Context
import org.json.JSONObject

/** What Channels was playing when the TV was last powered off: live TV on a channel, or a recording. [at] = when it was seen playing; [posSec] = playback position of a recording then. */
data class ResumeSnapshot(val kind: String, val id: String, val at: Long, val posSec: Double = 0.0, val playing: Boolean = true) {
    companion object {
        const val LIVE = "live"
        const val RECORDING = "rec"
        private val FILE_ID = Regex("""/dvr/files/(\d+)/""")

        /** From the Channels client API's /api/status body: null unless it is playing or paused. Live TV has a `channel`; a recording has none but its file id is in `thumb_url`. */
        fun fromStatus(body: String?): ResumeSnapshot? {
            if (body == null) return null
            return try {
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "playing" && status != "paused") return null
                val now = System.currentTimeMillis()
                val channel = json.optJSONObject("channel")?.optString("number")?.ifEmpty { null }
                if (channel != null) return ResumeSnapshot(LIVE, channel, now)
                val thumb = json.optJSONObject("now_playing")?.optString("thumb_url").orEmpty()
                FILE_ID.find(thumb)?.let { ResumeSnapshot(RECORDING, it.groupValues[1], now, json.optDouble("playback_time", 0.0), status == "playing") }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** "Restore TV to last state" option (Configuration dialog) and the state saved at power off. Stored in the same prefs file as the Live channel. */
object ResumePrefs {
    private const val PREFS = "tivo_prefs"
    private const val KEY_POWER_ON = "power_on_mode"
    private const val KEY_POWER_OFF = "power_off_mode"
    private const val KEY_POWER_PENDING_SINCE = "power_pending_since"
    private const val KEY_KIND = "resume_kind"
    private const val KEY_ID = "resume_id"
    private const val KEY_AT = "resume_at"
    private const val KEY_POS_MS = "resume_pos_ms"
    private const val KEY_OFF_AT = "power_off_at"
    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Power On (all devices): nothing, just Channels, or Channels tuned to the last state (else the Live channel). */
    const val ON_NONE = "none"
    const val ON_CHANNELS = "channels"
    const val ON_TUNE = "tune"
    fun powerOnMode(context: Context): String = prefs(context).getString(KEY_POWER_ON, ON_TUNE) ?: ON_TUNE
    fun setPowerOnMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_POWER_ON, mode).apply()
        if (mode != ON_TUNE) clear(context)
    }

    /** True when the state at power off is saved for the next power on (Power On = "Channels and tune to"). */
    fun isEnabled(context: Context): Boolean = powerOnMode(context) == ON_TUNE

    /**
     * Power Off (TiVo boxes only; they stay on when their TV goes off and the remote's power key is what the app sees): pause what Channels plays,
     * leave it playing, or stop and exit Channels. The other TVs sleep by themselves, so for them it is always "continue".
     */
    const val OFF_PAUSE = "pause"
    const val OFF_CONTINUE = "continue"
    const val OFF_EXIT = "exit"
    fun isTiVoDevice(): Boolean = android.os.Build.MODEL.contains("tivo", ignoreCase = true) || android.os.Build.MANUFACTURER.contains("SEI Robotics", ignoreCase = true)
    fun powerOffMode(context: Context): String = if (!isTiVoDevice()) OFF_CONTINUE else prefs(context).getString(KEY_POWER_OFF, OFF_EXIT) ?: OFF_EXIT
    fun setPowerOffMode(context: Context, mode: String) = prefs(context).edit().putString(KEY_POWER_OFF, mode).apply()

    /** The stored Power off choice even on a TV that ignores it (non-TiVo), so Home Assistant keeps showing what was chosen. */
    fun storedPowerOffMode(context: Context): String = prefs(context).getString(KEY_POWER_OFF, OFF_EXIT) ?: OFF_EXIT

    /** "on:off" as published by Home Assistant (see [PowerSync]). */
    fun powerValue(context: Context): String = "${powerOnMode(context)}:${storedPowerOffMode(context)}"

    /** Adopts a value from Home Assistant ("on:off"); false when it is not a valid pair. */
    fun adoptPowerValue(context: Context, value: String): Boolean {
        val (on, off) = value.split(':', limit = 2).let { if (it.size == 2) it[0] to it[1] else return false }
        if (on !in listOf(ON_NONE, ON_CHANNELS, ON_TUNE) || off !in listOf(OFF_PAUSE, OFF_CONTINUE, OFF_EXIT)) return false
        setPowerOnMode(context, on)
        setPowerOffMode(context, off)
        return true
    }

    /** A choice made in the Configuration dialog: stored, then sent to Home Assistant (kept as pending until HA's file shows it). */
    fun setPowerFromUser(context: Context, on: String? = null, off: String? = null) {
        if (on != null) setPowerOnMode(context, on)
        if (off != null) setPowerOffMode(context, off)
        prefs(context).edit().putLong(KEY_POWER_PENDING_SINCE, System.currentTimeMillis()).apply()
        PowerSync.pushNow()
    }
    fun powerPendingSince(context: Context): Long = prefs(context).getLong(KEY_POWER_PENDING_SINCE, 0L)
    fun clearPowerPending(context: Context) = prefs(context).edit().remove(KEY_POWER_PENDING_SINCE).apply()

    fun save(context: Context, s: ResumeSnapshot) {
        prefs(context).edit().putString(KEY_KIND, s.kind).putString(KEY_ID, s.id).putLong(KEY_AT, s.at).putLong(KEY_POS_MS, (s.posSec * 1000).toLong()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_KIND).remove(KEY_ID).remove(KEY_AT).remove(KEY_POS_MS).apply()
    }

    /** The saved state, removed so it is used once; null when none or older than 12 h. */
    fun take(context: Context): ResumeSnapshot? {
        val p = prefs(context)
        val kind = p.getString(KEY_KIND, null)
        val id = p.getString(KEY_ID, null)
        val at = p.getLong(KEY_AT, 0L)
        val posSec = p.getLong(KEY_POS_MS, 0L) / 1000.0
        clear(context)
        if (kind == null || id == null || System.currentTimeMillis() - at > MAX_AGE_MS) return null
        return ResumeSnapshot(kind, id, at, posSec)
    }

    /**
     * TiVo boxes stay awake when their TV goes off, and the remote's power key is a toggle, so the service tracks "off" itself:
     * the first press is a power off, the next one a power on. A stale flag (over 12 h) counts as on.
     */
    fun isOff(context: Context): Boolean {
        val at = prefs(context).getLong(KEY_OFF_AT, 0L)
        return at != 0L && System.currentTimeMillis() - at < MAX_AGE_MS
    }
    fun setOff(context: Context, off: Boolean) {
        prefs(context).edit().apply { if (off) putLong(KEY_OFF_AT, System.currentTimeMillis()) else remove(KEY_OFF_AT) }.apply()
    }
}
