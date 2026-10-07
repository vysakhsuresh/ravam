package com.layerbit.ravam.consent

import com.layerbit.ravam.jurisdiction.ConsentRule
import com.layerbit.ravam.jurisdiction.JurisdictionResolver
import com.layerbit.ravam.jurisdiction.RecordingMode

/**
 * Turns a jurisdiction resolution into what the user actually sees and decides.
 *
 * This is where PLAN.md §6 is enforced in behaviour:
 *   - The app is never blocked. A resolution only sets a default mode.
 *   - Where caution is needed the user is warned in plain words and chooses — at their own
 *     informed risk — and nothing about that choice is written to disk.
 *   - The wording points the user at the law; it does not tell them they are safe. It is a
 *     tool, not a lawyer.
 */
object ConsentController {

    data class Prompt(
        val title: String,
        val body: String,
        val proceedLabel: String,
        val cancelLabel: String,
    )

    /** True when this resolution needs the user to confirm before recording. */
    fun needsPrompt(resolution: JurisdictionResolver.Resolution): Boolean =
        resolution.mode != RecordingMode.AUTO

    /**
     * The warning for an all-party or unclear place. Deliberately factual and local:
     * it names the country and what the law requires, and leaves the decision with the user.
     */
    fun promptFor(resolution: JurisdictionResolver.Resolution): Prompt {
        val where = resolution.decidingCountry?.let { countryName(it) }

        val body = when (resolution.rule) {
            ConsentRule.ALL_PARTY -> buildString {
                append("In ")
                append(where ?: "this region")
                append(", everyone on a call must agree before it is recorded. ")
                append("Recording without the other person's consent can be a criminal offence.\n\n")
                append("The safest way is to tell them at the start and let them answer — ")
                append("Ravam keeps that reply inside the recording.\n\n")
                append("This is not legal advice. Whether you record is your decision and your responsibility.")
            }
            ConsentRule.UNCLEAR -> buildString {
                append("The recording rules where you are")
                append(where?.let { " ($it)" } ?: "")
                append(" aren't settled — in some places everyone on the call must agree first.\n\n")
                append("If you're unsure, tell the other person you're recording and let them answer.\n\n")
                append("This is not legal advice. The decision, and the responsibility, are yours.")
            }
            ConsentRule.ONE_PARTY -> "" // no prompt in a one-party place
        }

        return Prompt(
            title = "Before you record",
            body = body,
            proceedLabel = "I understand — record",
            cancelLabel = "Not now",
        )
    }

    private fun countryName(iso2: String): String = when (iso2.uppercase()) {
        "AE" -> "the UAE"
        "SA" -> "Saudi Arabia"
        "DE" -> "Germany"
        "FR" -> "France"
        "US" -> "the United States"
        "IN" -> "India"
        "GB" -> "the UK"
        else -> iso2
    }
}
