#pragma once
// Sistemas Funcionais de P.K. Anokhin — gêmeo C++20 de
// core/cognition/AnokhinFunctionalSystem.kt (paridade bit a bit; compilar com -ffp-contract=off).
//
// Síntese aferente (motivação dominante + memória + aferência situacional) → modelo do resultado
// esperado (Aceitador de Resultados da Ação) → ação → aferência de retorno → comparação explícita.
// Só depois da comparação o núcleo atualiza plasticidade e surpresa.
#include <array>
#include <cstdint>
#include <cstring>

#include "../common/splitmix64.hpp"

namespace girassol::anokhin {

constexpr int NEEDS = 6;
constexpr double SUCCESS_WEIGHT = 0.5;
constexpr double CONFIRM_THRESHOLD = 0.04;
constexpr double MAX_PLASTICITY = 2.0;

struct Acceptor {
    std::array<double, NEEDS> expectedNeeds{};
    double expectedSuccess = 0.5;
};

struct Comparison {
    double mismatch = 0.0, valence = 0.0, surprise = 0.0, plasticity = 1.0;
    bool confirmed = true;
};

inline double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline Acceptor afferentSynthesis(const double* projected, double forecast, double memoryUtility) {
    Acceptor a;
    const double mem = clamp(memoryUtility, -1.0, 1.0);
    a.expectedSuccess = clamp(forecast + 0.1 * mem, 0.02, 0.98);
    for (int k = 0; k < NEEDS; ++k) a.expectedNeeds[k] = projected[k];
    return a;
}

inline std::array<double, NEEDS> motivationWeights(const double* atDecision) {
    std::array<double, NEEDS> w{};
    double sum = 0.0;
    for (int k = 0; k < NEEDS; ++k) { w[k] = 0.05 + (1.0 - clamp(atDecision[k], 0.0, 1.0)); sum += w[k]; }
    for (int k = 0; k < NEEDS; ++k) w[k] = w[k] / sum;
    return w;
}

inline Comparison compare(const Acceptor& acc, const std::array<double, NEEDS>& w, const double* obtained, bool success) {
    double err = 0.0, signed_ = 0.0;
    for (int k = 0; k < NEEDS; ++k) {
        const double d = obtained[k] - acc.expectedNeeds[k];
        err += w[k] * (d < 0.0 ? -d : d);
        signed_ += w[k] * d;
    }
    const double o = success ? 1.0 : 0.0;
    const double ds = o - acc.expectedSuccess;
    Comparison c;
    c.mismatch = clamp((1.0 - SUCCESS_WEIGHT) * err + SUCCESS_WEIGHT * (ds < 0.0 ? -ds : ds), 0.0, 1.0);
    c.valence = clamp((1.0 - SUCCESS_WEIGHT) * signed_ + SUCCESS_WEIGHT * ds, -1.0, 1.0);
    c.confirmed = c.mismatch < CONFIRM_THRESHOLD;
    c.surprise = c.confirmed ? 0.0 : c.mismatch;
    const double asym = c.valence < 0.0 ? 1.5 : 1.0;
    c.plasticity = clamp(1.0 + asym * c.surprise * 2.0, 1.0, MAX_PLASTICITY);
    return c;
}

/** Saída no formato do ActionAcceptorPort: [mismatch, valence, plasticity, confirmed]. */
inline void evaluate(const double* projected, double forecast, double mem, const double* atDecision,
                     const double* obtained, bool success, double out[4]) {
    const Acceptor a = afferentSynthesis(projected, forecast, mem);
    const Comparison c = compare(a, motivationWeights(atDecision), obtained, success);
    out[0] = c.mismatch; out[1] = c.valence; out[2] = c.plasticity; out[3] = c.confirmed ? 1.0 : 0.0;
}

inline uint64_t paritySignature() {
    SplitMix64 rng(1935ULL);
    uint64_t h = 0xcbf29ce484222325ULL;
    auto mix = [&h](double v) {
        uint64_t bits; std::memcpy(&bits, &v, 8);
        for (int b = 0; b < 8; ++b) { h ^= (bits >> (b * 8)) & 0xFFULL; h *= 0x100000001b3ULL; }
    };
    for (int i = 0; i < 128; ++i) {
        double projected[NEEDS], atDecision[NEEDS], obtained[NEEDS];
        for (double& v : projected) v = rng.nextDouble();
        const double forecast = rng.nextDouble();
        const double mem = rng.nextDouble() * 2.0 - 1.0;
        for (double& v : atDecision) v = rng.nextDouble();
        for (double& v : obtained) v = rng.nextDouble();
        const bool success = (rng.next() >> 63) == 1ULL;
        double out[4];
        evaluate(projected, forecast, mem, atDecision, obtained, success, out);
        for (double v : out) mix(v);
    }
    return h;
}

}  // namespace girassol::anokhin
