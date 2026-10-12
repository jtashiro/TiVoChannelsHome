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
    private const val KEY_ENABLED = "restore_last_state"
    private const val KEY_KIND = "resume_kind"
    private const val KEY_ID = "resume_id"
    private const val KEY_AT = "resume_at"
    private const val KEY_POS_MS = "resume_pos_ms"
    private const val KEY_OFF_AT = "power_off_at"
    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) clear(context)
    }

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
