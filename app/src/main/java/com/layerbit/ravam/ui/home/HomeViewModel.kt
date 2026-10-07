package com.layerbit.ravam.ui.home

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.layerbit.ravam.capture.CaptureTier
import com.layerbit.ravam.capture.TierSelector
import com.layerbit.ravam.consent.ConsentController
import com.layerbit.ravam.jurisdiction.JurisdictionReader
import com.layerbit.ravam.jurisdiction.JurisdictionResolver
import com.layerbit.ravam.service.RecordingLog
import com.layerbit.ravam.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SetupItem(val label: String, val ready: Boolean, val action: String?)

data class HomeState(
    val tier: CaptureTier = CaptureTier.ACCESSIBILITY,
    val tierReady: Boolean = false,
    val tierSetupAction: String? = null,
    val setup: List<SetupItem> = emptyList(),
    val allReady: Boolean = false,
    val jurisdiction: JurisdictionResolver.Resolution? = null,
    val isRecording: Boolean = false,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val selector = TierSelector(app)
    private val reader = JurisdictionReader(app)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    val recordingLog = RecordingLog.last

    fun refresh() {
        val app = getApplication<Application>()
        val sel = selector.select()

        val mic = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notif = android.os.Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val phone = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val a11y = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().contains(app.packageName)
        }.getOrDefault(false)

        val setup = listOf(
            SetupItem("Microphone", mic, if (mic) null else "Allow Ravam to use the microphone"),
            SetupItem("Notifications", notif, if (notif) null else "Allow Ravam to show a recording notice"),
            SetupItem("Phone state", phone, if (phone) null else "Allow Ravam to notice when a call starts"),
            SetupItem(
                "Call audio (Accessibility)", a11y,
                if (a11y) null else "Turn Ravam on in Accessibility — the setting that lets it hear a call",
            ),
        )

        _state.value = HomeState(
            tier = sel.tier,
            tierReady = sel.ready,
            tierSetupAction = sel.setupAction,
            setup = setup,
            allReady = setup.all { it.ready },
            jurisdiction = reader.resolveFor(null),
            isRecording = _state.value.isRecording,
        )
    }

    /** The prompt to show before a manual test recording, or null if none is needed here. */
    fun consentPrompt(): ConsentController.Prompt? {
        val res = _state.value.jurisdiction ?: return null
        return if (ConsentController.needsPrompt(res)) ConsentController.promptFor(res) else null
    }

    fun startManualRecording() {
        RecordingService.start(getApplication(), _state.value.tier, "test-${nowLabel()}")
        _state.value = _state.value.copy(isRecording = true)
    }

    fun stopRecording() {
        RecordingService.stop(getApplication())
        _state.value = _state.value.copy(isRecording = false)
    }

    private fun nowLabel(): String {
        // No Date.now in the shared module, but the app side may use it freely.
        val d = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return d.format(java.util.Date())
    }
}
