package com.layerbit.ravam.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Signed 16-bit PCM primitives.
 *
 * Deliberately free of Android imports so the whole measurement path can be exercised
 * on the JVM, against recorded fixtures, without a handset in the loop. The verdict this
 * code produces is the product's central claim; it has to be testable like one.
 */
object Pcm {

    /** Frame length used everywhere. 25 ms is long enough for a stable RMS and short
     *  enough that a speaker change lands inside one frame rather than across three. */
    const val FRAME_MS = 25

    /** Full scale for 16-bit audio. */
    private const val FULL_SCALE = 32768.0

    /** Floor for the dB conversion, so digital silence yields a finite number. */
    const val SILENCE_DBFS = -120.0

    fun frameSize(sampleRateHz: Int, frameMs: Int = FRAME_MS): Int =
        (sampleRateHz.toLong() * frameMs / 1000L).toInt().coerceAtLeast(1)

    /** Root-mean-square of one frame, in dBFS. Returns [SILENCE_DBFS] for an empty or silent frame. */
    fun rmsDbfs(samples: ShortArray, from: Int = 0, until: Int = samples.size): Double {
        if (until <= from) return SILENCE_DBFS
        var sumSquares = 0.0
        for (i in from until until) {
            val v = samples[i] / FULL_SCALE
            sumSquares += v * v
        }
        val rms = sqrt(sumSquares / (until - from))
        return if (rms <= 0.0) SILENCE_DBFS else (20.0 * log10(rms)).coerceAtLeast(SILENCE_DBFS)
    }

    /** Per-frame dBFS across a buffer. */
    fun frameEnergies(samples: ShortArray, sampleRateHz: Int, frameMs: Int = FRAME_MS): DoubleArray {
        val size = frameSize(sampleRateHz, frameMs)
        if (samples.isEmpty() || size <= 0) return DoubleArray(0)
        val count = samples.size / size
        if (count == 0) return DoubleArray(0)
        return DoubleArray(count) { f -> rmsDbfs(samples, f * size, (f + 1) * size) }
    }

    /** De-interleave an interleaved stereo buffer into (left, right). */
    fun deinterleave(interleaved: ShortArray): Pair<ShortArray, ShortArray> {
        val n = interleaved.size / 2
        val l = ShortArray(n)
        val r = ShortArray(n)
        for (i in 0 until n) {
            l[i] = interleaved[i * 2]
            r[i] = interleaved[i * 2 + 1]
        }
        return l to r
    }

    /** Value at [p] (0..1) of a sorted copy of [values]. Used for noise-floor estimation. */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return SILENCE_DBFS
        val sorted = values.sortedArray()
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[idx]
    }
}
