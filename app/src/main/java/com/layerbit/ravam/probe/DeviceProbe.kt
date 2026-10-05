package com.layerbit.ravam.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.provider.Settings
import com.layerbit.ravam.audio.Pcm
import com.layerbit.ravam.capture.CaptureTier
import com.layerbit.ravam.capture.TierAvailability
import com.layerbit.ravam.capture.TierStatus
import java.io.File

/**
 * Finds out what this specific handset will actually do, by trying it.
 *
 * Every other app in this category infers capability from a model-name table, or simply
 * assumes it works and lets the user discover otherwise. Ravam measures, on the device,
 * and reports in plain words — before anyone relies on it.
 *
 * This class is also the project's instrument. The spikes in PLAN.md are not throwaway
 * APKs; they are these functions, shipping. The week-one experiment and the flagship
 * feature are the same code, which is why none of it gets thrown away.
 */
class DeviceProbe(private val context: Context) {

    companion object {
        /** 16 kHz mono is plenty for speech and keeps the buffer small on cheap phones. */
        const val SAMPLE_RATE = 16_000

        /** Long enough for the audio policy to settle and for RMS to mean something. */
        const val PROBE_DURATION_MS = 2_000

        /** Sources worth trying, best-first. */
        val SOURCES = listOf(
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
    }

    // ---------------------------------------------------------------- tiers

    /** Which capture routes exist on this device right now. */
    fun tiers(): List<TierAvailability> = listOf(
        TierAvailability(CaptureTier.PRIVILEGED_ROOT, rootStatus()),
        TierAvailability(CaptureTier.PRIVILEGED_SHELL, shellStatus()),
        TierAvailability(CaptureTier.ACCESSIBILITY, accessibilityStatus()),
        TierAvailability(CaptureTier.SPEAKERPHONE, micStatus()),
    )

    private fun rootStatus(): TierStatus {
        val su = listOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su")
            .any { runCatching { File(it).exists() }.getOrDefault(false) }
        return if (su) TierStatus.Ready
        else TierStatus.Unavailable("No root on this device, and it does not need one")
    }

    /**
     * Shizuku, or an on-device ADB pairing.
     *
     * `CAPTURE_VOICE_COMMUNICATION_OUTPUT` — the permission that makes VoIP far-end
     * capture deterministic — only exists from Android 14. Below that this tier can
     * still reach carrier audio but not app calls.
     */
    private fun shellStatus(): TierStatus {
        val shizuku = isInstalled("moe.shizuku.privileged.api")
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                TierStatus.Unavailable(
                    "App-call capture through this route needs Android 14 or newer " +
                        "(this phone is on ${Build.VERSION.RELEASE})"
                )
            shizuku -> TierStatus.NeedsSetup("Shizuku is installed — start it, then grant Ravam access")
            else -> TierStatus.NeedsSetup("Pair once over wireless debugging, on this phone, no PC needed")
        }
    }

    private fun accessibilityStatus(): TierStatus {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty().contains(context.packageName)
        }.getOrDefault(false)

        return when {
            enabled -> TierStatus.Ready
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> TierStatus.NeedsSetup(
                "Turn on Ravam in Accessibility. Installed outside an app store, Android " +
                    "hides that switch until you allow it under App info → Restricted settings."
            )
            else -> TierStatus.NeedsSetup("Turn on Ravam in Settings → Accessibility")
        }
    }

    private fun micStatus(): TierStatus =
        if (hasRecordPermission()) TierStatus.Ready
        else TierStatus.NeedsSetup("Allow Ravam to use the microphone")

    // ---------------------------------------------------------------- capture test

    /**
     * Open [source], record for [durationMs], and report what came back.
     *
     * Call this with no call in progress to establish a baseline, and again during a
     * real call to find out what this handset does when it matters. Those are two very
     * different answers on most phones, and the gap between them is the whole story.
     */
    @SuppressLint("MissingPermission")
    fun captureTest(
        source: Int,
        durationMs: Int = PROBE_DURATION_MS,
    ): CaptureTestResult {
        if (!hasRecordPermission()) {
            return CaptureTestResult.Failed("Microphone permission not granted")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return CaptureTestResult.Failed("This device rejected a 16 kHz mono stream")
        }

        val bufferBytes = minBuffer * 4
        var record: AudioRecord? = null
        try {
            record = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes)

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return CaptureTestResult.Failed("Could not open this audio source")
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return CaptureTestResult.Failed("Source opened but would not start")
            }

            val wanted = SAMPLE_RATE * durationMs / 1000
            val collected = ShortArray(wanted)
            var got = 0
            val chunk = ShortArray(bufferBytes / 2)
            val deadline = System.nanoTime() + durationMs * 2_000_000L

            while (got < wanted && System.nanoTime() < deadline) {
                val n = record.read(chunk, 0, minOf(chunk.size, wanted - got))
                if (n <= 0) break
                chunk.copyInto(collected, got, 0, n)
                got += n
            }

            if (got == 0) return CaptureTestResult.Failed("Source produced no frames at all")

            val samples = collected.copyOf(got)
            val silenced = isSilencedByFramework(record)
            val energies = Pcm.frameEnergies(samples, SAMPLE_RATE)
            val peak = energies.maxOrNull() ?: Pcm.SILENCE_DBFS
            val floor = Pcm.percentile(energies, 0.10)

            // Digital zero is the signature of the framework muting us: the call
            // succeeded, the buffer filled, and every sample is 0.
            val allZero = samples.all { it.toInt() == 0 }

            return when {
                allZero || silenced == true -> CaptureTestResult.Silenced(
                    frameworkConfirmed = silenced == true,
                )
                peak <= Pcm.SILENCE_DBFS + 1.0 -> CaptureTestResult.Silenced(frameworkConfirmed = false)
                else -> CaptureTestResult.Captured(
                    samples = samples,
                    sampleRateHz = SAMPLE_RATE,
                    peakDbfs = peak,
                    noiseFloorDbfs = floor,
                )
            }
        } catch (e: SecurityException) {
            return CaptureTestResult.Failed("Denied by the system: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return CaptureTestResult.Failed("This device does not offer that source")
        } catch (e: IllegalStateException) {
            return CaptureTestResult.Failed("Audio system busy: ${e.message}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    /**
     * Ask the framework directly whether it is feeding us zeros.
     *
     * `isClientSilenced()` lands in API 29 and is the single highest-value call in this
     * whole project: it turns the category's defining silent failure into a fact the app
     * can read, in one line, while the call is still happening.
     *
     * Returns null when the framework does not say.
     */
    private fun isSilencedByFramework(record: AudioRecord): Boolean? = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.activeRecordingConfigurations
            .firstOrNull { it.clientAudioSessionId == record.audioSessionId }
            ?.isClientSilenced
    }.getOrNull()

    // ---------------------------------------------------------------- helpers

    private fun hasRecordPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)
}

/** Outcome of one capture attempt. */
sealed interface CaptureTestResult {
    /** Audio arrived. */
    data class Captured(
        val samples: ShortArray,
        val sampleRateHz: Int,
        val peakDbfs: Double,
        val noiseFloorDbfs: Double,
    ) : CaptureTestResult {
        // ShortArray needs these by hand; the identity that matters is the measurement.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = samples.size * 31 + peakDbfs.hashCode()
    }

    /** The stream opened and returned silence. [frameworkConfirmed] when the OS said so. */
    data class Silenced(val frameworkConfirmed: Boolean) : CaptureTestResult

    /** The source could not be used at all. */
    data class Failed(val reason: String) : CaptureTestResult
}
