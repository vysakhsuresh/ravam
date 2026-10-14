package com.layerbit.ravam.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Brings the quiet speaker up to the loud one, without wrecking either.
 *
 * ## The problem this solves
 *
 * On the accessibility tier both sides land in one mono stream, and they land at wildly
 * different levels. The local speaker is centimetres from the microphone. The far side
 * has been through a codec, a network and a tiny earpiece before leaking back into that
 * same microphone — typically 20–30 dB quieter, which on playback is the difference
 * between "clear" and "turn it up and hold the phone to your ear". The mic side records
 * fine; the earpiece side is the one people cannot hear. This fixes that side.
 *
 * ## What it does, in order
 *
 * 1. **High-pass at 150 Hz** — removes handling rumble, DC offset, and mains hum with
 *    its first harmonics, which gain would otherwise amplify first. A phone call carries
 *    nothing below 300 Hz from the far side anyway, and the near voice keeps everything
 *    that makes it intelligible.
 * 2. **Noise-floor tracking** — the quietest level seen recently, falling fast and rising
 *    slowly. Anything within [GATE_MARGIN_DB] of it is "between words", not speech.
 * 3. **Automatic gain** — each 10 ms block of speech is pushed toward [targetDbfs]. The
 *    gain can only add (never cuts a voice that is already fine), is capped at
 *    [maxGainDb], and is further capped so the room noise under a boosted voice never
 *    rises above [NOISE_CEILING_DBFS] — a loud room gets less boost rather than a loud
 *    room. Attack is fast so the near speaker's first word after a far turn does not
 *    slam; release is slow so a voice does not pump syllable to syllable.
 * 4. **Pauses return to natural** — the speech gain is remembered across a pause so the
 *    next word opens at full boost, but it is *not applied* to the pause itself: after
 *    [HOLD_MS] of quiet the output fades back to the level the microphone actually heard.
 *    The first build of this held the boost through every pause, and a silent room came
 *    out as a wall of hiss between the other person's words. That was the whole
 *    complaint, and this is the fix.
 * 5. **Optional noise gate** — with [noiseGate] on, pauses are additionally pulled
 *    [GATE_DEPTH_DB] *below* natural. This is the software half of "noise cancellation";
 *    the platform suppressor, where present, is attached by the recorder separately.
 * 6. **Soft limiter** — a smooth knee from −3 dBFS to full scale, so a burst the gain
 *    did not see coming rounds off instead of clipping.
 *
 * ## What it does not touch
 *
 * The two-voice verdict and the live monitor read the *raw* stream. Levelling two
 * speakers to one level is exactly the thing the analyser keys on being absent in a
 * one-sided recording, so handing it levelled audio would blind it. The recorder feeds
 * this class only the copy that goes to disk.
 *
 * Streaming-safe: feed it any chunk sizes in any order and the output is identical to
 * processing the whole thing at once. No Android imports, so it is tested on the JVM.
 */
