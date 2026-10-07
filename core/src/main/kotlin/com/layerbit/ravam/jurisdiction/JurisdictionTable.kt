package com.layerbit.ravam.jurisdiction

/**
 * Country (ISO 3166-1 alpha-2) → consent rule.
 *
 * Deliberately short. An entry is here only when the rule has actually been researched;
 * everything else resolves to [ConsentRule.UNCLEAR], which is treated as all-party. A
 * long table of half-remembered law would be worse than a short honest one, because each
 * wrong "one-party" entry is a user auto-recorded into an offence.
 *
 * Expand this only with a verified source noted in docs/legal.md. It is data, not logic,
 * so adding a country never touches the resolver.
 */
object JurisdictionTable {

    private val RULES: Map<String, ConsentRule> = mapOf(
        // One-party — a participant may record their own call. Researched.
        "IN" to ConsentRule.ONE_PARTY,   // India — R.M. Malkani; reaffirmed 2025 (nuance in docs/legal.md)
        "GB" to ConsentRule.ONE_PARTY,   // United Kingdom — personal use

        // All-party — everyone must agree. Researched.
        "AE" to ConsentRule.ALL_PARTY,   // UAE — Federal Decree-Law 34/2021 Article 44
        "SA" to ConsentRule.ALL_PARTY,   // Saudi Arabia — shipped all-party deliberately (PLAN.md §6)
        "DE" to ConsentRule.ALL_PARTY,   // Germany
        "FR" to ConsentRule.ALL_PARTY,   // France

        // The United States is one-party federally but eleven-ish states require all
        // parties, and ISO country alone cannot tell them apart. Unclear is the honest,
        // safe answer — it asks rather than assumes.
        "US" to ConsentRule.UNCLEAR,
    )

    /** The rule for [iso2], or [ConsentRule.UNCLEAR] for anywhere not verified. */
    fun ruleFor(iso2: String?): ConsentRule {
        val key = iso2?.trim()?.uppercase() ?: return ConsentRule.UNCLEAR
        return RULES[key] ?: ConsentRule.UNCLEAR
    }

    /** Whether we hold a verified rule for this country at all (for honest UI wording). */
    fun isVerified(iso2: String?): Boolean =
        iso2?.trim()?.uppercase()?.let { RULES.containsKey(it) } ?: false
}
