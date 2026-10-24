package com.layerbit.ravam.capture.shell

import android.content.Context
import android.provider.Settings
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Ravam's own route to the shell UID — no companion app. It pairs with the phone's own
 * Wireless Debugging (one time, on-device, no PC) using [AdbConnectionManager]'s key, and
 * from then on connects automatically over mDNS and runs the privileged capture as shell.
 *
 * This is the self-contained replacement for the Shizuku-app dependency (PLAN.md §2.3).
 * The capture itself is the same proven [VoipCaptureCore], launched through [VoipCaptureCli].
 */
object EmbeddedAdb {

    enum class State { NOT_PAIRED, PAIRED, CONNECTED }

    private const val PREFS = "ravam_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_TCP_PORT = "tcp_port"
    private const val TAG = "RavamAdb"

    /**
     * The fixed TCP port Ravam asks `adbd` to listen on so the privileged path survives the
     * loss of Wi-Fi.
     *
     * Wireless Debugging (Android 11+) runs over Wi-Fi and the system switches it off the
     * moment Wi-Fi drops — which is every call on mobile data, and exactly when the far side
     * was going missing. But `adbd` can also listen the old way, on a fixed TCP port bound to
     * every interface, reachable over the loopback address regardless of which network is up.
     * This is what `adb tcpip` does. Once Ravam has a connection over Wi-Fi it asks `adbd` to
     * open this port, then from then on reconnects to 127.0.0.1 here — no Wi-Fi, no companion
     * app, no pairing again — until the next reboot. Re-arming after a reboot is the same
     * one-time Wireless Debugging step as before.
     */
    private const val FIXED_TCP_PORT = 5555

    private const val LOOPBACK = "127.0.0.1"

    @Volatile
    var lastError: String? = null
        private set

