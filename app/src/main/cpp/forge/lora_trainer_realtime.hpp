#pragma once
// Treinador LoRA (ΔW = B·A) — espelho exato de core/.../forge/LoraAdapter.kt (LoraMath).
// Somente +, −, ×, ÷ e √ (corretamente arredondadas), na mesma ordem da JVM; compilar
// com -ffp-contract=off para impedir FMA. Resultado: estado bit a bit idêntico.
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "../common/splitmix64.hpp"

namespace girassol::forge {

struct LoraState {
    int32_t inDim = 0, outDim = 0, rank = 0;
    float scaling = 1.0f;
    std::vector<float> a, b, anchorA, anchorB, fisherA, fisherB, mA, vA, mB, vB;
    float b1Pow = 1.0f, b2Pow = 1.0f;
    int32_t step = 0;

    LoraState(int32_t in, int32_t out, int32_t r, float s)
        : inDim(in), outDim(out), rank(r), scaling(s),
          a(r * in), b(out * r), anchorA(r * in), anchorB(out * r), fisherA(r * in), fisherB(out * r),
          mA(r * in), vA(r * in), mB(out * r), vB(out * r) {}

    size_t packedSize() const { return 5 * a.size() + 5 * b.size() + 2; }

    std::vector<float> pack() const {
        std::vector<float> out;
        out.reserve(packedSize());
        for (const auto* p : {&a, &b, &anchorA, &anchorB, &fisherA, &fisherB, &mA, &vA, &mB, &vB})
            out.insert(out.end(), p->begin(), p->end());
        out.push_back(b1Pow);
        out.push_back(b2Pow);
        return out;
    }

    bool unpack(const float* p, size_t n) {
        if (n < packedSize()) return false;
        size_t o = 0;
        for (auto* v : {&a, &b, &anchorA, &anchorB, &fisherA, &fisherB, &mA, &vA, &mB, &vB}) {
            std::memcpy(v->data(), p + o, v->size() * sizeof(float));
            o += v->size();
        }
        b1Pow = p[o];
        b2Pow = p[o + 1];
        return true;
    }
};

class LoraMath {
public:
    static constexpr float BETA1 = 0.9f;
    static constexpr float BETA2 = 0.999f;
    static constexpr float EPS = 1e-8f;
    static constexpr float WD = 0.01f;

    static void initA(std::vector<float>& a, int32_t inDim, uint64_t seed) {
        SplitMix64 rng(seed);
        const float limit = 1.0f / std::sqrt(static_cast<float>(inDim));
        for (auto& v : a) v = (rng.nextFloat() * 2.0f - 1.0f) * limit;
    }

    static void predict(const LoraState& s, const float* x, float* out, float* h) {
        for (int32_t r = 0; r < s.rank; ++r) {
            float acc = 0.0f;
            const int32_t off = r * s.inDim;
            for (int32_t k = 0; k < s.inDim; ++k) acc += s.a[off + k] * x[k];
            h[r] = acc;
        }
        for (int32_t d = 0; d < s.outDim; ++d) {
            float acc = 0.0f;
            const int32_t off = d * s.rank;
            for (int32_t r = 0; r < s.rank; ++r) acc += s.b[off + r] * h[r];
            out[d] = 0.0f + s.scaling * acc;
        }
    }

    static float trainStep(LoraState& s, const float* x, const float* y, float lr, float ewc) {
        std::vector<float> h(s.rank), pred(s.outDim), dY(s.outDim);
        predict(s, x, pred.data(), h.data());
        float mse = 0.0f;
        for (int32_t d = 0; d < s.outDim; ++d) {
            const float diff = pred[d] - y[d];
            mse += diff * diff;
            dY[d] = (2.0f / static_cast<float>(s.outDim)) * diff;
        }
        mse /= static_cast<float>(s.outDim);
        std::vector<float> gradB(s.b.size()), dH(s.rank, 0.0f), gradA(s.a.size());
        for (int32_t d = 0; d < s.outDim; ++d) {
            const int32_t off = d * s.rank;
            for (int32_t r = 0; r < s.rank; ++r) {
                const float g = s.scaling * dY[d] * h[r];
                gradB[off + r] = g + ewc * s.fisherB[off + r] * (s.b[off + r] - s.anchorB[off + r]);
                s.fisherB[off + r] = 0.95f * s.fisherB[off + r] + 0.05f * (g * g);
                dH[r] += s.scaling * s.b[off + r] * dY[d];
            }
        }
        for (int32_t r = 0; r < s.rank; ++r) {
            const int32_t off = r * s.inDim;
            for (int32_t k = 0; k < s.inDim; ++k) {
                const float g = dH[r] * x[k];
                gradA[off + k] = g + ewc * s.fisherA[off + k] * (s.a[off + k] - s.anchorA[off + k]);
                s.fisherA[off + k] = 0.95f * s.fisherA[off + k] + 0.05f * (g * g);
            }
        }
        s.step++;
        s.b1Pow *= BETA1;
        s.b2Pow *= BETA2;
        adamW(s.a, gradA, s.mA, s.vA, lr, s.b1Pow, s.b2Pow);
        adamW(s.b, gradB, s.mB, s.vB, lr * 4.0f, s.b1Pow, s.b2Pow);
        return mse;
    }

    // xs: n × inDim, ys: n × outDim. Retorna {mse da 1ª época, mse da última}.
    static std::pair<float, float> trainEpochs(LoraState& s, const float* xs, const float* ys, int32_t n,
                                               int32_t epochs, float lr, float ewc) {
        float first = 0.0f, last = 0.0f;
        for (int32_t e = 0; e < epochs; ++e) {
            float sum = 0.0f;
            for (int32_t i = 0; i < n; ++i) sum += trainStep(s, xs + i * s.inDim, ys + i * s.outDim, lr, ewc);
            const float avg = n == 0 ? 0.0f : sum / static_cast<float>(n);
            if (e == 0) first = avg;
            last = avg;
        }
        return {first, last};
    }

private:
    static void adamW(std::vector<float>& w, const std::vector<float>& g, std::vector<float>& m, std::vector<float>& v,
                      float lr, float b1Pow, float b2Pow) {
        const float b1Corr = 1.0f - b1Pow;
        const float b2Corr = 1.0f - b2Pow;
        const float oneMinusB1 = 1.0f - BETA1;
        const float oneMinusB2 = 1.0f - BETA2;
        for (size_t i = 0; i < w.size(); ++i) {
            m[i] = BETA1 * m[i] + oneMinusB1 * g[i];
            v[i] = BETA2 * v[i] + oneMinusB2 * (g[i] * g[i]);
            const float mHat = m[i] / b1Corr;
            const float vHat = v[i] / b2Corr;
            w[i] = w[i] - lr * (mHat / (std::sqrt(vHat) + EPS) + WD * w[i]);
        }
    }
};

}  // namespace girassol::forge
