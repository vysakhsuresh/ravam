package com.layerbit.ravam.data

import android.media.MediaPlayer
import java.io.File
import java.io.FileInputStream

/**
 * A thin MediaPlayer wrapper for recording playback.
 *
 * One player at a time, released on stop. WAV plays natively on every Android version, so
 * no codec concerns in v1 — a point in favour of the simple storage format while the app
 * finds its feet.
 */
class AudioPlayer {

    private var player: MediaPlayer? = null
    private var playingId: String? = null

    val currentId: String? get() = playingId
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Starts, pauses or resumes [recording]. Returns false if it could not be played. */
    fun toggle(recording: File, id: String, onComplete: () -> Unit): Boolean {
        if (playingId == id && isPlaying) { pause(); return true }
        if (playingId == id && player != null) { player?.start(); return true }

        stop()
        return runCatching {
            // Open the file here, in our own process, and hand MediaPlayer the descriptor.
            //
            // setDataSource(String) looks like it would do, but it only passes the *path*
            // over Binder and the media server does the opening — as the media uid, which
            // has no business inside /data/user/0/<pkg>/files and is refused by the kernel.
            // That surfaces as a bare "Prepare failed.: status=0x1" with the real cause
            // (FileSource: Permission denied) visible only in logcat, from a process that
            // is not ours. Recordings are deliberately app-private, so this is not a corner
            // case: it is every recording the app has ever made.
            //
            // An already-open descriptor carries our access rights with it, so the media
            // server never needs any of its own.
            FileInputStream(recording).use { input ->
                player = MediaPlayer().apply {
                    setDataSource(input.fd)
                    setOnCompletionListener { this@AudioPlayer.stop(); onComplete() }
                    prepare()   // safe to close the stream after this; prepare() has read what it needs
                    start()
                }
            }
            playingId = id
            true
        }.getOrElse {
            // A truncated or half-written WAV must not take the app down with it. Recordings
            // are flushed per buffer precisely so a killed process leaves a readable file, but
            // the one orphan that loses the race still has to fail quietly.
            stop()
            false
        }
    }

    fun pause() = runCatching { player?.pause() }.let {}

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingId = null
    }

    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun durationMs(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)
}
