package com.notcherry.lfm3pocket;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;

/** Immutable per-assistant-reply inference timings (on-device only). */
public final class InferenceStats {
    public final long ttftMs;
    public final long totalMs;
    public final int tokensGenerated;
    public final Double decodeTokensPerSec;
    public final Double prefillTokensPerSec;

    public InferenceStats(long ttftMs, long totalMs, int tokensGenerated,
                          Double decodeTokensPerSec, Double prefillTokensPerSec) {
        this.ttftMs = Math.max(-1L, ttftMs);
        this.totalMs = Math.max(0L, totalMs);
        this.tokensGenerated = Math.max(0, tokensGenerated);
        this.decodeTokensPerSec = decodeTokensPerSec;
        this.prefillTokensPerSec = prefillTokensPerSec;
    }

    public boolean hasAnyMetric() {
        return ttftMs >= 0 || totalMs > 0 || tokensGenerated > 0
                || decodeTokensPerSec != null || prefillTokensPerSec != null;
    }

    public String toJson() {
        try {
            JSONObject object = new JSONObject();
            if (ttftMs >= 0) object.put("ttftMs", ttftMs);
            object.put("totalMs", totalMs);
            object.put("tokens", tokensGenerated);
            if (decodeTokensPerSec != null) object.put("decodeTps", decodeTokensPerSec);
            if (prefillTokensPerSec != null) object.put("prefillTps", prefillTokensPerSec);
            return object.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InferenceStats fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONObject object = new JSONObject(json);
            long ttft = object.has("ttftMs") ? object.getLong("ttftMs") : -1L;
            long total = object.optLong("totalMs", 0L);
            int tokens = object.optInt("tokens", 0);
            Double decode = object.has("decodeTps") && !object.isNull("decodeTps")
                    ? object.getDouble("decodeTps") : null;
            Double prefill = object.has("prefillTps") && !object.isNull("prefillTps")
                    ? object.getDouble("prefillTps") : null;
            InferenceStats stats = new InferenceStats(ttft, total, tokens, decode, prefill);
            return stats.hasAnyMetric() ? stats : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Prefer native decode/prefill rates; fill wall-clock TTFT/total and token count from Java when missing.
     */
    public static InferenceStats merge(long wallTtftMs, long wallTotalMs, int wallTokens,
                                       long[] nativeStats) {
        long ttft = wallTtftMs;
        long total = wallTotalMs;
        int tokens = wallTokens;
        Double decodeTps = null;
        Double prefillTps = null;
        if (nativeStats != null && nativeStats.length >= 5) {
            long prefillMs = nativeStats[0];
            long prefillTokens = nativeStats[1];
            long decodeMs = nativeStats[2];
            long decodeTokens = nativeStats[3];
            long nativeFirstMs = nativeStats[4];
            if (nativeFirstMs > 0 && (ttft < 0 || nativeFirstMs < ttft)) ttft = nativeFirstMs;
            if (decodeTokens > 0) tokens = Math.max(tokens, (int) decodeTokens);
            if (decodeMs > 0 && decodeTokens > 0)
                decodeTps = decodeTokens * 1000.0 / decodeMs;
            if (prefillMs > 0 && prefillTokens > 0)
                prefillTps = prefillTokens * 1000.0 / prefillMs;
        }
        if (tokens <= 0 && total > 0 && decodeTps == null && ttft >= 0 && total > ttft) {
            long decodeMs = Math.max(1L, total - ttft);
            decodeTps = tokens * 1000.0 / decodeMs;
        }
        InferenceStats stats = new InferenceStats(ttft, total, tokens, decodeTps, prefillTps);
        return stats.hasAnyMetric() ? stats : null;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InferenceStats)) return false;
        InferenceStats stats = (InferenceStats) other;
        return ttftMs == stats.ttftMs && totalMs == stats.totalMs && tokensGenerated == stats.tokensGenerated
                && Objects.equals(decodeTokensPerSec, stats.decodeTokensPerSec)
                && Objects.equals(prefillTokensPerSec, stats.prefillTokensPerSec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ttftMs, totalMs, tokensGenerated, decodeTokensPerSec, prefillTokensPerSec);
    }

    /** Compact English fallback for logs/tests without Android resources. */
    public String formatSummary() {
        StringBuilder out = new StringBuilder();
        if (ttftMs >= 0) out.append(String.format(Locale.US, "TTFT %.1f s", ttftMs / 1000.0));
        if (totalMs > 0) {
            if (out.length() > 0) out.append(" · ");
            out.append(String.format(Locale.US, "Total %.1f s", totalMs / 1000.0));
        }
        if (tokensGenerated > 0) {
            if (out.length() > 0) out.append(" · ");
            out.append(tokensGenerated).append(tokensGenerated == 1 ? " token" : " tokens");
        }
        if (decodeTokensPerSec != null) {
            if (out.length() > 0) out.append(" · ");
            out.append(String.format(Locale.US, "Decode %.0f tok/s", decodeTokensPerSec));
        }
        if (prefillTokensPerSec != null) {
            if (out.length() > 0) out.append(" · ");
            out.append(String.format(Locale.US, "Prefill %.0f tok/s", prefillTokensPerSec));
        }
        return out.toString();
    }
}