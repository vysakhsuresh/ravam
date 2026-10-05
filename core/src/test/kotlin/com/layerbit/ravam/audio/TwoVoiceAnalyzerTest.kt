package com.layerbit.ravam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoVoiceAnalyzerTest {

    // ------------------------------------------------------------------ mono

    @Test
    fun `two speakers at different levels are recognised as both sides`() {
        val v = TwoVoiceAnalyzer.analyzeMono(Signals.twoSpeakers(), Signals.RATE)
        assertEquals(v.basis, Voices.BOTH_SIDES, v.voices)
        assertNotNull(v.separationDb)
        assertTrue(
            "expected a clear level gap, got ${v.separationDb}",
            v.separationDb!! >= TwoVoiceAnalyzer.SEPARATION_DB,
        )
    }

    @Test
    fun `one speaker is never reported as both sides`() {
        val v = TwoVoiceAnalyzer.analyzeMono(Signals.oneSpeaker(), Signals.RATE)
        assertEquals(v.basis, Voices.LOCAL_ONLY, v.voices)
    }

    @Test
    fun `natural pauses in one speaker do not fake a second voice`() {
        // The failure that would matter most in the field: long gaps between turns
        // must not be mistaken for a quieter person.
        val v = TwoVoiceAnalyzer.analyzeMono(
            Signals.conversation(List(10) { -16.0 }, turnMs = 900, gapMs = 1_500),
            Signals.RATE,
        )
        assertEquals(v.basis, Voices.LOCAL_ONLY, v.voices)
    }

    @Test
    fun `digital silence is inconclusive, never local-only`() {
        // A silenced stream is a different problem from a one-sided one and the user
        // needs a different instruction, so the verdicts must not collapse together.
        val v = TwoVoiceAnalyzer.analyzeMono(Signals.silence(5_000), Signals.RATE)
        assertEquals(Voices.INCONCLUSIVE, v.voices)
    }

    @Test
    fun `room tone with no speech is inconclusive`() {
        val v = TwoVoiceAnalyzer.analyzeMono(Signals.noiseOnly(5_000), Signals.RATE)
        assertEquals(v.basis, Voices.INCONCLUSIVE, v.voices)
    }

    @Test
    fun `a clip too short to judge says so rather than guessing`() {
        val v = TwoVoiceAnalyzer.analyzeMono(Signals.conversation(listOf(-18.0), turnMs = 300), Signals.RATE)
        assertEquals(Voices.INCONCLUSIVE, v.voices)
    }

    @Test
    fun `a faint far side is still found`() {
        // Earpiece leakage is quiet. If the analyser only works on speakerphone it is
        // useless for the case that actually matters.
        val v = TwoVoiceAnalyzer.analyzeMono(
            Signals.twoSpeakers(turns = 12, near = -16.0, far = -34.0), Signals.RATE,
        )
        assertEquals(v.basis, Voices.BOTH_SIDES, v.voices)
    }

    // ---------------------------------------------------------------- stereo

    @Test
    fun `stereo with a silent far channel is local only`() {
        val near = Signals.oneSpeaker()
        val far = ShortArray(near.size)
        val v = TwoVoiceAnalyzer.analyzeStereo(near, far, Signals.RATE)
        assertEquals(v.basis, Voices.LOCAL_ONLY, v.voices)
    }

    @Test
    fun `stereo with speech on both channels is both sides`() {
        val near = Signals.oneSpeaker(level = -18.0)
        val far = Signals.oneSpeaker(level = -26.0)
        val v = TwoVoiceAnalyzer.analyzeStereo(near, far, Signals.RATE)
        assertEquals(v.basis, Voices.BOTH_SIDES, v.voices)
    }

    // ------------------------------------------------------------- clustering

    @Test
    fun `clustering is deterministic`() {
        val audio = Signals.twoSpeakers()
        val a = TwoVoiceAnalyzer.analyzeMono(audio, Signals.RATE)
        val b = TwoVoiceAnalyzer.analyzeMono(audio, Signals.RATE)
        assertEquals(a.separationDb, b.separationDb)
        assertEquals(a.voices, b.voices)
    }

    @Test
    fun `clustering on a single-valued set does not divide by zero`() {
        val (low, high) = TwoVoiceAnalyzer.kMeans2(DoubleArray(50) { -20.0 })
        assertEquals(-20.0, low, 1e-9)
        assertEquals(-20.0, high, 1e-9)
    }

    @Test
    fun `clustering separates two obvious groups`() {
        val values = DoubleArray(200) { if (it % 2 == 0) -40.0 else -20.0 }
        val (low, high) = TwoVoiceAnalyzer.kMeans2(values)
        assertEquals(-40.0, low, 0.5)
        assertEquals(-20.0, high, 0.5)
    }

    // ------------------------------------------------------------------ Pcm

    @Test
    fun `silence measures as the floor, not negative infinity`() {
        assertEquals(Pcm.SILENCE_DBFS, Pcm.rmsDbfs(ShortArray(400)), 1e-9)
    }

    @Test
    fun `full scale measures near zero dBFS`() {
        val full = ShortArray(400) { if (it % 2 == 0) 32767 else -32767 }
        assertTrue(Pcm.rmsDbfs(full) > -0.5)
    }

    @Test
    fun `deinterleave splits channels`() {
        val (l, r) = Pcm.deinterleave(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertTrue(l.contentEquals(shortArrayOf(1, 3, 5)))
        assertTrue(r.contentEquals(shortArrayOf(2, 4, 6)))
    }
}
