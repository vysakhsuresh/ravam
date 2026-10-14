package com.layerbit.ravam.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.ravam.data.AudioPlayer
import com.layerbit.ravam.data.RecordingStore
import com.layerbit.ravam.data.RetentionCleaner
import com.layerbit.ravam.data.SettingsStore
import com.layerbit.ravam.domain.Recording
import com.layerbit.ravam.service.RecordingLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the player is doing right now, for the scrubber and the controls. */
data class Playback(
    val id: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

data class RecordingsState(
    val recordings: List<Recording> = emptyList(),
    val query: String = "",
    val playback: Playback = Playback(),
    /** Set when a recording could not be played, so the list can say so instead of ignoring the tap. */
    val playbackError: String? = null,
)

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RecordingStore(app)
    private val settings = SettingsStore(app)
    private val player = AudioPlayer()

    private val _state = MutableStateFlow(RecordingsState())
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    private var ticker: Job? = null

    init {
        // Live refresh: the moment a recording finishes — a call ends, or a manual test stops —
        // RecordingLog emits, and the list updates even while the user is looking at it. This is
        // the fix for "the new recording only appears after I close and reopen the app".
        viewModelScope.launch {
            RecordingLog.last.collect { outcome -> if (outcome != null) refresh() }
        }
    }

    fun refresh() {
        RetentionCleaner.apply(getApplication(), settings) // enforce the keep-for window
        _state.value = _state.value.copy(recordings = store.list())
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun clearPlaybackError() { _state.value = _state.value.copy(playbackError = null) }

    val visible: List<Recording>
        get() {
            val q = _state.value.query.trim().lowercase()
            val all = _state.value.recordings
            return if (q.isEmpty()) all
            else all.filter { it.displayName.lowercase().contains(q) || it.channel.lowercase().contains(q) }
        }

    // --- playback ----------------------------------------------------------

    fun togglePlay(recording: Recording) {
        val pb = _state.value.playback
        if (pb.id == recording.id) {
            val playing = player.togglePlayPause()
            _state.value = _state.value.copy(playback = pb.copy(isPlaying = playing))
            if (playing) startTicker()
            return
        }
        val started = player.play(recording.file, recording.id) { onCompleted() }
        if (!started) {
            _state.value = _state.value.copy(
                playback = Playback(),
                playbackError = "Couldn't play “${recording.displayName}”.",
            )
            return
        }
        _state.value = _state.value.copy(
            playback = Playback(recording.id, isPlaying = true, 0, player.durationMs()),
            playbackError = null,
        )
        startTicker()
    }

    fun seekTo(ms: Int) {
        player.seekTo(ms)
        _state.value = _state.value.copy(playback = _state.value.playback.copy(positionMs = ms))
    }

    /** Skip ±; the UI wires this to the rewind / forward buttons. */
    fun skip(deltaMs: Int) {
        player.skip(deltaMs)
        _state.value = _state.value.copy(playback = _state.value.playback.copy(positionMs = player.positionMs()))
    }

    fun toggleStar(recording: Recording) { store.setStarred(recording, !recording.starred); refresh() }

    fun delete(recording: Recording) {
        if (_state.value.playback.id == recording.id) stopPlayback()
        store.delete(recording)
        refresh()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive && player.isPlaying) {
                _state.value = _state.value.copy(
                    playback = _state.value.playback.copy(
                        positionMs = player.positionMs(),
                        durationMs = player.durationMs(),
                        isPlaying = true,
                    ),
                )
                delay(250)
            }
        }
    }

    private fun onCompleted() {
        ticker?.cancel()
        _state.value = _state.value.copy(
            playback = _state.value.playback.copy(isPlaying = false, positionMs = 0),
        )
        player.seekTo(0)
    }

    private fun stopPlayback() {
        ticker?.cancel()
        player.stop()
        _state.value = _state.value.copy(playback = Playback())
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }
}
