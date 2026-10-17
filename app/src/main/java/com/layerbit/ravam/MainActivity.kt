package com.layerbit.ravam

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.layerbit.ravam.capture.shell.EmbeddedAdb
import com.layerbit.ravam.capture.shell.OverlayPairing
import com.layerbit.ravam.capture.shell.PairingDiscovery
import com.layerbit.ravam.capture.shell.ShizukuShellCapture
import com.layerbit.ravam.ui.RavamRoot
import com.layerbit.ravam.ui.theme.RavamTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * The single Activity. Hosts the Compose tree and owns the permission + settings launchers,
 * which are the one piece of this flow that has to live at the Activity level.
 *
 * Setup actions from the Home checklist route through here: a runtime permission is requested
 * inline, and anything that lives in system Settings (Accessibility, the mic permission after
 * a permanent denial) opens the right screen rather than leaving the user to hunt for it.
 */
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* HomeViewModel.refresh() re-reads state when the screen resumes */ }

    // Shizuku reports the grant result here; the checklist re-reads state on resume.
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermission) }
        requestCorePermissions()
        setContent {
            RavamTheme {
                RavamRoot(
                    onRequestSetup = ::handleSetup,
                    onOpenUrl = ::openUrl,
                    onDeclineTerms = { finish() },
                )
            }
        }
    }

    private fun requestCorePermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    /**
     * A setup item was tapped. Runtime permissions re-request inline; accessibility and a
     * post-denial permission open the system screen, because Android does not let an app grant
     * those itself — the honest move is to take the user straight there.
     */
    private fun handleSetup(action: String) {
        when {
            action.contains("Accessibility", ignoreCase = true) ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            // Ravam's own shell access: pair with the phone's Wireless Debugging, using a
            // floating panel so the system pairing dialog stays open while the code is typed.
            action.contains("Pair Ravam", ignoreCase = true) ->
                startAdbPairing()
            // Shizuku is running but Ravam isn't authorised yet: show its grant dialog.
            action.contains("grant Ravam access", ignoreCase = true) ->
                ShizukuShellCapture.requestPermission()
            // Shizuku needs installing or starting: take the user to the Shizuku app.
            action.contains("Shizuku", ignoreCase = true) -> {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api".toUri()),
                        )
                    }
                }
            }
            else -> {
                // Try an inline request; if permanently denied, Android ignores it and the
                // app-details screen is the only route, so offer that too.
                requestCorePermissions()
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri(),
                    ),
                )
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /**
     * Pair Ravam with the phone's own Wireless Debugging — no companion app.
     *
     * Android cancels its "Pair device with pairing code" dialog the moment you leave
     * Settings, and it hides app overlays drawn on top of that dialog, so neither a normal
     * in-app dialog nor a floating panel works on its own. The one reliable way on-device
     * is split screen: Settings in one pane keeps the pairing session alive while the user
     * types the code into Ravam in the other. This dialog explains exactly that, discovers
     * the port over mDNS so only the six-digit code must be typed, and reports the result.
     */
    private fun startAdbPairing() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(this).apply {
            text = "Do this in SPLIT SCREEN so the pairing dialog stays open:\n\n" +
                "1. Open Recents, long-press Ravam's icon, choose \"Split screen\", pick Settings.\n" +
                "2. In the Settings pane: Developer options → Wireless debugging → " +
                "\"Pair device with pairing code\".\n" +
                "3. The port fills in here automatically. Type the 6-digit code in this pane " +
                "and tap Pair — without closing the Settings pane."
        }
        val status = TextView(this).apply {
            text = "Waiting for the pairing dialog…"
            setPadding(0, pad, 0, 0)
        }
        val portField = EditText(this).apply {
            hint = "Pairing port (auto, or type it)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val codeField = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(info); layout.addView(status); layout.addView(portField); layout.addView(codeField)

        discoveredHost = null
        val discovery = PairingDiscovery(this)
        discovery.start { host, port ->
            runOnUiThread {
                discoveredHost = host
                if (portField.text.isNullOrBlank()) portField.setText(port.toString())
                status.text = "Pairing dialog found (port $port). Type the 6-digit code, tap Pair."
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Pair Ravam with Wireless debugging")
            .setView(layout)
            .setPositiveButton("Pair") { _, _ ->
                val port = portField.text.toString().trim().toIntOrNull()
                val code = codeField.text.toString().trim()
                if (port == null || code.isEmpty()) {
                    toast("Enter the pairing code (and the port if it wasn't found)")
                } else {
                    pairAdb(port, code, discoveredHost)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { discovery.stop() }
            .show()
    }

    private var discoveredHost: String? = null

    private fun pairAdb(port: Int, code: String, host: String?) {
        toast("Pairing…")
        lifecycleScope.launch {
            val paired = EmbeddedAdb.pair(this@MainActivity, port, code, host)
            if (!paired) {
                showResult("Pairing failed", EmbeddedAdb.lastError ?: "Unknown error")
                return@launch
            }
            val connected = EmbeddedAdb.connect(this@MainActivity)
            showResult(
                if (connected) "Paired and connected" else "Paired",
                if (connected) "Privileged recording is ready."
                else "Paired, but not connected yet. Ravam will connect when a call starts.",
            )
        }
    }

    private fun showResult(title: String, message: String) = runCatching {
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermission) }
    }
}
