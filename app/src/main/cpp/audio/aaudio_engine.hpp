#pragma once
// Saída de áudio de baixa latência via AAudio (API nativa do Android 8.1+, base do Oboe).
// Modo de desempenho POWER_SAVING: prioriza bateria para operação 24/7.
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

#include "ambient_binaural_synth.hpp"

namespace girassol::audio {

class AAudioEngine {
public:
    AAudioEngine();
    ~AAudioEngine();
    bool start();
    void stop();
    bool running() const { return running_.load(); }
    AmbientBinauralSynth& synth() { return *synth_; }
    int32_t sampleRate() const { return sampleRate_; }
    std::string lastError() const;
    // usado pelo callback
    void renderCallback(float* out, int32_t frames) { synth_->render(out, frames); }
    void onError(int32_t code);

private:
    std::unique_ptr<AmbientBinauralSynth> synth_;
    void* stream_ = nullptr;  // AAudioStream*
    std::atomic<bool> running_{false};
    int32_t sampleRate_ = 48000;
    mutable std::mutex mu_;
    std::string lastError_;
};

}  // namespace girassol::audio
