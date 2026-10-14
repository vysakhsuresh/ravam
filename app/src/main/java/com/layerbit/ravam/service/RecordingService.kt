package com.layerbit.ravam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.layerbit.ravam.MainActivity
import com.layerbit.ravam.R
import com.layerbit.ravam.audio.LiveIntegrityMonitor
import com.layerbit.ravam.capture.CaptureTier
import com.layerbit.ravam.capture.engine.CallRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the microphone for the length of a call.
 *
 * A foreground service is not optional here — it is the only way Android lets an app keep
 * recording once the user is looking at their dialer rather than at Ravam.
 *
 * **The known hazard, stated plainly:** `RECORD_AUDIO` is a while-in-use permission, so
 * from Android 14 a `microphone` foreground service cannot be *started* while the app is
 * in the background — which is every incoming call. There is an untested escape: the
 * platform binds an AccessibilityService with `BIND_INCLUDE_CAPABILITIES`, documented as
 * passing while-in-use access including the microphone to the bound app. Whether that is
 * enough is spike E3 in PLAN.md, and it is unresolved. Until it is, incoming-call capture
 * on this tier should be assumed broken rather than assumed working.
 */
class RecordingService : LifecycleService() {

    private var recorder: CallRecorder? = null
    private var startedAt: Long = 0
    private var activeChannel: String = "Phone"

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> { stopRecording(); return START_NOT_STICKY }
            else -> startRecording(
                tier = intent?.getStringExtra(EXTRA_TIER)
                    ?.let { runCatching { CaptureTier.valueOf(it) }.getOrNull() }
                    ?: CaptureTier.ACCESSIBILITY,
                label = intent?.getStringExtra(EXTRA_LABEL) ?: "call",
                channel = intent?.getStringExtra(EXTRA_CHANNEL) ?: "Phone",
                isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false,
            )
        }
        // Not START_STICKY: a service the system restarts after killing us would come back
        // with no call to record and the microphone held for nothing.
        return START_NOT_STICKY
    }

    private fun startRecording(tier: CaptureTier, label: String, channel: String, isTest: Boolean) {
        if (recorder != null) return
        activeChannel = channel

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(tier, LiveIntegrityMonitor.Health.WARMING_UP, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        val dir = recordingsDir(this)
        val settings = com.layerbit.ravam.data.SettingsStore(this)
        val r = CallRecorder(
            this, tier, dir,
            processing = CallRecorder.Processing(
                boostFarSide = settings.boostFarSide,
                noiseCancel = settings.noiseCancel,
            ),
        )
        recorder = r
        startedAt = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = r.record(label) { status ->
                notify(buildNotification(tier, status.health, status.elapsedMs, status.route))
            }.copy(isTest = isTest)

            // Write the human-readable sidecar FIRST, then announce completion — so a library
            // that refreshes the instant RecordingLog emits already sees the verdict and
            // channel, not a bare file. No legal flags are stored, only what a user wants to
            // see: a name, the time, how long, and whether both sides were heard (PLAN.md §6).
            outcome.file?.let { file ->
                val name = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(file.lastModified()))
                com.layerbit.ravam.data.RecordingStore.writeSidecar(
                    context = this@RecordingService,
                    wav = file,
                    name = name,
                    durationMs = outcome.durationMs,
                    voices = outcome.verdict?.voices ?: com.layerbit.ravam.audio.Voices.INCONCLUSIVE,
                    channel = activeChannel,
                    isTest = outcome.isTest,
                    levelled = outcome.levelled,
                    viaBluetooth = outcome.viaBluetooth,
                )
            }
            RecordingLog.record(outcome)
            recorder = null
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        recorder?.stop()
    }

    // ------------------------------------------------------------------ notification

    /**
     * The notification carries the live verdict, not just "recording".
     *
     * This is the one surface a user sees during a call, so it is where a failure has to
     * appear. Telling someone their far side has gone missing *now* lets them switch to
     * speakerphone and keep the conversation; telling them afterwards is just bad news.
     */
    private fun buildNotification(
        tier: CaptureTier,
        health: LiveIntegrityMonitor.Health,
        elapsedMs: Int,
        route: CallRecorder.Route = CallRecorder.Route.UNKNOWN,
    ): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val (title, text) = when {
            // Said before the stream is even judged dead: on a Bluetooth headset the phone's
            // microphone hears neither voice, and the only fix is the user's to make. If
            // this handset does feed the headset's audio through, HEALTHY wins below.
            route == CallRecorder.Route.BLUETOOTH && health != LiveIntegrityMonitor.Health.HEALTHY ->
                "Call is on your Bluetooth headset" to
                    "Ravam can't hear it there. Switch the call to the phone or speaker"
            else -> when (health) {
            LiveIntegrityMonitor.Health.WARMING_UP ->
                "Starting…" to "Listening via ${tier.displayName}"
            LiveIntegrityMonitor.Health.HEALTHY ->
                "Recording · both sides" to "${mmss(elapsedMs)} · ${tier.displayName}"
            LiveIntegrityMonitor.Health.FAR_SIDE_LOST ->
                "Only your voice is recording" to "Turn on speakerphone to capture them too"
            LiveIntegrityMonitor.Health.DEAD_STREAM ->
                "Recording has gone silent" to "This phone has blocked Ravam from hearing the call"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(health == LiveIntegrityMonitor.Health.HEALTHY)
            .setPriority(
                if (health == LiveIntegrityMonitor.Health.HEALTHY) {
                    NotificationCompat.PRIORITY_LOW
                } else {
                    // A problem must be able to interrupt; a healthy recording must not.
                    NotificationCompat.PRIORITY_HIGH
                },
            )
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notify(n: Notification) = runCatching {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while a call is being recorded" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun mmss(ms: Int) = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)

    companion object {
        const val CHANNEL_ID = "ravam.recording"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.layerbit.ravam.STOP"
        const val EXTRA_TIER = "tier"
        const val EXTRA_LABEL = "label"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_IS_TEST = "isTest"

        /** App-private, so nothing here is readable by other apps or picked up by media scanners. */
        fun recordingsDir(context: Context): File =
            File(context.filesDir, "recordings").apply { mkdirs() }

        /**
         * Returns false when the system refused to let the service start.
         *
         * On Android 12+ a foreground service may not be started from the background, and a
         * phone-state broadcast for an *incoming* call arrives with the app in the background.
         * The refusal is an exception thrown straight into the receiver, which took the whole
         * process down (seen on-device: `ForegroundServiceStartNotAllowedException ...
         * mAllowStartForeground false`). Whether that call can be recorded at all is an open
         * question in PLAN.md; crashing is not an acceptable answer to it either way.
         */
        fun start(
            context: Context,
            tier: CaptureTier,
            label: String,
            channel: String = "Phone",
            isTest: Boolean = false,
        ): Boolean {
            val i = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_TIER, tier.name)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_CHANNEL, channel)
                .putExtra(EXTRA_IS_TEST, isTest)
            return try {
                context.startForegroundService(i)
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException is API 31+; catching the base type
                // keeps this compiling for minSdk 29 and also covers SecurityException.
                android.util.Log.w("Ravam", "Could not start recording for \"$label\": ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
