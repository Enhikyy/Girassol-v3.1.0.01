// Ponte JNI do núcleo cognitivo C++20 (world/cognition.hpp) — v3.8.
// Classe Kotlin: com.enhikyy.girassol.core.world.NativeCognition.
// Cada função processa o LOTE inteiro do tick (todos os cidadãos) numa única travessia JNI.
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "../common/jni_utf.hpp"
#include "../world/cognition.hpp"

using namespace girassol::cognition;

namespace {

constexpr int kCandD = 35;
constexpr int kCandI = 6;
constexpr int kCitD = 7; // 6 necessidades (ordem Need.entries) + amostra uniforme

void throwIae(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

template <typename T, typename A>
std::vector<T> readArr(JNIEnv* env, A arr);
template <>
std::vector<jlong> readArr<jlong>(JNIEnv* env, jlongArray a) {
    std::vector<jlong> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetLongArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
template <>
std::vector<jdouble> readArr<jdouble>(JNIEnv* env, jdoubleArray a) {
    std::vector<jdouble> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
template <>
std::vector<jint> readArr<jint>(JNIEnv* env, jintArray a) {
    std::vector<jint> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
template <>
std::vector<jfloat> readArr<jfloat>(JNIEnv* env, jfloatArray a) {
    std::vector<jfloat> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}

std::vector<std::string> readStrings(JNIEnv* env, jobjectArray arr, std::vector<uint8_t>* present = nullptr) {
    std::vector<std::string> out;
    const jsize n = arr ? env->GetArrayLength(arr) : 0;
    out.reserve(static_cast<size_t>(n));
    if (present) present->assign(static_cast<size_t>(n), 0);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (present) (*present)[static_cast<size_t>(i)] = s != nullptr;
        out.push_back(girassol::jniutf::fromJava(env, s));
        if (s) env->DeleteLocalRef(s);
    }
    return out;
}

Grid gridOf(const std::vector<jfloat>& data, jint w, jint h, jfloat cell) {
    Grid g;
    if (!data.empty() && w > 0 && h > 0 && static_cast<size_t>(w) * static_cast<size_t>(h) <= data.size()) {
        g.data = data.data();
        g.width = w;
        g.height = h;
        g.cellSize = cell;
    }
    return g;
}

// Cursor sobre o LongArray empacotado da recuperação de memória (com verificação de limites).
struct Cursor {
    const std::vector<jlong>& v;
    size_t i = 0;
    jlong next() {
        if (i >= v.size()) throw std::out_of_range("girassol: lote de memória truncado");
        return v[i++];
    }
    std::vector<int64_t> list() {
        const jlong n = next();
        if (n < 0 || static_cast<size_t>(n) > v.size() - i) throw std::out_of_range("girassol: lista de memória inválida");
        std::vector<int64_t> out(v.begin() + static_cast<std::ptrdiff_t>(i), v.begin() + static_cast<std::ptrdiff_t>(i + static_cast<size_t>(n)));
        i += static_cast<size_t>(n);
        return out;
    }
};

} // namespace

extern "C" {

// ---------------------------------------------------------------- 3. percepção em lote
JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativePerceive(
    JNIEnv* env, jclass, jdoubleArray jNeeds, jintArray jCodes, jintArray jx, jintArray jz,
    jfloatArray jFood, jfloatArray jDanger, jint w, jint h, jfloat cell, jboolean drought, jdoubleArray jSalOut) {
    const auto needs = readArr<jdouble>(env, jNeeds);
    const auto codes = readArr<jint>(env, jCodes);
    const auto xs = readArr<jint>(env, jx);
    const auto zs = readArr<jint>(env, jz);
    const auto food = readArr<jfloat>(env, jFood);
    const auto danger = readArr<jfloat>(env, jDanger);
    const size_t n = xs.size();
    if (zs.size() != n || needs.size() != n * kNeeds || codes.size() != n * kNeeds ||
        !jSalOut || static_cast<size_t>(env->GetArrayLength(jSalOut)) < n * kWorkspaceCap) {
        throwIae(env, "girassol: dimensões inconsistentes no lote de percepção");
        return nullptr;
    }
    const Grid gF = gridOf(food, w, h, cell), gD = gridOf(danger, w, h, cell);
    std::vector<jint> out(n * (1 + kWorkspaceCap), -1);
    std::vector<jdouble> sal(n * kWorkspaceCap, 0.0);
    for (size_t i = 0; i < n; ++i) {
        const float fx = static_cast<float>(xs[i]), fz = static_cast<float>(zs[i]);
        const auto b = broadcast(perceive(&needs[i * kNeeds], &codes[i * kNeeds], gF.read(fx, fz), gD.read(fx, fz), drought == JNI_TRUE));
        out[i * (1 + kWorkspaceCap)] = static_cast<jint>(b.size());
        for (size_t k = 0; k < b.size(); ++k) {
            out[i * (1 + kWorkspaceCap) + 1 + k] = b[k].code;
            sal[i * kWorkspaceCap + k] = b[k].salience;
        }
    }
    env->SetDoubleArrayRegion(jSalOut, 0, static_cast<jsize>(sal.size()), sal.data());
    jintArray res = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!res) return nullptr;
    env->SetIntArrayRegion(res, 0, static_cast<jsize>(out.size()), out.data());
    return res;
}

// ---------------------------------------------------------------- 6–7. inferência ativa + política em lote
JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeCognitionBatch(
    JNIEnv* env, jclass, jdoubleArray jCandD, jintArray jCandI, jintArray jOffsets, jdoubleArray jCitD, jintArray jCurrent,
    jfloatArray jDanger, jfloatArray jSocial, jint w, jint h, jfloat cell,
    jdouble preferred, jdouble switchCost, jdouble gamma,
    jdoubleArray jEfeOut, jdoubleArray jProjOut, jintArray jOrderOut, jdoubleArray jVfeOut) {
    const auto cd = readArr<jdouble>(env, jCandD);
    const auto ci = readArr<jint>(env, jCandI);
    const auto off = readArr<jint>(env, jOffsets);
    const auto cit = readArr<jdouble>(env, jCitD);
    const auto cur = readArr<jint>(env, jCurrent);
    const auto danger = readArr<jfloat>(env, jDanger);
    const auto social = readArr<jfloat>(env, jSocial);
    const size_t nCand = ci.size() / kCandI;
    const size_t nCit = cur.size();
    bool ok = cd.size() == nCand * kCandD && ci.size() == nCand * kCandI && off.size() == nCit + 1 &&
              cit.size() == nCit * kCitD && jEfeOut && jProjOut && jOrderOut && jVfeOut &&
              static_cast<size_t>(env->GetArrayLength(jEfeOut)) >= nCand &&
              static_cast<size_t>(env->GetArrayLength(jProjOut)) >= nCand * kNeeds &&
              static_cast<size_t>(env->GetArrayLength(jOrderOut)) >= nCand &&
              static_cast<size_t>(env->GetArrayLength(jVfeOut)) >= nCit;
    if (ok) {
        if (off.front() != 0 || static_cast<size_t>(off.back()) != nCand) ok = false;
        for (size_t i = 0; ok && i < nCit; ++i) if (off[i] > off[i + 1]) ok = false;
    }
    if (!ok) {
        throwIae(env, "girassol: dimensões inconsistentes no lote de inferência ativa");
        return nullptr;
    }
    const Grid gD = gridOf(danger, w, h, cell), gS = gridOf(social, w, h, cell);
    std::vector<CandidateIn> ins(nCand);
    for (size_t j = 0; j < nCand; ++j) {
        const double* d = &cd[j * kCandD];
        const jint* t = &ci[j * kCandI];
        auto& c = ins[j];
        for (int k = 0; k < kNeeds; ++k) { c.needs[k] = d[k]; c.decay[k] = d[6 + k]; c.gains[k] = d[12 + k]; }
        c.skillEff = d[18]; c.yieldP = d[19]; c.uncertainty = d[20];
        c.cortisol = d[21]; c.dopamine = d[22]; c.curiosity = d[23]; c.epistemicPolicy = d[24];
        c.neural = d[25]; c.socTempC = d[26]; c.economicBonus = d[27]; c.memQ = d[28]; c.novelty = d[29];
        c.oxytocin = d[30]; c.adapterPred = d[31]; c.loraPred = d[32]; c.adapterWeight = d[33]; c.loraWeight = d[34];
        c.travel = t[0]; c.duration = t[1]; c.targetX = t[2]; c.targetZ = t[3]; c.actionOrdinal = t[4];
        c.hasTarget = (t[5] & 1) != 0; c.isSocial = (t[5] & 2) != 0; c.isExplore = (t[5] & 4) != 0; c.loraActive = (t[5] & 8) != 0;
    }
    std::vector<CandidateOut> outs(nCand);
    for (size_t j = 0; j < nCand; ++j) outs[j] = evaluate(ins[j], preferred, gD, gS);
    std::vector<jdouble> efe(nCand), proj(nCand * kNeeds), vfe(nCit);
    std::vector<jint> order(nCand), chosen(nCit, -1);
    for (size_t j = 0; j < nCand; ++j) {
        efe[j] = outs[j].efe;
        for (int k = 0; k < kNeeds; ++k) proj[j * kNeeds + static_cast<size_t>(k)] = outs[j].projected[k];
    }
    for (size_t i = 0; i < nCit; ++i) {
        const size_t a = static_cast<size_t>(off[i]), b = static_cast<size_t>(off[i + 1]);
        vfe[i] = variationalFreeEnergy(&cit[i * kCitD]);
        if (a == b) continue;
        const auto local = sortCandidates(std::span<const CandidateOut>(outs.data() + a, b - a), std::span<const CandidateIn>(ins.data() + a, b - a));
        std::vector<double> e;
        std::vector<int32_t> ords;
        for (size_t k = 0; k < local.size(); ++k) {
            order[a + k] = local[k];
            e.push_back(outs[a + static_cast<size_t>(local[k])].efe);
            ords.push_back(ins[a + static_cast<size_t>(local[k])].actionOrdinal);
        }
        chosen[i] = selectPolicy(e, ords, cur[i], switchCost, gamma, cit[i * kCitD + 6]);
    }
    env->SetDoubleArrayRegion(jEfeOut, 0, static_cast<jsize>(nCand), efe.data());
    env->SetDoubleArrayRegion(jProjOut, 0, static_cast<jsize>(proj.size()), proj.data());
    env->SetIntArrayRegion(jOrderOut, 0, static_cast<jsize>(nCand), order.data());
    env->SetDoubleArrayRegion(jVfeOut, 0, static_cast<jsize>(nCit), vfe.data());
    jintArray res = env->NewIntArray(static_cast<jsize>(nCit));
    if (!res) return nullptr;
    env->SetIntArrayRegion(res, 0, static_cast<jsize>(nCit), chosen.data());
    return res;
}

// ---------------------------------------------------------------- 4. recuperação de memória em lote
JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeRetrieve(
    JNIEnv* env, jclass, jlongArray jL, jdoubleArray jD, jint nQueries, jdoubleArray jExcOut) {
    const auto L = readArr<jlong>(env, jL);
    const auto D = readArr<jdouble>(env, jD);
    std::vector<jint> out;
    std::vector<jdouble> exc;
    try {
        Cursor cur{L};
        size_t di = 0;
        for (jint qi = 0; qi < nQueries; ++qi) {
            MemoryQuery q;
            q.tick = cur.next();
            q.topK = static_cast<int>(cur.next());
            const jlong nNodes = cur.next();
            if (nNodes < 0) throw std::out_of_range("girassol: número de memórias inválido");
            q.features = cur.list();
            q.terms = cur.list();
            q.tags = cur.list();
            q.entities = cur.list();
            std::vector<MemoryNode> nodes(static_cast<size_t>(nNodes));
            for (auto& n : nodes) {
                n.id = cur.next();
                for (auto& b : n.bits) b = static_cast<uint64_t>(cur.next());
                n.createdTick = cur.next();
                n.hawkesLastTick = cur.next();
                n.zettel = cur.next() != 0;
                n.features = cur.list();
                n.docTerms = cur.list();
                n.tags = cur.list();
                n.entities = cur.list();
                n.accessTicks = cur.list();
                if (di + 4 > D.size()) throw std::out_of_range("girassol: escalares de memória truncados");
                n.salience = D[di]; n.confidence = D[di + 1]; n.qUtility = D[di + 2]; n.hawkesExcitation = D[di + 3];
                di += 4;
            }
            const auto r = retrieve(q, nodes);
            out.push_back(r.stage1);
            out.push_back(static_cast<jint>(r.chosen.size()));
            for (size_t k = 0; k < r.chosen.size(); ++k) { out.push_back(r.chosen[k]); exc.push_back(r.newExcitation[k]); }
        }
    } catch (const std::exception& e) {
        throwIae(env, e.what());
        return nullptr;
    }
    if (!jExcOut || static_cast<size_t>(env->GetArrayLength(jExcOut)) < exc.size()) {
        throwIae(env, "girassol: buffer de excitação de Hawkes pequeno demais");
        return nullptr;
    }
    if (!exc.empty()) env->SetDoubleArrayRegion(jExcOut, 0, static_cast<jsize>(exc.size()), exc.data());
    jintArray res = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!res) return nullptr;
    if (!out.empty()) env->SetIntArrayRegion(res, 0, static_cast<jsize>(out.size()), out.data());
    return res;
}

JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeSimHash(
    JNIEnv* env, jclass, jlongArray jFeatures) {
    const auto f = readArr<jlong>(env, jFeatures);
    std::vector<int64_t> v(f.begin(), f.end());
    const Bits b = simHash(v);
    jlong out[kWords];
    for (int i = 0; i < kWords; ++i) out[i] = static_cast<jlong>(b[static_cast<size_t>(i)]);
    jlongArray res = env->NewLongArray(kWords);
    if (!res) return nullptr;
    env->SetLongArrayRegion(res, 0, kWords, out);
    return res;
}

// ---------------------------------------------------------------- ledger
JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeLedgerZeroSum(
    JNIEnv* env, jclass, jlongArray jBalances, jintArray jRes, jint nResources) {
    const auto b = readArr<jlong>(env, jBalances);
    const auto r = readArr<jint>(env, jRes);
    if (b.size() != r.size() || nResources < 0) {
        throwIae(env, "girassol: dimensões inconsistentes no ledger");
        return nullptr;
    }
    std::vector<int64_t> bb(b.begin(), b.end());
    std::vector<int32_t> rr(r.begin(), r.end());
    const auto bad = ledgerZeroSum(bb, rr, nResources);
    std::vector<jint> out(bad.begin(), bad.end());
    jintArray res = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!res) return nullptr;
    if (!out.empty()) env->SetIntArrayRegion(res, 0, static_cast<jsize>(out.size()), out.data());
    return res;
}

JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeVerifyChain(
    JNIEnv* env, jclass, jlongArray jSeq, jlongArray jTick, jintArray jRules, jobjectArray jTypes, jobjectArray jActors,
    jobjectArray jPayloads, jobjectArray jPrev, jobjectArray jHash, jstring jAnchor, jstring jGenesis) {
    const auto seq = readArr<jlong>(env, jSeq);
    const auto tick = readArr<jlong>(env, jTick);
    const auto rules = readArr<jint>(env, jRules);
    const auto types = readStrings(env, jTypes);
    std::vector<uint8_t> hasActor;
    const auto actors = readStrings(env, jActors, &hasActor);
    const auto payloads = readStrings(env, jPayloads);
    const auto prevs = readStrings(env, jPrev);
    const auto hashes = readStrings(env, jHash);
    const size_t n = seq.size();
    if (tick.size() != n || rules.size() != n || types.size() != n || actors.size() != n || payloads.size() != n ||
        prevs.size() != n || hashes.size() != n) {
        throwIae(env, "girassol: dimensões inconsistentes na cadeia de eventos");
        return nullptr;
    }
    std::vector<ChainEvent> ev(n);
    for (size_t i = 0; i < n; ++i)
        ev[i] = ChainEvent{seq[i], tick[i], rules[i], types[i], actors[i], hasActor[i] != 0, payloads[i], prevs[i], hashes[i]};
    const std::string anchor = girassol::jniutf::fromJava(env, jAnchor);
    const std::string genesis = girassol::jniutf::fromJava(env, jGenesis);
    const ChainReport r = verifyChain(ev, jAnchor ? &anchor : nullptr, genesis);
    const jlong out[4] = {r.reason, r.checked, r.firstBadSeq, r.headFrom};
    jlongArray res = env->NewLongArray(4);
    if (!res) return nullptr;
    env->SetLongArrayRegion(res, 0, 4, out);
    return res;
}

JNIEXPORT jlong JNICALL Java_com_enhikyy_girassol_core_world_NativeCognition_nativeParitySignature(JNIEnv*, jclass) {
    return static_cast<jlong>(paritySignature());
}

} // extern "C"
