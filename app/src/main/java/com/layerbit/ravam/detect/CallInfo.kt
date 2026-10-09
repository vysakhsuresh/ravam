package com.layerbit.ravam.detect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything known about a call at the moment it starts — enough to label the recording and
 * to decide whether, and how, to record it.
 */
data class CallInfo(
    val channel: String,          // "Phone", "BOTIM", "WhatsApp", …
    val direction: Direction,
    val number: String?,          // E.164 or raw, when the OS provides it
    val contactName: String?,     // resolved from contacts, if permission is granted
) {
    enum class Direction { INCOMING, OUTGOING, UNKNOWN }

    /** A human filename stem: "9 Oct 2026, 3:42 PM — Rahul (BOTIM)". No cryptic flags. */
    fun label(): String {
        val time = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        val who = contactName ?: number ?: "Unknown"
        return "$time — $who ($channel)"
    }
}

/** Resolves a contact name for a number, when READ_CONTACTS is granted. Best-effort, never fails. */
object ContactLookup {
    fun nameFor(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
