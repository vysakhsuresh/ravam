package com.layerbit.ravam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.ravam.ui.home.HomeScreen
import com.layerbit.ravam.ui.probe.ProbeScreen
import com.layerbit.ravam.ui.recordings.RecordingsScreen
import com.layerbit.ravam.ui.theme.RavamColors

/**
 * The shell: three destinations behind a simple bottom bar.
 *
 * Deliberately no navigation library — three top-level tabs do not need a back stack or a
 * dependency, and keeping the surface small keeps the APK small and the build robust. State
 * is a single enum.
 */
private enum class Tab(val label: String) {
    HOME("Home"), RECORDINGS("Recordings"), DEVICE("My phone")
}

@Composable
fun RavamRoot(onRequestSetup: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.HOME -> HomeScreen(onRequestSetup = onRequestSetup)
                Tab.RECORDINGS -> RecordingsScreen()
                Tab.DEVICE -> ProbeScreen(onOpenUrl = onOpenUrl)
            }
        }
        BottomBar(current = tab, onSelect = { tab = it })
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(RavamColors.BgElevated)
            .navigationBarsPadding()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val selected = t == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 6.dp),
            ) {
                Text(
                    t.label,
                    color = if (selected) RavamColors.Accent else RavamColors.TextMuted,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
