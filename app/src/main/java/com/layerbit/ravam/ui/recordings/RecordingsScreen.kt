package com.layerbit.ravam.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.layerbit.ravam.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.ravam.domain.Recording
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.components.VerdictBadge
import com.layerbit.ravam.ui.theme.Numeric
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The library — every recording on the phone, newest first, searchable, with playback and
 * the verdict badge on each. The badge is the point: at a glance, a user sees which calls
 * actually caught both sides, without opening a single one.
 */
@Composable
fun RecordingsScreen(vm: RecordingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("Recordings", style = MaterialTheme.typography.displaySmall, color = RavamColors.TextMain)
        Spacer(Modifier.height(16.dp))

        // search
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(RavamColors.CardBg).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (state.query.isEmpty()) {
                Text("Search a name or app", color = RavamColors.TextFaint, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                textStyle = TextStyle(color = RavamColors.TextMain, fontSize = 16.sp),
                cursorBrush = SolidColor(RavamColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))

        val list = vm.visible
        if (list.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text(
                if (state.recordings.isEmpty()) "No recordings yet.\nTry one from the Home screen."
                else "Nothing matches “${state.query}”.",
                color = RavamColors.TextFaint, style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { rec ->
                    RecordingRow(
                        rec = rec,
                        playing = state.playingId == rec.id,
                        onPlay = { vm.togglePlay(rec) },
                        onStar = { vm.toggleStar(rec) },
                        onDelete = { vm.delete(rec) },
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    playing: Boolean,
    onPlay: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
) = RavamCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = if (playing) "Pause" else "Play",
            tint = RavamColors.Accent,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onPlay).padding(4.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rec.displayName, color = RavamColors.TextMain, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${rec.channel} · ${mmss(rec.durationMs)} · ${sizeMb(rec.sizeBytes)}",
                color = RavamColors.TextFaint, style = Numeric,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            painter = painterResource(if (rec.starred) R.drawable.ic_star_filled else R.drawable.ic_star),
            contentDescription = if (rec.starred) "Starred" else "Not starred",
            tint = if (rec.starred) RavamColors.Warning else RavamColors.TextFaint,
            modifier = Modifier.size(28.dp).clickable(onClick = onStar).padding(4.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        VerdictBadge(rec.voices)
        Text(
            "Delete",
            color = RavamColors.TextFaint,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
        )
    }
}

private fun mmss(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
private fun sizeMb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)
