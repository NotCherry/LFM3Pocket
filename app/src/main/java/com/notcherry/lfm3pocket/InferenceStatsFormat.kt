package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

object InferenceStatsFormat {
    fun format(context: Context, stats: InferenceStats): String {
        val parts = mutableListOf<String>()
        if (stats.ttftMs >= 0) {
            parts.add(
                context.getString(
                    R.string.inference_stats_ttft,
                    stats.ttftMs / 1000.0,
                ),
            )
        }
        if (stats.totalMs > 0) {
            parts.add(
                context.getString(
                    R.string.inference_stats_total,
                    stats.totalMs / 1000.0,
                ),
            )
        }
        if (stats.tokensGenerated > 0) {
            parts.add(
                context.resources.getQuantityString(
                    R.plurals.inference_stats_tokens,
                    stats.tokensGenerated,
                    stats.tokensGenerated,
                ),
            )
        }
        stats.decodeTokensPerSec?.let {
            parts.add(context.getString(R.string.inference_stats_decode_tps, it))
        }
        stats.prefillTokensPerSec?.let {
            parts.add(context.getString(R.string.inference_stats_prefill_tps, it))
        }
        return parts.joinToString(context.getString(R.string.inference_stats_separator))
    }

    fun toggleShowDescription(context: Context): String =
        context.getString(R.string.inference_stats_show_a11y)

    fun toggleHideDescription(context: Context): String =
        context.getString(R.string.inference_stats_hide_a11y)

    @Composable
    fun format(stats: InferenceStats): String {
        val parts = mutableListOf<String>()
        if (stats.ttftMs >= 0) {
            parts.add(stringResource(R.string.inference_stats_ttft, stats.ttftMs / 1000.0))
        }
        if (stats.totalMs > 0) {
            parts.add(stringResource(R.string.inference_stats_total, stats.totalMs / 1000.0))
        }
        if (stats.tokensGenerated > 0) {
            parts.add(
                stringResource(
                    R.plurals.inference_stats_tokens,
                    stats.tokensGenerated,
                    stats.tokensGenerated,
                ),
            )
        }
        stats.decodeTokensPerSec?.let {
            parts.add(stringResource(R.string.inference_stats_decode_tps, it))
        }
        stats.prefillTokensPerSec?.let {
            parts.add(stringResource(R.string.inference_stats_prefill_tps, it))
        }
        return parts.joinToString(stringResource(R.string.inference_stats_separator))
    }

    fun formatForTest(stats: InferenceStats): String = stats.formatSummary()

    fun roundedRate(value: Double): String = String.format(Locale.US, "%.0f", value)
}