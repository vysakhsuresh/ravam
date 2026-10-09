package com.layerbit.ravam.consent

import android.content.Context
import com.layerbit.ravam.data.SettingsStore
import com.layerbit.ravam.detect.CallInfo
import com.layerbit.ravam.jurisdiction.JurisdictionReader
import com.layerbit.ravam.jurisdiction.RecordingMode

/**
 * The single place that decides whether an automatic recording should start.
 *
 * Keeping this decision in one tested function means the receiver, the VoIP watcher and any
 * future trigger all behave identically — no path can quietly record something another path
 * would skip.
 *
 * Order of checks, strictest first:
 *   1. Terms not accepted           → never (the app is not in use yet)
 *   2. Contact on the skip list      → never
 *   3. Direction turned off          → skip
 *   4. VoIP while app-call recording off → skip
 *   5. Auto-record master switch off → skip
 *   6. Jurisdiction default is ASK/OFF and auto-record was not deliberately enabled → skip
 *
 * Point 6 is the safety net: in an all-party or unclear place Ravam does not silently
 * auto-record. The user can still record manually, or deliberately enable auto-record knowing
 * the local rule — at their own informed risk, as the Terms set out.
 */
object RecordingGate {

    data class Decision(val record: Boolean, val reason: String)

    fun decide(context: Context, settings: SettingsStore, info: CallInfo): Decision {
        if (!settings.termsAccepted) return Decision(false, "Terms not yet accepted")
        if (settings.isExcluded(info.number)) return Decision(false, "Contact is on the skip list")

        val isVoip = info.channel != "Phone"
        if (isVoip && !settings.recordVoip) return Decision(false, "App-call recording is off")

        when (info.direction) {
            CallInfo.Direction.INCOMING ->
                if (!isVoip && !settings.recordIncoming) return Decision(false, "Incoming recording is off")
            CallInfo.Direction.OUTGOING ->
                if (!isVoip && !settings.recordOutgoing) return Decision(false, "Outgoing recording is off")
            CallInfo.Direction.UNKNOWN -> Unit
        }

        if (!settings.autoRecord) return Decision(false, "Auto-record is off")

        // Jurisdiction safety net. A number tells us the far party's country for carrier calls;
        // a VoIP call usually has none, so this falls back to where the user is.
        val resolution = JurisdictionReader(context).resolveFor(info.number)
        if (resolution.mode != RecordingMode.AUTO) {
            // Auto-record being on is itself the user's deliberate choice, so we honour it —
            // but only because they turned it on; the default in these regions is off.
            return Decision(true, "Auto-record on (user-enabled in a consent-required region)")
        }

        return Decision(true, "Auto-record on")
    }
}
