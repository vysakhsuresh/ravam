package com.layerbit.ravam.capture

/**
 * How Ravam might get at call audio on this handset, best first.
 *
 * Four routes exist on Android and they are not interchangeable — they differ in what
 * they can capture, what they cost the user, and how likely they are to survive the
 * next platform release. The app picks the best one available **and says which one it
 * picked**, on screen, during every recording.
 *
 * Silently degrading from one tier to a worse one is the defining sin of this app
 * category. It is how a user ends up with ninety minutes of their own voice.
 */
enum class CaptureTier(
    val displayName: String,
    /** Rank for selection. Lower is better. */
    val rank: Int,
) {
    /**
     * Root, or installed as a system app.
     *
     * Holds `CAPTURE_AUDIO_OUTPUT` outright, so `VOICE_CALL`, `VOICE_UPLINK` and
     * `VOICE_DOWNLINK` are all available and uplink/downlink arrive genuinely separated.
     * The best audio in every respect, available to the fewest people.
     */
    PRIVILEGED_ROOT("Root", 0),

    /**
     * Shell UID, reached over Shizuku or an on-device ADB pairing.
     *
     * `com.android.shell` is whitelisted for `CAPTURE_AUDIO_OUTPUT`,
     * `CAPTURE_VOICE_COMMUNICATION_OUTPUT` (added in Android 14), `CAPTURE_MEDIA_OUTPUT`,
     * `CALL_AUDIO_INTERCEPTION` and `MODIFY_AUDIO_ROUTING`.
     *
     * `CAPTURE_VOICE_COMMUNICATION_OUTPUT` is the one permission that legitimately
     * unlocks VoIP far-end capture, through a dynamic `AudioPolicy` whose mixing rule
     * sets `voiceCommunicationCaptureAllowed(true)` and `allowPrivilegedPlaybackCapture(true)`.
     * The latter overrides an app's own opt-out.
     *
     * Deterministic where it works, and no speakerphone required. Costs the user a
     * pairing step, and on most devices has to be re-armed after a reboot.
     *
     * **Unproven for BOTIM.** This is spike E2 and nothing here should be trusted until
     * it has run on real hardware. See PLAN.md.
     */
    PRIVILEGED_SHELL("Shizuku / ADB", 1),

    /**
     * A bound AccessibilityService recording on `VOICE_RECOGNITION`.
     *
     * AOSP's audio policy contains an explicit carve-out: an accessibility UID in
     * process state TOP..BOUND_FOREGROUND_SERVICE, capturing on `VOICE_RECOGNITION`
     * or `HOTWORD`, is never silenced — not during a cellular call, not while a VoIP
     * app holds a privacy-sensitive capture. Confirmed present and semantically
     * unchanged from Android 11 through 17.
     *
     * But the carve-out only stops the *framework* muting us. It does not route the far
     * end into the buffer; that is the audio HAL's business and it varies by chipset.
     * On many phones the far side arrives only as earpiece leakage, or not at all —
     * which is exactly why [com.layerbit.ravam.audio.TwoVoiceAnalyzer] exists.
     *
     * Two open risks: Android 14's while-in-use rule may block starting a microphone
     * foreground service for an *incoming* call (spike E3), and Android 17's Advanced
     * Protection can revoke accessibility outright.
     */
    ACCESSIBILITY("Accessibility", 2),

    /**
     * Plain microphone, speakerphone on.
     *
     * Always available, needs no privilege, and sounds like what it is. Offered only as
     * an explicit last resort, never selected silently, and always labelled.
     */
    SPEAKERPHONE("Speakerphone", 3),
}

/** Whether a tier can be used right now, and if not, what the user would have to do. */
sealed interface TierStatus {
    data object Ready : TierStatus
    /** Present but needs a user action first. [action] is shown verbatim in the UI. */
    data class NeedsSetup(val action: String) : TierStatus
    /** Not possible on this device or OS version. */
    data class Unavailable(val reason: String) : TierStatus
}

data class TierAvailability(val tier: CaptureTier, val status: TierStatus) {
    val isReady: Boolean get() = status is TierStatus.Ready
}

/**
 * What a tier turned out to actually do on this handset, per target app.
 *
 * Ravam never claims a capability it has not measured. Every entry here is the result
 * of a real capture, not a guess from the model name.
 */
data class CaptureCapability(
    val tier: CaptureTier,
    /** `null` for carrier calls; otherwise the VoIP package tested. */
    val targetPackage: String?,
    val targetLabel: String,
    val result: Result,
) {
    enum class Result {
        /** Both sides measured. */
        BOTH_SIDES,
        /** Capture worked, far side absent. */
        LOCAL_ONLY,
        /** Capture produced digital silence — the framework muted us. */
        SILENCED,
        /** Could not open the audio source at all. */
        FAILED,
        /** Not tested yet. */
        UNTESTED,
    }
}
