package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Atlas type roles from DESIGN.md.
 * [Typography] covers Material3 slots; [mono] is the dedicated tools/paths/code face.
 */
@Immutable
data class AtlasTypeRoles(
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
)

val LocalAtlasTypography = staticCompositionLocalOf { AtlasTypeRoles() }

internal fun atlasMaterialTypography(roles: AtlasTypeRoles): Typography = Typography(
    displayLarge = roles.display.copy(fontSize = 40.sp, lineHeight = 44.sp),
    displayMedium = roles.display,
    displaySmall = roles.display.copy(fontSize = 32.sp, lineHeight = 36.sp),
    headlineLarge = roles.headline.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = roles.headline,
    headlineSmall = roles.headline.copy(fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = roles.title.copy(fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = roles.title,
    titleSmall = roles.title.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = roles.body,
    bodyMedium = roles.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = roles.bodySmall,
    labelLarge = roles.label.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = roles.label,
    labelSmall = roles.label.copy(fontSize = 11.sp, lineHeight = 14.sp),
)
