package com.layerbit.ravam.capture.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import com.layerbit.ravam.audio.LiveIntegrityMonitor
import com.layerbit.ravam.audio.PcmSink
import com.layerbit.ravam.audio.TwoVoiceAnalyzer
import com.layerbit.ravam.audio.VoiceLeveler
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
 *
 * A fourth thing happens on the way to disk, and only on the way to disk: the audio is
 * run through [VoiceLeveler] so the far side — which arrives as faint earpiece leakage —
 * comes out as loud as the person holding the phone. The monitor and the analyser see
 * the raw stream; the verdict is about what the phone captured, not what was done to it.
 */
class CallRecorder(
    private val context: Context,
    private val tier: CaptureTier,
    private val outputDir: File,
    private val processing: Processing = Processing(),
    private val sampleRateHz: Int = 16_000,
) {

    /** What is done to the audio between the microphone and the file. */
    data class Processing(
        /** Lift the quieter voice to the level of the louder one. */
        val boostFarSide: Boolean = true,
        /** Platform noise suppressor on the capture, plus the software gate between words. */
        val noiseCancel: Boolean = false,
    ) {
        val touchesAudio: Boolean get() = boostFarSide || noiseCancel
    }

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
        /**
         * True when this came from Home's "Test a recording", not from a call.
         *
         * A test has nobody on the other end, so there is no far side to find and the
         * two-voice verdict is not a result *about* it. The UI must not show one.
         */
        val isTest: Boolean = false,
        /** True when the far side was boosted on the way to disk, so playback knows not to add more. */
        val levelled: Boolean = false,
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

        // With the boost off and noise cancel on, the leveler still runs — for its high-pass
        // and its gate — but with no gain allowed, so the voices stay at the level they came in.
        val leveler = if (processing.touchesAudio) {
            VoiceLeveler(
                sampleRateHz,
                maxGainDb = if (processing.boostFarSide) 24.0 else 0.0,
                noiseGate = processing.noiseCancel,
            )
        } else {
            null
        }

        // Kept for the end-of-call verdict. Capped so an hour-long call cannot exhaust
        // memory: the analyser needs a representative sample, not every byte.
        val keepSamples = sampleRateHz * MAX_ANALYSIS_SECONDS
        val analysisBuf = ShortArray(keepSamples)
        var analysisLen = 0

        var record: AudioRecord? = null
        var suppressor: NoiseSuppressor? = null
        try {
            record = AudioRecord(
                source, sampleRateHz, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return Outcome(null, tier, 0, null, emptyList(), "Could not open the audio source")
            }

            // The platform suppressor is the better half of noise cancellation where it
            // exists — tuned per chipset, running before the samples reach us. It is only
            // ever attached on request, because it is also the thing most likely to decide
            // a faint far side is noise. Absent or refusing, the software gate still runs.
            if (processing.noiseCancel && NoiseSuppressor.isAvailable()) {
                suppressor = runCatching { NoiseSuppressor.create(record.audioSessionId) }
                    .getOrNull()?.also { runCatching { it.enabled = true } }
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

                // Raw to the monitor and the analyser; levelled to the file. Order matters:
                // the verdict must be about the capture, not about the processing.
                monitor.offer(slice)
                if (analysisLen < keepSamples) {
                    val room = minOf(n, keepSamples - analysisLen)
                    slice.copyInto(analysisBuf, analysisLen, 0, room)
                    analysisLen += room
                }

                if (leveler != null) {
                    val out = leveler.process(slice, n)
                    if (out.isNotEmpty()) sink.write(out, out.size)
                } else {
                    sink.write(slice, n)
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
            runCatching { suppressor?.release() }
            runCatching { record?.release() }
        }

        // The leveler holds back up to one block; let it go before the file is closed.
        leveler?.flush()?.let { tail -> if (tail.isNotEmpty()) sink.write(tail, tail.size) }
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
            levelled = leveler != null && processing.boostFarSide,
        )
    }

    /**
     * Which audio source a tier uses.
     *
     * `VOICE_RECOGNITION` is not an arbitrary pick: AOSP's audio policy exempts a bound
     * accessibility service recording on that source from the concurrency rule that
     * silences everyone else during a call. The CDD also requires devices to leave noise
     * suppression off for it by default, which matters because suppression can subtract
     * the far side — the quieter speaker — from the mix. Ravam attaches a suppressor only
     * when the user has turned noise cancellation on, and says so in Settings.
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
