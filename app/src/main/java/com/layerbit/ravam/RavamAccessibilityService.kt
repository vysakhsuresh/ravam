package com.layerbit.ravam

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.layerbit.ravam.capture.TierSelector
import com.layerbit.ravam.consent.RecordingGate
import com.layerbit.ravam.data.SettingsStore
import com.layerbit.ravam.detect.CallInfo
import com.layerbit.ravam.service.RecordingService

/**
 * Two jobs, and neither reads the screen's contents.
 *
 * 1. **Registers this UID as an accessibility UID.** That is what AOSP's audio policy keys its
 *    call-capture exemption on — the only route to call audio without root or a shell binding.
 *
 * 2. **Hosts the VoIP watcher.** A bound accessibility service is always running while enabled,
 *    which makes it the natural, battery-free place to notice an app call starting. A voice call
 *    forces the audio system into MODE_IN_COMMUNICATION; that transition is observable without
 *    reading a single pixel. When it happens, Ravam starts recording; when it clears, it stops.
 *
 * The one thing it reads about the foreground app is its **package name** — purely to label the
 * recording "BOTIM" vs "WhatsApp". No window content, no text, nothing the user typed or saw.
 *
 * The hard caveat (spike E3): starting a microphone foreground service from the background may be
 * blocked on Android 14+. If it is, VoIP auto-capture will fail to start and the live monitor will
 * report it rather than leaving a silent file. That is a device-verified unknown, not a promise.
 */
class RavamAccessibilityService : AccessibilityService() {

    private var audioManager: AudioManager? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private var voipActive = false
    private var lastForegroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The audio-mode listener is the VoIP trigger, and it needs Android 12 (API 31).
        // Below that, the accessibility UID still earns the audio-policy exemption, so carrier
        // capture works; only automatic VoIP start is unavailable, which the UI can reflect.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val listener = AudioManager.OnModeChangedListener { mode ->
            val active = mode == AudioManager.MODE_IN_COMMUNICATION
            if (active && !voipActive) {
                voipActive = true
                onVoipCallStarted()
            } else if (!active && voipActive) {
                voipActive = false
                RecordingService.stop(this)
            }
        }
        modeListener = listener
        runCatching { am.addOnModeChangedListener(mainExecutor, listener) }
    }

    /** Track only the foreground app's package, to label which messenger the call is on. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in VOIP_PACKAGES) lastForegroundPackage = pkg
    }

    override fun onInterrupt() { /* nothing */ }

    override fun onDestroy() {
        modeListener?.let { l -> runCatching { audioManager?.removeOnModeChangedListener(l) } }
        super.onDestroy()
    }

    private fun onVoipCallStarted() {
        val settings = SettingsStore(this)
        val channel = VOIP_PACKAGES[lastForegroundPackage] ?: "App call"
        val info = CallInfo(
            channel = channel,
            direction = CallInfo.Direction.UNKNOWN, // VoIP rarely exposes a number or direction
            number = null,
            contactName = null,
        )
        val decision = RecordingGate.decide(this, settings, info)
        if (!decision.record) return

        // An app (VoIP) call: the privileged voice-communication tier is allowed here.
        val tier = TierSelector(this).select(forVoip = true).tier
        RecordingService.start(this, tier, info.label(), channel = channel)
    }

    private companion object {
        // Labels only. Recording triggers off the audio mode, so a call on an app not in
        // this map still records — it is just labelled "App call". The list is wide on
        // purpose: the privileged tier captures any voice-communication app by usage, so
        // coverage should not be bottlenecked by a short hard-coded list.
        val VOIP_PACKAGES = mapOf(
            "im.thebot.messenger" to "BOTIM",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.thoughtcrime.securesms" to "Signal",
            "com.microsoft.teams" to "Teams",
            "com.microsoft.teams.personal" to "Teams",
            "us.zoom.videomeetings" to "Zoom",
            "com.google.android.apps.tachyon" to "Google Meet",
            "com.google.android.gm" to "Google Meet",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.instagram.android" to "Instagram",
            "com.discord" to "Discord",
            "com.skype.raider" to "Skype",
            "com.viber.voip" to "Viber",
            "jp.naver.line.android" to "LINE",
            "com.tencent.mm" to "WeChat",
            "com.snapchat.android" to "Snapchat",
            "com.imo.android.imoim" to "imo",
            "com.imo.android.imoimbeta" to "imo",
            "com.google.android.apps.dynamite" to "Google Chat",
            "com.slack" to "Slack",
            "com.truecaller" to "Truecaller",
            "com.bria.voip" to "Bria",
            "com.visettel.mocha" to "Mocha",
            "com.botim.bot" to "BOTIM",
        )
    }
}
