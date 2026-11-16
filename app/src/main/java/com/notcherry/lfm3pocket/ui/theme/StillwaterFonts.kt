package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.notcherry.lfm3pocket.R

/** Stillwater faces. Display is serif; UI is Inter; tools and paths are mono. */
internal val StillwaterSans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

internal val StillwaterSerif = FontFamily(
    Font(R.font.noto_serif_display_regular, FontWeight.Normal),
    Font(R.font.noto_serif_display_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.noto_serif_display_bold, FontWeight.Bold),
)

internal val StillwaterMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)
