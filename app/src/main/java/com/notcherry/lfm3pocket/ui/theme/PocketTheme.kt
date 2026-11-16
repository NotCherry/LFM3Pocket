package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Stillwater theme for every Compose entry point.
 * Prefer [atlasColors] / [atlasTypography] for tokens beyond the Material3 mapping.
 */
@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val atlas = AtlasColors()
    val roles = AtlasTypeRoles()
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
