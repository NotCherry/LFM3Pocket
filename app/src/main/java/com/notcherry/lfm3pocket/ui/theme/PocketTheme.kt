package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.InterfaceSize

/**
 * Stillwater theme for every Compose entry point.
 * Prefer [atlasColors] / [atlasTypography] / [atlasSpacing] for tokens beyond the Material3 mapping.
 *
 * Interface size scales density via [InterfaceSize.wrap]; this theme keeps the Material3
 * [LocalMinimumInteractiveComponentSize] at a system 48dp finger target so compact visuals
 * do not shrink touch targets.
 */
@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val atlas = AtlasColors()
    val roles = AtlasTypeRoles()
    val spacing = AtlasSpacing()
    val context = LocalContext.current
    // Scale only changes through Activity recreate after prefs write.
    val scale = remember(context) { InterfaceSize.read(context) }
    // densityDpi is already scaled; enlarge the Dp floor so the resulting pixels stay ~48 system-dp.
    val minTouch: Dp = (InterfaceSize.MIN_TOUCH_DP / scale).dp
    val scheme = darkColorScheme(
        primary = atlas.accent,
        onPrimary = atlas.candleInk,
        primaryContainer = atlas.accentMuted,
        onPrimaryContainer = atlas.ink,
        secondary = atlas.accentMuted,
        onSecondary = atlas.ink,
        tertiary = atlas.ok,
        onTertiary = atlas.surface,
        background = atlas.surface,
        onBackground = atlas.ink,
        surface = atlas.surface,
        onSurface = atlas.ink,
        surfaceVariant = atlas.surfaceRaised,
        onSurfaceVariant = atlas.inkMuted,
        surfaceContainerLowest = atlas.surface,
        surfaceContainerLow = atlas.surface,
        surfaceContainer = atlas.surfaceRaised,
        surfaceContainerHigh = atlas.surfaceOverlay,
        surfaceContainerHighest = atlas.surfaceOverlay,
        surfaceBright = atlas.surfaceRaised,
        surfaceDim = atlas.surface,
        outline = atlas.border,
        outlineVariant = atlas.borderStrong,
        error = atlas.danger,
        onError = atlas.ink,
        errorContainer = atlas.danger.copy(alpha = 0.25f),
        onErrorContainer = atlas.ink,
        inverseSurface = atlas.ink,
        inverseOnSurface = atlas.surface,
        inversePrimary = atlas.accentMuted,
        scrim = Color.Black.copy(alpha = 0.5f),
    )
    CompositionLocalProvider(
        LocalAtlasColors provides atlas,
        LocalAtlasTypography provides roles,
        LocalAtlasSpacing provides spacing,
        LocalMinimumInteractiveComponentSize provides minTouch,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = atlasMaterialTypography(roles),
            shapes = AtlasShapes,
            content = content,
        )
    }
}

object PocketTheme {
    val colors: AtlasColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAtlasColors.current

    val type: AtlasTypeRoles
        @Composable
        @ReadOnlyComposable
        get() = LocalAtlasTypography.current

    val spacing: AtlasSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalAtlasSpacing.current
}

/** Atlas extras not fully expressible in Material3 ColorScheme. */
val androidx.compose.material3.MaterialTheme.atlasColors: AtlasColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAtlasColors.current

/** Atlas type roles including [AtlasTypeRoles.mono]. */
val androidx.compose.material3.MaterialTheme.atlasTypography: AtlasTypeRoles
    @Composable
    @ReadOnlyComposable
    get() = LocalAtlasTypography.current

/** Compact spacing tokens. */
val androidx.compose.material3.MaterialTheme.atlasSpacing: AtlasSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalAtlasSpacing.current
