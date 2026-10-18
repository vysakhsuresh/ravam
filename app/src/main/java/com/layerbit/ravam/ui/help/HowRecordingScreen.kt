package com.layerbit.ravam.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * A plain-language page that tells the user exactly what Ravam records, and what each kind of
 * call needs to capture the other person.
 *
 * It exists for one reason: a user whose app-call recording comes out one-sided should never
 * be surprised. The rule they most need is simple — the other person on an app call, or any
 * call on a Bluetooth headset, is captured in full only when the phone is on Wi-Fi with
 * Wireless debugging on — so that rule is stated plainly and more than once, in their words,
 * not the engine's.
 */
@Composable
fun HowRecordingScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "‹ Back",
            style = MaterialTheme.typography.labelLarge, color = RavamColors.Accent,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("How recording works", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
        Spacer(Modifier.height(6.dp))
        Text(
            "What Ravam captures, and what each kind of call needs to record the other person.",
            style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted,
        )
        Spacer(Modifier.height(20.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            RavamCard {
                Title("Normal phone calls")
                Body(
                    "Recorded in full, both sides, with nothing to set up. Held to your ear or " +
                        "on speaker, Ravam hears both voices through the microphone.",
                )
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("App calls — WhatsApp, BOTIM, Teams and others")
                Body(
                    "The other person is recorded clearly only when both of these are true:",
                )
                Spacer(Modifier.height(10.dp))
                Bullet("Your phone is connected to Wi-Fi.")
                Bullet("Wireless debugging is on (a one-time pairing in Settings).")
                Spacer(Modifier.height(10.dp))
                Body(
                    "Without both, your call still records, but the other person comes out faint " +
                        "or missing. Turn on speakerphone to capture them through the microphone.",
                )
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("Calls on a Bluetooth headset")
                Body(
                    "Same rule as app calls: the other person is captured only with Wi-Fi on and " +
                        "Wireless debugging on. Without them, a Bluetooth call records your side " +
                        "only, because their voice never reaches the phone's microphone. Move the " +
                        "call to the phone or speaker to record both sides without any setup.",
                )
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("Why Wi-Fi and Wireless debugging?")
                Body(
                    "Capturing the other person at full quality uses your phone's own Wireless " +
                        "Debugging, and Android runs that only over Wi-Fi. On mobile data it " +
                        "switches off on its own, so the other side falls back to the microphone. " +
                        "This is an Android rule, not a Ravam limit.",
                )
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("To record app calls in full")
                Spacer(Modifier.height(8.dp))
                Step(1, "Connect your phone to Wi-Fi.")
                Step(2, "Turn on Wireless debugging and pair once, from Set up in Settings.")
                Step(3, "Make or answer the call — both sides are recorded.")
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("Every recording tells you")
                Body(
                    "Each recording is labelled Both sides, Your side only, Not enough speech, or " +
                        "Nothing captured — so you always know what was caught. If the other side " +
                        "came through the microphone only, the recording says so and why.",
                )
            }
            Spacer(Modifier.height(14.dp))

            RavamCard {
                Title("Nothing leaves your phone")
                Body(
                    "Ravam has no account and no internet permission. Every recording stays on this " +
                        "device until you choose to share it.",
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = RavamColors.TextMain)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMuted)
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = RavamColors.Accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMain)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(
            "$n.  ",
            style = MaterialTheme.typography.bodyMedium, color = RavamColors.Accent,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextMain)
    }
}
