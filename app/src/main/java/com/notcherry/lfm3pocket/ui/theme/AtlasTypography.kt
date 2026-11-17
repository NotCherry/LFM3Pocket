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
 */
@Immutable
data class AtlasTypeRoles(
    val display: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 72.sp,
        lineHeight = 68.sp,
        letterSpacing = (-1.5).sp,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = StillwaterMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val italic: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
)

val LocalAtlasTypography = staticCompositionLocalOf { AtlasTypeRoles() }

internal fun atlasMaterialTypography(roles: AtlasTypeRoles): Typography = Typography(
    displayLarge = roles.display,
    displayMedium = roles.display.copy(fontSize = 56.sp, lineHeight = 58.sp),
    displaySmall = roles.display.copy(fontSize = 40.sp, lineHeight = 44.sp),
    headlineLarge = roles.headline,
    headlineMedium = roles.headline.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = roles.headline.copy(fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = roles.title,
    titleMedium = roles.title.copy(fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = roles.title.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = roles.body,
    bodyMedium = roles.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = roles.bodySmall,
    labelLarge = roles.label.copy(fontSize = 13.sp, letterSpacing = 0.2.sp),
    labelMedium = roles.label,
    labelSmall = roles.label.copy(fontSize = 10.sp, lineHeight = 12.sp),
)
