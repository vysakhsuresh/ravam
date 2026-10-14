package com.layerbit.ravam.audio

import kotlin.math.abs

/**
 * Makes a recording louder.
 *
 * Call audio — especially the far side arriving through a codec and a loudspeaker — is
 * often captured quiet, so a recording that is technically correct can still be hard to
 * hear. This applies a fixed gain and clamps to the 16-bit range so a boost can never
 * wrap a loud sample around into noise.
 *
 * Gain is applied to what is *written to disk*, not to what the verdict and the live
 * monitor measure — those stay on the original signal, so amplifying never changes whether
 * Ravam thinks both sides were present. A pure function, so the louder-but-not-broken
 * promise is unit-tested rather than trusted.
 */
object Gain {

    const val SHORT_MAX = 32767
    const val SHORT_MIN = -32768

    /** Common presets, surfaced in Settings as Normal / Loud / Loudest. */
    const val NORMAL = 1.0f
    const val LOUD = 2.5f
    const val LOUDEST = 4.0f

    /**
     * Return a new buffer amplified by [factor], hard-limited to the valid range.
     *
     * A factor of 1.0 returns an identical copy. Clipping is counted and returned so the
     * caller can tell the user if a chosen level is too hot for a given call.
     */
    fun amplify(samples: ShortArray, factor: Float): ShortArray {
        if (factor == 1.0f) return samples.copyOf()
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val scaled = (samples[i] * factor).toInt()
            out[i] = scaled.coerceIn(SHORT_MIN, SHORT_MAX).toShort()
        }
        return out
    }

    /** Amplify in place, returning how many samples hit the limit (clipped). */
    fun amplifyInPlace(samples: ShortArray, factor: Float): Int {
        if (factor == 1.0f) return 0
        var clipped = 0
        for (i in samples.indices) {
            val scaled = (samples[i] * factor).toInt()
            if (scaled > SHORT_MAX || scaled < SHORT_MIN) clipped++
            samples[i] = scaled.coerceIn(SHORT_MIN, SHORT_MAX).toShort()
        }
        return clipped
    }

    /**
     * The largest gain that would not clip a buffer, capped at [ceiling].
     *
     * The basis for an "auto" level: measure the loudest sample, compute the headroom, and
     * use as much of it as the ceiling allows — louder without distortion, per recording.
     */
    fun autoFactor(samples: ShortArray, ceiling: Float = LOUDEST): Float {
        var peak = 1
        for (s in samples) { val a = abs(s.toInt()); if (a > peak) peak = a }
        val headroom = SHORT_MAX.toFloat() / peak
        return headroom.coerceIn(NORMAL, ceiling)
    }
}
