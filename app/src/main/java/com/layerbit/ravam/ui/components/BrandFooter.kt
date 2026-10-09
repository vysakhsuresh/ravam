package com.layerbit.ravam.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.layerbit.ravam.R
import com.layerbit.ravam.brand.BrandLinks
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The Layerbit footer, as every app in the family carries it.
 *
 * "Powered by Layerbit AI", and under it one quiet line: Get Help · Buy me a coffee.
 *
 * Plain text rather than buttons, because neither is an action the user came here to take.
 * Two bordered pills read as the screen's primary controls and pull the eye away from the
 * content above them; set as a single line of coloured text they stay findable and stop
 * competing. Get Help takes the app's blue, Buy me a coffee a beige, so the pair is still
 * legible as two separate things without any chrome.
 *
 * Get Help opens a dialog offering email or WhatsApp; it does **not** throw the user out
 * to a web page. An app that bounces someone into a browser the moment they need help has
 * lost them.
 *
 * Lives on one screen only. Never over a live call.
 */
@Composable
fun BrandFooter(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {

        Spacer(Modifier.fillMaxWidth().height(1.dp).background(RavamColors.AccentDim))
        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onOpenUrl(BrandLinks.WEBSITE_URL) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Image(painterResource(R.drawable.ic_layerbit_mark), null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Powered by Layerbit AI",
                color = RavamColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // No spacer. The links carry their own vertical padding for the touch target, and
        // that padding is already the whole gap — adding a spacer on top of it pushed the
        // line far enough from the mark to stop reading as one block.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FooterLink("Get Help", RavamColors.Accent) { showHelp = true }
            Text(
                "·",
                color = RavamColors.TextFaint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            FooterLink("Buy me a coffee", RavamColors.CoffeeText) {
                onOpenUrl(BrandLinks.COFFEE_URL)
            }
        }

        // 2dp, not 10: the links already carry 8dp of tap padding below them, so this
        // spacer is only the remainder needed to match the 10dp above them. The three
        // lines are one block and the two gaps inside it have to be equal.
        Spacer(Modifier.height(2.dp))
        Text(
            "Nothing you record leaves this phone.",
            color = RavamColors.TextFaint,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }

    if (showHelp) GetHelpDialog(onOpenUrl = onOpenUrl, onDismiss = { showHelp = false })
}

/**
 * One half of the footer line.
 *
 * The vertical padding is the tap target, not decoration — the label alone is about 20dp
 * tall. It is also the entire gap to "Powered by Layerbit AI" above, which is why there
 * is no spacer between them: every dp here is spent twice, and 12 made the two lines look
 * unrelated. 8 keeps them reading as one block while leaving a ~36dp target, which is
 * short of the 48dp ideal but is the right side of the trade for two tertiary links that
 * sit alone at the bottom of a scroll with nothing adjacent to mis-hit.
 */
@Composable
private fun FooterLink(label: String, color: Color, onClick: () -> Unit) = Text(
    text = label,
    color = color,
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp),
)

/**
 * Email or WhatsApp, in the app's own skin.
 *
 * Deliberately a custom surface rather than a stock alert: the platform dialog inherits
 * DayNight chrome and shows up as a light panel on many devices, which looks like a
 * different app has taken over the screen.
 */
@Composable
private fun GetHelpDialog(
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) = Dialog(onDismissRequest = onDismiss) {
    Column(
        Modifier
            .widthIn(min = 290.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RavamColors.DialogBg)
            .border(1.dp, RavamColors.CardBorder, RoundedCornerShape(16.dp))
            .padding(22.dp),
    ) {
        Text("Get Help", color = RavamColors.TextMain, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Need a hand? Reach us by email or WhatsApp — whichever's easier for you.",
            color = RavamColors.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))

        HelpRow(R.drawable.ic_email, "Send Email") {
            onDismiss(); onOpenUrl(BrandLinks.SUPPORT_MAILTO)
        }
        Spacer(Modifier.height(10.dp))
        HelpRow(R.drawable.ic_whatsapp, "Chat on WhatsApp") {
            onDismiss(); onOpenUrl(BrandLinks.WHATSAPP_URL)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Close",
            color = RavamColors.TextMuted,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(10.dp),
        )
    }
}

@Composable
private fun HelpRow(icon: Int, label: String, onClick: () -> Unit) = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(RavamColors.ActionRowBg)
        .clickable(onClick = onClick)
        .padding(14.dp),
) {
    Image(painterResource(icon), null, Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Text(label, color = RavamColors.TextMain, style = MaterialTheme.typography.labelLarge)
}
