#include <jni.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "../cognitive/tsetlin_machine.hpp"
#include "../evolution/map_elites_aurora.hpp"
#include "../navigation/grid_astar.hpp"
#include "../audio/aaudio_engine.hpp"
#include "../llm/llm_engine.hpp"
#include <cmath>
#include <cstring>
#include "../audio/binaural_hrtf_spatializer.hpp"
#include "../forge/lora_trainer_realtime.hpp"
#include "../hardware/soc_hardware_probe.hpp"
#include <string>

namespace {

struct AuroraArchiveBundle {
    girassol::qd::AuroraBehavioralEncoder encoder;
    girassol::qd::MapElitesArchive archive;
    AuroraArchiveBundle(size_t dim, uint32_t bins) : encoder(dim), archive(bins) {}
};

std::mutex gRegistryMutex;
int64_t gNextHandle = 1;

std::unordered_map<int64_t, std::unique_ptr<girassol::tsetlin::PersistentTsetlinMachine>> gTsetlinPool;
std::unordered_map<int64_t, std::unique_ptr<AuroraArchiveBundle>> gQdPool;
std::mutex gAudioMutex;
std::unique_ptr<girassol::audio::AAudioEngine> gAudio;
girassol::llm::LlmEngine gLlm;

// NewStringUTF exige "Modified UTF-8" válido; tokens podem cortar sequências multibyte.
std::string sanitizeUtf8(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : 0;
        if (len == 0 || i + len > in.size()) { ++i; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) if ((static_cast<unsigned char>(in[i + k]) >> 6) != 0x2) ok = false;
        if (ok && c != 0) out.append(in, i, len);
        i += ok ? len : 1;
    }
    return out;
}

} // namespace

