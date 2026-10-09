package com.layerbit.ravam.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.ravam.R
import com.layerbit.ravam.audio.Voices
import com.layerbit.ravam.capture.engine.CallRecorder
import com.layerbit.ravam.consent.ConsentController
import com.layerbit.ravam.jurisdiction.ConsentRule
import com.layerbit.ravam.ui.components.ConsentDialog
import com.layerbit.ravam.ui.components.Measurement
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.components.StatusBadge
import com.layerbit.ravam.ui.components.VerdictBadge
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The main screen: what Ravam can do on this phone, a control to try capture now, the last
 * result, where the local law stands, and anything still to set up.
 *
 * The "Test a recording" button records until stopped, so the capture path can be exercised
 * without waiting for a real call to come in — the fastest way to find out whether both
 * sides land on this handset.
 */
@Composable
fun HomeScreen(
    onRequestSetup: (String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastOutcome by vm.recordingLog.collectAsStateWithLifecycle(initialValue = null)
    var pendingConsent by remember { mutableStateOf<ConsentController.Prompt?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Ravam", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
        Text(
            "Both sides of every call. Kept on your phone.",
            style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))

        RavamCard {
            val recording = state.isRecording
            Text(
                if (recording) "Recording…" else "Ready",
                style = MaterialTheme.typography.titleLarge,
                color = if (recording) RavamColors.Success else RavamColors.TextMain,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Capturing via ${state.tier.displayName}" +
                    if (!state.tierReady) " — setup needed" else "",
                style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            BigButton(
                label = if (recording) "Stop" else "Test a recording",
                color = if (recording) RavamColors.Danger else RavamColors.Accent,
            ) {
                if (recording) {
                    vm.stopRecording()
                } else {
                    val prompt = vm.consentPrompt()
                    if (prompt != null) pendingConsent = prompt else vm.startManualRecording()
                }
            }
            if (!recording) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Checks the microphone without waiting for a call. Speak for a few " +
                        "seconds, then stop.",
                    style = MaterialTheme.typography.labelSmall, color = RavamColors.TextFaint,
                )
            }
        }

        lastOutcome?.let { o ->
            Spacer(Modifier.height(16.dp))
            RavamCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (o.isTest) "Last test" else "Last recording",
                        style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain,
                    )
                    // A test has nobody on the other end, so "Both sides" is unreachable by
                    // construction and every test ended up badged amber or grey — the app
                    // reporting a failure for doing exactly what was asked. A test is only
                    // ever a question about the microphone path, so answer that instead.
                    if (o.isTest) CaptureBadge(o) else VerdictBadge(o.verdict?.voices ?: Voices.INCONCLUSIVE)
                }
                Spacer(Modifier.height(8.dp))
                Measurement("Length", "%d:%02d".format(o.durationMs / 60000, (o.durationMs / 1000) % 60))
                Spacer(Modifier.height(4.dp))
                Text(
                    if (o.isTest) testExplanation(o)
                    else o.verdict?.basis.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextFaint,
                )
                o.failure?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = RavamColors.Warning)
                }
            }
        }

        state.jurisdiction?.let { j ->
            Spacer(Modifier.height(16.dp))
            RavamCard {
                Text("Where you are", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
                Spacer(Modifier.height(6.dp))
                Text(
                    when (j.rule) {
                        ConsentRule.ONE_PARTY ->
                            "You can record your own calls here. Ravam records automatically."
                        ConsentRule.ALL_PARTY ->
                            "Everyone on a call must agree first here. Ravam asks before recording."
                        ConsentRule.UNCLEAR ->
                            "The rules here aren't settled, so Ravam asks before recording."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted,
                )
            }
        }

        if (!state.allReady) {
            Spacer(Modifier.height(16.dp))
            RavamCard {
                Text("Finish setup", style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
                Spacer(Modifier.height(10.dp))
                state.setup.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .then(if (item.ready) Modifier else Modifier.clickable { item.action?.let(onRequestSetup) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(if (item.ready) R.drawable.ic_check else R.drawable.ic_circle),
                            contentDescription = if (item.ready) "Done" else "To do",
                            tint = if (item.ready) RavamColors.Success else RavamColors.TextFaint,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, color = RavamColors.TextMain)
                            item.action?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = RavamColors.TextFaint)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    pendingConsent?.let { prompt ->
        ConsentDialog(
            prompt = prompt,
            onProceed = { pendingConsent = null; vm.startManualRecording() },
            onCancel = { pendingConsent = null },
        )
    }
}

@Composable
private fun BigButton(label: String, color: Color, onClick: () -> Unit) = Text(
    label, color = RavamColors.BgBase, style = MaterialTheme.typography.labelLarge,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(color).clickable(onClick = onClick).padding(vertical = 16.dp),
)

/**
 * What a test recording actually proved.
 *
 * The test exists to answer one question — "can this phone capture audio at all?" — and
 * that question has a clean yes/no answer regardless of how briefly the user spoke. The
 * two-voice verdict answers a different question that a test cannot pose, because there
 * is no second person on the line.
 */
@Composable
private fun CaptureBadge(o: CallRecorder.Outcome) {
    val heardSomething = o.failure == null &&
        o.file != null &&
        (o.verdict?.voicedFraction ?: 0.0) > 0.0
    if (heardSomething) {
        StatusBadge("Mic works", RavamColors.Success, RavamColors.SuccessBg)
    } else {
        StatusBadge("No audio", RavamColors.Danger, RavamColors.DangerBg)
    }
}

/** The sentence under a test result. Says what was proved and what was not. */
private fun testExplanation(o: CallRecorder.Outcome): String {
    if (o.failure != null) return "Nothing was captured."
    if ((o.verdict?.voicedFraction ?: 0.0) <= 0.0) {
        return "Recorded, but no speech was heard — check the mic permission and try again."
    }
    return "Your voice was captured and saved. A test records only your side, so the " +
        "both-sides check runs on real calls, not here."
}
