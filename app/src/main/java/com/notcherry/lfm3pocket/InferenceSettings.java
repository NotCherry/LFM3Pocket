package com.notcherry.lfm3pocket;

/** Validated resource limits shared by settings, the session and native inference. */
public final class InferenceSettings {
    /**
     * Compile-time safe defaults. First-run DataStore init overlays topology-based
     * threads via {@link InferenceSettingsStore}; do not read sysfs here.
     */
    public static final InferenceSettings DEFAULT = new InferenceSettings(4096, 4, 512);

    public final int contextTokens, threads, maxTokens;

    public InferenceSettings(int contextTokens, int threads, int maxTokens) {
        if (contextTokens < 512 || contextTokens > 8192 || contextTokens % 256 != 0)
            throw new IllegalArgumentException("Context must be 512–8192 tokens in steps of 256");
        if (threads < CpuTopology.MIN_THREADS || threads > CpuTopology.MAX_THREADS)
            throw new IllegalArgumentException("Threads must be 1–8");
        if (maxTokens < 1 || maxTokens > 2048 || maxTokens >= contextTokens)
            throw new IllegalArgumentException("Max tokens must be 1–2048 and less than context");
        this.contextTokens = contextTokens;
        this.threads = threads;
        this.maxTokens = maxTokens;
    }

    public String summary() {
        return "Context " + contextTokens + " • Threads " + threads + " • Max " + maxTokens;
    }
}
