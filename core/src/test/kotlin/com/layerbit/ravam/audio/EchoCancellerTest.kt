package com.layerbit.ravam.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The echo canceller is given a synthetic recording that reproduces the "double voice":
 * a clean far reference, and a near channel that is the far reference leaked back
 * delayed-and-quiet, with an independent near voice during one stretch.
 *
 * It must (1) remove the leak once it has locked on, (2) leave the near voice intact when
 * the near speaker talks over the far side, and (3) find roughly the right delay.
 */
class EchoCancellerTest {

    private val rate = 16_000
    private val delay = 1_600          // 100 ms leak delay
    private val leakGain = 0.25        // the earpiece leak is ~12 dB down

    @Test
    fun removesTheLeakButKeepsTheNearVoice() {
        val seconds = 8
        val total = rate * seconds
        val far = ShortArray(total)
        val near = ShortArray(total)

        // Far reference: speech-like gated bursts of a few tones (400 ms on, 100 ms off).
        for (i in 0 until total) {
            val t = i.toDouble() / rate
            val on = (i % (rate / 2)) < (rate * 2 / 5)
            far[i] = if (on) {
                val v = 6000.0 * (sin(2 * PI * 320 * t) * 0.5 +
                    sin(2 * PI * 540 * t) * 0.3 + sin(2 * PI * 870 * t) * 0.2)
                v.toInt().toShort()
            } else 0
        }

        // Near channel: the far reference, delayed and attenuated (the earpiece leak), plus
        // an independent near voice from 5.0 s to 5.6 s.
        val nearStart = (5.0 * rate).toInt()
        val nearEnd = (5.6 * rate).toInt()
        for (i in 0 until total) {
            var v = if (i >= delay) leakGain * far[i - delay] else 0.0
            if (i in nearStart until nearEnd) {
                val t = i.toDouble() / rate
                v += 9000.0 * sin(2 * PI * 290 * t)
            }
            near[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }

        val ec = EchoCanceller(rate)
        val out = ShortArray(total)
        var off = 0
        val chunk = 512
        while (off < total) {
            val n = minOf(chunk, total - off)
            val cleaned = ec.process(near.copyOfRange(off, off + n), far.copyOfRange(off, off + n), n)
            cleaned.copyInto(out, off)
            off += n
        }

        // 1. In a converged far-only window the leak is largely gone.
        val leakInWin = rmsDb(near, (7.0 * rate).toInt(), (7.8 * rate).toInt())
        val leakOutWin = rmsDb(out, (7.0 * rate).toInt(), (7.8 * rate).toInt())
        val reduction = leakInWin - leakOutWin
        assertTrue("Leak should drop by >8 dB once locked; was $reduction dB", reduction > 8.0)

        // 2. The near voice survives the double-talk stretch, well above the residual leak.
        val nearVoiceOut = rmsDb(out, nearStart + rate / 10, nearEnd - rate / 10)
        assertTrue(
            "Near voice should stand well above the cancelled residual; margin was " +
                "${nearVoiceOut - leakOutWin} dB",
            nearVoiceOut - leakOutWin > 12.0,
        )
        // The near voice alone (9000-amplitude sine) is about -11 dBFS; the output in that
        // window must be within a few dB of it, i.e. the voice was not cancelled away.
        assertTrue("Near voice was over-attenuated: $nearVoiceOut dBFS", nearVoiceOut > -15.0)

        // 3. The canceller locked onto roughly the true delay.
        assertTrue(
            "Expected a lock near $delay samples; got ${ec.lockedDelaySamples}",
            kotlin.math.abs(ec.lockedDelaySamples - delay) <= 160,
        )
    }

    @Test
    fun passesNearThroughWhenThereIsNoReference() {
        val total = rate * 2
        val near = ShortArray(total) { (4000.0 * sin(2 * PI * 300 * it / rate)).toInt().toShort() }
        val far = ShortArray(total) // silent reference

        val ec = EchoCanceller(rate)
        val out = ec.process(near, far, total)

        val inDb = rmsDb(near, 0, total)
        val outDb = rmsDb(out, 0, total)
        assertTrue("Near should pass through untouched: $inDb vs $outDb", kotlin.math.abs(inDb - outDb) < 1.0)
    }

    private fun rmsDb(x: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) { val v = x[i] / 32768.0; sum += v * v }
        val rms = sqrt(sum / (to - from))
        return if (rms <= 0.0) -120.0 else 20.0 * log10(rms)
    }
}
