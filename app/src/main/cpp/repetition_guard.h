#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace hanami {

// Conservative exact-cycle repetition circuit breaker for degenerate decode loops.
// Triggers ONLY on verbatim back-to-back repeats at the tail of generated tokens:
// - Short period (1..5 tokens): repeated continuously for at least 24 tokens
// - Phrase/line period (6..128 tokens): repeated 4 consecutive times verbatim
inline bool has_verbatim_token_loop(const int32_t * tokens, size_t count) {
    if (!tokens || count < 24) return false;
    constexpr size_t kMaxPeriod = 128;
    constexpr size_t kShortPeriodMax = 5;
    constexpr size_t kShortMinTokens = 24;
    constexpr size_t kPhraseMinRepeats = 4;

    const size_t max_period = (count / kPhraseMinRepeats) < kMaxPeriod
            ? (count / kPhraseMinRepeats)
            : kMaxPeriod;
    for (size_t period = 1; period <= max_period; ++period) {
        const size_t min_repeats = period <= kShortPeriodMax
                ? (kShortMinTokens + period - 1) / period
                : kPhraseMinRepeats;
        const size_t span = period * min_repeats;
        if (count < span) continue;
        const size_t start = count - span;
        bool match = true;
        for (size_t i = period; i < span; ++i) {
            if (tokens[start + i] != tokens[start + (i % period)]) {
                match = false;
                break;
            }
        }
        if (match) return true;
    }
    return false;
}

inline bool has_verbatim_token_loop(const std::vector<int32_t> & tokens) {
    return has_verbatim_token_loop(tokens.data(), tokens.size());
}

} // namespace hanami
