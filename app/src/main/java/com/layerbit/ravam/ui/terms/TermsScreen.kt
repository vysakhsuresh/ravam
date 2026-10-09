package com.layerbit.ravam.ui.terms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * First-run gate. The user reads the terms and accepts before the app does anything.
 *
 * This is the one acceptance Ravam records — a neutral, one-time EULA flag. It is not the
 * per-recording "accepted the risk" flag PLAN.md §6 forbids; it says only that the user has
 * seen the terms, with no bearing on any individual call.
 */
@Composable
fun TermsScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Before you start", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
        Spacer(Modifier.height(16.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            RavamCard {
                Section(
                    "You are responsible for how you use it",
                    "Recording a call is governed by the law where you are, where the other " +
                        "person is, or both. In many places everyone on the call must agree first, " +
                        "and recording without that agreement can be a criminal offence. Knowing " +
                        "and following your local law is your responsibility.",
                )
                Section(
                    "A tool, not legal advice",
                    "Ravam shows what local rules it knows to help you decide. That information " +
                        "may be incomplete or out of date and is not legal advice. Do not rely on " +
                        "it as a statement that a recording is lawful.",
                )
                Section(
                    "No warranty, your own risk",
                    "Call recording depends on your phone and the other apps involved. It may " +
                        "capture only one side, or fail without notice. Ravam is provided as is, and " +
                        "you use it at your own risk.",
                )
                Section(
                    "Nothing leaves your phone",
                    "Ravam has no account and no internet permission. Recordings stay on your " +
                        "device. What you do with one after you share it is up to you.",
                )
                Text(
                    "Full terms are in TERMS.md in the project.",
                    style = MaterialTheme.typography.labelSmall, color = RavamColors.TextFaint,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "I understand and accept",
            color = RavamColors.BgBase,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(RavamColors.Accent).clickable(onClick = onAccept).padding(vertical = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Decline and exit",
            color = RavamColors.TextMuted,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDecline).padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted)
    Spacer(Modifier.height(16.dp))
}
