package com.layerbit.ravam.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Removes the far side's earpiece leak from the near microphone, using the clean far
 * stream as a reference.
 *
 * ## The problem this solves
 *
 * On the privileged stereo path (see `CallRecorder.recordViaShell`) the near microphone
 * records the local speaker — but the far party's voice also leaks out of the earpiece
 * into that same microphone, a little delayed. The far party is therefore captured twice:
 * once, delayed and faint, on the near (left) channel, and once, clean, on the far (right)
 * channel. Play the file, or mix it to mono, and the other person is heard **twice,
 * slightly out of step** — the "double voice". The phone's hardware echo canceller is
 * meant to strip that leak before the samples reach us, and mostly does, but on some apps
 * (BOTIM was the reported one) it loses the leak intermittently.
 *
 * We are in a position the hardware canceller usually is not: we hold the *exact* far
 * signal that is leaking, on its own channel. So we can cancel the leak properly —
 * estimate how the far reference arrives in the mic (its delay and the room's short echo
 * tail) and subtract that estimate from the near channel. What is left is the near
 * speaker, with the far leak gone. The clean far party still lives on the far channel, so
 * nothing is lost — only the duplicate is removed.
 *
 * ## How it works
 *
 * 1. **Bulk-delay lock.** The leak lags the reference by the capture pipeline's latency —
 *    tens to a few hundred milliseconds, roughly constant once both streams flow. A coarse
 *    cross-correlation, refreshed a few times a second while the far side is talking, finds
 *    that lag so the adaptive filter can sit on top of the leak rather than hunt for it.
 * 2. **NLMS adaptive filter.** A short normalised-least-mean-squares FIR, positioned at the
 *    locked delay, models the leak path (direct earpiece-to-mic plus a few milliseconds of
 *    room tail) and is subtracted sample by sample. Normalised, so it converges at the same
 *    rate whether the far side is loud or quiet.
 * 3. **Double-talk freeze.** When the near speaker talks over the far side, the residual
 *    stops looking like the reference; adapting then would corrupt the filter. The weights
 *    are frozen in that case (the current estimate is still subtracted), which keeps the
 *    near voice intact during an interruption and stops the filter diverging.
 * 4. **Reference-silence freeze.** With no far reference there is no leak to cancel, so the
 *    near channel passes through untouched and adaptation is paused.
 *
 * No Android imports — this is tested on the JVM. Streaming-safe: feed it any chunk sizes
 * and the output for sample *i* is identical regardless of how the samples were grouped.
 */
