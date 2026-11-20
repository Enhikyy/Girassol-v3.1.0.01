#pragma once
// Sintetizador ambiente binaural. Um zumbido de baixa frequência (fluorescente/liminar)
// com batimento binaural: a diferença de frequência entre os ouvidos acompanha a
// estabilidade social (estável → ~4 Hz, crise → ~13 Hz); a emoção coletiva desloca o
// timbre (humor alto → mais harmônicos claros) e a tensão adiciona um ruído de sala.
// Falas dos cidadãos geram "blips" espacializados pelo BinauralHrtfMixer (ITD/ILD).
// Audio ducking: enquanto houver fala (blip espacial ou TTS do Android), a trilha
// Trautonium (drone + harmônicos) desce para ~0,22 com ataque rápido e liberação lenta
// (BinauralHrtfMixer::computeTrautoniumDuckingGain).
// Não participa da simulação — o determinismo do mundo não depende do áudio.
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <vector>

#include "binaural_hrtf_spatializer.hpp"

namespace girassol::audio {

struct AmbientParams {
    float stability = 0.7f;   // 0..1  (1 − PSI normalizado)
    float mood = 0.0f;        // −1..1 (emoção coletiva)
    float tension = 0.2f;     // 0..1
    float masterGain = 0.35f; // 0..1
};

class AmbientBinauralSynth {
public:
    explicit AmbientBinauralSynth(uint32_t sampleRate = 48000)
        : sr_(static_cast<float>(std::max(8000U, sampleRate))), mixer_(sampleRate) {}

    void setParams(const AmbientParams& p) {
        std::lock_guard<std::mutex> lock(mu_);
        target_ = p;
    }

    // TTS externo (Android TextToSpeech) falando: também abaixa a trilha.
    void setExternalSpeech(bool speaking) { externalSpeech_.store(speaking); }
    float duckGain() const { return duckGainPublished_.load(); }

    // Enfileira um blip de fala em (x, z) relativo ao ouvinte; pitch 0..1.
    void pushSpeech(float x, float z, float pitch) {
        std::lock_guard<std::mutex> lock(mu_);
        if (voices_.size() >= 6) voices_.erase(voices_.begin());
        Voice v;
        v.src.sourceId = ++voiceSeq_;
        v.src.x = x;
        v.src.z = z;
        v.src.baseGain = 0.8f;
        v.freq = 220.0f + 260.0f * std::clamp(pitch, 0.0f, 1.0f);
        v.samplesLeft = static_cast<int32_t>(sr_ * 0.22f);
        v.total = v.samplesLeft;
        voices_.push_back(v);
    }

