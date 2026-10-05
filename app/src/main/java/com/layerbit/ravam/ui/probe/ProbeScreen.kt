package com.layerbit.ravam.ui.probe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.ravam.capture.TierStatus
import com.layerbit.ravam.ui.components.Measurement
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.components.SupportRow
import com.layerbit.ravam.ui.components.VerdictBadge
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * What this phone can actually do, measured rather than assumed.
 *
 * In v1 this becomes the "Test my phone" screen. It is deliberately the first thing
 * built: the week-one experiment and the flagship feature are the same code, so none
 * of this work gets thrown away when the product arrives around it.
 */
@Composable
fun ProbeScreen(
    onOpenUrl: (String) -> Unit,
    vm: ProbeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(32.dp))

        Text("Ravam", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
        Spacer(Modifier.height(6.dp))
        Text(
            "Both sides of every call. Kept on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = RavamColors.TextMuted,
        )

        Spacer(Modifier.height(28.dp))

        // ---- capture routes -------------------------------------------------
        RavamCard {
            Text("How Ravam could record", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android only lets a few kinds of app hear the other person. " +
                    "These are the routes that exist on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = RavamColors.TextMuted,
            )
            Spacer(Modifier.height(16.dp))

            state.tiers.forEach { t ->
                Column(Modifier.padding(vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.tier.displayName, style = MaterialTheme.typography.bodyLarge, color = RavamColors.TextMain)
                        Text(
                            when (t.status) {
                                is TierStatus.Ready -> "Ready"
                                is TierStatus.NeedsSetup -> "Setup needed"
                                is TierStatus.Unavailable -> "Not available"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = when (t.status) {
                                is TierStatus.Ready -> RavamColors.Success
                                is TierStatus.NeedsSetup -> RavamColors.Warning
                                is TierStatus.Unavailable -> RavamColors.TextFaint
                            },
                        )
                    }
                    val detail = when (val s = t.status) {
                        is TierStatus.NeedsSetup -> s.action
                        is TierStatus.Unavailable -> s.reason
                        TierStatus.Ready -> null
                    }
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextFaint)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- measured results ----------------------------------------------
        RavamCard {
            Text("What came back", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
            Spacer(Modifier.height(4.dp))
            Text(
                "Measured on this handset just now — not guessed from the model name.",
                style = MaterialTheme.typography.bodyMedium,
                color = RavamColors.TextMuted,
            )
            Spacer(Modifier.height(16.dp))

            if (state.results.isEmpty()) {
                Text(
                    if (state.running) "Testing…" else "Not tested yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RavamColors.TextFaint,
                )
            } else {
                state.results.forEach { r ->
                    Column(Modifier.padding(vertical = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.sourceName, style = MaterialTheme.typography.bodyLarge, color = RavamColors.TextMain)
                            r.verdict?.let { VerdictBadge(it.voices) }
                        }
                        Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextFaint)
                        r.verdict?.let { v ->
                            Spacer(Modifier.height(6.dp))
                            Measurement("Noise floor", "%.1f dBFS".format(v.noiseFloorDbfs))
                            v.separationDb?.let { Measurement("Level separation", "%.1f dB".format(it)) }
                            Measurement("Speech", "%.0f%%".format(v.voicedFraction * 100))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        RavamCard {
            Text("Apps found on this phone", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
            Spacer(Modifier.height(12.dp))
            state.voipApps.forEach { (label, installed) ->
                Measurement(label, if (installed) "installed" else "—")
            }
        }

        Spacer(Modifier.height(36.dp))
        SupportRow(onOpen = onOpenUrl)
        Spacer(Modifier.height(40.dp))
    }
}
