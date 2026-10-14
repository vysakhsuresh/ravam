package com.layerbit.ravam.data

import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import java.io.File
import java.io.FileInputStream

/**
 * Playback for one recording at a time, with the controls a music player has: seek to any
 * point, skip forward and back, and an optional loudness boost on top of whatever levelling
 * was done at capture — because call audio can still be quiet and the user asked to hear it
 * clearly.
 *
 * The player holds no UI state of its own beyond the MediaPlayer; the ViewModel polls
 * [positionMs] on a timer and drives the scrubber. Keeping it dumb means seek, skip and resume
 * are all one-liners the UI can call freely.
 */
class AudioPlayer {

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var playingId: String? = null

    val currentId: String? get() = playingId
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /**
     * Start a recording from the beginning, or from [startMs]. Replaces anything playing.
     * Returns false if the file could not be opened.
     *
     * The file is opened HERE, in our own process, and MediaPlayer is handed the descriptor —
     * not the path. setDataSource(String) only passes the path over Binder and lets the media
     * server do the opening, as the media uid, which has no access to /data/user/0/<pkg>/files
     * and is refused by the kernel ("Prepare failed: status=0x1", the real cause visible only in
     * logcat). Recordings are deliberately app-private, so this is not a corner case — it is
     * every recording the app makes. An already-open descriptor carries our access rights with it.
     *
     * @param boostMb extra playback gain in millibels (100 = 1 dB); 0 for none. Applied
     *   through the platform's [LoudnessEnhancer], which limits as it lifts, so a file that
     *   is already loud is squeezed rather than clipped.
     */
    fun play(file: File, id: String, startMs: Int = 0, boostMb: Int = 0, onComplete: () -> Unit): Boolean {
        stop()
        return runCatching {
            FileInputStream(file).use { input ->
                player = MediaPlayer().apply {
                    setDataSource(input.fd)
                    setOnCompletionListener { onComplete() }
                    prepare() // safe to close the stream after this; prepare() has read what it needs
                    if (boostMb > 0) attachBoost(audioSessionId, boostMb)
                    if (startMs > 0) seekTo(startMs)
                    start()
                }
            }
            playingId = id
            true
        }.getOrElse {
            // A truncated or half-written WAV must not take the app down with it.
            stop()
            false
        }
    }

    fun pause() = runCatching { player?.pause() }.let {}
    fun resume() = runCatching { player?.start() }.let {}

    fun togglePlayPause(): Boolean {
        if (isPlaying) { pause(); return false }
        resume(); return isPlaying
    }

    /** Jump to an absolute position, clamped to the recording. */
    fun seekTo(ms: Int) = runCatching {
        player?.let { it.seekTo(ms.coerceIn(0, it.duration)) }
    }.let {}

    /** Skip by a delta — negative to rewind. */
    fun skip(deltaMs: Int) = runCatching {
        player?.let { it.seekTo((it.currentPosition + deltaMs).coerceIn(0, it.duration)) }
    }.let {}

    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun durationMs(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    fun stop() {
        runCatching { enhancer?.release() }
        enhancer = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    /**
     * Best effort. The effect is optional on a device and can refuse for reasons that have
     * nothing to do with us; a recording that plays at its own level beats one that does
     * not play.
     */
    private fun attachBoost(sessionId: Int, boostMb: Int) {
        enhancer = runCatching {
            LoudnessEnhancer(sessionId).apply {
                setTargetGain(boostMb)
                enabled = true
            }
        }.getOrNull()
    }
}
