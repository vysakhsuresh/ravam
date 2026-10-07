package com.layerbit.ravam.service

import com.layerbit.ravam.capture.engine.CallRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The outcome of recordings made this session, in memory only.
 *
 * Deliberately not persisted yet. The durable store is where the consent design has
 * consequences — see PLAN.md §6 — and writing a schema before that is settled risks
 * baking in exactly the thing that section exists to prevent.
 */
object RecordingLog {
    private val _last = MutableStateFlow<CallRecorder.Outcome?>(null)
    val last: StateFlow<CallRecorder.Outcome?> = _last.asStateFlow()

    fun record(outcome: CallRecorder.Outcome) { _last.value = outcome }
}
