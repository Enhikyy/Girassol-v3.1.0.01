#pragma once
#include <cstdint>

namespace girassol {

// SplitMix64 — mesmas constantes e conversões do Kotlin (core/util/Determinism.kt),
// garantindo resultados idênticos bit a bit entre a JVM e o código nativo.
class SplitMix64 {
public:
    explicit SplitMix64(uint64_t seed) : state_(seed) {}

    uint64_t next() {
        state_ += 0x9E3779B97F4A7C15ULL;
        uint64_t z = state_;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
        return z ^ (z >> 31);
    }

    float nextFloat() { return static_cast<float>(next() >> 40) * (1.0f / 16777216.0f); }
    double nextDouble() { return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0); }
    uint64_t state() const { return state_; }
    void setState(uint64_t s) { state_ = s; }

private:
    uint64_t state_;
};

}  // namespace girassol
