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
import androidx.core.net.toUri
import com.layerbit.ravam.ui.RavamRoot
import com.layerbit.ravam.ui.theme.RavamTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
}
