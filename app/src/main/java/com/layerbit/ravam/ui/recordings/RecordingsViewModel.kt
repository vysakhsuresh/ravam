package com.layerbit.ravam.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.layerbit.ravam.data.AudioPlayer
import com.layerbit.ravam.data.RecordingStore
import com.layerbit.ravam.domain.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingsState(
    val recordings: List<Recording> = emptyList(),
    val query: String = "",
    val playingId: String? = null,
)

class RecordingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RecordingStore(app)
    private val player = AudioPlayer()

    private val _state = MutableStateFlow(RecordingsState())
    val state: StateFlow<RecordingsState> = _state.asStateFlow()

    fun refresh() {
        _state.value = _state.value.copy(recordings = store.list())
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    val visible: List<Recording>
        get() {
            val q = _state.value.query.trim().lowercase()
            val all = _state.value.recordings
            return if (q.isEmpty()) all
            else all.filter { it.displayName.lowercase().contains(q) || it.channel.lowercase().contains(q) }
        }

    fun togglePlay(recording: Recording) {
        player.toggle(recording.file, recording.id) {
            _state.value = _state.value.copy(playingId = null)
        }
        _state.value = _state.value.copy(playingId = player.currentId)
    }

    fun toggleStar(recording: Recording) {
        store.setStarred(recording, !recording.starred)
        refresh()
    }

    fun delete(recording: Recording) {
        if (player.currentId == recording.id) player.stop()
        store.delete(recording)
        refresh()
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }
}