extern "C" {

// ==================== 1. TSETLIN MACHINE ====================

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeCreateTsetlin(
    JNIEnv*, jobject, jint numFeatures, jint numClauses, jint numStates, jfloat specificityS, jint thresholdT, jlong seed
) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const int64_t id = gNextHandle++;
    gTsetlinPool[id] = std::make_unique<girassol::tsetlin::PersistentTsetlinMachine>(
        static_cast<uint32_t>(numFeatures),
        static_cast<uint32_t>(numClauses),
        static_cast<int32_t>(numStates),
        static_cast<float>(specificityS),
        static_cast<int32_t>(thresholdT),
        static_cast<uint64_t>(seed)
    );
    return id;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeDestroyTsetlin(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gTsetlinPool.erase(handle);
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeTsetlinTrainStep(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArr, jboolean targetPositive
) {
    if (!featuresArr) return;
    const jsize len = env->GetArrayLength(featuresArr);
    std::vector<uint8_t> feats(static_cast<size_t>(len));
    env->GetByteArrayRegion(featuresArr, 0, len, reinterpret_cast<jbyte*>(feats.data()));

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gTsetlinPool.find(handle);
    if (it != gTsetlinPool.end()) {
        it->second->trainStep(feats, targetPositive == JNI_TRUE);
    }
}

JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeTsetlinPredictVote(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArr
) {
    if (!featuresArr) return 0;
    const jsize len = env->GetArrayLength(featuresArr);
    std::vector<uint8_t> feats(static_cast<size_t>(len));
    env->GetByteArrayRegion(featuresArr, 0, len, reinterpret_cast<jbyte*>(feats.data()));

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gTsetlinPool.find(handle);
    return (it != gTsetlinPool.end()) ? it->second->predictVoteEarlyExit(feats) : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeTsetlinConfidence(
    JNIEnv* env, jobject, jlong handle, jbyteArray featuresArr
) {
    if (!featuresArr) return 0.0f;
    const jsize len = env->GetArrayLength(featuresArr);
    std::vector<uint8_t> feats(static_cast<size_t>(len));
    env->GetByteArrayRegion(featuresArr, 0, len, reinterpret_cast<jbyte*>(feats.data()));

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gTsetlinPool.find(handle);
    return (it != gTsetlinPool.end()) ? it->second->confidenceScore(feats) : 0.0f;
}

JNIEXPORT jbyteArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeTsetlinSerialize(
    JNIEnv* env, jobject, jlong handle
) {
    std::vector<uint8_t> blob;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gTsetlinPool.find(handle);
        if (it != gTsetlinPool.end()) blob = it->second->serializeBinary();
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(blob.size()));
    if (!blob.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(blob.size()), reinterpret_cast<const jbyte*>(blob.data()));
    }
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeTsetlinDeserialize(
    JNIEnv* env, jobject, jlong handle, jbyteArray blobArr
) {
    if (!blobArr) return JNI_FALSE;
    const jsize len = env->GetArrayLength(blobArr);
    std::vector<uint8_t> blob(static_cast<size_t>(len));
    env->GetByteArrayRegion(blobArr, 0, len, reinterpret_cast<jbyte*>(blob.data()));

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gTsetlinPool.find(handle);
    if (it == gTsetlinPool.end()) return JNI_FALSE;
    return it->second->deserializeBinary(blob) ? JNI_TRUE : JNI_FALSE;
}

// ==================== 2. MAP-ELITES + AURORA ====================

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeCreateMapElites(
    JNIEnv*, jobject, jint trajectoryDim, jint binsPerAxis
) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const int64_t id = gNextHandle++;
    gQdPool[id] = std::make_unique<AuroraArchiveBundle>(
        static_cast<size_t>(std::max(2, trajectoryDim)),
        static_cast<uint32_t>(std::max(2, binsPerAxis))
    );
    return id;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeDestroyMapElites(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gQdPool.erase(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeMapElitesSubmit(
    JNIEnv* env, jobject, jlong handle, jlong citizenId, jint generation,
    jfloat fitness, jfloat explicitAxis, jfloatArray trajectoryStatsArr, jfloatArray weightsArr
) {
    std::vector<float> traj;
    if (trajectoryStatsArr) {
        const jsize n = env->GetArrayLength(trajectoryStatsArr);
        traj.resize(static_cast<size_t>(n));
        env->GetFloatArrayRegion(trajectoryStatsArr, 0, n, traj.data());
    }
    std::vector<float> weights;
    if (weightsArr) {
        const jsize n = env->GetArrayLength(weightsArr);
        weights.resize(static_cast<size_t>(n));
        env->GetFloatArrayRegion(weightsArr, 0, n, weights.data());
    }

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gQdPool.find(handle);
    if (it == gQdPool.end()) return JNI_FALSE;

    const auto latent2D = it->second->encoder.projectAndAdapt(traj);
    girassol::qd::EliteNicheCell cell;
    cell.citizenId = static_cast<uint64_t>(citizenId);
    cell.generation = static_cast<uint32_t>(generation);
    cell.fitness = fitness;
    cell.behaviorDescriptor = {explicitAxis, latent2D[0], latent2D[1]};
    cell.policyWeights = std::move(weights);
    return it->second->archive.submitCandidate(cell) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeMapElitesCoverage(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gQdPool.find(handle);
    return (it != gQdPool.end()) ? it->second->archive.coverageRatio() : 0.0f;
}

// ==================== 3. NAVEGAÇÃO (A* estigmérgico, espelho do Kotlin) ====================

JNIEXPORT jintArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeNavFindPathGrid(
    JNIEnv* env, jobject, jint w, jintArray tilesArr, jint sx, jint sz, jint gx, jint gz,
    jfloatArray trailArr, jfloatArray dangerArr
) {
    if (!tilesArr || w <= 0 || env->GetArrayLength(tilesArr) < w * w) return env->NewIntArray(0);
    thread_local girassol::nav::GridAStar astar;
    thread_local std::vector<int32_t> tiles;
    thread_local std::vector<float> trail, danger;
    const jsize n = w * w;
    tiles.resize(static_cast<size_t>(n));
    env->GetIntArrayRegion(tilesArr, 0, n, tiles.data());
    const float* tp = nullptr;
    const float* dp = nullptr;
    if (trailArr && env->GetArrayLength(trailArr) >= n) {
        trail.resize(static_cast<size_t>(n));
        env->GetFloatArrayRegion(trailArr, 0, n, trail.data());
        tp = trail.data();
    }
    if (dangerArr && env->GetArrayLength(dangerArr) >= n) {
        danger.resize(static_cast<size_t>(n));
        env->GetFloatArrayRegion(dangerArr, 0, n, danger.data());
        dp = danger.data();
    }
    const auto path = astar.findPath(w, tiles.data(), sx, sz, gx, gz, tp, dp);
    jintArray out = env->NewIntArray(static_cast<jsize>(path.size()));
    if (out && !path.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(path.size()), path.data());
    return out;
}

// ==================== 4. ÁUDIO BINAURAL (AAudio) ====================

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gAudioMutex);
    if (!gAudio) gAudio = std::make_unique<girassol::audio::AAudioEngine>();
    return gAudio->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gAudioMutex);
    if (gAudio) gAudio->stop();
    gAudio.reset();
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioSetParams(
    JNIEnv*, jobject, jfloat stability, jfloat mood, jfloat tension, jfloat gain
) {
    std::lock_guard<std::mutex> lock(gAudioMutex);
    if (!gAudio) return;
    girassol::audio::AmbientParams p;
    p.stability = stability; p.mood = mood; p.tension = tension; p.masterGain = gain;
    gAudio->synth().setParams(p);
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioSpeech(JNIEnv*, jobject, jfloat x, jfloat z, jfloat pitch) {
    std::lock_guard<std::mutex> lock(gAudioMutex);
    if (gAudio && gAudio->running()) gAudio->synth().pushSpeech(x, z, pitch);
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioStatus(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(gAudioMutex);
    std::string s = "parado";
    if (gAudio) {
        s = gAudio->running() ? ("tocando " + std::to_string(gAudio->sampleRate()) + " Hz, quadros=" +
                                 std::to_string(gAudio->synth().framesRendered()))
                              : ("erro: " + gAudio->lastError());
    }
    return env->NewStringUTF(s.c_str());
}

// Renderização offline (diagnóstico/benchmark): [rms, pico, quadros].
JNIEXPORT jfloatArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeAudioOfflineProbe(JNIEnv* env, jobject, jfloat stability, jint frames) {
    girassol::audio::AmbientBinauralSynth synth(48000);
    girassol::audio::AmbientParams p; p.stability = stability; p.masterGain = 0.5f;
    synth.setParams(p);
    synth.pushSpeech(2.0f, 1.0f, 0.5f);
    std::vector<float> buf(static_cast<size_t>(std::max(1, static_cast<int>(frames))) * 2);
    synth.render(buf.data(), std::max(1, static_cast<int>(frames)));
    double sum = 0; float peak = 0;
    for (float v : buf) { sum += v * v; peak = std::max(peak, std::fabs(v)); }
    const jfloat vals[3] = {static_cast<float>(std::sqrt(sum / buf.size())), peak, static_cast<float>(frames)};
    jfloatArray out = env->NewFloatArray(3);
    if (out) env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

// ==================== 5. TREINAMENTO LORA (espelho do LoraMath) ====================

// Retorna estado empacotado' + [mseFirst, mseLast, bits(step)].
JNIEXPORT jfloatArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLoraTrain(
    JNIEnv* env, jobject, jint inDim, jint outDim, jint rank, jfloat scaling, jfloatArray packedArr, jint step,
    jfloatArray xsArr, jfloatArray ysArr, jint n, jint epochs, jfloat lr, jfloat ewc
) {
    if (!packedArr || !xsArr || !ysArr || inDim <= 0 || outDim <= 0 || rank <= 0 || n < 0) return nullptr;
    girassol::forge::LoraState s(inDim, outDim, rank, scaling);
    const jsize pn = env->GetArrayLength(packedArr);
    if (static_cast<size_t>(pn) < s.packedSize()) return nullptr;
    std::vector<float> packed(static_cast<size_t>(pn));
    env->GetFloatArrayRegion(packedArr, 0, pn, packed.data());
    s.unpack(packed.data(), packed.size());
    s.step = step;
    if (env->GetArrayLength(xsArr) < n * inDim || env->GetArrayLength(ysArr) < n * outDim) return nullptr;
    std::vector<float> xs(static_cast<size_t>(n * inDim)), ys(static_cast<size_t>(n * outDim));
    if (n > 0) {
        env->GetFloatArrayRegion(xsArr, 0, n * inDim, xs.data());
        env->GetFloatArrayRegion(ysArr, 0, n * outDim, ys.data());
    }
    const auto mse = girassol::forge::LoraMath::trainEpochs(s, xs.data(), ys.data(), n, epochs, lr, ewc);
    std::vector<float> out = s.pack();
    out.push_back(mse.first);
    out.push_back(mse.second);
    float stepBits;
    std::memcpy(&stepBits, &s.step, sizeof(float));
    out.push_back(stepBits);
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (arr) env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

// ==================== 5b. MOTOR LLM (llama.cpp + GGUF + GBNF) ====================

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmAvailable(JNIEnv*, jobject) {
    return girassol::llm::LlmEngine::available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmLoad(JNIEnv* env, jobject, jstring pathStr, jint threads, jint nCtx) {
    const char* p = env->GetStringUTFChars(pathStr, nullptr);
    std::string path(p ? p : "");
    if (p) env->ReleaseStringUTFChars(pathStr, p);
    std::string err;
    bool ok = gLlm.load(path, threads, nCtx, &err);
    const std::string s = ok ? std::string() : err;
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmUnload(JNIEnv*, jobject) {
    gLlm.requestAbort();
    gLlm.unload();
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmAbort(JNIEnv*, jobject) {
    gLlm.requestAbort();
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmDescribe(JNIEnv* env, jobject) {
    const std::string s = gLlm.loaded() ? (gLlm.description() + "|" + std::to_string(gLlm.modelBytes())) : std::string();
    return env->NewStringUTF(s.c_str());
}

// Retorna [texto, erro, promptTokens, genTokens, millis, tokens/s].
JNIEXPORT jobjectArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLlmGenerate(
    JNIEnv* env, jobject, jstring sysStr, jstring userStr, jstring grammarStr, jint maxTokens
) {
    auto read = [&](jstring js) {
        const char* c = js ? env->GetStringUTFChars(js, nullptr) : nullptr;
        std::string s(c ? c : "");
        if (c) env->ReleaseStringUTFChars(js, c);
        return s;
    };
    const auto r = gLlm.generate(read(sysStr), read(userStr), read(grammarStr), maxTokens);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(6, strCls, nullptr);
    const std::string vals[6] = {sanitizeUtf8(r.text), r.error, std::to_string(r.promptTokens), std::to_string(r.genTokens),
                                 std::to_string(r.millis), std::to_string(r.tokensPerSecond)};
    for (int i = 0; i < 6; ++i) {
        jstring js = env->NewStringUTF(vals[i].c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

// ==================== 6. HARDWARE / AUTOTESTE ====================

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeProbeHardware(JNIEnv* env, jobject) {
    const std::string s = girassol::hw::probeSoc().toCompactString();
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeSelfTest(JNIEnv* env, jobject) {
    const std::string s = girassol::hw::runSelfTest();
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeBenchmarkTsetlin(JNIEnv* env, jobject, jlong seed, jint iterations) {
    uint64_t checksum = 0;
    const double ips = girassol::hw::benchmarkTsetlin(static_cast<uint64_t>(seed), static_cast<int>(iterations), &checksum);
    jdoubleArray out = env->NewDoubleArray(2);
    if (out == nullptr) return nullptr;
    const jdouble vals[2] = {ips, static_cast<jdouble>(checksum & 0xFFFFFFFFULL)};
    env->SetDoubleArrayRegion(out, 0, 2, vals);
    return out;
}

} // extern "C"