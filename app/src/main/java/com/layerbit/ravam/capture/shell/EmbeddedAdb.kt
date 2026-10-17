package com.layerbit.ravam.capture.shell

import android.content.Context
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
     * One-time pairing. [pairingPort] and [code] come from the phone's Wireless Debugging
     * screen under "Pair device with pairing code". Returns true on success.
     */
    suspend fun pair(context: Context, pairingPort: Int, code: String): Boolean =
        withContext(Dispatchers.IO) {
            val mgr = runCatching { AdbConnectionManager.getInstance(context) }.getOrElse {
                lastError = "Could not start the ADB client: ${it.message}"
                return@withContext false
            }
            // The pairing service may bind only to the Wi-Fi interface, not loopback, so
            // try the phone's own IPs first and 127.0.0.1 last.
            val errors = StringBuilder()
            for (host in candidateHosts()) {
                val result = runCatching { mgr.pair(host, pairingPort, code) }
                result.onSuccess { ok ->
                    if (ok) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_PAIRED, true).apply()
                        lastError = null
                        return@withContext true
                    }
                    errors.append("$host: rejected; ")
                }.onFailure {
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

    /** Discover the debugging port over mDNS and connect. Safe to call repeatedly. */
    suspend fun connect(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val mgr = AdbConnectionManager.getInstance(context)
            if (mgr.isConnected) return@runCatching true
            mgr.autoConnect(context, CONNECT_TIMEOUT_MS)
            mgr.isConnected
        }.getOrElse {
            lastError = "Connect failed: ${it.message}"
            false
        }
    }

    /**
     * Connect if needed, then launch the privileged capture as the shell UID and return a
     * session streaming the far-end PCM. Uses the `exec:` service with stderr dropped so
     * only raw PCM reaches the app.
     */
    suspend fun openCaptureSession(context: Context, sampleRateHz: Int): PcmStreamSession? =
        withContext(Dispatchers.IO) {
            if (!connect(context)) {
                lastError = lastError ?: "No ADB connection — pair in Settings first"
                return@withContext null
            }
            runCatching {
                val mgr = AdbConnectionManager.getInstance(context)
                val apk = context.applicationInfo.sourceDir
                val cmd = "exec:CLASSPATH=$apk app_process /system/bin " +
                    "com.layerbit.ravam.capture.shell.VoipCaptureCli $sampleRateHz 2>/dev/null"
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
