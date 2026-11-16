package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Stillwater palette. Token names stay stable so existing call sites keep compiling.
 * Candle is the only accent; body ink is warm chalk on a near-black field.
 */
@Immutable
data class AtlasColors(
    val surface: Color = Color(0xFF0E0E0C),
    val surfaceRaised: Color = Color(0xFF161614),
    val surfaceOverlay: Color = Color(0xFF1C1C18),
    val ink: Color = Color(0xFFF3EFE6),
    val inkMuted: Color = Color(0xFF8F897C),
    val inkDisabled: Color = Color(0xFF6E6A60),
    val accent: Color = Color(0xFFE6C27A),
    val accentMuted: Color = Color(0xFF8C7340),
    val border: Color = Color(0xFF2C2B28),
    val borderStrong: Color = Color(0xFF4A473F),
    val danger: Color = Color(0xFFE08A78),
    val ok: Color = Color(0xFF9DBEA4),
    val warn: Color = Color(0xFFC4A35A),
    val codeBg: Color = Color(0xFF0A0A09),
    val cursor: Color = Color(0xFFE6C27A),
    val candleInk: Color = Color(0xFF1C1508),
)

val LocalAtlasColors = staticCompositionLocalOf { AtlasColors() }
