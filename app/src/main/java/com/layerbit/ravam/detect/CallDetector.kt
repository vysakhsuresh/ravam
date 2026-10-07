package com.layerbit.ravam.detect

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Notices that a call has started, carrier or app.
 *
 * Two signals, because the two kinds of call are invisible to each other:
 *
 * **Carrier** — telephony reports it directly and reliably.
 *
 * **VoIP** — BOTIM, WhatsApp and the rest are just apps; nothing announces them. But a
 * voice call has to put the audio system into `MODE_IN_COMMUNICATION` to get echo
 * cancellation and the earpiece route, and that mode change is observable. It is the
 * most reliable VoIP signal available without the accessibility API, and unlike watching
 * notifications it cannot be defeated by a user who silences the app.
 *
 * Note `CallScreeningService` is deliberately **not** used: it only sees `tel:` calls,
 * and only from numbers *not* in the user's contacts unless granted READ_CONTACTS — so
 * it is useless for VoIP and would miss exactly the people someone calls most.
 */
class CallDetector(private val context: Context) {

    enum class Kind { CARRIER, VOIP }

    data class Event(val kind: Kind, val active: Boolean)

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var listener: AudioManager.OnModeChangedListener? = null
    private var lastVoipActive = false

    /**
     * Start watching. [onEvent] fires on each transition, never repeatedly for one state.
     *
     * Mode listening needs API 31. Below that the caller falls back to polling, which is
     * why [currentMode] is exposed.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun startVoipWatch(onEvent: (Event) -> Unit) {
        stopVoipWatch()
        val l = AudioManager.OnModeChangedListener { mode ->
            val active = mode == AudioManager.MODE_IN_COMMUNICATION
            if (active != lastVoipActive) {
                lastVoipActive = active
                onEvent(Event(Kind.VOIP, active))
            }
        }
        listener = l
        audioManager.addOnModeChangedListener(context.mainExecutor, l)
    }

    fun stopVoipWatch() {
        listener?.let { runCatching { audioManager.removeOnModeChangedListener(it) } }
        listener = null
    }

    val currentMode: Int get() = audioManager.mode

    /** True when a carrier call is up — `MODE_IN_CALL` is set by the telephony stack. */
    val carrierCallActive: Boolean get() = audioManager.mode == AudioManager.MODE_IN_CALL

    /** True when some app holds a voice call. */
    val voipCallActive: Boolean get() = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    /**
     * Which app is on the call, when that can be worked out.
     *
     * Android anonymises other apps' recording configurations — the UID is replaced with
     * -1 and the package name blanked — so the audio system will confirm *that* a
     * privacy-sensitive capture is live without saying whose it is. Naming the app needs
     * a different signal; until that exists, a recording is labelled by kind, not by app.
     */
    fun someoneElseIsCapturing(): Boolean = runCatching {
        audioManager.activeRecordingConfigurations.any {
            it.clientAudioSource == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }
    }.getOrDefault(false)
}
