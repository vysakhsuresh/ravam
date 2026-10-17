package com.layerbit.ravam.capture

import android.content.Context
import com.layerbit.ravam.probe.DeviceProbe

/**
 * Picks the best capture tier available on this device right now — and, crucially, says
 * which one it picked. Never silently downgrades.
 *
 * The ladder is fixed (root > shell > accessibility > speakerphone); this only chooses the
 * highest rung currently usable. When nothing better than speakerphone is ready, that is
 * surfaced to the user as a choice ("record one-sided now, or set up a better method"),
 * not slipped past them.
 */
class TierSelector(context: Context) {

    private val probe = DeviceProbe(context)

    data class Selection(
        val tier: CaptureTier,
        val ready: Boolean,
        /** If not ready, what the user must do — shown verbatim. */
        val setupAction: String?,
        /** Everything considered, best-first, for the UI. */
        val ladder: List<TierAvailability>,
    )

    /**
     * @param forVoip true for an app call, false for a carrier (telephony/VoLTE) call.
     *   The privileged Shizuku tier captures the voice-communication audio path and
     *   re-renders it; that belongs to VoIP calls only. On a carrier call it would inject
     *   a copy back into the live call — heard as a buzz by the far party — so it is
     *   excluded and the call falls to the accessibility/mic path, which never touches
     *   the call's own audio.
     */
    fun select(forVoip: Boolean = true): Selection {
        val ladder = probe.tiers().sortedBy { it.tier.rank }
            .filter { forVoip || it.tier != CaptureTier.PRIVILEGED_SHELL }
        val best = ladder.firstOrNull { it.isReady }

        if (best != null) {
            return Selection(best.tier, true, null, ladder)
        }

        // Nothing fully ready. Offer the top tier that only needs setup, so the user is
        // pointed at the best available next step rather than dropped to the floor.
        val needsSetup = ladder.firstOrNull { it.status is TierStatus.NeedsSetup }
        if (needsSetup != null) {
            return Selection(
                needsSetup.tier,
                false,
                (needsSetup.status as TierStatus.NeedsSetup).action,
                ladder,
            )
        }

        return Selection(CaptureTier.SPEAKERPHONE, false, "Allow the microphone to record", ladder)
    }
}
