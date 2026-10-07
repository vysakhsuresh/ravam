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
            )
        }
        // Not START_STICKY: a service the system restarts after killing us would come back
        // with no call to record and the microphone held for nothing.
        return START_NOT_STICKY
    }

    private fun startRecording(tier: CaptureTier, label: String) {
        if (recorder != null) return

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
        val r = CallRecorder(this, tier, dir)
        recorder = r
        startedAt = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = r.record(label) { status ->
                notify(buildNotification(tier, status.health, status.elapsedMs))
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

        val (title, text) = when (health) {
            LiveIntegrityMonitor.Health.WARMING_UP ->
                "Starting…" to "Listening via ${tier.displayName}"
            LiveIntegrityMonitor.Health.HEALTHY ->
                "Recording · both sides" to "${mmss(elapsedMs)} · ${tier.displayName}"
            LiveIntegrityMonitor.Health.FAR_SIDE_LOST ->
                "Only your voice is recording" to "Turn on speakerphone to capture them too"
            LiveIntegrityMonitor.Health.DEAD_STREAM ->
                "Recording has gone silent" to "This phone has blocked Ravam from hearing the call"
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

        /** App-private, so nothing here is readable by other apps or picked up by media scanners. */
        fun recordingsDir(context: Context): File =
            File(context.filesDir, "recordings").apply { mkdirs() }

        fun start(context: Context, tier: CaptureTier, label: String) {
            val i = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_TIER, tier.name)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
