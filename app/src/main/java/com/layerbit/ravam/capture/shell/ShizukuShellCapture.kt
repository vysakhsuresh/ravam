package com.layerbit.ravam.capture.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.io.InputStream
import kotlin.coroutines.resume

/**
 * The client half of the privileged VoIP capture tier (PLAN.md E2).
 *
 * Shizuku gives us a process at the shell UID; [VoipCaptureUserService] runs there and
 * does the actual capture. This object checks whether that path is usable right now, asks
 * for permission when it is not yet granted, and opens a [ShellPcmSession] that streams
 * the far end back to the app.
 *
 * Nothing here claims a capability it has not got: [state] reports exactly where the user
 * is in the setup, and the tier is only Ready when a real session can be opened.
 */
object ShizukuShellCapture {

    const val PERMISSION_REQUEST_CODE = 4713
    private const val SERVICE_VERSION = 1

    enum class State {
        /** Shizuku app isn't installed. */
        NOT_INSTALLED,
        /** Installed, but its service isn't running (user must start it). */
        NOT_RUNNING,
        /** Running, but Ravam hasn't been granted access yet. */
        NEEDS_PERMISSION,
        /** Running and granted — the privileged tier is usable. */
        READY,
    }

    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    }.getOrDefault(false)

    private fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun granted(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun state(context: Context): State = when {
        !running() && !installed(context) -> State.NOT_INSTALLED
        !running() -> State.NOT_RUNNING
        !granted() -> State.NEEDS_PERMISSION
        else -> State.READY
    }

    /** Ask Shizuku to show its grant dialog. Safe to call only when it is running. */
    fun requestPermission() = runCatching {
        if (running() && !granted()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, VoipCaptureUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("voip")
            .debuggable(false)
            .version(SERVICE_VERSION)

    /**
     * Bind the user service and start capture. Returns a live session, or null with the
     * reason in [lastError]. Suspends until Shizuku connects the service.
     */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun openSession(context: Context, sampleRateHz: Int): ShellPcmSession? {
        if (state(context) != State.READY) {
            lastError = "Shizuku not ready: ${state(context)}"
            return null
        }

        val args = userServiceArgs(context)
        val service: IVoipCaptureService = suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = if (binder != null && binder.pingBinder()) {
                        IVoipCaptureService.Stub.asInterface(binder)
                    } else {
                        null
                    }
                    if (svc != null) cont.resume(svc) else {
                        lastError = "service binder dead"
                        runCatching { cont.resume(null as IVoipCaptureService?) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            boundConnection = conn
            boundArgs = args
            runCatching { Shizuku.bindUserService(args, conn) }
                .onFailure {
                    lastError = "bindUserService failed: ${it.message}"
                    runCatching { cont.resume(null as IVoipCaptureService?) }
                }
            cont.invokeOnCancellation { unbind() }
        } ?: return null

        val pfd = runCatching { service.start(sampleRateHz) }.getOrNull()
        if (pfd == null) {
            lastError = runCatching { service.lastError() }.getOrNull() ?: "capture start failed"
            runCatching { service.stop() }
            unbind()
            return null
        }
        lastError = null
        return ShellPcmSession(service, pfd, sampleRateHz) { unbind() }
    }

    @Volatile private var boundConnection: ServiceConnection? = null
    @Volatile private var boundArgs: Shizuku.UserServiceArgs? = null

    private fun unbind() {
        val conn = boundConnection
        val args = boundArgs
        if (conn != null && args != null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
        boundConnection = null
        boundArgs = null
    }
}

/**
 * A running privileged capture. [input] streams raw 16-bit LE PCM mono at [sampleRateHz];
 * read it on a background thread and write it to the recording. [close] stops the capture
 * and unbinds the service.
 */
class ShellPcmSession(
    private val service: IVoipCaptureService,
    private val pfd: android.os.ParcelFileDescriptor,
    override val sampleRateHz: Int,
    private val onClosed: () -> Unit,
) : PcmStreamSession {
    override val input: InputStream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)

    override fun close() {
        runCatching { service.stop() }
        runCatching { input.close() }
        runCatching { onClosed() }
    }
}
