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
     * Pair Ravam with the phone's own Wireless Debugging — hands free, no companion app,
     * no typing, no split screen.
     *
     * Android cancels its "Pair device with pairing code" dialog the moment you leave
     * Settings and hides overlays over it, so a human can't comfortably copy the code into
     * Ravam. The answer: Ravam's own Accessibility service reads the code and port straight
     * off that dialog and pairs in the background while Settings stays open. The user only
     * has to open the pairing dialog; Ravam does the rest.
     */
    private fun startAdbPairing() {
        if (!isAccessibilityOn()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Turn on Ravam's Accessibility first")
                .setMessage(
                    "Ravam pairs itself by reading the pairing code for you — no typing, no " +
                        "split screen. For that it needs its Accessibility service on, which it " +
                        "also uses to record app calls. Open Accessibility settings now?",
                )
                .setPositiveButton("Open Accessibility") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        RavamAccessibilityService.armPairing = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Pair Ravam — hands free")
            .setMessage(
                "1. Tap \"Open Wireless debugging\" below.\n" +
                    "2. Tap \"Pair device with pairing code\".\n" +
                    "3. Leave that dialog open. Ravam reads the code and pairs itself — you'll " +
                    "get a message when it's done. You don't type anything.",
            )
            .setPositiveButton("Open Wireless debugging") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            .setNegativeButton("Cancel") { _, _ -> RavamAccessibilityService.armPairing = false }
            .show()
    }

    private fun isAccessibilityOn(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains(packageName)
    }.getOrDefault(false)

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermission) }
    }
}
