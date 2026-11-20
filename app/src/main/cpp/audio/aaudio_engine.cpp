#include "aaudio_engine.hpp"

#if defined(__ANDROID__)
#include <aaudio/AAudio.h>
#include <android/log.h>
#endif

namespace girassol::audio {

#if defined(__ANDROID__)
namespace {
aaudio_data_callback_result_t dataCallback(AAudioStream*, void* user, void* audioData, int32_t numFrames) {
    static_cast<AAudioEngine*>(user)->renderCallback(static_cast<float*>(audioData), numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
void errorCallback(AAudioStream*, void* user, aaudio_result_t error) {
    static_cast<AAudioEngine*>(user)->onError(static_cast<int32_t>(error));
}
}  // namespace
#endif

AAudioEngine::AAudioEngine() : synth_(std::make_unique<AmbientBinauralSynth>(48000)) {}

AAudioEngine::~AAudioEngine() { stop(); }

std::string AAudioEngine::lastError() const {
    std::lock_guard<std::mutex> lock(mu_);
    return lastError_;
}

void AAudioEngine::onError(int32_t code) {
    std::lock_guard<std::mutex> lock(mu_);
    lastError_ = "aaudio erro " + std::to_string(code);
    running_.store(false);
}

bool AAudioEngine::start() {
#if defined(__ANDROID__)
    if (running_.load()) return true;
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || b == nullptr) {
        std::lock_guard<std::mutex> lock(mu_);
        lastError_ = "createStreamBuilder falhou";
        return false;
    }
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, 48000);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    AAudioStreamBuilder_setDataCallback(b, dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(b, errorCallback, this);
    AAudioStream* s = nullptr;
    const aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || s == nullptr) {
        std::lock_guard<std::mutex> lock(mu_);
        lastError_ = std::string("openStream: ") + AAudio_convertResultToText(r);
        return false;
    }
    sampleRate_ = AAudioStream_getSampleRate(s);
    synth_ = std::make_unique<AmbientBinauralSynth>(static_cast<uint32_t>(sampleRate_));
    if (AAudioStream_requestStart(s) != AAUDIO_OK) {
        AAudioStream_close(s);
        std::lock_guard<std::mutex> lock(mu_);
        lastError_ = "requestStart falhou";
        return false;
    }
    stream_ = s;
    running_.store(true);
    return true;
#else
    return false;
#endif
}

void AAudioEngine::stop() {
#if defined(__ANDROID__)
    if (stream_ != nullptr) {
        auto* s = static_cast<AAudioStream*>(stream_);
        AAudioStream_requestStop(s);
        AAudioStream_close(s);
        stream_ = nullptr;
    }
#endif
    running_.store(false);
}

}  // namespace girassol::audio
