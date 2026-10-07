package com.layerbit.ravam.jurisdiction

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import com.layerbit.ravam.jurisdiction.JurisdictionResolver.Signal
import com.layerbit.ravam.jurisdiction.JurisdictionResolver.SignalSource
import java.util.Locale

/**
 * Gathers the location signals the resolver needs, from the SIM and the dialled number —
 * never from GPS. Ravam requests no location permission, and this is why it does not need
 * one: where you are is inferred from the telephony stack and the number on the call, both
 * of which the app can already see.
 */
class JurisdictionReader(private val context: Context) {

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /**
     * Build the signal set for a call.
     *
     * @param dialledNumber the number being called, if known. A VoIP call often has none,
     *   in which case location falls back to the SIM and the UI says the other party's
     *   country is unknown.
     */
    fun signalsFor(dialledNumber: String? = null): List<Signal> = buildList {
        add(Signal(SignalSource.SIM_NETWORK, tm?.networkCountryIso?.ifBlank { null }))
        add(Signal(SignalSource.SIM_HOME, tm?.simCountryIso?.ifBlank { null }))
        add(Signal(SignalSource.DIALLED_NUMBER, countryOfNumber(dialledNumber)))
        add(Signal(SignalSource.DEVICE_REGION, deviceRegion()))
    }

    fun resolveFor(dialledNumber: String? = null): JurisdictionResolver.Resolution =
        JurisdictionResolver.resolve(signalsFor(dialledNumber))

    private fun deviceRegion(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]?.country
        } else {
            @Suppress("DEPRECATION") context.resources.configuration.locale?.country
        }?.ifBlank { null }

    /**
     * Country of an international number from its calling code.
     *
     * A deliberately small prefix map rather than a full libphonenumber dependency — it
     * resolves exactly the codes Ravam has verified law for, which is all the resolver can
     * act on anyway. Unknown prefixes return null and the number simply does not vote.
     */
    private fun countryOfNumber(number: String?): String? {
        val n = number?.filter { it.isDigit() || it == '+' } ?: return null
        if (!n.startsWith("+")) return null   // only an explicit country code tells us anything
        val digits = n.drop(1)
        return CALLING_CODES.entries.firstOrNull { digits.startsWith(it.key) }?.value
    }

    private companion object {
        // Longest-prefix-first so "971" matches before "9".
        val CALLING_CODES: Map<String, String> = linkedMapOf(
            "971" to "AE", "966" to "SA", "49" to "DE", "44" to "GB",
            "91" to "IN", "1" to "US",
        ).entries.sortedByDescending { it.key.length }.associate { it.toPair() }
    }
}
