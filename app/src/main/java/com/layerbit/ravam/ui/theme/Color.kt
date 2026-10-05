package com.layerbit.ravam.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Taken from the Layerbit web design system (`layerbit-site/css/base.css :root`) so the
 * app and the site read as one thing. Dark only, on purpose — Layerbit has no light
 * theme, and this is an app people open in a hurry, often at night.
 */
object RavamColors {
    // Surfaces
    val BgBase        = Color(0xFF0A0C10)
    val BgElevated    = Color(0xFF1A1E2D)
    val CardBg        = Color(0x9914161E)
    val CardBorder    = Color(0x14FFFFFF)
    val CardHoverEdge = Color(0x6638BDF8)

    // Text
    val TextMain      = Color(0xFFF8FAFC)
    val TextMuted     = Color(0xFF94A3B8)
    val TextFaint     = Color(0xFF64748B)

    // Accent
    val Accent        = Color(0xFF38BDF8)
    val AccentGlow    = Color(0x8038BDF8)
    val NeonStart     = Color(0xFF00F2FE)
    val NeonEnd       = Color(0xFF4FACFE)

    // State
    val Success       = Color(0xFF22C55E)
    val Warning       = Color(0xFFEAB308)
    val Danger        = Color(0xFFEF4444)
    val SuccessBg     = Color(0x1A22C55E)
    val WarningBg     = Color(0x1AEAB308)
    val DangerBg      = Color(0x1AEF4444)

    // Support pill
    val CoffeeEdge    = Color(0x4DFFC107)
}

val NeonGradient = Brush.linearGradient(listOf(RavamColors.NeonStart, RavamColors.NeonEnd))

/** Matches the site's `--bg-gradient`: brighter at the top, falling away to near-black. */
val PageBackground = Brush.radialGradient(
    colors = listOf(RavamColors.BgElevated, RavamColors.BgBase),
    center = Offset(0.5f, 0f),
    radius = 1400f,
)
