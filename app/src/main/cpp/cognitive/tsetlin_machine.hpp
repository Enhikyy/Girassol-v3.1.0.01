#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include "../common/splitmix64.hpp"
#include <vector>
#include <utility>

namespace girassol::tsetlin {

class PersistentTsetlinMachine {
public:
    PersistentTsetlinMachine(
        uint32_t numFeatures,
        uint32_t numClauses,
        int32_t numStates = 64,
        float specificityS = 3.5f,
        int32_t thresholdT = 8,
        uint64_t seed = 42ULL
    )
        : numFeatures_(numFeatures),
          numClauses_(numClauses),
          numStates_(std::max(2, numStates)),
          specificityS_(std::max(1.01f, specificityS)),
          thresholdT_(std::max(1, thresholdT)),
          rng_(seed),
          taStates_(static_cast<size_t>(numClauses) * static_cast<size_t>(numFeatures) * 2ULL, std::max(2, numStates)),
          clausePolarity_(numClauses, 1) {
        for (uint32_t c = 0; c < numClauses_; ++c) {
            clausePolarity_[c] = (c % 2 == 0) ? 1 : -1;
        }
    }

    // Na inferência uma cláusula vazia vale false; no treino vale true (TM original),
    // espelhando KotlinTsetlinMachine.evaluateClause(training).
    bool evaluateClause(uint32_t clauseIdx, const std::vector<uint8_t>& features, bool training = false) const {
        if (clauseIdx >= numClauses_ || features.size() < numFeatures_) return false;
        const size_t offset = static_cast<size_t>(clauseIdx) * static_cast<size_t>(numFeatures_) * 2ULL;
        bool hasIncludedLiteral = false;
        for (uint32_t f = 0; f < numFeatures_; ++f) {
            const bool includePos = taStates_[offset + f] > numStates_;
            const bool includeNeg = taStates_[offset + numFeatures_ + f] > numStates_;
            if (includePos) {
                hasIncludedLiteral = true;
                if (features[f] == 0) return false;
            }
            if (includeNeg) {
                hasIncludedLiteral = true;
                if (features[f] != 0) return false;
            }
        }
        return hasIncludedLiteral || training;
    }

    int32_t predictVoteEarlyExit(const std::vector<uint8_t>& features) const {
        if (features.size() < numFeatures_) return 0;
        int32_t voteSum = 0;
        int32_t remainingClauses = static_cast<int32_t>(numClauses_);
        for (uint32_t c = 0; c < numClauses_; ++c) {
            if (evaluateClause(c, features)) {
                voteSum += clausePolarity_[c];
            }
            --remainingClauses;
            if (std::abs(voteSum) > thresholdT_ + remainingClauses) {
                break;
            }
        }
        return voteSum;
    }

    float confidenceScore(const std::vector<uint8_t>& features) const {
        const int32_t v = std::clamp(predictVoteEarlyExit(features), -thresholdT_, thresholdT_);
        return static_cast<float>(std::abs(v)) / static_cast<float>(thresholdT_);
    }

    void trainStep(const std::vector<uint8_t>& features, bool targetPositive) {
        if (features.size() < numFeatures_) return;
        const int32_t vote = std::clamp(predictVoteEarlyExit(features), -thresholdT_, thresholdT_);
        const int32_t targetSign = targetPositive ? 1 : -1;
        const float updateProb = static_cast<float>(thresholdT_ - targetSign * vote) /
                                 static_cast<float>(2 * thresholdT_);
        for (uint32_t c = 0; c < numClauses_; ++c) {
            if (rng_.nextFloat() >= updateProb) continue;
            const bool clauseMatches = evaluateClause(c, features, true);
            const bool isPositivePolarity = (clausePolarity_[c] == targetSign);
            if (isPositivePolarity) {
                applyTypeIFeedback(c, features, clauseMatches);
            } else if (clauseMatches) {
                applyTypeIIFeedback(c, features);
            }
        }
    }

    std::vector<uint8_t> serializeBinary() const {
        // V2 inclui hiperparâmetros e PRNG; sem isso o treino após restaurar não é reproduzível.
        std::vector<uint8_t> out;
        out.reserve(36 + numClauses_ + taStates_.size() * 4ULL);
        auto writeU32 = [&](uint32_t v) {
            uint8_t buf[4];
            std::memcpy(buf, &v, 4);
            out.insert(out.end(), buf, buf + 4);
        };
        auto writeI32 = [&](int32_t v) { writeU32(static_cast<uint32_t>(v)); };
        uint32_t specificityBits = 0;
        std::memcpy(&specificityBits, &specificityS_, sizeof(specificityBits));
        writeU32(0x32545347U); // "GST2"
        writeU32(2U);
        writeU32(numFeatures_);
        writeU32(numClauses_);
        writeI32(numStates_);
        writeI32(thresholdT_);
        writeU32(specificityBits);
        const uint64_t rs = rng_.state();
        writeU32(static_cast<uint32_t>(rs & 0xffffffffULL));
        writeU32(static_cast<uint32_t>(rs >> 32));
        for (int8_t p : clausePolarity_) out.push_back(static_cast<uint8_t>(p));
        for (int32_t st : taStates_) writeI32(st);
        return out;
    }

