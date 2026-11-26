package com.notcherry.lfm3pocket;

import java.util.Locale;

/**
 * Controls chain-of-thought ({@code <think>...</think>}) prompting and native reasoning token
 * budgets for thinking-capable models such as Liquid AI LFM3-1.7B and LFM3-VL-2B.
 */
public enum ReasoningLevel {
    /** Leave the assistant turn open ({@code <|im_start|>assistant\n}) and let the model decide. */
    AUTO("auto", -1),
    /** Prefill an empty {@code <think></think>} block to skip chain-of-thought and answer directly. */
    OFF("off", 0),
    /** Enable thinking with a compact reasoning budget. */
    LOW("low", 192),
    /** Enable thinking with a balanced reasoning budget. */
    MEDIUM("medium", 512),
    /** Enable thinking with a deep reasoning budget. */
    HIGH("high", 1536);

    private final String wire;
    private final int nominalBudget;

    ReasoningLevel(String wire, int nominalBudget) {
        this.wire = wire;
        this.nominalBudget = nominalBudget;
    }

    public String wireName() {
        return wire;
    }

    public int nominalBudgetTokens() {
        return nominalBudget;
    }

    /** Prefix appended immediately after {@code <|im_start|>assistant\n} in the chat prompt. */
    public String promptPrefix() {
        if (this == OFF) return "<think></think>";
        if (prefillsOpenThink()) return "<think>\n";
        return "";
    }

    /** True when the prompt itself opens {@code <think>} before generation starts. */
    public boolean prefillsOpenThink() {
        return this == LOW || this == MEDIUM || this == HIGH;
    }

    /**
     * Effective reasoning token budget for native generation ({@code -1} unlimited, {@code 0} off,
     * {@code > 0} cap before closing {@code </think>}). Clamps below {@code maxTokens} so the model
     * always retains room to emit a final answer.
     */
    public int effectiveBudgetTokens(int maxTokens) {
        if (this == AUTO) return -1;
        if (this == OFF) return 0;
        if (maxTokens < 64) return -1;
        final int reserve;
        final int floor;
        if (this == LOW) {
            reserve = Math.max(64, maxTokens / 4);
            floor = 32;
        } else if (this == MEDIUM) {
            reserve = Math.min(256, Math.max(96, maxTokens / 4));
            floor = 64;
        } else {
            reserve = Math.min(256, Math.max(96, maxTokens / 5));
            floor = 96;
        }
        int available = Math.max(floor, maxTokens - reserve);
        return Math.min(nominalBudget, available);
    }

    public static ReasoningLevel fromWire(String raw) {
        if (raw == null) return AUTO;
        String normalized = raw.trim().toLowerCase(Locale.US);
        for (ReasoningLevel level : values()) {
            if (level.wire.equals(normalized)) return level;
        }
        return AUTO;
    }
}
