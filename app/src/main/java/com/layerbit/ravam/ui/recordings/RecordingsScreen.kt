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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.layerbit.ravam.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.ravam.domain.Recording
import com.layerbit.ravam.ui.components.RavamCard
import com.layerbit.ravam.ui.components.StatusBadge
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

        state.playbackError?.let { message ->
            Text(
                message,
                color = RavamColors.Danger, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

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
                        playback = state.playback.takeIf { it.id == rec.id },
                        onPlay = { vm.togglePlay(rec) },
                        onStar = { vm.toggleStar(rec) },
                        onDelete = { vm.delete(rec) },
                        onSeek = { vm.seekTo(it) },
                        onSkip = { vm.skip(it) },
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
    playback: Playback?,
    onPlay: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
) = RavamCard {
    val selected = playback != null
    val playing = playback?.isPlaying == true

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

    // The player: appears for the selected recording, with a scrubber you can drag to any
    // point and 10-second skip either way — the thing a music player has and the last build
    // was missing.
    if (selected) {
        val total = (playback!!.durationMs.takeIf { it > 0 } ?: rec.durationMs).coerceAtLeast(1)
        val pos = playback.positionMs.coerceIn(0, total)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = pos.toFloat(),
            onValueChange = { onSeek(it.toInt()) },
            valueRange = 0f..total.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = RavamColors.Accent,
                activeTrackColor = RavamColors.Accent,
                inactiveTrackColor = RavamColors.CardBorder,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mmss(pos), color = RavamColors.TextMuted, style = Numeric)
            Text(mmss(total), color = RavamColors.TextMuted, style = Numeric)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_rewind), "Back 10 seconds", tint = RavamColors.TextMain,
                modifier = Modifier.size(30.dp).clickable { onSkip(-10_000) }.padding(2.dp),
            )
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (playing) "Pause" else "Play", tint = RavamColors.Accent,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(28.dp))
                    .clickable(onClick = onPlay).padding(4.dp),
            )
            Icon(
                painterResource(R.drawable.ic_forward), "Forward 10 seconds", tint = RavamColors.TextMain,
                modifier = Modifier.size(30.dp).clickable { onSkip(10_000) }.padding(2.dp),
            )
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        // A mic test has no far side, so a both-sides verdict would be a result about a
        // question it never asked. Home says "Mic works" for the same file; the library
        // must not contradict it with a grey "Not enough speech".
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rec.isTest) {
                StatusBadge("Mic test", RavamColors.TextMuted, Color(0x14FFFFFF))
            } else {
                VerdictBadge(rec.voices)
            }
            // Only the privileged tier produces a true two-channel recording — the two sides
            // on separate channels. Showing it is showing the one thing no other recorder on
            // the phone can do.
            if (rec.stereo && !rec.isTest) {
                StatusBadge("Stereo", RavamColors.Accent, Color(0x1F28C2FF))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val context = LocalContext.current
            Text(
                "Share",
                color = RavamColors.Accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { shareRecording(context, rec.file) }.padding(6.dp),
            )
            Text(
                "Delete",
                color = RavamColors.TextFaint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable(onClick = onDelete).padding(6.dp),
            )
        }
    }
    // "Nothing captured" needs its cause next to it, because the cause is the remedy.
    if (!rec.isTest && rec.voices == com.layerbit.ravam.audio.Voices.NOTHING) {
        Spacer(Modifier.height(6.dp))
        Text(
            if (rec.viaBluetooth) {
                "The call was on a Bluetooth headset, and this phone doesn't let Ravam hear " +
                    "calls there. Take the next one on the phone or on speaker."
            } else {
                "This phone didn't let Ravam hear the call."
            },
            style = MaterialTheme.typography.bodyMedium, color = RavamColors.TextFaint,
        )
    }
}

/**
 * Hands a recording to another app of the user's choosing, through a granted FileProvider URI.
 * Ravam has no INTERNET permission, so "share" is always the user picking a target — never an
 * upload by the app.
 */
private fun shareRecording(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/x-wav"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share recording"))
    }
}

private fun mmss(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
private fun sizeMb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)
