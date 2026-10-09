package com.layerbit.ravam.audio

import kotlin.math.abs

/**
 * Decides whether a recording contains two speakers or only the person holding the phone.
 *
 * ## Why this exists
 *
 * Android's audio policy fails *silently*. `startRecording()` succeeds, the file is the
 * right length, and the far end is simply absent — either digital zero, or so faint it
 * is useless. Every app in this category ships that failure to users, who discover it
 * days later when they go looking for a conversation that is not there.
 *
 * Ravam refuses to hand over a recording it has not measured.
 *
 * ## How
 *
 * **Stereo** — when the capture tier separates uplink and downlink onto two channels,
 * this is arithmetic: measure each channel. No inference, no thresholds, no doubt.
 *
 * **Mono** — when both sides arrive mixed into one stream, exploit the fact that they
 * arrive at *systematically different levels*. The local speaker is centimetres from
 * the microphone; the far end has been through a codec, a network, and a loudspeaker
 * before re-entering that same microphone. Frame the audio, keep the frames containing
 * speech, and look at the distribution of their levels in dB. Two speakers produce a
 * **bimodal** distribution. One speaker produces a **unimodal** one.
 *
 * Clustering is 1-D k-means with k=2 and deterministic seeding, which for a few thousand
 * scalars converges in a handful of passes and costs well under a second on a low-end
 * phone. There is no model to download and nothing leaves the device.
 *
 * ## Honesty about the thresholds
 *
 * [SEPARATION_DB] and [MIN_FAR_OCCUPANCY] are **provisional**. They are reasoned
 * starting points, not measured ones, and they are the first thing that must be tuned
 * against a labelled corpus of real calls from real handsets. Until that happens the
 * analyser is deliberately biased toward [Voices.INCONCLUSIVE] rather than toward a
 * green badge: under-claiming costs a user nothing, over-claiming costs them a call.
 */
object TwoVoiceAnalyzer {

    /** Minimum gap between cluster centroids to call a recording two-voiced. Provisional. */
    const val SEPARATION_DB = 6.0

    /** Minimum share of voiced frames the quieter cluster must hold. Provisional.
     *  Guards against a handful of outlier frames masquerading as a second speaker. */
    const val MIN_FAR_OCCUPANCY = 0.12

    /** How far above the noise floor a frame must sit to count as speech. */
    const val VOICE_MARGIN_DB = 9.0

    /** Below this share of voiced frames there is not enough signal to judge. */
    const val MIN_VOICED_FRACTION = 0.04

    /** Fewer voiced frames than this and the clustering is noise. ~2.5 s at 25 ms. */
    const val MIN_VOICED_FRAMES = 100

    /**
     * The easy case: uplink and downlink on separate channels.
     *
     * @param near the channel carrying the local microphone
     * @param far the channel carrying the remote party
     */
    fun analyzeStereo(near: ShortArray, far: ShortArray, sampleRateHz: Int): VoiceVerdict {
        val nearFrames = Pcm.frameEnergies(near, sampleRateHz)
        val farFrames = Pcm.frameEnergies(far, sampleRateHz)
        if (nearFrames.isEmpty() || farFrames.isEmpty()) {
            return inconclusive(0.0, Pcm.SILENCE_DBFS, "No audio captured")
        }

        val nearFloor = Pcm.percentile(nearFrames, 0.10)
        val farFloor = Pcm.percentile(farFrames, 0.10)

        val nearVoiced = nearFrames.count { it > nearFloor + VOICE_MARGIN_DB }
        val farVoiced = farFrames.count { it > farFloor + VOICE_MARGIN_DB }

        val nearShare = nearVoiced.toDouble() / nearFrames.size
        val farShare = farVoiced.toDouble() / farFrames.size

        val nearLevel = activeMean(nearFrames, nearFloor + VOICE_MARGIN_DB)
        val farLevel = activeMean(farFrames, farFloor + VOICE_MARGIN_DB)

        // The downlink channel carrying nothing but digital zero is the single most
        // common real-world failure. Name it explicitly rather than calling it quiet.
        val farIsSilent = farShare < MIN_VOICED_FRACTION

        return VoiceVerdict(
            voices = if (farIsSilent) Voices.LOCAL_ONLY else Voices.BOTH_SIDES,
            voicedFraction = maxOf(nearShare, farShare),
            noiseFloorDbfs = minOf(nearFloor, farFloor),
            nearLevelDbfs = nearLevel,
            farLevelDbfs = farLevel,
            separationDb = if (nearLevel != null && farLevel != null) abs(nearLevel - farLevel) else null,
            farOccupancy = farShare,
            basis = if (farIsSilent) {
                "Two channels captured, but the far channel carried no speech"
            } else {
                "Two channels captured, speech present on both"
            },
        )
    }

