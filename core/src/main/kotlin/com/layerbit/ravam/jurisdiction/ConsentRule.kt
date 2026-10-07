package com.layerbit.ravam.jurisdiction

/**
 * What a place requires before a call may be recorded.
 *
 * Only three values, and the middle one is load-bearing. Most of the world's recording
 * law is either unsettled, varies by sub-region (US states, Australian states), or we
 * simply have not verified it — and treating "I don't know" as "go ahead" is how an app
 * walks a user into an offence. So the honest default is [UNCLEAR], which behaves like
 * [ALL_PARTY]: ask first.
 */
enum class ConsentRule {
    /** A participant may generally record their own call. India, UK. */
    ONE_PARTY,

    /** Everyone on the call must agree first. UAE, Saudi Arabia, much of the EU. */
    ALL_PARTY,

    /** Not verified, or varies within the country. Treated as [ALL_PARTY] for safety. */
    UNCLEAR;

    /** Does the safe default here mean "ask the user first"? */
    val requiresCaution: Boolean get() = this != ONE_PARTY
}

/**
 * How Ravam behaves by default in a given place. Never a hard block — the user can always
 * override per call. See PLAN.md §6.
 */
enum class RecordingMode {
    /** Record automatically. The default where a participant may lawfully record. */
    AUTO,

    /** Ask before each recording, with the local rule shown plainly. */
    ASK,

    /** Off by default; the user turns it on deliberately. Reserved for an explicit opt-out. */
    OFF;

    companion object {
        fun forRule(rule: ConsentRule): RecordingMode =
            if (rule == ConsentRule.ONE_PARTY) AUTO else ASK
    }
}