    fun state(context: Context): State {
        val connected = runCatching {
            AdbConnectionManager.getInstance(context).isConnected
        }.getOrDefault(false)
        if (connected) return State.CONNECTED
        val paired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, false)
        return if (paired) State.PAIRED else State.NOT_PAIRED
    }

    /**
     * Whether the privileged path can actually be used *right now* — not just whether a
     * pairing was once stored.
     *
     * A cached pairing survives the user turning Developer options or Wireless debugging
     * back off, but the pairing is worthless then: there is no `adbd` listening to connect
     * to. Treating a stale [State.PAIRED] as ready made Home show "On" when it wasn't, and
     * made the tier selector pick the privileged route on a real call — where the connect
     * then stalled on its timeout and the recording came out degraded. So readiness means:
     * already connected, or paired *and* Wireless debugging is on so a connect can succeed.
     */
    fun ready(context: Context): Boolean = when (state(context)) {
        State.CONNECTED -> true
        // Ready if Wireless Debugging is on (we can connect over Wi-Fi), OR the persistent
        // loopback port is actually listening right now — which is what keeps the privileged
        // path available on mobile data, with Wireless Debugging switched off.
        State.PAIRED -> wirelessDebuggingOn(context) || loopbackReachable(context)
        State.NOT_PAIRED -> false
    }

    /**
     * Is `adbd` listening on the persistent loopback port right now? A quick TCP probe — it
     * proves the port is open without doing the full ADB handshake, which is enough to decide
     * the privileged tier is worth selecting. Cheap and short, so it is safe off the UI.
     */
    fun loopbackReachable(context: Context): Boolean {
        val port = storedPort(context)
        if (port <= 0) return false
        val probe = {
            runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(LOOPBACK, port), 250)
                    true
                }
            }.getOrDefault(false)
        }
        // Callers include the call-state receiver and tier selection, which run on the main
        // thread. A socket connect there throws NetworkOnMainThreadException (even to
        // loopback), which would silently read as "not reachable" and drop a call to mic-only.
        // So on the main thread, probe on a short-lived worker and wait for it.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return probe()
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread { result.set(probe()) }
        t.start()
        runCatching { t.join(400) }
        return result.get()
    }

    private fun storedPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TCP_PORT, 0)

    /**
     * Is Developer options switched on? Wireless debugging lives under it, so this being
     * off guarantees the privileged path is unreachable.
     *
     * Read from [Settings.Global]; on the rare OEM build that refuses the read we assume
     * on, so a genuinely working setup is never hidden — the connect-time fallback still
     * protects the recording.
     */
    fun developerOptionsOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
    }.getOrDefault(true)

    /** Is Wireless debugging switched on? Required before any [connect] can succeed. */
    fun wirelessDebuggingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(true)

    /** The hidden `Settings.Global` key for the Wireless debugging toggle (Android 11+). */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /**
     * One-time pairing. [pairingPort] and [code] come from the phone's Wireless Debugging
     * screen under "Pair device with pairing code". Returns true on success.
     */
    suspend fun pair(
        context: Context,
        pairingPort: Int,
        code: String,
        preferredHost: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val mgr = runCatching { AdbConnectionManager.getInstance(context) }.getOrElse {
                lastError = "Could not start the ADB client: ${it.message}"
                return@withContext false
            }
            // Try the mDNS-resolved host first (most reliable), then the phone's own IPs,
            // then loopback — some devices don't accept pairing on 127.0.0.1.
            val errors = StringBuilder()
            val hosts = (listOfNotNull(preferredHost) + candidateHosts()).distinct()
            android.util.Log.i(TAG, "pair: port=$pairingPort hosts=$hosts")
            for (host in hosts) {
                val result = runCatching { mgr.pair(host, pairingPort, code) }
                result.onSuccess { ok ->
                    android.util.Log.i(TAG, "pair $host -> $ok")
                    if (ok) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_PAIRED, true).apply()
                        lastError = null
                        return@withContext true
                    }
                    errors.append("$host: rejected; ")
                }.onFailure {
                    android.util.Log.w(TAG, "pair $host failed", it)
                    errors.append("$host: ${it.message ?: it.javaClass.simpleName}; ")
                }
            }
            lastError = "Could not pair. Keep the \"Pair device\" dialog open on your phone " +
                "and re-check the port and code. Details — $errors"
            false
        }

    /** The phone's own non-loopback IPv4 addresses first, then loopback as a fallback. */
    private fun candidateHosts(): List<String> {
        val ips = mutableListOf<String>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    val h = addr.hostAddress
                    if (addr is java.net.Inet4Address && h != null && !addr.isLoopbackAddress) ips.add(h)
                }
            }
        }
        return (ips + "127.0.0.1" + "localhost").distinct()
    }

    /**
     * Connect to the phone's own `adbd`. Safe to call repeatedly.
     *
     * Order matters: the persistent loopback port is tried first, because it is the one that
     * works on mobile data. Only if that is not up do we fall back to Wireless Debugging over
     * Wi-Fi (mDNS). After a fresh Wi-Fi connect, and when the loopback port is not already
     * listening, [armPersistentPort] opens it so the *next* call — perhaps on mobile data —
     * can take the loopback path.
     *
     * @param armIfFresh arm the persistent port after a fresh Wi-Fi connect. True from the
     *   pairing/setup flows, which run while idle; false from the capture path, because arming
     *   restarts `adbd` and would drop the stream we are about to open.
     */
    suspend fun connect(context: Context, armIfFresh: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val mgr = runCatching { AdbConnectionManager.getInstance(context) }.getOrElse {
            lastError = "Could not start the ADB client: ${it.message}"
            return@withContext false
        }
        if (mgr.isConnected) return@withContext true

        // 1. The persistent loopback port — independent of Wi-Fi.
        val port = storedPort(context)
        if (port > 0) {
            val ok = runCatching { mgr.connect(LOOPBACK, port) }.getOrElse {
                android.util.Log.i(TAG, "loopback connect $LOOPBACK:$port failed: ${it.message}")
                false
            }
            if (ok && mgr.isConnected) {
                lastError = null
                return@withContext true
            }
        }

        // 2. Wireless Debugging over Wi-Fi (mDNS discovery).
        val onWifi = runCatching { mgr.autoConnect(context, CONNECT_TIMEOUT_MS) }.getOrElse {
            lastError = "Connect failed: ${it.message}"
            false
        }
        if (!mgr.isConnected) return@withContext false

        // 3. Having reached it over Wi-Fi, open the persistent port so mobile data works next
        //    time. Best-effort: if arming cannot re-establish a connection it restores the
        //    Wi-Fi one, so the caller is never left worse off than a plain connect.
        if (armIfFresh && !loopbackReachable(context)) {
            return@withContext armPersistentPort(context, mgr)
        }
        onWifi || mgr.isConnected
    }

    /**
     * Ask `adbd` to open [FIXED_TCP_PORT] and listen there persistently (the `adb tcpip`
     * handshake), then reconnect over loopback. Called only while idle, never mid-capture,
     * because the restart drops the current connection.
     *
     * Returns whether a connection is live afterwards. On failure to reconnect over loopback
     * it falls back to Wi-Fi so the caller still ends connected where possible.
     */
    private suspend fun armPersistentPort(context: Context, mgr: AdbConnectionManager): Boolean =
        withContext(Dispatchers.IO) {
            // Store the port first: the restart drops this connection, and we want the next
            // connect to try loopback even if the reconnect below races the restart.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_TCP_PORT, FIXED_TCP_PORT).apply()

            // Trigger the restart. `tcpip:` is adbd's own service for this and needs no shell
            // permission; the setprop form is a fallback for builds that do not expose it.
            // Either one tears down the stream, so the failure we expect here is success.
            runCatching { mgr.openStream("tcpip:$FIXED_TCP_PORT").close() }
                .onFailure { android.util.Log.i(TAG, "tcpip service ended (expected): ${it.message}") }
            runCatching { mgr.disconnect() }

            // adbd needs a moment to rebind before the port accepts connections.
            var waited = 0
            while (waited < 2_000 && !loopbackReachable(context)) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }

            // Reconnect over the now-open loopback port.
            val viaLoopback = runCatching { mgr.connect(LOOPBACK, FIXED_TCP_PORT) }.getOrElse {
                android.util.Log.w(TAG, "loopback reconnect after arm failed: ${it.message}")
                false
            }
            if (viaLoopback && mgr.isConnected) {
                android.util.Log.i(TAG, "armed persistent adbd on $LOOPBACK:$FIXED_TCP_PORT")
                lastError = null
                return@withContext true
            }

            // Loopback did not come up (device may prompt to allow, or block the restart).
            // Restore the Wi-Fi connection so this call is no worse than a plain connect.
            runCatching { mgr.autoConnect(context, CONNECT_TIMEOUT_MS) }
            mgr.isConnected
        }

    /**
     * Connect if needed, then launch the privileged capture as the shell UID and return a
     * session streaming the far-end PCM. Uses the `exec:` service with stderr dropped so
     * only raw PCM reaches the app.
     */
    suspend fun openCaptureSession(
        context: Context,
        sampleRateHz: Int,
        carrier: Boolean = false,
    ): PcmStreamSession? =
        withContext(Dispatchers.IO) {
            // armIfFresh = false: a capture must never restart adbd (it would drop this very
            // stream). Arming is done by the pairing/setup flows while idle.
            if (!connect(context, armIfFresh = false)) {
                lastError = lastError ?: "No ADB connection — pair in Settings first"
                return@withContext null
            }
            runCatching {
                val mgr = AdbConnectionManager.getInstance(context)
                val apk = context.applicationInfo.sourceDir
                // For a carrier call the far end is the telephony downlink (VOICE_DOWNLINK);
                // for a VoIP call it is the voice-communication playback mix. The CLI picks
                // the source from the trailing "carrier" argument.
                val mode = if (carrier) " carrier" else ""
                val cmd = "exec:CLASSPATH=$apk app_process /system/bin " +
                    "com.layerbit.ravam.capture.shell.VoipCaptureCli $sampleRateHz$mode 2>/dev/null"
                val stream = mgr.openStream(cmd)
                AdbPcmSession(stream, sampleRateHz)
            }.getOrElse {
                lastError = "Could not start privileged capture: ${it.message}"
                null
            }
        }

    private const val CONNECT_TIMEOUT_MS = 10_000L

    private class AdbPcmSession(
        private val stream: AdbStream,
        override val sampleRateHz: Int,
    ) : PcmStreamSession {
        override val input: InputStream = stream.openInputStream()
        override fun close() {
            runCatching { stream.close() }
        }
    }
}
