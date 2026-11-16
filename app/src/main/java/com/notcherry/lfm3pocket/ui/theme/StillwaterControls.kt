package com.notcherry.lfm3pocket.ui.theme

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Button colors whose disabled state stays chalk on charcoal.
 * Material3's default 38% onSurface falls under WCAG AA on this field.
 */
object StillwaterControls {
    @Composable
    fun buttonColors() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.atlasColors.accent,
        contentColor = MaterialTheme.atlasColors.candleInk,
        disabledContainerColor = MaterialTheme.atlasColors.surfaceRaised,
        disabledContentColor = MaterialTheme.atlasColors.inkDisabled,
    )

    @Composable
    fun textButtonColors() = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.atlasColors.accent,
        disabledContentColor = MaterialTheme.atlasColors.inkDisabled,
    )

    @Composable
    fun outlinedColors() = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.atlasColors.ink,
        disabledContentColor = MaterialTheme.atlasColors.inkDisabled,
    )

    @Composable
    fun dangerTextColors() = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.atlasColors.danger,
        disabledContentColor = MaterialTheme.atlasColors.inkDisabled,
    )
}
