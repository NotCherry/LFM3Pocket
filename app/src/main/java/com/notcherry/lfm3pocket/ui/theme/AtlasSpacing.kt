package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact Stillwater spacing tokens (base unit 4dp). Defaults are denser than the first Atlas
 * pass so chat reads closer to a modern assistant. Interface size scales density on top.
 */
@Immutable
data class AtlasSpacing(
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 10.dp,
    val space4: Dp = 12.dp,
    val space5: Dp = 16.dp,
    val space6: Dp = 20.dp,
    val space8: Dp = 28.dp,
    val space10: Dp = 36.dp,
    val space12: Dp = 48.dp,
    /** Screen horizontal padding on phone. */
    val screen: Dp = 12.dp,
    /** Chat turn vertical padding. */
    val turnY: Dp = 7.dp,
    /** Composer inner padding. */
    val composer: Dp = 8.dp,
    /** Compact top bar / chrome control visual size (touch target stays 48dp). */
    val chromeControl: Dp = 36.dp,
)

val LocalAtlasSpacing = staticCompositionLocalOf { AtlasSpacing() }
