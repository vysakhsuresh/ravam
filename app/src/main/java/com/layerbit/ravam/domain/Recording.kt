package com.layerbit.ravam.domain

import com.layerbit.ravam.audio.Voices
import java.io.File

/**
 * One saved recording, as the app presents it.
 *
 * Metadata lives in the filename (human-readable, as PLAN.md §6 requires — no cryptic
 * flags) plus a small sidecar. Nothing here records a legal decision; the only facts kept
 * are the ones a user would want: who, when, how long, and whether both sides were heard.
 */
data class Recording(
    val file: File,
    val displayName: String,
    val epochMillis: Long,
    val durationMs: Int,
    val sizeBytes: Long,
    val voices: Voices,
    val channel: String,      // "Phone", "BOTIM", …
    /** A mic test from the Home screen, not a call. It has no far side to judge. */
    val isTest: Boolean = false,
    val starred: Boolean = false,
) {
    val id: String get() = file.name
}
