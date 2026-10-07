package com.layerbit.ravam.jurisdiction

/**
 * Works out which recording rule applies to a call, from whatever location signals exist.
 *
 * The hard case this exists for is a dual-SIM phone — a UAE SIM and an Indian SIM in one
 * device, physically in India, dialling a +971 number. No single signal is right: the
 * SIM's home country, the network it is on, and the number being called can all disagree,
 * and the person on the other end may be somewhere else again.
 *
 * So Ravam does not pick one signal. It takes **all** of them and applies the **strictest**
 * rule in the union. If any party to the call might be in an all-party place, the call is
 * treated as all-party. Over-caution costs the user a tap; under-caution can cost them a
 * prosecution, and those are not equal.
 *
 * Pure function of its inputs — no Android, no I/O — so every branch is unit-tested. Note
 * what it never takes: a location permission. The signals come from the SIM and the dialled
 * number, never from GPS.
 */
object JurisdictionResolver {

    /** Where a location hint came from, kept so the UI can explain itself honestly. */
    enum class SignalSource { SIM_NETWORK, SIM_HOME, DIALLED_NUMBER, DEVICE_REGION }

    data class Signal(val source: SignalSource, val iso2: String?)

    data class Resolution(
        val rule: ConsentRule,
        val mode: RecordingMode,
        /** The country whose rule decided this, for the warning text. Null if nothing known. */
        val decidingCountry: String?,
        /** Was the deciding rule a verified one, or the unclear default? */
        val verified: Boolean,
        /** Everything that fed the decision, for a transparent "why" in the UI. */
        val consideredSignals: List<Signal>,
    )

    /**
     * Resolve from the available signals. Any mix may be empty or null; nothing known at
     * all resolves to [ConsentRule.UNCLEAR] — ask, because we cannot say it is safe.
     */
    fun resolve(signals: List<Signal>): Resolution {
        val known = signals.filter { !it.iso2.isNullOrBlank() }
        if (known.isEmpty()) {
            return Resolution(ConsentRule.UNCLEAR, RecordingMode.ASK, null, false, signals)
        }

        // Strictest wins: ALL_PARTY beats UNCLEAR beats ONE_PARTY.
        var winner: Signal? = null
        var winnerRule = ConsentRule.ONE_PARTY
        for (s in known) {
            val rule = JurisdictionTable.ruleFor(s.iso2)
            if (strictnessOf(rule) >= strictnessOf(winnerRule)) {
                // >= so a real country with ONE_PARTY still names itself rather than
                // leaving decidingCountry null on an all-one-party call.
                if (winner == null || strictnessOf(rule) > strictnessOf(winnerRule)) {
                    winner = s
                    winnerRule = rule
                }
            }
        }

        val deciding = winner ?: known.first()
        return Resolution(
            rule = winnerRule,
            mode = RecordingMode.forRule(winnerRule),
            decidingCountry = deciding.iso2?.trim()?.uppercase(),
            verified = JurisdictionTable.isVerified(deciding.iso2),
            consideredSignals = signals,
        )
    }

    /** Convenience for the common four-signal call site. */
    fun resolve(
        simNetwork: String? = null,
        simHome: String? = null,
        dialledNumberCountry: String? = null,
        deviceRegion: String? = null,
    ): Resolution = resolve(
        listOf(
            Signal(SignalSource.SIM_NETWORK, simNetwork),
            Signal(SignalSource.SIM_HOME, simHome),
            Signal(SignalSource.DIALLED_NUMBER, dialledNumberCountry),
            Signal(SignalSource.DEVICE_REGION, deviceRegion),
        ),
    )

    private fun strictnessOf(rule: ConsentRule): Int = when (rule) {
        ConsentRule.ONE_PARTY -> 0
        ConsentRule.UNCLEAR -> 1
        ConsentRule.ALL_PARTY -> 2
    }
}
