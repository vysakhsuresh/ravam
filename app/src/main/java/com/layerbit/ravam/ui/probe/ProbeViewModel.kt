package com.layerbit.ravam.ui.probe

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.ravam.audio.TwoVoiceAnalyzer
import com.layerbit.ravam.audio.VoiceVerdict
import com.layerbit.ravam.capture.TierAvailability
import com.layerbit.ravam.probe.CaptureTestResult
import com.layerbit.ravam.probe.DeviceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourceResult(
    val sourceName: String,
    val summary: String,
    val verdict: VoiceVerdict?,
)

data class ProbeState(
    val running: Boolean = false,
    val tiers: List<TierAvailability> = emptyList(),
    val results: List<SourceResult> = emptyList(),
    val voipApps: List<Pair<String, Boolean>> = emptyList(),
)

class ProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val probe = DeviceProbe(app)

    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state.asStateFlow()

    /** VoIP targets Ravam cares about. BOTIM leads, because it is the one the Gulf uses
     *  and the one no other recorder has ever tested. */
    private val targets = listOf(
        "BOTIM" to "im.thebot.messenger",
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        "Signal" to "org.thoughtcrime.securesms",
    )

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(
            tiers = probe.tiers(),
            voipApps = targets.map { (label, pkg) -> label to probe.isInstalled(pkg) },
        )
    }

    /**
     * Try every audio source and measure what each returns.
     *
     * Run once with no call in progress for a baseline, and again during a real call —
     * the gap between those two answers is the whole story on most handsets.
     */
    fun runCaptureTests() {
        if (_state.value.running) return
        _state.value = _state.value.copy(running = true, results = emptyList())

        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                DeviceProbe.SOURCES.map { (name, source) ->
                    when (val r = probe.captureTest(source)) {
                        is CaptureTestResult.Captured -> {
                            val v = TwoVoiceAnalyzer.analyzeMono(r.samples, r.sampleRateHz)
                            SourceResult(name, v.basis, v)
                        }
                        is CaptureTestResult.Silenced -> SourceResult(
                            name,
                            if (r.frameworkConfirmed) {
                                "Android silenced this stream — it reports the capture as muted"
                            } else {
                                "Opened, but every sample came back as silence"
                            },
                            null,
                        )
                        is CaptureTestResult.Failed -> SourceResult(name, r.reason, null)
                    }
                }
            }
            _state.value = _state.value.copy(running = false, results = results)
        }
    }
}
