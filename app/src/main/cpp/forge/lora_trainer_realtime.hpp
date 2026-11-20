#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include "../common/splitmix64.hpp"
#include <vector>

namespace girassol::forge {

class RealTimeLoraAdapter {
public:
    RealTimeLoraAdapter(uint32_t inDim, uint32_t outDim, uint32_t rank, float alpha = 16.0f, uint64_t seed = 1337ULL)
        : inDim_(std::max(1U, inDim)),
          outDim_(std::max(1U, outDim)),
          rank_(std::max(1U, rank)),
          scaling_(alpha / static_cast<float>(std::max(1U, rank))),
          loraA_(static_cast<size_t>(std::max(1U, rank)) * std::max(1U, inDim), 0.0f),
          loraB_(static_cast<size_t>(std::max(1U, outDim)) * std::max(1U, rank), 0.0f),
          anchorA_(static_cast<size_t>(std::max(1U, rank)) * std::max(1U, inDim), 0.0f),
          anchorB_(static_cast<size_t>(std::max(1U, outDim)) * std::max(1U, rank), 0.0f),
          fisherA_(static_cast<size_t>(std::max(1U, rank)) * std::max(1U, inDim), 0.0f),
          fisherB_(static_cast<size_t>(std::max(1U, outDim)) * std::max(1U, rank), 0.0f),
          mA_(loraA_.size(), 0.0f),
          vA_(loraA_.size(), 0.0f),
          mB_(loraB_.size(), 0.0f),
          vB_(loraB_.size(), 0.0f) {
        // Inicialização gaussiana determinística (Box–Muller sobre SplitMix64): mesma
        // semente → mesmos pesos em qualquer ABI, ao contrário de std::normal_distribution.
        girassol::SplitMix64 rng(seed);
        const float sigma = 1.0f / std::sqrt(static_cast<float>(std::max(1U, inDim_)));
        for (float& w : loraA_) {
            const double u1 = std::max(1e-12, rng.nextDouble());
            const double u2 = rng.nextDouble();
            w = static_cast<float>(std::sqrt(-2.0 * std::log(u1)) * std::cos(6.283185307179586 * u2)) * sigma;
        }
        anchorA_ = loraA_;
    }

    std::vector<float> forward(
        const std::vector<float>& baseOut,
        const std::vector<float>& x,
        std::vector<float>& outHiddenH
    ) const {
        outHiddenH.assign(rank_, 0.0f);
        if (x.size() < inDim_) return baseOut;

        for (uint32_t r = 0; r < rank_; ++r) {
            float acc = 0.0f;
            const size_t rowOffset = static_cast<size_t>(r) * inDim_;
            for (uint32_t k = 0; k < inDim_; ++k) {
                acc += loraA_[rowOffset + k] * x[k];
            }
            outHiddenH[r] = acc;
        }

        std::vector<float> y = baseOut;
        if (y.size() < outDim_) y.resize(outDim_, 0.0f);
        for (uint32_t d = 0; d < outDim_; ++d) {
            float acc = 0.0f;
            const size_t rowOffset = static_cast<size_t>(d) * rank_;
            for (uint32_t r = 0; r < rank_; ++r) {
                acc += loraB_[rowOffset + r] * outHiddenH[r];
            }
            y[d] += scaling_ * acc;
        }
        return y;
    }

    float trainStepMse(
        const std::vector<float>& baseOut,
        const std::vector<float>& x,
        const std::vector<float>& targetY,
        float lrA = 1e-3f,
        float ewcLambda = 15.0f
    ) {
        if (x.size() < inDim_ || targetY.size() < outDim_) return 0.0f;
        std::vector<float> h;
        const std::vector<float> pred = forward(baseOut, x, h);

        float mse = 0.0f;
        std::vector<float> dY(outDim_, 0.0f);
        for (uint32_t d = 0; d < outDim_; ++d) {
            const float diff = pred[d] - targetY[d];
            mse += diff * diff;
            dY[d] = (2.0f / static_cast<float>(outDim_)) * diff;
        }
        mse /= static_cast<float>(outDim_);

        std::vector<float> gradB(loraB_.size(), 0.0f);
        std::vector<float> dH(rank_, 0.0f);
        for (uint32_t d = 0; d < outDim_; ++d) {
            const size_t rowOffset = static_cast<size_t>(d) * rank_;
            for (uint32_t r = 0; r < rank_; ++r) {
                const float g = scaling_ * dY[d] * h[r];
                gradB[rowOffset + r] = g + ewcLambda * fisherB_[rowOffset + r] * (loraB_[rowOffset + r] - anchorB_[rowOffset + r]);
                fisherB_[rowOffset + r] = 0.95f * fisherB_[rowOffset + r] + 0.05f * (g * g);
                dH[r] += scaling_ * loraB_[rowOffset + r] * dY[d];
            }
        }

        std::vector<float> gradA(loraA_.size(), 0.0f);
        for (uint32_t r = 0; r < rank_; ++r) {
            const size_t rowOffset = static_cast<size_t>(r) * inDim_;
            for (uint32_t k = 0; k < inDim_; ++k) {
                const float g = dH[r] * x[k];
                gradA[rowOffset + k] = g + ewcLambda * fisherA_[rowOffset + k] * (loraA_[rowOffset + k] - anchorA_[rowOffset + k]);
                fisherA_[rowOffset + k] = 0.95f * fisherA_[rowOffset + k] + 0.05f * (g * g);
            }
        }

        ++stepCount_;
        adamWUpdate(loraA_, gradA, mA_, vA_, lrA);
        adamWUpdate(loraB_, gradB, mB_, vB_, lrA * 4.0f);
        return mse;
    }

    void consolidateEwcAnchor() {
        anchorA_ = loraA_;
        anchorB_ = loraB_;
    }

    const std::vector<float>& weightsA() const { return loraA_; }
    const std::vector<float>& weightsB() const { return loraB_; }
    uint64_t stepCount() const { return stepCount_; }

private:
    void adamWUpdate(std::vector<float>& w, const std::vector<float>& g, std::vector<float>& m, std::vector<float>& v, float lr) {
        const float beta1 = 0.9f, beta2 = 0.999f, eps = 1e-8f, wd = 0.01f;
        const float b1Corr = 1.0f - std::pow(beta1, static_cast<float>(stepCount_));
        const float b2Corr = 1.0f - std::pow(beta2, static_cast<float>(stepCount_));
        for (size_t i = 0; i < w.size(); ++i) {
            m[i] = beta1 * m[i] + (1.0f - beta1) * g[i];
            v[i] = beta2 * v[i] + (1.0f - beta2) * (g[i] * g[i]);
            const float mHat = m[i] / b1Corr;
            const float vHat = v[i] / b2Corr;
            w[i] = w[i] * (1.0f - lr * wd) - lr * (mHat / (std::sqrt(vHat) + eps));
        }
    }

    uint32_t inDim_, outDim_, rank_;
    float scaling_;
    uint64_t stepCount_ = 0;
    std::vector<float> loraA_, loraB_;
    std::vector<float> anchorA_, anchorB_;
    std::vector<float> fisherA_, fisherB_;
    std::vector<float> mA_, vA_, mB_, vB_;
};

} // namespace girassol::forge