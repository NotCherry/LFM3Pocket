package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stillwater smartphone-first type roles. Display and headlines are serif; UI and reading copy
 * use Inter at comfortable mobile sizes (~16sp body, 14sp secondary/mono, 12sp labels) so the
 * app reads effortlessly on a phone without squinting. Interface size scales on top.
 */
@Immutable
data class AtlasTypeRoles(
    val display: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 54.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.0).sp,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
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
        lineHeight = 24.sp,
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = StillwaterSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = StillwaterMono,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val italic: TextStyle = TextStyle(
        fontFamily = StillwaterSerif,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
)

val LocalAtlasTypography = staticCompositionLocalOf { AtlasTypeRoles() }

internal fun atlasMaterialTypography(roles: AtlasTypeRoles): Typography = Typography(
    displayLarge = roles.display,
    displayMedium = roles.display.copy(fontSize = 44.sp, lineHeight = 48.sp),
    displaySmall = roles.display.copy(fontSize = 34.sp, lineHeight = 38.sp),
    headlineLarge = roles.headline,
    headlineMedium = roles.headline.copy(fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = roles.headline.copy(fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = roles.title,
    titleMedium = roles.title.copy(fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = roles.title.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = roles.body,
    bodyMedium = roles.body.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = roles.bodySmall,
    labelLarge = roles.bodySmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = roles.label.copy(fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelSmall = roles.label,
)