class VoiceLeveler(
    sampleRateHz: Int,
    /** Level speech is pushed toward, dBFS RMS per block. −14 is loud with headroom. */
    private val targetDbfs: Double = -14.0,
    /** Absolute cap on added gain. */
    private val maxGainDb: Double = 24.0,
    /** Fade the gaps between words down, up to [GATE_DEPTH_DB]. */
    var noiseGate: Boolean = false,
) {

    companion object {
        /** Gain is decided per block of this length; short enough that one syllable
         *  cannot drag the next, long enough for a stable RMS. */
        const val BLOCK_MS = 10

        /** Below the floor plus this margin a block counts as "between words". The same
         *  margin the analyser uses to call a frame speech, so the two agree. Room noise
         *  wobbles a few dB block to block; this keeps a wobble from being boosted. */
        const val GATE_MARGIN_DB = TwoVoiceAnalyzer.VOICE_MARGIN_DB

        /** With the gate on, how far below natural the pauses are pulled. Deep enough to
         *  hear the difference, shallow enough that a trailing consonant survives. */
        const val GATE_DEPTH_DB = 15.0

        /** Boost is capped so the room under a boosted voice never rises above this.
         *  −46 dBFS is quiet enough to sit under speech at −14 unnoticed. */
        const val NOISE_CEILING_DBFS = -46.0

        /** How long after the last speech block the boost stays applied, so the gaps
         *  inside a sentence do not flutter. Longer than that is a pause. */
        const val HOLD_MS = 120

        /** How fast the floor estimate is allowed to climb when nothing quiet is seen —
         *  fast enough to follow a fan switching on, slow enough that a long sentence
         *  does not become the floor. */
        const val FLOOR_RISE_DB_PER_SEC = 2.0

        /** The floor is never assumed quieter than this, so the first second of a
         *  recording is not handed the maximum boost before anything is known. */
        const val FLOOR_START_DBFS = -50.0

        /** Loudest the floor can be taken as; above this the room is the signal. */
        const val FLOOR_MAX_DBFS = -30.0

        /** A block quieter than this is a dropout or digital zero, not the room, and must
         *  not drag the floor down with it — or everything after it is "speech". */
        const val FLOOR_MIN_DBFS = -90.0

        /** Corner of the high-pass. */
        const val HIGH_PASS_HZ = 150.0

        /** Where the limiter's knee starts, as linear amplitude (≈ −3 dBFS). */
        private const val KNEE = 0.7

        private const val FULL_SCALE = 32768.0
    }

    private val blockSize = Pcm.frameSize(sampleRateHz, BLOCK_MS)
    private val floorRisePerBlock = FLOOR_RISE_DB_PER_SEC * BLOCK_MS / 1000.0

    // Smoothing per block. The attack coefficient takes most of a gain cut inside the
    // block that needs it — the near speaker's first word after a long far turn arrives
    // with up to 24 dB of boost still applied — and the release takes roughly half a
    // second to open back up, so one voice does not pump syllable to syllable.
    private val attack = 0.8
    private val release = 0.02

    // The pause fade: once the hold has run out, the output drops back to natural over
    // roughly 100 ms. Opening is nearly instant — the block that contains the word is
    // the block that decides, so a word onset is never swallowed.
    private val gateClose = 0.25
    private val gateOpen = 0.8
    private val holdBlocks = HOLD_MS / BLOCK_MS

    // --- high-pass: 2nd-order Butterworth, direct form I ----------------------------
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    init {
        val w0 = 2.0 * PI * HIGH_PASS_HZ / sampleRateHz
        val alpha = sin(w0) / sqrt(2.0)   // Q = 1/√2: Butterworth
        val c = cos(w0)
        val a0 = 1.0 + alpha
        b0 = ((1.0 + c) / 2.0) / a0
        b1 = (-(1.0 + c)) / a0
        b2 = ((1.0 + c) / 2.0) / a0
        a1 = (-2.0 * c) / a0
        a2 = (1.0 - alpha) / a0
    }

    // --- state -----------------------------------------------------------------------
    private var floorDb = FLOOR_START_DBFS
    /** The boost speech gets. Remembered through pauses; not applied to them. */
    private var gainDb = 0.0
    /** What is taken back off during a pause: 0 on speech, down to −gain (natural) or
     *  −gain − [GATE_DEPTH_DB] with the gate on. */
    private var gateDb = 0.0
    private var holdLeft = 0

    /** Samples held over because the last chunk did not end on a block boundary. */
    private val pending = DoubleArray(blockSize)
    private var pendingLen = 0

    /** The gain applied to the most recent block, for diagnostics and tests. */
    val currentGainDb: Double get() = gainDb

    /** The noise floor as currently estimated, dBFS. */
    val noiseFloorDbfs: Double get() = floorDb

    /**
     * Process [count] samples of [input] and return the levelled audio.
     *
     * Output lags input by up to one block (10 ms) so that each block's gain is decided
     * from its own level — a one-block lookahead that costs nothing on a recording. A
     * call may therefore return slightly fewer samples than it was given; the remainder
     * comes out of the next call, or of [flush] at the very end.
     */
    fun process(input: ShortArray, count: Int = input.size): ShortArray {
        val out = ShortArray(((pendingLen + count) / blockSize) * blockSize)
        var written = 0
        var i = 0
        while (i < count) {
            val take = minOf(blockSize - pendingLen, count - i)
            for (k in 0 until take) pending[pendingLen + k] = highPass(input[i + k].toDouble())
            pendingLen += take
            i += take

            if (pendingLen == blockSize) {
                levelBlock(pending, blockSize)
                for (k in 0 until blockSize) out[written + k] = toShort(pending[k])
                written += blockSize
                pendingLen = 0
            }
        }
        return out
    }

    /**
     * Drain the partial block held back by the last [process] call. Call once, when the
     * recording ends. Returns the final samples; may be empty.
     */
    fun flush(): ShortArray {
        if (pendingLen == 0) return ShortArray(0)
        levelBlock(pending, pendingLen)
        val out = ShortArray(pendingLen) { toShort(pending[it]) }
        pendingLen = 0
        return out
    }

    // ------------------------------------------------------------------ internals

    private fun highPass(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    private fun levelBlock(buf: DoubleArray, n: Int) {
        val levelDb = rmsDb(buf, n)

        // Floor: drop quickly to anything quieter, climb slowly otherwise. A dropout is
        // not the room and is ignored.
        if (levelDb > FLOOR_MIN_DBFS) {
            floorDb = if (levelDb < floorDb) {
                (floorDb * 0.7) + (levelDb * 0.3)
            } else {
                floorDb + floorRisePerBlock
            }
        }
        floorDb = floorDb.coerceIn(FLOOR_MIN_DBFS, FLOOR_MAX_DBFS)

        val isSpeech = levelDb > floorDb + GATE_MARGIN_DB

        // Gain: aim speech at the target, never cut, never lift the room past the ceiling.
        val gainCap = minOf(maxGainDb, NOISE_CEILING_DBFS - floorDb).coerceAtLeast(0.0)
        val wantedDb = if (isSpeech) (targetDbfs - levelDb).coerceIn(0.0, gainCap) else gainDb.coerceAtMost(gainCap)
        val startGain = gainDb
        gainDb += (wantedDb - gainDb) * if (wantedDb < gainDb) attack else release

        // Pause handling: on speech, nothing is taken off. After the hold runs out, fade
        // the boost back off so the room sits where it really is — or below it, with the
        // gate on.
        val startGate = gateDb
        if (isSpeech) {
            holdLeft = holdBlocks
            gateDb += (0.0 - gateDb) * gateOpen
        } else if (holdLeft > 0) {
            holdLeft--
        } else {
            val restDb = -gainDb - (if (noiseGate) GATE_DEPTH_DB else 0.0)
            gateDb += (restDb - gateDb) * gateClose
        }

        // Ramp both gains across the block so there is no zipper step at the boundary.
        val fromLin = dbToLin(startGain + startGate)
        val toLin = dbToLin(gainDb + gateDb)
        for (k in 0 until n) {
            val t = (k + 1).toDouble() / n
            val g = fromLin + (toLin - fromLin) * t
            buf[k] = limit(buf[k] * g / FULL_SCALE) * FULL_SCALE
        }
    }

    private fun rmsDb(buf: DoubleArray, n: Int): Double {
        if (n == 0) return Pcm.SILENCE_DBFS
        var sum = 0.0
        for (k in 0 until n) { val v = buf[k] / FULL_SCALE; sum += v * v }
        val rms = sqrt(sum / n)
        return if (rms <= 0.0) Pcm.SILENCE_DBFS else (20.0 * log10(rms)).coerceAtLeast(Pcm.SILENCE_DBFS)
    }

    /** Soft knee: linear to [KNEE], then a tanh curve that approaches but never reaches ±1. */
    private fun limit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1.0 - KNEE)
        val y = KNEE + (1.0 - KNEE) * tanh(over)
        return if (x < 0) -y else y
    }

    private fun dbToLin(db: Double) = 10.0.pow(db / 20.0)

    private fun toShort(v: Double): Short = v.toInt().coerceIn(-32768, 32767).toShort()
}
