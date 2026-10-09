package com.layerbit.ravam.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.layerbit.ravam.data.RecordingStore

/**
 * Runs once after the phone restarts.
 *
 * The phone-state receiver is manifest-registered, so it re-arms itself on boot with nothing
 * to do here. What this handles is housekeeping the app could not do while powered off:
 * recovering any recording a crash or shutdown left as a raw .pcm, so a call interrupted by a
 * dying battery still becomes a playable file.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val dir = com.layerbit.ravam.service.RecordingService.recordingsDir(context)
        runCatching { com.layerbit.ravam.audio.PcmSink.recoverOrphans(dir, 16_000) }
        // Touch the store so a first launch after boot sees recovered files.
        runCatching { RecordingStore(context).list() }
    }
}
