package com.layerbit.ravam.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.layerbit.ravam.capture.TierSelector
import com.layerbit.ravam.consent.RecordingGate
import com.layerbit.ravam.data.SettingsStore
import com.layerbit.ravam.service.RecordingService

/**
 * The thing that makes Ravam a *call* recorder: it starts recording when a carrier call
 * begins and stops when it ends. Registered in the manifest, so it runs whether or not the
 * app is open — that is the whole point, and the piece that was missing before.
 *
 * Call state is a three-step dance and the transitions, not the states, carry the meaning:
 *   IDLE → RINGING              an incoming call is arriving (not yet answered)
 *   RINGING → OFFHOOK           the incoming call was answered  → start (incoming)
 *   IDLE → OFFHOOK              an outgoing call was placed      → start (outgoing)
 *   OFFHOOK/RINGING → IDLE      the call ended                   → stop
 *
 * Whether a given call is actually recorded is not decided here — [RecordingGate] applies the
 * user's settings, the per-contact skip list, and the jurisdiction default. This receiver only
 * reports that a call happened.
 */
class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                lastRingingNumber = number
                lastState = Call.RINGING
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == Call.ACTIVE) return // already recording this call
                val direction =
                    if (lastState == Call.RINGING) CallInfo.Direction.INCOMING
                    else CallInfo.Direction.OUTGOING
                lastState = Call.ACTIVE
                onCallStarted(context, number ?: lastRingingNumber, direction)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == Call.ACTIVE) RecordingService.stop(context)
                lastState = Call.IDLE
                lastRingingNumber = null
            }
        }
    }

    private fun onCallStarted(context: Context, number: String?, direction: CallInfo.Direction) {
        val settings = SettingsStore(context)
        val info = CallInfo(
            channel = "Phone",
            direction = direction,
            number = number,
            contactName = ContactLookup.nameFor(context, number),
        )

        val decision = RecordingGate.decide(context, settings, info)
        if (!decision.record) return

        val tier = TierSelector(context).select().tier
        RecordingService.start(context, tier, info.label(), channel = "Phone")
    }

    private enum class Call { IDLE, RINGING, ACTIVE }

    private companion object {
        // Static because the system may deliver the sequence to fresh receiver instances.
        var lastState: Call = Call.IDLE
        var lastRingingNumber: String? = null
    }
}
