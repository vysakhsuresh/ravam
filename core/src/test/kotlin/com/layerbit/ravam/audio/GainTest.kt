package com.layerbit.ravam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainTest {

    @Test
    fun `unity gain returns an identical copy`() {
        val input = shortArrayOf(100, -200, 300, 0)
        val out = Gain.amplify(input, 1.0f)
        assertTrue(out.contentEquals(input))
        assertTrue("must be a copy, not the same array", out !== input)
    }

    @Test
    fun `doubling makes samples twice as loud`() {
        val out = Gain.amplify(shortArrayOf(1000, -1500), 2.0f)
        assertEquals(2000.toShort(), out[0])
        assertEquals((-3000).toShort(), out[1])
    }

    @Test
    fun `a boost can never wrap a loud sample into noise`() {
        // The whole point: clamp, don't overflow. 30000 * 4 would wrap without the clamp.
        val out = Gain.amplify(shortArrayOf(30000, -30000), 4.0f)
        assertEquals(Gain.SHORT_MAX.toShort(), out[0])
        assertEquals(Gain.SHORT_MIN.toShort(), out[1])
    }

    @Test
    fun `clipping is counted`() {
        val buf = shortArrayOf(30000, 100, -30000)
        val clipped = Gain.amplifyInPlace(buf, 4.0f)
        assertEquals(2, clipped)
        assertEquals(Gain.SHORT_MAX.toShort(), buf[0])
        assertEquals(400.toShort(), buf[1])
    }

    @Test
    fun `auto factor uses the headroom but never clips`() {
        // Peak at ~8192 has 4x headroom before hitting full scale.
        val f = Gain.autoFactor(shortArrayOf(8192, -4000, 1000))
        assertTrue("expected roughly 4x, got $f", f in 3.9f..4.0f)
        // Applying it must not clip.
        assertEquals(0, Gain.amplifyInPlace(shortArrayOf(8192, -4000, 1000), f))
    }

    @Test
    fun `auto factor never goes below unity for an already-full-scale buffer`() {
        // A sample at the limit has no headroom, so the factor floors at 1.0 — never below.
        val f = Gain.autoFactor(shortArrayOf(32767, -32768))
        assertEquals(Gain.NORMAL, f, 0.01f)
        assertTrue(f >= Gain.NORMAL)
    }

    @Test
    fun `auto factor is capped at the ceiling for very quiet audio`() {
        val f = Gain.autoFactor(shortArrayOf(10, -10), ceiling = Gain.LOUDEST)
        assertEquals(Gain.LOUDEST, f, 0.01f)
    }
}