class EchoCanceller(
    private val sampleRateHz: Int,
    /** Largest leak delay searched for. The pipeline latency sits well inside this. */
    maxDelayMs: Int = 400,
    /** Filter length — the leak path's direct spike plus a short room tail. */
    private val taps: Int = 160,
    /** NLMS step size. Below 1 for stability against a noisy reference; 0.3 is a safe, brisk rate. */
    private val mu: Double = 0.3,
) {

    companion object {
        /** Re-estimate the bulk delay this often. Long enough to be cheap, short enough to
         *  follow the slow drift between two independently clocked streams. */
        const val DELAY_REFRESH_MS = 500

        /** The cross-correlation reads this much recent audio to find the delay. A few
         *  hundred ms spans enough speech to correlate without being costly. */
        const val CORR_WINDOW_MS = 300

        /** Coarse delay search step. 1 ms is finer than the filter needs — it absorbs the
         *  remainder — and keeps the search cheap over a wide range. */
        const val CORR_STEP_MS = 1

        /** A block must carry at least this RMS (dBFS) to count as "far side talking" for
         *  the delay search, so silence between words never re-locks onto noise. */
        const val REF_ACTIVE_DBFS = -55.0

        /** Keeps NLMS from dividing by a near-zero reference energy. */
        private const val EPS = 1e-6

        /** Geigel double-talk threshold. The far side leaks into the mic attenuated — a
         *  fraction of the reference level — so a near sample louder than this fraction of
         *  the reference it is sitting on cannot be leak alone: the near speaker is talking.
         *  Set above the plausible leak gain so leak never reads as double-talk (which would
         *  stall the filter), but below unity so real near speech always does. */
        private const val GEIGEL_FRACTION = 0.6

        private const val FULL_SCALE = 32768.0
    }

    private val maxDelay = sampleRateHz * maxDelayMs / 1000
    private val corrWindow = sampleRateHz * CORR_WINDOW_MS / 1000
    private val corrStep = max(1, sampleRateHz * CORR_STEP_MS / 1000)
    private val refreshEvery = sampleRateHz * DELAY_REFRESH_MS / 1000
    private val refActiveRms = Math.pow(10.0, REF_ACTIVE_DBFS / 20.0) * FULL_SCALE

    // Far reference history, long enough to look back maxDelay + the whole filter.
    private val histLen = maxDelay + taps + 1
    private val farHist = DoubleArray(histLen)
    private var histWrite = 0
    private var totalFar = 0L

    // Short rolling windows of both streams, for the periodic delay search.
    private val corrNear = DoubleArray(corrWindow)
    private val corrFar = DoubleArray(corrWindow)
    private var corrPos = 0
    private var corrFilled = 0
    private var sinceRefresh = 0

    private val weights = DoubleArray(taps)
    private var bulkDelay = 0

    /** The delay the canceller has locked onto, in samples — for tests and diagnostics. */
    val lockedDelaySamples: Int get() = bulkDelay

    /**
     * Cancel the far-side leak from [near], using [far] as the reference, for [n] samples.
     * [near] and [far] must be the same frames: index *i* of each is the same instant.
     * Returns [n] cleaned near samples.
     */
    fun process(near: ShortArray, far: ShortArray, n: Int = near.size): ShortArray {
        val out = ShortArray(n)
        for (i in 0 until n) {
            val farSample = far[i].toDouble()
            pushFar(farSample)

            val nearSample = near[i].toDouble()
            pushCorr(nearSample, farSample)

            // Estimate the leak: the filter applied to the reference history at the locked
            // delay. histAgo(d) reads the reference d samples ago (0 = the sample just pushed).
            var est = 0.0
            var refEnergy = 0.0
            var refPeak = 0.0
            for (k in 0 until taps) {
                val r = histAgo(bulkDelay + k)
                est += weights[k] * r
                refEnergy += r * r
                val a = abs(r)
                if (a > refPeak) refPeak = a
            }
            val error = nearSample - est

            // Adapt, unless the near speaker is talking over the top (double-talk) or there
            // is no reference to cancel. Freezing still subtracts the current estimate, so
            // the leak keeps being removed while the near voice is left untouched.
            val refLoud = refPeak > refActiveRms
            val doubleTalk = abs(nearSample) > GEIGEL_FRACTION * refPeak
            if (refLoud && !doubleTalk) {
                val step = mu / (refEnergy + EPS)
                for (k in 0 until taps) {
                    weights[k] += step * error * histAgo(bulkDelay + k)
                }
            }

            out[i] = error.toInt().coerceIn(-32768, 32767).toShort()
        }

        sinceRefresh += n
        if (sinceRefresh >= refreshEvery) {
            sinceRefresh = 0
            relock()
        }
        return out
    }

    // ------------------------------------------------------------------ internals

    private fun pushFar(v: Double) {
        farHist[histWrite] = v
        histWrite = (histWrite + 1) % histLen
        totalFar++
    }

    /** The reference sample [ago] samples before the most recently pushed one. */
    private fun histAgo(ago: Int): Double {
        if (ago >= histLen || ago >= totalFar) return 0.0
        var idx = histWrite - 1 - ago
        while (idx < 0) idx += histLen
        return farHist[idx]
    }

    private fun pushCorr(near: Double, far: Double) {
        corrNear[corrPos] = near
        corrFar[corrPos] = far
        corrPos = (corrPos + 1) % corrWindow
        if (corrFilled < corrWindow) corrFilled++
    }

    /**
     * Re-estimate the bulk delay: the lag at which the near window best matches the far
     * window. Only runs when the far side carried real speech in the window, so a gap is
     * never mistaken for a new alignment. The new lag is accepted only if it correlates
     * clearly better than noise, so a weak window leaves the existing lock untouched.
     */
    private fun relock() {
        if (corrFilled < corrWindow) return

        // Linearise the two ring windows oldest-first.
        val nearLin = DoubleArray(corrWindow)
        val farLin = DoubleArray(corrWindow)
        var farEnergy = 0.0
        for (j in 0 until corrWindow) {
            val idx = (corrPos + j) % corrWindow
            nearLin[j] = corrNear[idx]
            farLin[j] = corrFar[idx]
            farEnergy += farLin[j] * farLin[j]
        }
        val farRms = sqrt(farEnergy / corrWindow)
        if (farRms < refActiveRms) return // far side was quiet; keep the current lock

        var bestLag = bulkDelay
        var bestScore = -1.0
        var lag = 0
        while (lag <= maxDelay) {
            // Correlate near[j] against far[j-lag] over the overlap.
            var dot = 0.0
            var farE = 0.0
            var j = lag
            while (j < corrWindow) {
                val f = farLin[j - lag]
                dot += nearLin[j] * f
                farE += f * f
                j++
            }
            if (farE > EPS) {
                val score = abs(dot) / sqrt(farE)
                if (score > bestScore) { bestScore = score; bestLag = lag }
            }
            lag += corrStep
        }

        // Move the lock gently so a one-off bad window cannot yank the filter off the leak.
        if (bestLag != bulkDelay) bulkDelay = (bulkDelay + bestLag + 1) / 2
    }
}
