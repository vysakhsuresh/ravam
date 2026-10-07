package com.layerbit.ravam.data

import android.media.MediaPlayer
import java.io.File

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

    fun toggle(recording: File, id: String, onComplete: () -> Unit) {
        if (playingId == id && isPlaying) { pause(); return }
        if (playingId == id && player != null) { player?.start(); return }

        stop()
        player = MediaPlayer().apply {
            setDataSource(recording.absolutePath)
            setOnCompletionListener { this@AudioPlayer.stop(); onComplete() }
            prepare()
            start()
        }
        playingId = id
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
