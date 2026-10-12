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
 *  - HA -> TV: HA publishes `/local/tivo-power.txt` (`ip=channel;ip=channel;`, blank channel = none, no login needed); this polls it and adopts this TV's entry.
 *  - TV -> HA: a change made on the TV is POSTed to an HA webhook; it stays "pending" (and is re-sent) until the published file shows it, and a
 *    pending change is never overwritten by HA's older value.
 * Address and webhook id come from local.properties at build time (BuildConfig); without them this does nothing.
 */
object PowerSync {
    private const val TAG = "PowerSync"
    private const val POLL_MS = 45_000L
    private const val PENDING_GIVE_UP_MS = 10 * 60_000L

    private val started = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    private val wake = LinkedBlockingQueue<Unit>()

    val enabled: Boolean get() = BuildConfig.HA_HOST.isNotBlank() && BuildConfig.HA_POWER_WEBHOOK.isNotBlank()

    fun start(context: Context) {
        if (!enabled) {
            Log.i(TAG, "Home Assistant sync is off: ha.host / ha.power.webhook are not set in local.properties")
            return
        }
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "PowerSync").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "started: polling http://${BuildConfig.HA_HOST}:${BuildConfig.HA_PORT}/local/tivo-power.txt every ${POLL_MS / 1000} s")
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
        val local = ResumePrefs.powerValue(ctx)
        val remote = fetchRemote(ip)
        val pendingSince = ResumePrefs.powerPendingSince(ctx)

        if (pendingSince != 0L) {
            if (remote != null && remote == local) {
                ResumePrefs.clearPowerPending(ctx)
                Log.i(TAG, "Home Assistant confirmed power options '$local'")
                return
            }
            if (System.currentTimeMillis() - pendingSince > PENDING_GIVE_UP_MS) {
                ResumePrefs.clearPowerPending(ctx)
                Log.w(TAG, "gave up sending power options '$local' to Home Assistant after ${PENDING_GIVE_UP_MS / 60_000} min; will follow HA again")
                return
            }
            if (push(ip, local)) {
                Log.i(TAG, "sent power options '$local' to Home Assistant, waiting for it to show up")
                Thread.sleep(2_000)
                if (fetchRemote(ip) == local) {
                    ResumePrefs.clearPowerPending(ctx)
                    Log.i(TAG, "Home Assistant confirmed power options '$local'")
                }
            } else {
                Log.d(TAG, "could not reach Home Assistant to send the power options; will retry")
            }
            return
        }

        if (remote != null && remote != local && ResumePrefs.adoptPowerValue(ctx, remote)) {
            Log.i(TAG, "Home Assistant set the power options to '$remote' (was '$local')")
        }
    }

    /** This TV's entry in HA's published file ("on:off"), or null when HA does not answer or has no entry for this TV. */
    private fun fetchRemote(ip: String): String? {
        val body = ChannelsHttp.request(BuildConfig.HA_HOST, BuildConfig.HA_PORT, "GET", "/local/tivo-power.txt") ?: return null
        return body.split(';', '\n').map { it.trim() }.firstNotNullOfOrNull { entry ->
            val eq = entry.indexOf('=')
            if (eq > 0 && entry.substring(0, eq) == ip) entry.substring(eq + 1).trim() else null
        }
    }

    private fun push(ip: String, value: String): Boolean {
        val (on, off) = value.split(':', limit = 2)
        val json = JSONObject().put("ip", ip).put("power_on", on).put("power_off", off).toString()
        return ChannelsHttp.request(
            BuildConfig.HA_HOST, BuildConfig.HA_PORT, "POST", "/api/webhook/${BuildConfig.HA_POWER_WEBHOOK}",
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
