package com.layerbit.ravam.audio

/**
 * Watches a recording while it is still happening, and says the moment it stops being
 * worth keeping.
 *
 * ## The problem this solves
 *
 * Android binds an AccessibilityService with `BIND_FOREGROUND_SERVICE_WHILE_AWAKE`.
 * Raise the phone to your ear and the proximity sensor blanks the screen; the process
 * state can fall out of the band the audio policy requires, and capture is silenced
 * **mid-call**. The file keeps growing. The user keeps talking. Nothing says a word.
 *
 * That is almost certainly a large share of the "my recording just stops halfway"
 * reports across this whole category, and no app reacts to it, because they all verify
 * after the fact — if at all.
 *
 * Ravam reacts inside a few seconds, while the call is still live and the user can
 * still do something about it: switch to speakerphone, and save the conversation.
 *
 * ## How it decides
 *
 * Two independent alarms, because they have different causes and different fixes:
 *
 * - **Dead stream** — no frame above the noise floor at all. The capture was silenced.
 *   Nothing the user says or does to the call will help; the tier has failed.
 * - **Far side lost** — the local speaker is clearly present, but no quieter speech
 *   level has appeared for a sustained stretch. Capture is alive and getting one side.
 *
 * Hysteresis matters more than sensitivity here. People fall silent mid-sentence, take
 * long turns, and listen for a minute at a time. An alert that fires during a normal
 * pause is worse than no alert, because the user learns to ignore it. So a far-side
 * alarm requires [FAR_LOST_AFTER_MS] of *continuous local speech* with no far-side
 * evidence — a condition a real conversation does not produce by accident.
 *
 * This is a streaming counterpart to [TwoVoiceAnalyzer], and it deliberately shares its
 * thresholds so a live warning and the final verdict cannot contradict each other.
 */
class LiveIntegrityMonitor(
    private val sampleRateHz: Int,
    private val frameMs: Int = Pcm.FRAME_MS,
) {

    enum class Health {
        /** Not enough audio yet to have an opinion. */
        WARMING_UP,

        /** Both sides present, recently. */
        HEALTHY,

        /** Local speech is landing, the far side has not been heard for a long time. */
        FAR_SIDE_LOST,

        /** Nothing is landing at all. The capture tier has been silenced. */
        DEAD_STREAM,
    }

    companion object {
        /** Rolling window the decision is made over. */
        const val WINDOW_MS = 6_000

        /** Continuous local-only speech before the far-side alarm fires. */
        const val FAR_LOST_AFTER_MS = 12_000

        /** Continuous silence before the dead-stream alarm fires. */
        const val DEAD_AFTER_MS = 4_000

        /** Warm-up before any verdict at all. */
        const val WARMUP_MS = 3_000
    }

    private val windowFrames = (WINDOW_MS / frameMs).coerceAtLeast(1)
    private val window = ArrayDeque<Double>(windowFrames)

    private var totalFrames = 0L
    private var silentRunMs = 0
    private var localOnlyRunMs = 0

    /** Noise floor, tracked adaptively from the quietest frames seen so far. */
    private var noiseFloor = Pcm.SILENCE_DBFS
    private var floorSeeded = false

    var health: Health = Health.WARMING_UP
        private set

    /** Every stretch where the far side was missing, for the final evidence record. */
    private val gaps = mutableListOf<IntRange>()
    private var currentGapStartMs: Int? = null

    val elapsedMs: Int get() = (totalFrames * frameMs).toInt()

    /**
     * Feed one captured buffer. Returns the health *after* this buffer, so a caller can
     * compare against the previous value and alert only on a transition.
     */
    fun offer(samples: ShortArray): Health {
        val energies = Pcm.frameEnergies(samples, sampleRateHz, frameMs)
        for (e in energies) offerFrame(e)
        return health
    }

    private fun offerFrame(dbfs: Double) {
        totalFrames++

        if (window.size >= windowFrames) window.removeFirst()
        window.addLast(dbfs)

        // Seed the floor from the first window, then let it track downward only — a floor
        // that rises with the conversation would swallow the quieter speaker entirely.
        if (!floorSeeded && window.size >= windowFrames) {
            noiseFloor = Pcm.percentile(window.toDoubleArray(), 0.10)
            floorSeeded = true
        } else if (floorSeeded && dbfs < noiseFloor) {
            noiseFloor = (noiseFloor * 0.95) + (dbfs * 0.05)
        }

        val threshold = noiseFloor + TwoVoiceAnalyzer.VOICE_MARGIN_DB
        val isSpeech = dbfs > threshold

        silentRunMs = if (isSpeech) 0 else silentRunMs + frameMs

        if (elapsedMs < WARMUP_MS) { health = Health.WARMING_UP; return }

        if (silentRunMs >= DEAD_AFTER_MS) {
            transition(Health.DEAD_STREAM)
            return
        }

        // Is there a second, quieter speech level anywhere in the current window?
        val voiced = window.filter { it > threshold }.toDoubleArray()
        val bothSidesInWindow = if (voiced.size >= 20) {
            val (low, high) = TwoVoiceAnalyzer.kMeans2(voiced)
            (high - low) >= TwoVoiceAnalyzer.SEPARATION_DB
        } else {
            false
        }

        if (bothSidesInWindow) {
            localOnlyRunMs = 0
            transition(Health.HEALTHY)
        } else if (isSpeech) {
            localOnlyRunMs += frameMs
            if (localOnlyRunMs >= FAR_LOST_AFTER_MS) transition(Health.FAR_SIDE_LOST)
        }
    }

    private fun transition(to: Health) {
        if (health == to) return

        val now = elapsedMs
        val leavingGood = to == Health.FAR_SIDE_LOST || to == Health.DEAD_STREAM
        if (leavingGood && currentGapStartMs == null) {
            currentGapStartMs = now
        } else if (!leavingGood) {
            currentGapStartMs?.let { gaps += it..now }
            currentGapStartMs = null
        }
        health = to
    }

    /** Close the final gap and hand back everywhere the far side was missing. */
    fun gaps(): List<IntRange> {
        val closed = gaps.toMutableList()
        currentGapStartMs?.let { closed += it..elapsedMs }
        return closed
    }
}
