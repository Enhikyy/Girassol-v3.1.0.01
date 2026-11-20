#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace girassol::audio {

struct BinauralListener {
    float x = 0.0f;
    float y = 1.65f;
    float z = 0.0f;
    float yawRad = 0.0f;
};

struct SpatialVoiceSource {
    uint64_t sourceId = 0;
    float x = 0.0f;
    float y = 1.60f;
    float z = 0.0f;
    bool occludedByWall = false;
    float baseGain = 1.0f;
};

class BinauralHrtfMixer {
public:
    explicit BinauralHrtfMixer(uint32_t sampleRate = 48000)
        : sampleRate_(std::max(8000U, sampleRate)),
          delayLineL_(2048, 0.0f),
          delayLineR_(2048, 0.0f),
          reverbTail_(static_cast<size_t>(std::max(8000U, sampleRate)) / 4, 0.0f) {}

    void mixMonoVoiceIntoStereo(
        const float* monoSamples,
        size_t numFrames,
        const SpatialVoiceSource& src,
        const BinauralListener& listener,
        std::vector<float>& outInterleavedStereo
    ) {
        if (!monoSamples || numFrames == 0) return;
        if (outInterleavedStereo.size() < numFrames * 2) {
            outInterleavedStereo.resize(numFrames * 2, 0.0f);
        }

        const float dx = src.x - listener.x;
        const float dy = src.y - listener.y;
        const float dz = src.z - listener.z;
        const float dist = std::max(0.75f, std::sqrt(dx * dx + dy * dy + dz * dz));
        const float azimuth = std::atan2(dz, dx) - listener.yawRad;
        const float sinAz = std::sin(azimuth);

        const float headRadius = 0.0875f;
        const float speedOfSound = 343.0f;
        const float itdSec = (headRadius / speedOfSound) * (azimuth + sinAz);
        const int32_t itdSamples = static_cast<int32_t>(
            std::clamp(itdSec * static_cast<float>(sampleRate_), -40.0f, 40.0f)
        );
        const size_t delayL = static_cast<size_t>(std::max(0, itdSamples));
        const size_t delayR = static_cast<size_t>(std::max(0, -itdSamples));

        const float distAtten = std::clamp(1.0f / (1.0f + 0.14f * dist), 0.04f, 1.0f);
        const float occlusionGain = src.occludedByWall ? 0.38f : 1.0f;
        const float gainL = src.baseGain * distAtten * occlusionGain * std::clamp(0.72f - 0.28f * sinAz, 0.20f, 1.0f);
        const float gainR = src.baseGain * distAtten * occlusionGain * std::clamp(0.72f + 0.28f * sinAz, 0.20f, 1.0f);

        for (size_t i = 0; i < numFrames; ++i) {
            const float dry = monoSamples[i];
            delayLineL_[writeIdx_ & 2047] = dry;
            delayLineR_[writeIdx_ & 2047] = dry;

            const float sL = delayLineL_[(writeIdx_ - delayL) & 2047] * gainL;
            const float sR = delayLineR_[(writeIdx_ - delayR) & 2047] * gainR;

            const float rev = reverbTail_[revIdx_];
            reverbTail_[revIdx_] = 0.32f * rev + 0.18f * (sL + sR);
            revIdx_ = (revIdx_ + 1) % reverbTail_.size();

            outInterleavedStereo[i * 2] += sL + 0.22f * rev;
            outInterleavedStereo[i * 2 + 1] += sR + 0.22f * rev;
            ++writeIdx_;
        }
    }

    static float computeTrautoniumDuckingGain(bool anyCitizenSpeaking, float currentDuckGain, float dt) {
        const float target = anyCitizenSpeaking ? 0.22f : 1.0f;
        const float rate = anyCitizenSpeaking ? 12.0f : 3.5f;
        return currentDuckGain + (target - currentDuckGain) * std::clamp(rate * dt, 0.0f, 1.0f);
    }

private:
    uint32_t sampleRate_;
    std::vector<float> delayLineL_;
    std::vector<float> delayLineR_;
    std::vector<float> reverbTail_;
    size_t writeIdx_ = 0;
    size_t revIdx_ = 0;
};

} // namespace girassol::audio