package com.layerbit.ravam.data

import android.content.Context

/**
 * Deletes recordings past the keep-for window the user chose. Starred recordings are never
 * touched — a call worth keeping is kept regardless of age.
 *
 * Runs on a refresh and after boot: there is no background scheduler and, with no INTERNET
 * permission, nothing to schedule one with. Housekeeping happens when the app is looked at,
 * which is enough for a local-only tool and costs nothing while it sits idle.
 */
object RetentionCleaner {

    fun apply(context: Context, settings: SettingsStore) {
        val days = settings.retentionDays
        if (days <= 0) return // keep forever

        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        val store = RecordingStore(context)
        store.list()
            .filter { !it.starred && it.epochMillis in 1 until cutoff }
            .forEach { runCatching { store.delete(it) } }
    }
}
