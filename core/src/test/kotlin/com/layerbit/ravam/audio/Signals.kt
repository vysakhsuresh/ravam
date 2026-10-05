package com.layerbit.ravam.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic call audio, so the verdict engine can be exercised without a phone.
 *
 * These are not pretending to be speech. They reproduce the one property the analyser
 * actually keys on — that a near speaker and a far speaker arrive at systematically
 * different levels, interleaved in turns, over a noise floor.
 */
object Signals {

    const val RATE = 16_000

    /** Amplitude for a given dBFS level, as a 16-bit peak. */
    private fun amplitudeFor(dbfs: Double): Double = 32768.0 * Math.pow(10.0, dbfs / 20.0)

    /**
     * A conversation.
     *
     * @param turns level of each turn in dBFS, in order. Use one repeated level for a
     *   single speaker, two alternating levels for two.
     * @param turnMs how long each turn lasts
     * @param gapMs silence between turns
     * @param noiseDbfs the floor
     */
    fun conversation(
        turns: List<Double>,
        turnMs: Int = 1_200,
        gapMs: Int = 300,
        noiseDbfs: Double = -62.0,
        seed: Int = 7,
    ): ShortArray {
        val rng = Random(seed)
        val out = ArrayList<Short>(turns.size * (turnMs + gapMs) * RATE / 1000)
        val noise = amplitudeFor(noiseDbfs)

        fun pushNoise(ms: Int) {
            repeat(ms * RATE / 1000) {
                out += (rng.nextDouble(-noise, noise)).toInt().toShort()
            }
        }

        turns.forEachIndexed { index, levelDbfs ->
            // sqrt(2) relates a sine's peak to its RMS, so the frame RMS lands on the
            // level we asked for rather than 3 dB above it.
            val peak = amplitudeFor(levelDbfs) * 1.414
            val freq = 180.0 + (index % 3) * 40.0   // vary so it is not one pure tone
            repeat(turnMs * RATE / 1000) { n ->
                val tone = sin(2.0 * PI * freq * n / RATE) * peak
                val grit = rng.nextDouble(-noise, noise)
                out += (tone + grit).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (index != turns.lastIndex) pushNoise(gapMs)
        }
        return out.toShortArray()
    }

    /** Pure digital zero. */
    fun silence(ms: Int) = ShortArray(ms * RATE / 1000)

    /** Room tone only, no speech. */
    fun noiseOnly(ms: Int, dbfs: Double = -62.0, seed: Int = 3): ShortArray {
        val rng = Random(seed)
        val a = amplitudeFor(dbfs)
        return ShortArray(ms * RATE / 1000) { rng.nextDouble(-a, a).toInt().toShort() }
    }

    /** One speaker, close to the mic. */
    fun oneSpeaker(turns: Int = 8, level: Double = -18.0) =
        conversation(List(turns) { level })

    /** Two speakers at different levels, taking turns. */
    fun twoSpeakers(turns: Int = 8, near: Double = -18.0, far: Double = -30.0) =
        conversation(List(turns) { if (it % 2 == 0) near else far })
}
