package com.layerbit.ravam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveIntegrityMonitorTest {

    private fun monitor() = LiveIntegrityMonitor(Signals.RATE)

    @Test
    fun `says nothing until it has heard enough`() {
        val m = monitor()
        m.offer(Signals.conversation(listOf(-18.0), turnMs = 500))
        assertEquals(LiveIntegrityMonitor.Health.WARMING_UP, m.health)
    }

    @Test
    fun `a healthy two-sided call stays healthy`() {
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 20))
        assertEquals(LiveIntegrityMonitor.Health.HEALTHY, m.health)
    }

    @Test
    fun `a silenced stream is reported as dead, not merely one-sided`() {
        // The user's instruction differs: a dead stream means the capture tier failed
        // and switching to speakerphone will not help. Conflating the two misleads.
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 8))
        m.offer(Signals.silence(6_000))
        assertEquals(LiveIntegrityMonitor.Health.DEAD_STREAM, m.health)
    }

    @Test
    fun `the far side going missing mid-call is caught`() {
        // This is the WHILE_AWAKE failure: capture is alive, the far end stops arriving,
        // and every other app in this category finds out never.
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 10))
        assertEquals(LiveIntegrityMonitor.Health.HEALTHY, m.health)

        m.offer(Signals.conversation(List(20) { -18.0 }, turnMs = 1_200, gapMs = 200))
        assertEquals(LiveIntegrityMonitor.Health.FAR_SIDE_LOST, m.health)
    }

    @Test
    fun `a normal pause does not trigger a false alarm`() {
        // The single most important negative test. An alert that fires while someone is
        // listening teaches the user to ignore every alert afterwards.
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 10))
        m.offer(Signals.conversation(List(4) { -18.0 }, turnMs = 1_000, gapMs = 800))
        assertTrue(
            "alarmed during a normal exchange: ${m.health}",
            m.health != LiveIntegrityMonitor.Health.FAR_SIDE_LOST,
        )
    }

    @Test
    fun `recovery is noticed when the far side comes back`() {
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 8))
        m.offer(Signals.conversation(List(20) { -18.0 }, turnMs = 1_200, gapMs = 200))
        assertEquals(LiveIntegrityMonitor.Health.FAR_SIDE_LOST, m.health)

        m.offer(Signals.twoSpeakers(turns = 10))
        assertEquals(LiveIntegrityMonitor.Health.HEALTHY, m.health)
    }

    @Test
    fun `gaps are recorded for the evidence trail`() {
        val m = monitor()
        m.offer(Signals.twoSpeakers(turns = 8))
        m.offer(Signals.conversation(List(20) { -18.0 }, turnMs = 1_200, gapMs = 200))
        m.offer(Signals.twoSpeakers(turns = 10))

        val gaps = m.gaps()
        assertTrue("expected at least one recorded gap", gaps.isNotEmpty())
        assertTrue("a gap must have positive duration", gaps.all { it.last > it.first })
    }

    @Test
    fun `elapsed time tracks the audio fed in`() {
        val m = monitor()
        m.offer(Signals.silence(10_000))
        // Frame quantisation means this lands near, not exactly on, 10s.
        assertTrue("got ${m.elapsedMs}ms", m.elapsedMs in 9_900..10_100)
    }
}
