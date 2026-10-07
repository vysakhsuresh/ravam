package com.layerbit.ravam.capture.shell

/**
 * The privileged VoIP path — spike E2 — kept behind a clean interface so the rest of the
 * app can be built and shipped before it lands.
 *
 * The plan (PLAN.md §2.3): a process running as the shell UID, reached over Shizuku or an
 * on-device ADB pairing, registers a dynamic AudioPolicy whose mixing rule sets
 * `voiceCommunicationCaptureAllowed(true)` and `allowPrivilegedPlaybackCapture(true)`.
 * `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, held by shell from Android 14, is what makes that
 * legal — it is the one documented route to a VoIP app's far-end audio without root.
 *
 * It is **not implemented here**, and this file does not pretend otherwise. Wiring it needs
 * the Shizuku client library and must be verified on real hardware against a real BOTIM
 * call — that is the experiment, not an assumption. Until then [isAvailable] is false and
 * the tier selector routes around it, honestly. Reporting a capability we have not measured
 * is the exact failure this whole app exists to avoid.
 */
interface ShellCapture {
    val isAvailable: Boolean
    fun describe(): String
}

/** The honest default until E2 is built and proven on a device. */
object ShellCaptureUnavailable : ShellCapture {
    override val isAvailable: Boolean = false
    override fun describe(): String =
        "The privileged VoIP path is not wired up yet. It needs a one-time Shizuku pairing " +
            "and has to be proven on real hardware first — see the roadmap."
}
