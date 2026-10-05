package com.layerbit.ravam.audio

/**
 * What Ravam is willing to say about a recording, and why.
 *
 * Three states, never two. "Couldn't tell" is a real answer and it is far more useful
 * than a confident wrong one — a green badge the user cannot trust is worse than no
 * badge at all, because it is the exact failure that makes people lose conversations
 * they thought they had.
 */
enum class Voices {
    /** Two distinct speakers measured. The only state that may be shown in green. */
    BOTH_SIDES,

    /** Speech present, but all of it at one level — consistent with the local mic only. */
    LOCAL_ONLY,

    /** Not enough voiced audio to decide. Too short, too quiet, or all silence. */
    INCONCLUSIVE,
}

/**
 * A verdict plus the measurements behind it.
 *
 * The evidence travels with the recording. A user who doubts the badge can see the
 * numbers that produced it, and a bug report can be diagnosed without the audio.
 */
data class VoiceVerdict(
    val voices: Voices,
    /** Fraction of frames judged to contain speech, 0..1. */
    val voicedFraction: Double,
    /** Estimated noise floor, dBFS. */
    val noiseFloorDbfs: Double,
    /** Centroid of the louder cluster, dBFS. Null when inconclusive. */
    val nearLevelDbfs: Double?,
    /** Centroid of the quieter cluster, dBFS. Null when inconclusive or unimodal. */
    val farLevelDbfs: Double?,
    /** Separation between the two centroids, dB. */
    val separationDb: Double?,
    /** Share of voiced frames belonging to the quieter cluster, 0..1. */
    val farOccupancy: Double?,
    /** How the verdict was reached, for the UI and for bug reports. */
    val basis: String,
) {
    val isTrustworthyGreen: Boolean get() = voices == Voices.BOTH_SIDES
}
