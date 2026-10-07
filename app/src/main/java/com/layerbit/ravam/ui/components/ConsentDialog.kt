package com.layerbit.ravam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.layerbit.ravam.consent.ConsentController
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The warn-and-decide dialog for an all-party or unclear place.
 *
 * It names the local rule in plain words and lets an informed adult choose. It does not
 * claim the user is safe, it does not block the app, and — by design — nothing about the
 * choice is written anywhere (PLAN.md §6). Proceeding is at the user's own informed risk,
 * stated honestly rather than buried.
 */
@Composable
fun ConsentDialog(
    prompt: ConsentController.Prompt,
    onProceed: () -> Unit,
    onCancel: () -> Unit,
) = Dialog(onDismissRequest = onCancel) {
    Column(
        Modifier
            .widthIn(min = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RavamColors.DialogBg)
            .border(1.dp, RavamColors.Warning.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(22.dp),
    ) {
        Text(prompt.title, color = RavamColors.TextMain, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(prompt.body, color = RavamColors.TextMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        Text(
            prompt.proceedLabel,
            color = RavamColors.BgBase,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(RavamColors.Warning)
                .clickable(onClick = onProceed)
                .padding(vertical = 14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            prompt.cancelLabel,
            color = RavamColors.TextMuted,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(vertical = 12.dp),
        )
    }
}
