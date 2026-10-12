package com.fiospace.tivochannelshome

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps this TV's LIVE-button channel in step with Home Assistant. HA is the source of truth; the TV keeps a local copy ([LiveChannelPrefs]) so the
 * button works when HA is down.
 *  - HA -> TV: HA publishes `/local/tivo-live.txt` (`ip=channel;ip=channel;`, blank channel = none, no login needed); this polls it and adopts this TV's entry.
 *  - TV -> HA: a change made on the TV is POSTed to an HA webhook; it stays "pending" (and is re-sent) until the published file shows it, and a
 *    pending change is never overwritten by HA's older value.
 * Address and webhook id come from local.properties at build time (BuildConfig); without them this does nothing.
 */
object LiveSync {
    private const val TAG = "LiveSync"
    private const val POLL_MS = 45_000L
    private const val PENDING_GIVE_UP_MS = 10 * 60_000L

    private val started = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val wake = LinkedBlockingQueue<Unit>()

    val enabled: Boolean get() = BuildConfig.HA_HOST.isNotBlank() && BuildConfig.HA_LIVE_WEBHOOK.isNotBlank()

    fun start(context: Context) {
        if (!enabled) {
            Log.i(TAG, "Home Assistant sync is off: ha.host / ha.live.webhook are not set in local.properties")
            return
        }
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "LiveSync").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "started: polling http://${BuildConfig.HA_HOST}:${BuildConfig.HA_PORT}/local/tivo-live.txt every ${POLL_MS / 1000} s")
    }

    fun stop() {
        started.set(false)
        thread?.interrupt()
        thread = null
    }

    /** Wakes the sync loop now (a local change is waiting to be sent). */
    fun pushNow() {
        wake.offer(Unit)
    }

    private fun loop(ctx: Context) {
        while (started.get()) {
            wake.clear()
            try {
                syncOnce(ctx)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
            }
            try {
                wake.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun syncOnce(ctx: Context) {
        val ip = ownIp() ?: run {
            Log.d(TAG, "no LAN address yet")
            return
        }
        val local = LiveChannelPrefs.get(ctx) ?: ""
        val remote = fetchRemote(ip)
        val pendingSince = LiveChannelPrefs.pendingSince(ctx)

        if (pendingSince != 0L) {
            if (remote != null && remote == local) {
                LiveChannelPrefs.clearPending(ctx)
                Log.i(TAG, "Home Assistant confirmed Live channel '${local.ifEmpty { "none" }}'")
                return
            }
            if (System.currentTimeMillis() - pendingSince > PENDING_GIVE_UP_MS) {
                LiveChannelPrefs.clearPending(ctx)
                Log.w(TAG, "gave up sending Live channel '$local' to Home Assistant after ${PENDING_GIVE_UP_MS / 60_000} min; will follow HA again")
                return
            }
            if (push(ip, local)) {
                Log.i(TAG, "sent Live channel '${local.ifEmpty { "none" }}' to Home Assistant, waiting for it to show up")
                Thread.sleep(2_000)
                if (fetchRemote(ip) == local) {
                    LiveChannelPrefs.clearPending(ctx)
                    Log.i(TAG, "Home Assistant confirmed Live channel '${local.ifEmpty { "none" }}'")
                }
            } else {
                Log.d(TAG, "could not reach Home Assistant to send the Live channel; will retry")
            }
            return
        }

        if (remote != null && remote != local) {
            LiveChannelPrefs.set(ctx, remote)
            Log.i(TAG, "Home Assistant set the Live channel to '${remote.ifEmpty { "none" }}' (was '${local.ifEmpty { "none" }}')")
        }
    }

    /** This TV's entry in HA's published file: the channel ("" = none), or null when HA does not answer or has no entry for this TV. */
    private fun fetchRemote(ip: String): String? {
        val body = ChannelsHttp.request(BuildConfig.HA_HOST, BuildConfig.HA_PORT, "GET", "/local/tivo-live.txt") ?: return null
        return body.split(';', '\n').map { it.trim() }.firstNotNullOfOrNull { entry ->
            val eq = entry.indexOf('=')
            if (eq > 0 && entry.substring(0, eq) == ip) entry.substring(eq + 1).trim() else null
        }
    }

    private fun push(ip: String, channel: String): Boolean {
        val json = JSONObject().put("ip", ip).put("channel", channel).toString()
        return ChannelsHttp.request(
            BuildConfig.HA_HOST, BuildConfig.HA_PORT, "POST", "/api/webhook/${BuildConfig.HA_LIVE_WEBHOOK}",
            body = json, contentType = "application/json",
        ) != null
    }

    /** This TV's LAN IPv4 address (Wi-Fi or Ethernet); HA identifies TVs by it. */
    private fun ownIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { it != null && it.startsWith("192.168.") }
            ?: NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) {
        null
    }
}