    /**
     * The hard case: both sides mixed into one stream.
     *
     * @param samples mono 16-bit PCM
     */
    fun analyzeMono(samples: ShortArray, sampleRateHz: Int): VoiceVerdict {
        val frames = Pcm.frameEnergies(samples, sampleRateHz)
        if (frames.isEmpty()) {
            return inconclusive(0.0, Pcm.SILENCE_DBFS, "No audio captured")
        }

        val floor = Pcm.percentile(frames, 0.10)
        val threshold = floor + VOICE_MARGIN_DB
        val voiced = frames.filter { it > threshold }.toDoubleArray()
        val voicedFraction = voiced.size.toDouble() / frames.size

        if (voiced.size < MIN_VOICED_FRAMES || voicedFraction < MIN_VOICED_FRACTION) {
            // Said in seconds, not frames. This line is shown to the person holding the
            // phone, and "only 11 frames" tells them nothing they can act on, whereas
            // "0.3 seconds" explains itself and implies the remedy.
            val seconds = voiced.size * Pcm.FRAME_MS / 1000.0
            return inconclusive(
                voicedFraction, floor,
                "Only %.1f seconds of speech — not enough to tell the voices apart".format(seconds),
            )
        }

        val (low, high) = kMeans2(voiced)
        val separation = high - low

        // Occupancy of the quieter cluster. A second speaker who said three words is
        // still a second speaker, but three *frames* is a measurement artefact.
        val midpoint = (low + high) / 2.0
        val farCount = voiced.count { it <= midpoint }
        val farOccupancy = farCount.toDouble() / voiced.size

        val twoVoices = separation >= SEPARATION_DB &&
            farOccupancy >= MIN_FAR_OCCUPANCY &&
            farOccupancy <= (1.0 - MIN_FAR_OCCUPANCY)

        return VoiceVerdict(
            voices = if (twoVoices) Voices.BOTH_SIDES else Voices.LOCAL_ONLY,
            voicedFraction = voicedFraction,
            noiseFloorDbfs = floor,
            nearLevelDbfs = high,
            farLevelDbfs = low,
            separationDb = separation,
            farOccupancy = farOccupancy,
            basis = if (twoVoices) {
                "Two speech levels %.1f dB apart, quieter one in %.0f%% of speech"
                    .format(separation, farOccupancy * 100)
            } else {
                "Speech sits at a single level (%.1f dB spread) — consistent with one speaker"
                    .format(separation)
            },
        )
    }

    /**
     * 1-D k-means, k=2, seeded at the extremes so the result is deterministic —
     * the same audio must always produce the same verdict.
     *
     * @return (quieter centroid, louder centroid)
     */
    internal fun kMeans2(values: DoubleArray, maxIterations: Int = 25): Pair<Double, Double> {
        var low = values.min()
        var high = values.max()
        if (high - low < 1e-9) return low to high

        repeat(maxIterations) {
            var lowSum = 0.0; var lowCount = 0
            var highSum = 0.0; var highCount = 0
            val midpoint = (low + high) / 2.0

            for (v in values) {
                if (v <= midpoint) { lowSum += v; lowCount++ } else { highSum += v; highCount++ }
            }
            // An empty cluster means the data is unimodal; the current centroids already say so.
            if (lowCount == 0 || highCount == 0) return low to high

            val newLow = lowSum / lowCount
            val newHigh = highSum / highCount
            if (abs(newLow - low) < 0.01 && abs(newHigh - high) < 0.01) return newLow to newHigh
            low = newLow; high = newHigh
        }
        return low to high
    }

    private fun activeMean(frames: DoubleArray, threshold: Double): Double? {
        var sum = 0.0; var n = 0
        for (f in frames) if (f > threshold) { sum += f; n++ }
        return if (n == 0) null else sum / n
    }

    private fun inconclusive(voicedFraction: Double, floor: Double, why: String) = VoiceVerdict(
        voices = Voices.INCONCLUSIVE,
        voicedFraction = voicedFraction,
        noiseFloorDbfs = floor,
        nearLevelDbfs = null,
        farLevelDbfs = null,
        separationDb = null,
        farOccupancy = null,
        basis = why,
    )
}
