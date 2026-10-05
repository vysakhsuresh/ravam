package com.layerbit.ravam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.audio.Voices
import com.layerbit.ravam.ui.theme.Numeric
import com.layerbit.ravam.ui.theme.RavamColors

/** The Layerbit card: translucent panel, hairline border, no shadow. Depth comes from
 *  the border and the translucency, never from elevation. */
@Composable
fun RavamCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(RavamColors.CardBg)
        .border(1.dp, RavamColors.CardBorder, RoundedCornerShape(16.dp))
        .padding(20.dp),
    content = content,
)

/**
 * The most important component in the app.
 *
 * Green appears here and nowhere else, and only when two voices were actually
 * measured. Everything else in the design gives way to that rule — it is the single
 * thing a user has to be able to trust at a glance.
 */
@Composable
fun VerdictBadge(voices: Voices, modifier: Modifier = Modifier) {
    val (label, fg, bg) = when (voices) {
        Voices.BOTH_SIDES  -> Triple("Both sides",    RavamColors.Success,   RavamColors.SuccessBg)
        Voices.LOCAL_ONLY  -> Triple("Your side only", RavamColors.Warning,  RavamColors.WarningBg)
        Voices.INCONCLUSIVE -> Triple("Couldn't tell", RavamColors.TextMuted, Color(0x14FFFFFF))
    }
    Text(
        text = label,
        color = fg,
        style = Numeric.copy(fontWeight = FontWeight.Medium),
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(30.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** A labelled measurement. Values use the mono face so columns line up. */
@Composable
fun Measurement(label: String, value: String, modifier: Modifier = Modifier) =
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RavamColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = RavamColors.TextMain, style = Numeric)
    }