    // Renderiza `frames` quadros estéreo intercalados em `out` (sobrescreve).
    void render(float* out, int32_t frames) {
        if (!out || frames <= 0) return;
        AmbientParams tgt;
        std::vector<Voice> voices;
        {
            std::lock_guard<std::mutex> lock(mu_);
            tgt = target_;
            voices.swap(voices_);
        }
        const float twoPi = 6.28318530718f;
        const bool speaking = !voices.empty() || externalSpeech_.load();
        const float duckStart = duckGain_;
        duckGain_ = BinauralHrtfMixer::computeTrautoniumDuckingGain(speaking, duckGain_, static_cast<float>(frames) / sr_);
        duckGainPublished_.store(duckGain_);
        for (int32_t i = 0; i < frames; ++i) {
            const float duck = duckStart + (duckGain_ - duckStart) * (static_cast<float>(i) / static_cast<float>(frames));
            // suavização exponencial dos parâmetros (~200 ms)
            cur_.stability += (tgt.stability - cur_.stability) * 0.0001f;
            cur_.mood += (tgt.mood - cur_.mood) * 0.0001f;
            cur_.tension += (tgt.tension - cur_.tension) * 0.0001f;
            cur_.masterGain += (tgt.masterGain - cur_.masterGain) * 0.0005f;
            const float beat = 4.0f + 9.0f * (1.0f - std::clamp(cur_.stability, 0.0f, 1.0f));
            const float carrier = 96.0f + 14.0f * cur_.mood;
            phaseL_ += twoPi * carrier / sr_;
            phaseR_ += twoPi * (carrier + beat) / sr_;
            phaseH_ += twoPi * 120.0f / sr_;  // zumbido de reator fluorescente
            if (phaseL_ > twoPi) phaseL_ -= twoPi;
            if (phaseR_ > twoPi) phaseR_ -= twoPi;
            if (phaseH_ > twoPi) phaseH_ -= twoPi;
            const float bright = 0.25f + 0.2f * std::clamp(cur_.mood, -1.0f, 1.0f);
            const float hum = 0.06f * std::sin(phaseH_) + 0.02f * std::sin(2.0f * phaseH_);
            // ruído rosa aproximado (filtro de 1 polo) — "tom de sala"
            noiseState_ = noiseState_ * 6364136223846793005ULL + 1442695040888963407ULL;
            const float white = static_cast<float>(static_cast<int32_t>(noiseState_ >> 33)) / 2147483648.0f;
            pink_ = 0.985f * pink_ + 0.015f * white;
            const float room = pink_ * (0.5f + 2.5f * cur_.tension);
            const float l = std::sin(phaseL_) + bright * std::sin(3.0f * phaseL_) * 0.3f;
            const float r = std::sin(phaseR_) + bright * std::sin(3.0f * phaseR_) * 0.3f;
            out[i * 2] = cur_.masterGain * (duck * (0.18f * l + hum) + room);
            out[i * 2 + 1] = cur_.masterGain * (duck * (0.18f * r + hum) + room);
        }
        // blips de fala espacializados
        if (!voices.empty()) {
            mono_.assign(static_cast<size_t>(frames), 0.0f);
            stereo_.assign(static_cast<size_t>(frames) * 2, 0.0f);
            BinauralListener listener;
            std::vector<Voice> alive;
            for (auto& v : voices) {
                std::fill(mono_.begin(), mono_.end(), 0.0f);
                const int32_t n = std::min(frames, v.samplesLeft);
                for (int32_t i = 0; i < n; ++i) {
                    const float t = 1.0f - static_cast<float>(v.samplesLeft - i) / static_cast<float>(v.total);
                    const float env = std::sin(3.14159265f * t);
                    v.phase += twoPi * v.freq * (1.0f + 0.15f * std::sin(twoPi * 7.0f * t)) / sr_;
                    mono_[i] = 0.25f * env * std::sin(v.phase);
                }
                v.samplesLeft -= n;
                mixer_.mixMonoVoiceIntoStereo(mono_.data(), static_cast<size_t>(frames), v.src, listener, stereo_);
                if (v.samplesLeft > 0) alive.push_back(v);
            }
            for (int32_t i = 0; i < frames * 2; ++i) out[i] += cur_.masterGain * stereo_[i];
            std::lock_guard<std::mutex> lock(mu_);
            alive.insert(alive.end(), voices_.begin(), voices_.end());
            voices_.swap(alive);
        }
        for (int32_t i = 0; i < frames * 2; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
        framesRendered_.fetch_add(static_cast<uint64_t>(frames));
    }

    uint64_t framesRendered() const { return framesRendered_.load(); }

private:
    struct Voice {
        SpatialVoiceSource src;
        float freq = 300.0f;
        float phase = 0.0f;
        int32_t samplesLeft = 0;
        int32_t total = 1;
    };
    float sr_;
    BinauralHrtfMixer mixer_;
    std::mutex mu_;
    AmbientParams target_;
    AmbientParams cur_;
    std::vector<Voice> voices_;
    uint64_t voiceSeq_ = 0;
    float phaseL_ = 0, phaseR_ = 0, phaseH_ = 0, pink_ = 0;
    uint64_t noiseState_ = 0x9E3779B97F4A7C15ULL;
    std::vector<float> mono_, stereo_;
    float duckGain_ = 1.0f;
    std::atomic<float> duckGainPublished_{1.0f};
    std::atomic<bool> externalSpeech_{false};
    std::atomic<uint64_t> framesRendered_{0};
};

}  // namespace girassol::audio