    bool deserializeBinary(const std::vector<uint8_t>& blob) {
        constexpr uint32_t kMagicV1 = 0x47525354U;
        constexpr uint32_t kMagicV2 = 0x32545347U;
        if (blob.size() < 20) return false;
        size_t pos = 0;
        auto readU32 = [&]() -> uint32_t {
            uint32_t v = 0;
            std::memcpy(&v, blob.data() + pos, 4);
            pos += 4;
            return v;
        };
        auto readI32 = [&]() -> int32_t { return static_cast<int32_t>(readU32()); };

        const uint32_t magic = readU32();
        uint32_t nf = 0, nc = 0;
        int32_t ns = 0, tt = 0;
        float specificity = specificityS_;
        uint64_t restoredRng = rng_.state();
        size_t headerBytes = 20;
        if (magic == kMagicV1) {
            nf = readU32(); nc = readU32(); ns = readI32(); tt = readI32();
        } else if (magic == kMagicV2) {
            if (blob.size() < 36 || readU32() != 2U) return false;
            nf = readU32(); nc = readU32(); ns = readI32(); tt = readI32();
            const uint32_t specificityBits = readU32();
            std::memcpy(&specificity, &specificityBits, sizeof(specificity));
            const uint32_t lo = readU32();
            const uint32_t hi = readU32();
            restoredRng = (static_cast<uint64_t>(hi) << 32) | lo;
            headerBytes = 36;
        } else {
            return false;
        }
        if (nf == 0 || nc == 0 ||
            nf > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()) ||
            nc > static_cast<uint32_t>(std::numeric_limits<int32_t>::max()) ||
            ns < 2 || ns > std::numeric_limits<int32_t>::max() / 2 || tt < 1 ||
            !std::isfinite(specificity) || specificity < 1.01f) return false;
        const uint64_t stateCount64 = static_cast<uint64_t>(nc) * static_cast<uint64_t>(nf) * 2ULL;
        if (stateCount64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max() / sizeof(int32_t))) return false;
        const uint64_t expectedBytes = static_cast<uint64_t>(headerBytes) + nc + stateCount64 * 4ULL;
        if (expectedBytes != blob.size()) return false;

        std::vector<int8_t> polarities(nc);
        for (uint32_t c = 0; c < nc; ++c) {
            polarities[c] = static_cast<int8_t>(blob[pos++]);
            const int8_t expected = (c % 2 == 0) ? 1 : -1;
            if (polarities[c] != expected) return false;
        }
        std::vector<int32_t> states(static_cast<size_t>(stateCount64));
        for (int32_t& st : states) {
            st = readI32();
            if (st < 1 || st > ns * 2) return false;
        }

        numFeatures_ = nf;
        numClauses_ = nc;
        numStates_ = ns;
        thresholdT_ = tt;
        specificityS_ = specificity;
        clausePolarity_ = std::move(polarities);
        taStates_ = std::move(states);
        rng_.setState(restoredRng);
        return true;
    }

    uint32_t numFeatures() const { return numFeatures_; }
    uint64_t rngState() const { return rng_.state(); }
    size_t includedLiteralCount() const {
        size_t n = 0;
        for (int32_t st : taStates_) if (st > numStates_) ++n;
        return n;
    }
    uint32_t numClauses() const { return numClauses_; }

private:
    void applyTypeIFeedback(
        uint32_t clauseIdx,
        const std::vector<uint8_t>& features,
        bool clauseMatches
    ) {
        const size_t offset = static_cast<size_t>(clauseIdx) * static_cast<size_t>(numFeatures_) * 2ULL;
        const float pReinforce = (specificityS_ - 1.0f) / specificityS_;
        const float pForget = 1.0f / specificityS_;
        const int32_t maxState = numStates_ * 2;

        for (uint32_t f = 0; f < numFeatures_; ++f) {
            int32_t& posState = taStates_[offset + f];
            int32_t& negState = taStates_[offset + numFeatures_ + f];
            if (clauseMatches && features[f] != 0) {
                if (rng_.nextFloat() < pReinforce && posState < maxState) ++posState;
                if (rng_.nextFloat() < pForget && negState > 1) --negState;
            } else if (clauseMatches && features[f] == 0) {
                if (rng_.nextFloat() < pReinforce && negState < maxState) ++negState;
                if (rng_.nextFloat() < pForget && posState > 1) --posState;
            } else {
                if (rng_.nextFloat() < pForget && posState > 1) --posState;
                if (rng_.nextFloat() < pForget && negState > 1) --negState;
            }
        }
    }

    void applyTypeIIFeedback(uint32_t clauseIdx, const std::vector<uint8_t>& features) {
        const size_t offset = static_cast<size_t>(clauseIdx) * static_cast<size_t>(numFeatures_) * 2ULL;
        for (uint32_t f = 0; f < numFeatures_; ++f) {
            int32_t& posState = taStates_[offset + f];
            int32_t& negState = taStates_[offset + numFeatures_ + f];
            if (features[f] == 0 && posState <= numStates_) ++posState;
            if (features[f] != 0 && negState <= numStates_) ++negState;
        }
    }

    uint32_t numFeatures_;
    uint32_t numClauses_;
    int32_t numStates_;
    float specificityS_;
    int32_t thresholdT_;
    SplitMix64 rng_;
    std::vector<int32_t> taStates_;
    std::vector<int8_t> clausePolarity_;
};

} // namespace girassol::tsetlin