package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stillwater type roles. Display and headlines are serif; everything else is Inter.
 * Defaults are compact (~14sp body) so chat density matches a modern assistant; interface
 * size then scales density on top of the system font scale.
 */
@Immutable
data class AtlasTypeRoles(
    val display: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 60.sp,
        lineHeight = 58.sp,
        letterSpacing = (-1.2).sp,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.2.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = StillwaterMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    val italic: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
)

val LocalAtlasTypography = staticCompositionLocalOf { AtlasTypeRoles() }

internal fun atlasMaterialTypography(roles: AtlasTypeRoles): Typography = Typography(
    displayLarge = roles.display,
    displayMedium = roles.display.copy(fontSize = 48.sp, lineHeight = 50.sp),
    displaySmall = roles.display.copy(fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = roles.headline,
    headlineMedium = roles.headline.copy(fontSize = 24.sp, lineHeight = 28.sp),
    headlineSmall = roles.headline.copy(fontSize = 20.sp, lineHeight = 24.sp),
    titleLarge = roles.title,
    titleMedium = roles.title.copy(fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = roles.title.copy(fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = roles.body,
    bodyMedium = roles.body.copy(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = roles.bodySmall,
    labelLarge = roles.label.copy(fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelMedium = roles.label,
    labelSmall = roles.label.copy(fontSize = 9.sp, lineHeight = 11.sp),
)
