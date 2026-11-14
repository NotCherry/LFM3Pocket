package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Atlas palette from [docs/DESIGN.md](docs/DESIGN.md). Hex values are normative. */
@Immutable
data class AtlasColors(
    val surface: Color = Color(0xFF0E1116),
    val surfaceRaised: Color = Color(0xFF151A21),
    val surfaceOverlay: Color = Color(0xFF1C232D),
    val ink: Color = Color(0xFFE8EAED),
    val inkMuted: Color = Color(0xFF9AA3AD),
    val inkDisabled: Color = Color(0xFF5C6670),
    val accent: Color = Color(0xFF5B8FA8),
    val accentMuted: Color = Color(0xFF3D6478),
    val border: Color = Color(0xFF2A3340),
    val borderStrong: Color = Color(0xFF3E4A5A),
    val danger: Color = Color(0xFFC45B5B),
    val ok: Color = Color(0xFF6B9B7A),
    val warn: Color = Color(0xFFC4A35A),
    val codeBg: Color = Color(0xFF0A0D11),
    val cursor: Color = Color(0xFFE8EAED),
)

val LocalAtlasColors = staticCompositionLocalOf { AtlasColors() }
