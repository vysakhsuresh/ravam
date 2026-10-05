package com.layerbit.ravam.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private val RavamScheme = darkColorScheme(
    primary            = RavamColors.Accent,
    onPrimary          = RavamColors.BgBase,
    secondary          = RavamColors.NeonEnd,
    background         = RavamColors.BgBase,
    onBackground       = RavamColors.TextMain,
    surface            = RavamColors.BgBase,
    onSurface          = RavamColors.TextMain,
    surfaceVariant     = RavamColors.BgElevated,
    onSurfaceVariant   = RavamColors.TextMuted,
    outline            = RavamColors.CardBorder,
    error              = RavamColors.Danger,
    onError            = RavamColors.TextMain,
)

/**
 * Dark only, and no dynamic colour.
 *
 * Material You would repaint the verdict badges from the user's wallpaper, and the
 * three verdict colours are load-bearing: green has to mean "two voices were measured"
 * and nothing else, on every phone. A theme engine is not allowed near that.
 */
@Composable
fun RavamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RavamScheme, typography = RavamTypography) {
        Box(Modifier.fillMaxSize().background(PageBackground)) { content() }
    }
}
