package com.layerbit.ravam.capture.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.layerbit.ravam.audio.LiveIntegrityMonitor
import com.layerbit.ravam.audio.PcmSink
import com.layerbit.ravam.audio.TwoVoiceAnalyzer
import com.layerbit.ravam.audio.VoiceVerdict
import com.layerbit.ravam.capture.CaptureTier
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One recording, from the moment a call starts to the moment it ends.
 *
 * Three things happen at once and all three matter:
 *
 * 1. **Capture** — an `AudioRecord` on the source the active tier dictates.
 * 2. **Write** — straight to disk through [PcmSink], flushed continuously, so a process
 *    death costs seconds rather than the call.
 * 3. **Watch** — [LiveIntegrityMonitor] measures the audio as it arrives and reports the
 *    moment the far side stops landing, while the user can still do something about it.
 *
 * The third is the point. Everything else in this category records and hopes.
 */
class CallRecorder(
    private val context: Context,
    private val tier: CaptureTier,
    private val outputDir: File,
    private val sampleRateHz: Int = 16_000,
) {

    /** What the recorder is telling the rest of the app, moment to moment. */
    data class Status(
        val elapsedMs: Int,
        val health: LiveIntegrityMonitor.Health,
        val bytesWritten: Long,
    )

    /** What the recording turned out to be, once it ended. */
    data class Outcome(
        val file: File?,
        val tier: CaptureTier,
        val durationMs: Int,
        val verdict: VoiceVerdict?,
        val farSideGapsMs: List<IntRange>,
        val failure: String? = null,
    )

    private val cancelled = AtomicBoolean(false)

    /** Stop the current recording. Safe to call from any thread. */
    fun stop() = cancelled.set(true)

    /**
     * Record until [stop] is called. Blocking — call on a background thread.
     *
     * @param onStatus invoked roughly every buffer, for the notification and UI
     */
    @SuppressLint("MissingPermission")
    fun record(label: String, onStatus: (Status) -> Unit = {}): Outcome {
        val source = sourceFor(tier)
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return Outcome(null, tier, 0, null, emptyList(), "This device rejected the audio format")
        }

        val pcm = File(outputDir, "$label.pcm")
        val sink = PcmSink(pcm, sampleRateHz)
        val monitor = LiveIntegrityMonitor(sampleRateHz)

        // Kept for the end-of-call verdict. Capped so an hour-long call cannot exhaust
        // memory: the analyser needs a representative sample, not every byte.
        val keepSamples = sampleRateHz * MAX_ANALYSIS_SECONDS
        val analysisBuf = ShortArray(keepSamples)
        var analysisLen = 0

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                source, sampleRateHz, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return Outcome(null, tier, 0, null, emptyList(), "Could not open the audio source")
            }

            sink.open()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return Outcome(null, tier, 0, null, emptyList(), "Source opened but would not start")
            }

            val chunk = ShortArray(minBuffer)
            var lastReported = 0

            while (!cancelled.get()) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break

                val slice = if (n == chunk.size) chunk else chunk.copyOf(n)
                sink.write(slice, n)
                monitor.offer(slice)

                if (analysisLen < keepSamples) {
                    val room = minOf(n, keepSamples - analysisLen)
                    slice.copyInto(analysisBuf, analysisLen, 0, room)
                    analysisLen += room
                }

                // Don't spam the caller; once a second is plenty for a notification.
                if (monitor.elapsedMs - lastReported >= 1_000) {
                    lastReported = monitor.elapsedMs
                    onStatus(Status(monitor.elapsedMs, monitor.health, sink.bytesWritten))
                }
            }
        } catch (e: SecurityException) {
            return Outcome(null, tier, monitor.elapsedMs, null, monitor.gaps(), "Denied: ${e.message}")
        } catch (e: IllegalStateException) {
            return Outcome(null, tier, monitor.elapsedMs, null, monitor.gaps(), "Audio system: ${e.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }

        val file = sink.finish()
        val verdict = if (analysisLen > 0) {
            TwoVoiceAnalyzer.analyzeMono(analysisBuf.copyOf(analysisLen), sampleRateHz)
        } else {
            null
        }

        return Outcome(
            file = file,
            tier = tier,
            durationMs = monitor.elapsedMs,
            verdict = verdict,
            farSideGapsMs = monitor.gaps(),
        )
    }

    /**
     * Which audio source a tier uses.
     *
     * `VOICE_RECOGNITION` is not an arbitrary pick: AOSP's audio policy exempts a bound
     * accessibility service recording on that source from the concurrency rule that
     * silences everyone else during a call. The CDD also requires devices to leave noise
     * suppression off for it, which matters because suppression would subtract the far
     * side — the quieter speaker — from the mix.
     */
    private fun sourceFor(tier: CaptureTier): Int = when (tier) {
        // Until the privileged spikes land (PLAN.md E2), these fall back to the source
        // that is known to open. They are NOT yet doing privileged capture.
        CaptureTier.PRIVILEGED_ROOT,
        CaptureTier.PRIVILEGED_SHELL,
        CaptureTier.ACCESSIBILITY -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        CaptureTier.SPEAKERPHONE -> MediaRecorder.AudioSource.MIC
    }

    companion object {
        /** Enough audio for a stable end-of-call verdict without holding a whole call in RAM. */
        const val MAX_ANALYSIS_SECONDS = 180

        /** Put the speaker on, for the tier that depends on it. */
        fun forceSpeakerphone(context: Context, on: Boolean) = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }
}
