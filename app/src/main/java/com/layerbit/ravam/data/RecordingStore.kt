package com.layerbit.ravam.data

import android.content.Context
import android.media.MediaMetadataRetriever
import com.layerbit.ravam.audio.Voices
import com.layerbit.ravam.domain.Recording
import com.layerbit.ravam.service.RecordingService
import org.json.JSONObject
import java.io.File

/**
 * Lists and reads recordings from the app-private directory.
 *
 * The directory is the single source of truth — there is no database in v1. A recording is
 * a `.wav` plus an optional `.json` sidecar carrying the verdict and channel. Keeping it to
 * files means a recording is complete the moment it is written, survives an uninstall-proof
 * export by simple copy, and can never drift out of sync with a separate index.
 *
 * A durable, queryable store is a later step — and deliberately so, because that is where
 * the consent design has consequences (PLAN.md §6), and a schema frozen before it is
 * settled risks baking in the very thing that section exists to prevent.
 */
class RecordingStore(private val context: Context) {

    private val dir: File get() = RecordingService.recordingsDir(context)

    fun list(): List<Recording> =
        dir.listFiles { f -> f.extension == "wav" }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.epochMillis }
            ?: emptyList()

    private fun read(wav: File): Recording? {
        val sidecar = File(dir, wav.nameWithoutExtension + ".json")
        val meta = runCatching { if (sidecar.exists()) JSONObject(sidecar.readText()) else null }
            .getOrNull()

        val durationMs = meta?.optInt("durationMs", -1)?.takeIf { it >= 0 }
            ?: durationFromFile(wav)

        return Recording(
            file = wav,
            displayName = meta?.optString("name")?.ifBlank { null } ?: wav.nameWithoutExtension,
            epochMillis = meta?.optLong("epochMillis", 0L)?.takeIf { it > 0 } ?: wav.lastModified(),
            durationMs = durationMs,
            sizeBytes = wav.length(),
            voices = runCatching { Voices.valueOf(meta?.optString("voices") ?: "") }
                .getOrDefault(Voices.INCONCLUSIVE),
            channel = meta?.optString("channel")?.ifBlank { null } ?: "Phone",
            starred = meta?.optBoolean("starred", false) ?: false,
        )
    }

    fun delete(recording: Recording): Boolean {
        File(dir, recording.file.nameWithoutExtension + ".json").delete()
        return recording.file.delete()
    }

    fun setStarred(recording: Recording, starred: Boolean) {
        val sidecar = File(dir, recording.file.nameWithoutExtension + ".json")
        val json = runCatching { if (sidecar.exists()) JSONObject(sidecar.readText()) else JSONObject() }
            .getOrDefault(JSONObject())
        json.put("starred", starred)
        runCatching { sidecar.writeText(json.toString()) }
    }

    private fun durationFromFile(wav: File): Int = runCatching {
        MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(wav.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        }
    }.getOrDefault(0)

    companion object {
        /** Written beside each recording as it finishes. Human-readable; no legal flags. */
        fun writeSidecar(
            context: Context,
            wav: File,
            name: String,
            durationMs: Int,
            voices: Voices,
            channel: String,
        ) {
            val sidecar = File(wav.parentFile, wav.nameWithoutExtension + ".json")
            val json = JSONObject()
                .put("name", name)
                .put("epochMillis", wav.lastModified())
                .put("durationMs", durationMs)
                .put("voices", voices.name)
                .put("channel", channel)
            runCatching { sidecar.writeText(json.toString()) }
        }
    }
}
