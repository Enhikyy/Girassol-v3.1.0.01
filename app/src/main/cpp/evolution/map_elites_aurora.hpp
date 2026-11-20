#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iterator>
#include <map>
#include <vector>

namespace girassol::qd {

struct EliteNicheCell {
    uint64_t citizenId = 0;
    uint32_t generation = 0;
    float fitness = -1e9f;
    std::array<float, 3> behaviorDescriptor{};
    std::vector<float> policyWeights;
};

class AuroraBehavioralEncoder {
public:
    explicit AuroraBehavioralEncoder(size_t inputDim = 8)
        : dim_(inputDim), pc1_(inputDim, 0.0f), pc2_(inputDim, 0.0f), mean_(inputDim, 0.0f) {
        if (dim_ > 0) pc1_[0] = 1.0f;
        if (dim_ > 1) pc2_[1] = 1.0f;
    }

    std::array<float, 2> projectAndAdapt(const std::vector<float>& trajectoryStats, float lr = 0.015f) {
        if (trajectoryStats.size() < dim_ || dim_ == 0) return {0.5f, 0.5f};
        std::vector<float> x(dim_);
        for (size_t i = 0; i < dim_; ++i) {
            mean_[i] = 0.99f * mean_[i] + 0.01f * trajectoryStats[i];
            x[i] = trajectoryStats[i] - mean_[i];
        }

        float y1 = 0.0f;
        for (size_t i = 0; i < dim_; ++i) y1 += pc1_[i] * x[i];
        float norm1 = 0.0f;
        for (size_t i = 0; i < dim_; ++i) {
            pc1_[i] += lr * y1 * (x[i] - y1 * pc1_[i]);
            norm1 += pc1_[i] * pc1_[i];
        }
        norm1 = std::sqrt(std::max(norm1, 1e-8f));
        for (size_t i = 0; i < dim_; ++i) pc1_[i] /= norm1;

        std::vector<float> x2(dim_);
        for (size_t i = 0; i < dim_; ++i) x2[i] = x[i] - y1 * pc1_[i];

        float y2 = 0.0f;
        for (size_t i = 0; i < dim_; ++i) y2 += pc2_[i] * x2[i];
        float norm2 = 0.0f;
        for (size_t i = 0; i < dim_; ++i) {
            pc2_[i] += lr * y2 * (x2[i] - y2 * pc2_[i]);
            norm2 += pc2_[i] * pc2_[i];
        }
        norm2 = std::sqrt(std::max(norm2, 1e-8f));
        for (size_t i = 0; i < dim_; ++i) pc2_[i] /= norm2;

        const float u = 1.0f / (1.0f + std::exp(-std::clamp(y1, -20.0f, 20.0f)));
        const float v = 1.0f / (1.0f + std::exp(-std::clamp(y2, -20.0f, 20.0f)));
        return {u, v};
    }

private:
    size_t dim_;
    std::vector<float> pc1_;
    std::vector<float> pc2_;
    std::vector<float> mean_;
};

class MapElitesArchive {
public:
    explicit MapElitesArchive(uint32_t binsPerAxis = 8) : bins_(std::max(2U, binsPerAxis)) {}

    bool submitCandidate(const EliteNicheCell& candidate) {
        const uint32_t key = encodeBinKey(candidate.behaviorDescriptor);
        auto it = archive_.find(key);
        if (it == archive_.end() || candidate.fitness > it->second.fitness) {
            archive_[key] = candidate;
            return true;
        }
        return false;
    }

    float coverageRatio() const {
        const float totalBins = static_cast<float>(bins_) * static_cast<float>(bins_) * static_cast<float>(bins_);
        return static_cast<float>(archive_.size()) / std::max(1.0f, totalBins);
    }

    const EliteNicheCell* selectCuriosityDonor(uint64_t seed) const {
        if (archive_.empty()) return nullptr;
        const size_t idx = static_cast<size_t>(seed % archive_.size());
        auto it = archive_.begin();
        std::advance(it, idx);
        return &it->second;
    }

    size_t occupiedNiches() const { return archive_.size(); }

private:
    uint32_t encodeBinKey(const std::array<float, 3>& bd) const {
        auto toBin = [&](float v) -> uint32_t {
            const float clamped = std::clamp(v, 0.0f, 0.9999f);
            return static_cast<uint32_t>(clamped * static_cast<float>(bins_));
        };
        return (toBin(bd[0]) * bins_ + toBin(bd[1])) * bins_ + toBin(bd[2]);
    }

    uint32_t bins_;
    // std::map: iteração ordenada e determinística (paridade com o arquivo Kotlin)
    std::map<uint32_t, EliteNicheCell> archive_;
};

} // namespace girassol::qd