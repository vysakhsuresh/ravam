package com.layerbit.ravam.capture.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.layerbit.ravam.audio.LiveIntegrityMonitor
import com.layerbit.ravam.audio.PcmSink
import com.layerbit.ravam.audio.TwoVoiceAnalyzer
import com.layerbit.ravam.audio.VoiceLeveler
import com.layerbit.ravam.audio.VoiceVerdict
import com.layerbit.ravam.capture.CaptureTier
import com.layerbit.ravam.capture.shell.PcmStreamSession
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
    /**
     * When set and [tier] is [CaptureTier.PRIVILEGED_SHELL], the far end arrives from the
     * Shizuku-backed privileged capture (PLAN.md E2) instead of an AudioRecord. The far
     * side comes in genuinely, not as earpiece leakage, so no speakerphone is needed.
     */
    private val shellSession: PcmStreamSession? = null,
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

    /**
     * Where the call's audio is.
     *
     * This matters more than any tier. On a Bluetooth headset both voices travel the
     * headset's own link to the modem, and the phone's microphone hears neither: the
     * capture opens, the file grows, and every sample is zero — until the headset link
     * drops at hang-up and the handset mic catches the last word. Seen exactly so on a
     * moto g84 (Android 15). The user has to be told *during* the call, because the fix
     * is theirs to make: move the call to the phone or the speaker.
     */
    enum class Route { PHONE, BLUETOOTH, WIRED, UNKNOWN }

    /** What the recorder is telling the rest of the app, moment to moment. */
    data class Status(
        val elapsedMs: Int,
        val health: LiveIntegrityMonitor.Health,
        val bytesWritten: Long,
        val route: Route = Route.UNKNOWN,
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
        /** True when the call's audio was on a Bluetooth headset at any point while recording. */
        val viaBluetooth: Boolean = false,
        /**
         * True when this was written as a two-channel file — the local microphone on the
         * left, the remote party on the right. Only the privileged tier can do this, because
         * only it has the two sides as genuinely separate streams. It is the one thing a
         * leakage-based recorder can never produce, and it makes the two-voice verdict
         * arithmetic rather than inference.
         */
        val stereo: Boolean = false,
    )

    private val cancelled = AtomicBoolean(false)

    /**
     * The far-end stream, which may arrive *after* capture has already started.
     *
     * Instant start: the near microphone begins the moment the call does, so no opening
     * word is lost while the privileged shell connects (that connect can take seconds).
     * When it does connect, [attachFarSession] hands the stream in and the far side is
     * mixed/separated from that point on — a mid-call promotion, never a restart.
     */
    @Volatile
    private var liveSession: PcmStreamSession? = shellSession

    /** Hand in the far-end stream once the shell session is live. Safe to call any time. */
    fun attachFarSession(session: PcmStreamSession) {
        if (cancelled.get()) {
            runCatching { session.close() }
            return
        }
        liveSession = session
    }

    /** Stop the current recording. Safe to call from any thread. */
    fun stop() {
        cancelled.set(true)
        // A blocking read on the Shizuku PCM stream only returns when the far side next
        // sends bytes; closing the stream unblocks it so stop is immediate.
        runCatching { liveSession?.close() }
    }

    /**
     * Record until [stop] is called. Blocking — call on a background thread.
     *
     * @param onStatus invoked roughly every buffer, for the notification and UI
     */
    @SuppressLint("MissingPermission")
    fun record(label: String, onStatus: (Status) -> Unit = {}): Outcome {
        if (tier == CaptureTier.PRIVILEGED_SHELL) {
            return recordViaShell(onStatus)
        }
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
                maxGainDb = if (processing.boostFarSide) farSideBoostDb() else 0.0,
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

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var route = Route.UNKNOWN
        var sawBluetooth = false

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

            // Checked once a second with the status, not per buffer: it is a Binder call.
            route = routeOf(record, audioManager)
            if (route == Route.BLUETOOTH) sawBluetooth = true

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
                    route = routeOf(record, audioManager)
                    if (route == Route.BLUETOOTH) sawBluetooth = true
                    onStatus(Status(monitor.elapsedMs, monitor.health, sink.bytesWritten, route))
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
            viaBluetooth = sawBluetooth,
        )
    }

    /**
     * Record from the privileged capture stream (PLAN.md E2), as a **two-channel** file.
     *
     * This is the capability no leakage-based recorder can match. The remote party arrives
     * as real PCM on the shell session, diverted from the voice-communication mix; the local
     * party is the microphone on VOICE_RECOGNITION, which the accessibility carve-out keeps
     * unsilenced during a call. The two are genuinely separate streams, so Ravam writes them
     * to genuinely separate channels: **near on the left, far on the right.** The two-voice
     * verdict is then arithmetic — measure each channel — not the level-clustering inference
     * the mono tiers are forced into.
     *
     * Instant start: capture begins with the microphone the moment the call does. The far
     * stream is handed in by [attachFarSession] whenever the shell connect finishes, which
     * can be seconds later; until then the right channel is silent and the left already holds
     * the opening words. Nothing is lost waiting for a connection — a mid-call promotion, not
     * a restart.
     *
     * Each side runs its own leveler, so boosting the faint far side never pumps the near
     * side, and the echo canceller on the mic never bleeds into the clean far channel. Fed the
     * same sample count every call, the two levelers' streaming output stays sample-aligned,
     * so the channels never drift.
     */
    @SuppressLint("MissingPermission")
    private fun recordViaShell(onStatus: (Status) -> Unit): Outcome {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val mic: AudioRecord? = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096) * 4,
            )
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }

        // Without a microphone there is no near channel to pace the file and nothing to
        // interleave the far side against; fall back to the single-channel far-end path.
        if (mic == null) return recordFarOnly(onStatus)

        // VOICE_RECOGNITION gives a raw mic, so the far end leaking out of the earpiece lands
        // in it as a delayed second copy. The platform echo canceller removes that leak and
        // auto-gain lifts the near voice, keeping the left channel clean of the far party.
        val micSession = mic.audioSessionId
        val aec = if (AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(micSession)?.apply { enabled = true } }.getOrNull() else null
        val agc = if (AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(micSession)?.apply { enabled = true } }.getOrNull() else null
        val micNs = if (processing.noiseCancel && NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(micSession)?.apply { enabled = true } }.getOrNull() else null

        // The ".2ch" marker lets orphan recovery wrap a crash-truncated file as stereo.
        val pcm = File(outputDir, "shell-${System.currentTimeMillis()}.2ch.pcm")
        val sink = PcmSink(pcm, sampleRateHz, channels = 2)
        val monitor = LiveIntegrityMonitor(sampleRateHz)

        fun leveler(maxGainDb: Double) = if (processing.touchesAudio) {
            VoiceLeveler(
                sampleRateHz,
                maxGainDb = if (processing.boostFarSide) maxGainDb else 0.0,
                noiseGate = processing.noiseCancel,
            )
        } else null
        val nearLeveler = leveler(NEAR_BOOST_DB)
        val farLeveler = leveler(SHELL_BOOST_DB)

        // The verdict reads each channel raw — before levelling — so it describes what was
        // captured, not what was done to it.
        val keepSamples = sampleRateHz * MAX_ANALYSIS_SECONDS
        val analysisNear = ShortArray(keepSamples); var nearLen = 0
        val analysisFar = ShortArray(keepSamples); var farLen = 0

        try {
            sink.open()
            mic.startRecording()
            var lastReported = 0

            val chunk = 2048
            val micS = ShortArray(chunk)
            val farBytes = ByteArray(chunk * 2)
            val farRaw = ShortArray(chunk)
            val mono = ShortArray(chunk)

            while (!cancelled.get()) {
                val n = mic.read(micS, 0, chunk)
                if (n <= 0) break

                // Drain waiting far-end audio without blocking the mic clock. The session is
                // null until it is attached mid-call; the right channel is silent until then.
                var farSamples = 0
                val input = liveSession?.input
                if (input != null) {
                    val avail = runCatching { input.available() }.getOrDefault(0)
                    if (avail >= 2) {
                        var want = minOf(avail, n * 2)
                        if (want % 2 == 1) want -= 1
                        if (want > 0) {
                            val got = runCatching { input.read(farBytes, 0, want) }.getOrDefault(0)
                            if (got > 0) farSamples = got / 2
                        }
                    }
                }
                for (i in 0 until n) {
                    farRaw[i] = if (i < farSamples) {
                        val lo = farBytes[i * 2].toInt() and 0xFF
                        val hi = farBytes[i * 2 + 1].toInt()
                        ((hi shl 8) or lo).toShort()
                    } else 0
                }

                // Live health sees a mono mix of the two sides; the verdict sees each side.
                for (i in 0 until n) {
                    var s = micS[i].toInt() + farRaw[i].toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    mono[i] = s.toShort()
                }
                monitor.offer(if (n == chunk) mono else mono.copyOf(n))
                if (nearLen < keepSamples) {
                    val room = minOf(n, keepSamples - nearLen)
                    micS.copyInto(analysisNear, nearLen, 0, room); nearLen += room
                }
                if (farLen < keepSamples) {
                    val room = minOf(n, keepSamples - farLen)
                    farRaw.copyInto(analysisFar, farLen, 0, room); farLen += room
                }

                // To disk: levelled per channel, interleaved as L = near, R = far.
                if (nearLeveler != null && farLeveler != null) {
                    val l = nearLeveler.process(micS, n)
                    val r = farLeveler.process(farRaw, n)
                    writeStereo(sink, l, r, minOf(l.size, r.size))
                } else {
                    writeStereo(sink, micS, farRaw, n)
                }

                if (monitor.elapsedMs - lastReported >= 1_000) {
                    lastReported = monitor.elapsedMs
                    onStatus(Status(monitor.elapsedMs, monitor.health, sink.bytesWritten, Route.PHONE))
                }
            }
        } finally {
            runCatching { aec?.release() }
            runCatching { agc?.release() }
            runCatching { micNs?.release() }
            runCatching { mic.stop() }
            runCatching { mic.release() }
            runCatching { liveSession?.close() }
        }

        // Drain the levelers' held-back final block, keeping the channels aligned.
        if (nearLeveler != null && farLeveler != null) {
            val l = nearLeveler.flush(); val r = farLeveler.flush()
            writeStereo(sink, l, r, minOf(l.size, r.size))
        }
        val file = sink.finish()
        val verdict = if (nearLen > 0 || farLen > 0) {
            TwoVoiceAnalyzer.analyzeStereo(
                analysisNear.copyOf(nearLen), analysisFar.copyOf(farLen), sampleRateHz,
            )
        } else {
            null
        }

        return Outcome(
            file = file,
            tier = tier,
            durationMs = monitor.elapsedMs,
            verdict = verdict,
            farSideGapsMs = monitor.gaps(),
            levelled = nearLeveler != null && processing.boostFarSide,
            viaBluetooth = false,
            stereo = true,
        )
    }

    /** Interleave two equal-length channels (L = near, R = far) and write [n] frames. */
    private fun writeStereo(sink: PcmSink, left: ShortArray, right: ShortArray, n: Int) {
        if (n <= 0) return
        val out = ShortArray(n * 2)
        for (i in 0 until n) { out[i * 2] = left[i]; out[i * 2 + 1] = right[i] }
        sink.write(out, n * 2)
    }

    /**
     * Degenerate fallback: the far end only, mono, when no microphone could be opened.
     * Should not happen with RECORD_AUDIO granted, but one real side beats none.
     */
    @SuppressLint("MissingPermission")
    private fun recordFarOnly(onStatus: (Status) -> Unit): Outcome {
        val pcm = File(outputDir, "shell-${System.currentTimeMillis()}.pcm")
        val sink = PcmSink(pcm, sampleRateHz)
        val monitor = LiveIntegrityMonitor(sampleRateHz)
        val leveler = if (processing.touchesAudio) {
            VoiceLeveler(
                sampleRateHz,
                maxGainDb = if (processing.boostFarSide) SHELL_BOOST_DB else 0.0,
                noiseGate = processing.noiseCancel,
            )
        } else null
        val keepSamples = sampleRateHz * MAX_ANALYSIS_SECONDS
        val analysisBuf = ShortArray(keepSamples); var analysisLen = 0
        try {
            sink.open()
            var lastReported = 0
            val chunk = 2048
            val byteBuf = ByteArray(chunk * 2)
            val mix = ShortArray(chunk)
            while (!cancelled.get()) {
                val input = liveSession?.input ?: break
                val read = runCatching { input.read(byteBuf) }.getOrDefault(-1)
                if (read <= 0) break
                val samples = read / 2
                for (i in 0 until samples) {
                    val lo = byteBuf[i * 2].toInt() and 0xFF
                    val hi = byteBuf[i * 2 + 1].toInt()
                    mix[i] = ((hi shl 8) or lo).toShort()
                }
                monitor.offer(if (samples == chunk) mix else mix.copyOf(samples))
                if (analysisLen < keepSamples) {
                    val room = minOf(samples, keepSamples - analysisLen)
                    mix.copyInto(analysisBuf, analysisLen, 0, room); analysisLen += room
                }
                if (leveler != null) {
                    val out = leveler.process(mix, samples); if (out.isNotEmpty()) sink.write(out, out.size)
                } else {
                    sink.write(mix, samples)
                }
                if (monitor.elapsedMs - lastReported >= 1_000) {
                    lastReported = monitor.elapsedMs
                    onStatus(Status(monitor.elapsedMs, monitor.health, sink.bytesWritten, Route.PHONE))
                }
            }
        } finally {
            runCatching { liveSession?.close() }
        }
        leveler?.flush()?.let { tail -> if (tail.isNotEmpty()) sink.write(tail, tail.size) }
        val file = sink.finish()
        val verdict = if (analysisLen > 0) {
            TwoVoiceAnalyzer.analyzeMono(analysisBuf.copyOf(analysisLen), sampleRateHz)
        } else {
            null
        }
        return Outcome(
            file, tier, monitor.elapsedMs, verdict, monitor.gaps(),
            levelled = leveler != null && processing.boostFarSide, viaBluetooth = false, stereo = false,
        )
    }

    /**
     * Where the call's audio is right now.
     *
     * Two questions, because they can have different answers. Where is *our capture*
     * being fed from — the device the policy routed the AudioRecord to? And where is
     * *the call* — the device Telecom has made the communication device? A Bluetooth
     * answer to either means the microphone is not hearing the conversation.
     */
    private fun routeOf(record: AudioRecord, am: AudioManager): Route {
        val captured = runCatching { record.routedDevice }.getOrNull()
        if (captured != null && captured.type.isBluetooth()) return Route.BLUETOOTH

        val call = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.communicationDevice }.getOrNull()
        } else {
            null
        }
        if (call != null && call.type.isBluetooth()) return Route.BLUETOOTH

        @Suppress("DEPRECATION")
        if (runCatching { am.isBluetoothScoOn }.getOrDefault(false)) return Route.BLUETOOTH

        return when (captured?.type) {
            null -> Route.UNKNOWN
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> Route.WIRED
            else -> Route.PHONE
        }
    }

    private fun Int.isBluetooth(): Boolean = when (this) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
        else -> false
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
    /**
     * How much the far side may be lifted, by tier.
     *
     * On the leakage-based tiers (accessibility, mic) the far side has been through an
     * earpiece and is 20–30 dB down, so it needs real gain. On the privileged tier it is
     * captured directly at full level, so only a gentle lift is applied — more than that
     * just drives the limiter and makes loud speech sound rough.
     */
    private fun farSideBoostDb(): Double =
        if (tier == CaptureTier.PRIVILEGED_SHELL) SHELL_BOOST_DB else LEAKAGE_BOOST_DB

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

        /** Far-side boost cap for leakage tiers (accessibility, mic) — the far side is faint. */
        const val LEAKAGE_BOOST_DB = 30.0

        /** Far-side boost cap for the privileged tier — it arrives full-level, so lift gently. */
        const val SHELL_BOOST_DB = 8.0

        /** Near-side boost cap on the stereo path — the close mic is already loud; only a
         *  gentle normalisation so a soft-spoken user is not left faint on the left channel. */
        const val NEAR_BOOST_DB = 6.0

        /** Put the speaker on, for the tier that depends on it. */
        fun forceSpeakerphone(context: Context, on: Boolean) = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }
}
