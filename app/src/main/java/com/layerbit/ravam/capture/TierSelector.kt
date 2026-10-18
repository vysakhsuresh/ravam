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
     *   re-renders it; on a carrier call that re-rendered copy comes out of the phone's
     *   earpiece, is picked up by the phone's own microphone, and travels back up the
     *   uplink as a buzz the far party hears — so it is excluded for carrier calls on the
     *   handset, which fall to the accessibility/mic path instead.
     * @param onBluetooth true when the call's audio is on a Bluetooth headset. This is the
     *   one case where the privileged tier is safe on a carrier call: the uplink is the
     *   headset's microphone, not the phone's, so the re-rendered copy is never picked up
     *   and never buzzes the far party. It is also the only way to reach the far end there,
     *   because on Bluetooth nothing leaks into the phone mic for the accessibility tier to
     *   capture. (This is exactly why an app call over Bluetooth already works.)
     */
    fun select(forVoip: Boolean = true, onBluetooth: Boolean = false): Selection {
        val allowPrivileged = forVoip || onBluetooth
        val ladder = probe.tiers().sortedBy { it.tier.rank }
            .filter { allowPrivileged || it.tier != CaptureTier.PRIVILEGED_SHELL }
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
