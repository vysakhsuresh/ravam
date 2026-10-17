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
            // Ravam's own shell access: pair with the phone's Wireless Debugging.
            action.contains("Pair Ravam", ignoreCase = true) ->
                showAdbPairingDialog()
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
     * Pair Ravam with the phone's own Wireless Debugging — the self-contained route to the
     * shell UID, with no companion app. The user reads a port and a six-digit code from
     * Developer options → Wireless debugging → "Pair device with pairing code" and enters
     * them here. Pairing is one time; after it, Ravam reconnects automatically.
     */
    private fun showAdbPairingDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(this).apply {
            text = "On this phone: Settings → Developer options → Wireless debugging → " +
                "\"Pair device with pairing code\". Keep that dialog OPEN, then type the " +
                "port and 6-digit code it shows here and tap Pair."
        }
        val portField = EditText(this).apply {
            hint = "Pairing port (e.g. 37123)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val codeField = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(info)
        layout.addView(portField)
        layout.addView(codeField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Pair Ravam with Wireless debugging")
            .setView(layout)
            .setNeutralButton("Open Developer options") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            .setPositiveButton("Pair") { _, _ ->
                val port = portField.text.toString().trim().toIntOrNull()
                val code = codeField.text.toString().trim()
                if (port == null || code.isEmpty()) {
                    toast("Enter the pairing port and code")
                } else {
                    pairAdb(port, code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairAdb(port: Int, code: String) {
        toast("Pairing…")
        lifecycleScope.launch {
            val paired = EmbeddedAdb.pair(this@MainActivity, port, code)
            if (!paired) {
                showResult("Pairing failed", EmbeddedAdb.lastError ?: "Unknown error")
                return@launch
            }
            val connected = EmbeddedAdb.connect(this@MainActivity)
            if (connected) {
                showResult("Paired and connected", "Privileged recording is ready.")
            } else {
                showResult(
                    "Paired, but not connected yet",
                    "Pairing succeeded. Ravam couldn't reach the debugging port right now. " +
                        "It will try again when a call starts. If it keeps failing, make sure " +
                        "Wireless debugging is still on.\n\n" + (EmbeddedAdb.lastError ?: ""),
                )
            }
        }
    }

    private fun showResult(title: String, message: String) {
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermission) }
    }
}
