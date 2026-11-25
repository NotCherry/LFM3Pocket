package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stillwater spacing tokens (base unit 4dp), tuned for comfortable daily smartphone ergonomics.
 */
@Immutable
data class AtlasSpacing(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space5: Dp = 20.dp,
    val space6: Dp = 24.dp,
    val space8: Dp = 32.dp,
    val space10: Dp = 40.dp,
    val space12: Dp = 48.dp,
    /** Screen horizontal padding on phone. */
    val screen: Dp = 16.dp,
    /** Chat turn vertical padding. */
    val turnY: Dp = 12.dp,
    /** Composer inner padding. */
    val composer: Dp = 12.dp,
    /** Top bar / chrome control target size (48dp system floor). */
    val chromeControl: Dp = 48.dp,
)

val LocalAtlasSpacing = staticCompositionLocalOf { AtlasSpacing() }
