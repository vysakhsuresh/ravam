package com.layerbit.ravam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The Layerbit furniture: support, help, and who made this.
 *
 * Lives in one place, on one screen, and nowhere else. Never floating over the app,
 * never on the recording screen, never near a live call. An app that asks for money
 * while someone is trying not to lose a conversation has misunderstood its job.
 */
@Composable
fun SupportRow(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {

    GlassPill(
        text = "☕  Support Layerbit",
        borderColor = RavamColors.CoffeeEdge,
        onClick = { onOpen("https://www.buymeacoffee.com/layerbit") },
    )

    Spacer(Modifier.height(10.dp))

    GlassPill(
        text = "Get help",
        borderColor = RavamColors.CardBorder,
        onClick = { onOpen("https://layerbit.com/contact.html") },
    )

    Spacer(Modifier.height(22.dp))

    Text(
        "© 2025–2026 Layerbit Technologies",
        color = RavamColors.TextFaint,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Nothing you record leaves this phone.",
        color = RavamColors.TextFaint,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GlassPill(
    text: String,
    borderColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) = Text(
    text = text,
    color = RavamColors.TextMain,
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier
        .clip(RoundedCornerShape(30.dp))
        .background(RavamColors.CardBg)
        .border(1.dp, borderColor, RoundedCornerShape(30.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp)
        .defaultMinSize(minHeight = 24.dp),
)
