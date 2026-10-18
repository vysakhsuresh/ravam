package com.layerbit.ravam.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceLevelerTest {

    private val rate = Signals.RATE

    /** Run a whole buffer through, including the held-back tail. */
    private fun level(input: ShortArray, gate: Boolean = false, maxGainDb: Double = 24.0): ShortArray {
        val l = VoiceLeveler(rate, noiseGate = gate, maxGainDb = maxGainDb)
        return l.process(input) + l.flush()
    }

    /** RMS dBFS of the second half of turn [turn], as [Signals.conversation] lays turns out. */
    private fun turnLevel(out: ShortArray, turn: Int, turnMs: Int = 1_200, gapMs: Int = 300): Double {
        val start = turn * (turnMs + gapMs) * rate / 1000
        val from = start + (turnMs / 2) * rate / 1000
        val until = start + turnMs * rate / 1000
        return Pcm.rmsDbfs(out, from, until)
    }

    /** RMS dBFS of the last 100 ms of the gap that follows turn [turn]. */
    private fun gapLevel(out: ShortArray, turn: Int, turnMs: Int = 1_200, gapMs: Int = 300): Double {
        val gapStart = (turn * (turnMs + gapMs) + turnMs) * rate / 1000
        val until = gapStart + gapMs * rate / 1000
        return Pcm.rmsDbfs(out, until - rate / 10, until)
    }

    private fun peak(out: ShortArray) = out.maxOf { abs(it.toInt()) }

    @Test
    fun `a quiet speaker is lifted toward the target`() {
        // The far side on a real phone: twenty-odd dB under the local voice, over the
        // floor a phone microphone actually has in a quiet room.
        val quiet = Signals.conversation(List(5) { -40.0 }, noiseDbfs = -72.0)
        val out = level(quiet)
        val before = turnLevel(quiet, 3)
        val after = turnLevel(out, 3)
        assertTrue("was $before, now $after", after > before + 18.0)
        assertTrue("overshot the target: $after", after <= -12.0)
    }

    @Test
    fun `a speaker who is already loud is left alone and never clipped`() {
        val loud = Signals.conversation(List(4) { -12.0 })
        val out = level(loud)
        val diff = turnLevel(out, 2) - turnLevel(loud, 2)
        assertTrue("changed a loud voice by $diff dB", abs(diff) < 1.0)
        assertTrue("clipped", peak(out) < 32767)
    }

    @Test
    fun `two speakers end up at close to one level`() {
        // The whole point. Near at -18, far at -36, taking turns.
        val call = Signals.conversation(List(8) { if (it % 2 == 0) -18.0 else -36.0 }, noiseDbfs = -72.0)
        val out = level(call)

        val nearBefore = turnLevel(call, 4); val farBefore = turnLevel(call, 5)
        val nearAfter = turnLevel(out, 4); val farAfter = turnLevel(out, 5)

        assertTrue("fixture not as expected", nearBefore - farBefore > 15.0)
        assertTrue(
            "still ${nearAfter - farAfter} dB apart",
            abs(nearAfter - farAfter) < 8.0,
        )
        assertTrue("clipped", peak(out) < 32767)
    }

    @Test
    fun `room noise alone is not boosted into hiss`() {
        val room = Signals.noiseOnly(5_000, dbfs = -62.0)
        val out = level(room)
        val tail = Pcm.rmsDbfs(out, out.size - rate, out.size)
        assertTrue("noise came out at $tail dBFS", tail < -55.0)
    }

    @Test
    fun `the gain is capped so a loud room does not become a louder room`() {
        // Speech only 10 dB over a loud floor: the boost must stop where the floor
        // would pass the ceiling, not run to the full 24 dB.
        val noisy = Signals.conversation(List(6) { -40.0 }, noiseDbfs = -48.0)
        val out = level(noisy)
        val gap = gapLevel(out, 4)
        assertTrue("floor rose to $gap dBFS", gap <= VoiceLeveler.NOISE_CEILING_DBFS + 2.0)
    }

    @Test
    fun `a pause after the quiet speaker comes out at the room's real level`() {
        // The complaint from the first build: with the far side boosted 20-odd dB, every
        // pause between their words carried the room boosted 20-odd dB too — hiss from a
        // silent room. The boost belongs to the words, not to the gaps.
        val call = Signals.conversation(List(8) { if (it % 2 == 0) -18.0 else -40.0 }, noiseDbfs = -70.0)
        val out = level(call)

        val farTurnLift = turnLevel(out, 5) - turnLevel(call, 5)
        assertTrue("fixture: far side was not boosted ($farTurnLift dB)", farTurnLift > 12.0)

        val pauseLift = gapLevel(out, 5) - gapLevel(call, 5)
        assertTrue("the pause after them was lifted $pauseLift dB", pauseLift < 5.0)
    }

    @Test
    fun `a dropout does not become the new floor`() {
        // One block of digital zero mid-call must not convince the leveler the room is
        // silent, or every bit of room noise afterwards would read as speech and be boosted.
        val before = Signals.conversation(List(4) { -18.0 }, noiseDbfs = -60.0)
        val zero = Signals.silence(200)
        val after = Signals.noiseOnly(3_000, dbfs = -60.0)
        val out = level(before + zero + after)
        val tail = Pcm.rmsDbfs(out, out.size - rate, out.size)
        assertTrue("room noise after a dropout came out at $tail dBFS", tail < -55.0)
    }

    @Test
    fun `the noise gate pulls the gaps between words down`() {
        val call = Signals.twoSpeakers(turns = 8)
        val open = level(call, gate = false)
        val gated = level(call, gate = true)

        val gapOpen = gapLevel(open, 4)
        val gapGated = gapLevel(gated, 4)
        assertTrue("gate did nothing: $gapOpen vs $gapGated", gapGated < gapOpen - 6.0)

        // And the speech itself is untouched by the gate.
        val diff = turnLevel(open, 5) - turnLevel(gated, 5)
        assertTrue("gate ate speech: $diff dB", abs(diff) < 1.0)
    }

    @Test
    fun `digital silence stays digital silence`() {
        val out = level(Signals.silence(2_000))
        assertEquals(0, peak(out))
    }

    @Test
    fun `a DC offset is removed`() {
        val offset = ShortArray(rate) { 8000 }
        val out = level(offset)
        val mean = out.copyOfRange(rate / 2, out.size).sumOf { it.toInt() }.toDouble() / (rate / 2)
        assertTrue("mean after HPF was $mean", abs(mean) < 50.0)
    }

    @Test
    fun `chunk boundaries do not change the result`() {
        // The recorder hands over whatever AudioRecord returns. Output must not depend on it.
        val call = Signals.twoSpeakers(turns = 4)
        val whole = level(call)

        val chunked = VoiceLeveler(rate)
        val pieces = ArrayList<ShortArray>()
        var i = 0
        var step = 37
        while (i < call.size) {
            val n = minOf(step, call.size - i)
            pieces += chunked.process(call.copyOfRange(i, i + n), n)
            i += n
            step = when (step) { 37 -> 1_000; 1_000 -> 7; else -> 37 }
        }
        pieces += chunked.flush()
        val joined = ShortArray(pieces.sumOf { it.size }).also { dst ->
            var at = 0
            for (p in pieces) { p.copyInto(dst, at); at += p.size }
        }

        assertEquals(call.size, joined.size)
        assertArrayEquals(whole, joined)
    }

    @Test
    fun `not one sample is lost or invented`() {
        val odd = Signals.noiseOnly(1_234)
        assertEquals(odd.size, level(odd).size)
    }

    @Test
    fun `with no gain allowed it is only a high-pass and a gate`() {
        // Noise cancellation on, boost off: the voice must come out at the level it went in.
        val call = Signals.twoSpeakers(turns = 6)
        val out = level(call, gate = true, maxGainDb = 0.0)
        val diff = turnLevel(out, 5) - turnLevel(call, 5)
        assertTrue("gain crept in: $diff dB", abs(diff) < 1.0)
    }

    @Test
    fun `two levelers fed the same chunk sizes stay sample-aligned`() {
        // The stereo capture path runs one leveler per channel and interleaves their output
        // L and R. That only yields aligned channels if, fed the same count every call, the
        // two emit the same number of samples every call. Lock that invariant here: it is
        // what keeps the near and far channels from drifting apart over a long call.
        val near = Signals.oneSpeaker(turns = 10, level = -16.0)
        val far = Signals.oneSpeaker(turns = 10, level = -30.0)
        val ln = VoiceLeveler(rate, maxGainDb = 6.0)   // near cap
        val lf = VoiceLeveler(rate, maxGainDb = 8.0)   // far cap — different gain, same timing
        val n = minOf(near.size, far.size)
        // Feed in deliberately irregular chunk sizes, as a real mic read would.
        val sizes = listOf(2048, 2048, 1000, 777, 2048, 333, 2048)
        var i = 0; var si = 0
        var nearTotal = 0; var farTotal = 0
        while (i < n) {
            val take = minOf(sizes[si % sizes.size], n - i); si++
            val a = ln.process(near.copyOfRange(i, i + take), take)
            val b = lf.process(far.copyOfRange(i, i + take), take)
            assertEquals("channels drifted at offset $i", a.size, b.size)
            nearTotal += a.size; farTotal += b.size
            i += take
        }
        assertEquals(ln.flush().size, lf.flush().size)
        assertEquals(nearTotal, farTotal)
    }
}
