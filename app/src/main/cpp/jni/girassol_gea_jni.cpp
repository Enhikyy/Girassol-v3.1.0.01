#include <jni.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "../cognitive/tsetlin_machine.hpp"
#include "../evolution/map_elites_aurora.hpp"
#include "../navigation/recast_rvo2_nav.hpp"
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
std::unordered_map<int64_t, std::unique_ptr<girassol::nav::DistrictNavMesh>> gNavPool;
std::unordered_map<int64_t, std::unique_ptr<girassol::audio::BinauralHrtfMixer>> gAudioPool;
std::unordered_map<int64_t, std::unique_ptr<girassol::forge::RealTimeLoraAdapter>> gLoraPool;

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

// ==================== 3. NAVMESH + RVO2 / ORCA ====================

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeCreateNavWorld(
    JNIEnv*, jobject, jint gridW, jint gridH, jfloat cellSize, jfloat originX, jfloat originZ
) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const int64_t id = gNextHandle++;
    gNavPool[id] = std::make_unique<girassol::nav::DistrictNavMesh>(gridW, gridH, cellSize, originX, originZ);
    return id;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeDestroyNavWorld(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gNavPool.erase(handle);
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeNavBakeObstacles(
    JNIEnv* env, jobject, jlong handle, jfloatArray flatBoxesMinXMinZMaxXMaxZ, jfloat clearance
) {
    if (!flatBoxesMinXMinZMaxXMaxZ) return;
    const jsize len = env->GetArrayLength(flatBoxesMinXMinZMaxXMaxZ);
    std::vector<float> raw(static_cast<size_t>(len));
    env->GetFloatArrayRegion(flatBoxesMinXMinZMaxXMaxZ, 0, len, raw.data());

    std::vector<girassol::nav::ObstacleBox2D> boxes;
    for (size_t i = 0; i + 3 < raw.size(); i += 4) {
        boxes.push_back({raw[i], raw[i + 1], raw[i + 2], raw[i + 3]});
    }

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gNavPool.find(handle);
    if (it != gNavPool.end()) {
        it->second->bakeObstacles(boxes, clearance);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeNavFindPath(
    JNIEnv* env, jobject, jlong handle, jfloat startX, jfloat startZ, jfloat goalX, jfloat goalZ
) {
    std::vector<girassol::nav::Vec2> path;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        auto it = gNavPool.find(handle);
        if (it != gNavPool.end()) {
            path = it->second->findStraightPath({startX, startZ}, {goalX, goalZ});
        }
    }
    std::vector<float> flat;
    flat.reserve(path.size() * 2);
    for (const auto& pt : path) {
        flat.push_back(pt.x);
        flat.push_back(pt.z);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(flat.size()));
    if (!flat.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    }
    return out;
}

// ==================== 4. ÁUDIO BINAURAL HRTF ====================

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeCreateBinauralMixer(
    JNIEnv*, jobject, jint sampleRate
) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const int64_t id = gNextHandle++;
    gAudioPool[id] = std::make_unique<girassol::audio::BinauralHrtfMixer>(static_cast<uint32_t>(sampleRate));
    return id;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeDestroyBinauralMixer(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gAudioPool.erase(handle);
}

// ==================== 5. TREINAMENTO LORA TEMPO REAL ====================

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeCreateLoraAdapter(
    JNIEnv*, jobject, jint inDim, jint outDim, jint rank, jfloat alpha, jlong seed
) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const int64_t id = gNextHandle++;
    gLoraPool[id] = std::make_unique<girassol::forge::RealTimeLoraAdapter>(
        static_cast<uint32_t>(inDim),
        static_cast<uint32_t>(outDim),
        static_cast<uint32_t>(rank),
        alpha,
        static_cast<uint64_t>(seed)
    );
    return id;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeDestroyLoraAdapter(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gLoraPool.erase(handle);
}

JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLoraTrainStepMse(
    JNIEnv* env, jobject, jlong handle, jfloatArray baseOutArr, jfloatArray xArr, jfloatArray targetYArr,
    jfloat lrA, jfloat ewcLambda
) {
    if (!baseOutArr || !xArr || !targetYArr) return 0.0f;
    auto readFloats = [&](jfloatArray arr) {
        const jsize n = env->GetArrayLength(arr);
        std::vector<float> v(static_cast<size_t>(n));
        env->GetFloatArrayRegion(arr, 0, n, v.data());
        return v;
    };
    const auto baseOut = readFloats(baseOutArr);
    const auto x = readFloats(xArr);
    const auto targetY = readFloats(targetYArr);

    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gLoraPool.find(handle);
    return (it != gLoraPool.end()) ? it->second->trainStepMse(baseOut, x, targetY, lrA, ewcLambda) : 0.0f;
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_GeaNativeBridge_nativeLoraConsolidateEwc(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    auto it = gLoraPool.find(handle);
    if (it != gLoraPool.end()) it->second->consolidateEwcAnchor();
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