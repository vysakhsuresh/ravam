package com.layerbit.ravam.detect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.layerbit.ravam.capture.shell.EmbeddedAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets the pairing be triggered without a foreground UI, so the system's "Pair device with
 * pairing code" dialog can stay open (keeping its session alive) while Ravam pairs in the
 * background. Primarily a developer/driver hook:
 *
 *   adb shell am broadcast -a com.layerbit.ravam.PAIR_ADB --ei port <P> --es code <6digits> -n com.layerbit.ravam/.detect.AdbPairReceiver
 *
 * Pairing still requires the live six-digit code, so exposing this is harmless.
 */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val port = intent.getIntExtra("port", -1)
        val code = intent.getStringExtra("code")
        val host = intent.getStringExtra("host")
        if (port <= 0 || code.isNullOrBlank()) {
            android.util.Log.w("RavamAdb", "PAIR_ADB ignored: port=$port codeBlank=${code.isNullOrBlank()}")
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = EmbeddedAdb.pair(app, port, code, host)
                val connected = if (ok) EmbeddedAdb.connect(app) else false
                android.util.Log.i("RavamAdb", "PAIR_ADB result ok=$ok connected=$connected err=${EmbeddedAdb.lastError}")
            } finally {
                pending.finish()
            }
        }
    }
}
