package com.layerbit.ravam.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.data.SettingsStore
import com.layerbit.ravam.ui.components.BrandFooter
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The options a real call recorder has: what to record automatically, which directions,
 * app calls, and how long to keep recordings.
 *
 * State is read from and written straight to [SettingsStore]; a local recomposition key makes
 * the switches reflect the stored value immediately. No ViewModel ceremony for a settings list.
 */
@Composable
fun SettingsScreen(onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    var tick by remember { mutableStateOf(0) }
    fun changed() { tick++ }

    key(tick) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .systemBarsPadding().padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
            Spacer(Modifier.height(20.dp))

            RavamCard {
                Text("Recording", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
                Spacer(Modifier.height(10.dp))
                Toggle("Record calls automatically", settings.autoRecord) { settings.autoRecord = it; changed() }
                Toggle("Incoming calls", settings.recordIncoming) { settings.recordIncoming = it; changed() }
                Toggle("Outgoing calls", settings.recordOutgoing) { settings.recordOutgoing = it; changed() }
                Toggle(
                    "App calls (BOTIM, WhatsApp…)",
                    settings.recordVoip,
                    subtitle = "Opt-in, experimental — depends on your phone",
                ) { settings.recordVoip = it; changed() }
            }

            Spacer(Modifier.height(16.dp))
            RavamCard {
                Text("Storage", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Delete recordings older than — starred ones are always kept.",
                    style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Forever", 30 to "30 days", 90 to "90 days", 365 to "1 year").forEach { (days, label) ->
                        val selected = settings.retentionDays == days
                        Text(
                            label,
                            color = if (selected) RavamColors.BgBase else RavamColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (selected) RavamColors.Accent else RavamColors.CardBg)
                                .clickable { settings.retentionDays = days; changed() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(36.dp))
            BrandFooter(onOpenUrl = onOpenUrl)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    value: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) = Row(
    Modifier.fillMaxWidth().padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = RavamColors.TextMain)
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = RavamColors.TextFaint) }
    }
    Switch(
        checked = value,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = RavamColors.BgBase,
            checkedTrackColor = RavamColors.Accent,
            uncheckedTrackColor = RavamColors.CardBg,
        ),
    )
}
