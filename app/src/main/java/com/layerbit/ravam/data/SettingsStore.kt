package com.layerbit.ravam.data

import android.content.Context
import androidx.core.content.edit

/**
 * Every user-facing preference, in one place, backed by SharedPreferences.
 *
 * What is deliberately NOT here: anything recording a legal decision. `termsAccepted` is a
 * neutral one-time EULA flag with no per-call semantics — it says the user has seen the
 * terms, nothing about any individual recording. The per-recording "accepted the risk" flag
 * that PLAN.md §6 forbids is still forbidden and lives nowhere.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("ravam_settings", Context.MODE_PRIVATE)

    // --- first run ---------------------------------------------------------
    var termsAccepted: Boolean
        get() = prefs.getBoolean(TERMS, false)
        set(v) = prefs.edit { putBoolean(TERMS, v) }

    // --- auto-record -------------------------------------------------------
    /** Master switch. When off, nothing records automatically; manual still works. */
    var autoRecord: Boolean
        get() = prefs.getBoolean(AUTO, true)
        set(v) = prefs.edit { putBoolean(AUTO, v) }

    var recordIncoming: Boolean
        get() = prefs.getBoolean(INCOMING, true)
        set(v) = prefs.edit { putBoolean(INCOMING, v) }

    var recordOutgoing: Boolean
        get() = prefs.getBoolean(OUTGOING, true)
        set(v) = prefs.edit { putBoolean(OUTGOING, v) }

    /** Record app calls (BOTIM, WhatsApp, …) too. Opt-in, experimental. */
    var recordVoip: Boolean
        get() = prefs.getBoolean(VOIP, false)
        set(v) = prefs.edit { putBoolean(VOIP, v) }

    /** Contacts the user never wants recorded — a per-contact skip list, stored by number. */
    var excludedNumbers: Set<String>
        get() = prefs.getStringSet(EXCLUDED, emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet(EXCLUDED, v) }

    fun isExcluded(number: String?): Boolean {
        val n = number?.filter { it.isDigit() } ?: return false
        return excludedNumbers.any { it.filter { c -> c.isDigit() }.endsWith(n.takeLast(8)) && n.isNotEmpty() }
    }

    fun setExcluded(number: String, excluded: Boolean) {
        val set = excludedNumbers.toMutableSet()
        if (excluded) set.add(number) else set.remove(number)
        excludedNumbers = set
    }

    // --- storage -----------------------------------------------------------
    /** Delete recordings older than this many days. 0 = keep forever. Starred are never auto-deleted. */
    var retentionDays: Int
        get() = prefs.getInt(RETENTION, 0)
        set(v) = prefs.edit { putInt(RETENTION, v) }

    private companion object {
        const val TERMS = "terms_accepted"
        const val AUTO = "auto_record"
        const val INCOMING = "record_incoming"
        const val OUTGOING = "record_outgoing"
        const val VOIP = "record_voip"
        const val EXCLUDED = "excluded_numbers"
        const val RETENTION = "retention_days"
    }
}
