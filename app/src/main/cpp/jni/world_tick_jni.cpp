// JNI do WorldTick nativo (core/world/NativeWorldTick.kt). Linhas compactas em arrays
// primitivos — sem objetos Java por cidadão, sem alocações no caminho quente além dos vetores
// de trabalho. Também compilado como biblioteca de host para os testes JVM (core/native).
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "../common/jni_utf.hpp"
#include "../world/world_tick.hpp"

using namespace girassol::world;

namespace {

constexpr int kNeedsStride = 12;
constexpr int kMeritL = 13;
constexpr int kMeritD = 19;
constexpr int kSocialD = 14;

// v3.8: UTF-8 padrão (igual a String.toByteArray(UTF_8) do Kotlin). GetStringUTFRegion devolvia
// UTF-8 modificado e quebrava a paridade das sementes SHA-256 para ids com emoji ou U+0000.
std::string jstr(JNIEnv* env, jstring s) { return girassol::jniutf::fromJava(env, s); }

std::vector<std::string> jstrings(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    const jsize n = arr ? env->GetArrayLength(arr) : 0;
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

template <typename T, typename A>
std::vector<T> read(JNIEnv* env, A arr);
template <>
std::vector<jlong> read<jlong>(JNIEnv* env, jlongArray arr) {
    std::vector<jlong> v(arr ? env->GetArrayLength(arr) : 0);
    if (!v.empty()) env->GetLongArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
template <>
std::vector<jdouble> read<jdouble>(JNIEnv* env, jdoubleArray arr) {
    std::vector<jdouble> v(arr ? env->GetArrayLength(arr) : 0);
    if (!v.empty()) env->GetDoubleArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
template <>
std::vector<jint> read<jint>(JNIEnv* env, jintArray arr) {
    std::vector<jint> v(arr ? env->GetArrayLength(arr) : 0);
    if (!v.empty()) env->GetIntArrayRegion(arr, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}

std::vector<MeritRow> unpackMerit(const std::vector<std::string>& ids, const std::vector<jlong>& L, const std::vector<jdouble>& D, int n) {
    std::vector<MeritRow> rows(n);
    for (int i = 0; i < n; ++i) {
        auto& r = rows[i];
        const jlong* l = &L[static_cast<size_t>(i) * kMeritL];
        const jdouble* d = &D[static_cast<size_t>(i) * kMeritD];
        r.id = ids[i];
        r.birthTick = l[0]; r.children = static_cast<int32_t>(l[1]); r.lastReproductionTick = l[2];
        r.superacao = l[3] != 0; r.sex = static_cast<int32_t>(l[4]);
        r.acceptorBaseN = static_cast<int32_t>(l[5]); r.acceptorBaselineTick = l[6];
        r.brierBaseN = static_cast<int32_t>(l[7]); r.brierBaselineTick = l[8];
        r.promotions = static_cast<int32_t>(l[9]); r.promotionsAtLastReproduction = static_cast<int32_t>(l[10]);
        r.generation = static_cast<int32_t>(l[11]); r.theta0Tick = l[12];
        r.acceptorBaseSum = d[0]; r.acceptorEma = d[1]; r.brierBaseSum = d[2]; r.brierEma = d[3];
        r.meanNeed = d[4]; r.opinion = d[5]; r.theta = d[6]; r.thetaSe = d[7]; r.theta0 = d[8];
        for (int j = 0; j < kIrtItems; ++j) { r.irtAttempts[j] = d[9 + j]; r.irtSuccesses[j] = d[14 + j]; }
    }
    return rows;
}

void packMerit(const std::vector<MeritRow>& rows, std::vector<jlong>& L, std::vector<jdouble>& D) {
    for (size_t i = 0; i < rows.size(); ++i) {
        const auto& r = rows[i];
        jlong* l = &L[i * kMeritL];
        jdouble* d = &D[i * kMeritD];
        l[1] = r.children; l[2] = r.lastReproductionTick; l[10] = r.promotionsAtLastReproduction; l[12] = r.theta0Tick;
        d[6] = r.theta; d[7] = r.thetaSe; d[8] = r.theta0;
        for (int j = 0; j < kIrtItems; ++j) { d[9 + j] = r.irtAttempts[j]; d[14 + j] = r.irtSuccesses[j]; }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeStreamSeed(
    JNIEnv* env, jclass, jlong seed, jstring agent, jlong tick, jstring stream) {
    return static_cast<jlong>(streamSeed(static_cast<uint64_t>(seed), jstr(env, agent), tick, jstr(env, stream)));
}

JNIEXPORT void JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeStepNeeds(
    JNIEnv* env, jclass, jdoubleArray packed, jint n, jboolean night) {
    auto v = read<jdouble>(env, packed);
    if (static_cast<int>(v.size()) < n * kNeedsStride) return;
    std::vector<NeedsRow> rows(n);
    for (int i = 0; i < n; ++i) {
        const double* p = &v[static_cast<size_t>(i) * kNeedsStride];
        for (int k = 0; k < kNeeds; ++k) rows[i].needs[k] = p[k];
        rows[i].metabolismo = p[6]; rows[i].sociabilidade = p[7]; rows[i].conscienciosidade = p[8]; rows[i].curiosidade = p[9];
        rows[i].resting = p[10] != 0.0; rows[i].starvation = static_cast<int32_t>(p[11]);
    }
    stepNeeds(rows, night == JNI_TRUE);
    for (int i = 0; i < n; ++i) {
        double* p = &v[static_cast<size_t>(i) * kNeedsStride];
        for (int k = 0; k < kNeeds; ++k) p[k] = rows[i].needs[k];
        p[11] = rows[i].starvation;
    }
    env->SetDoubleArrayRegion(packed, 0, n * kNeedsStride, v.data());
}

JNIEXPORT jdoubleArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeNeedDecay(
    JNIEnv* env, jclass, jdouble metab, jdouble sociab, jdouble consc, jdouble curios) {
    NeedsRow r{};
    r.metabolismo = metab; r.sociabilidade = sociab; r.conscienciosidade = consc; r.curiosidade = curios;
    double out[kNeeds];
    for (int k = 0; k < kNeeds; ++k) out[k] = needDecay(r, k);
    jdoubleArray arr = env->NewDoubleArray(kNeeds);
    if (!arr) return nullptr;
    env->SetDoubleArrayRegion(arr, 0, kNeeds, out);
    return arr;
}

// ints: [starvation, birthTick, parentA, parentB] por linha → [causa, herdeiro] por linha
JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeLifecycle(
    JNIEnv* env, jclass, jobjectArray ids, jlongArray packed, jdoubleArray longevity, jlong tick) {
    auto names = jstrings(env, ids);
    auto L = read<jlong>(env, packed);
    auto lon = read<jdouble>(env, longevity);
    const int n = static_cast<int>(names.size());
    std::vector<LifeRow> rows(n);
    for (int i = 0; i < n; ++i) {
        rows[i].id = names[i];
        rows[i].starvation = static_cast<int32_t>(L[i * 4]);
        rows[i].birthTick = L[i * 4 + 1];
        rows[i].parentA = static_cast<int32_t>(L[i * 4 + 2]);
        rows[i].parentB = static_cast<int32_t>(L[i * 4 + 3]);
        rows[i].longevidade = lon[i];
    }
    stepLifecycle(rows, tick);
    std::vector<jint> out(static_cast<size_t>(n) * 2);
    for (int i = 0; i < n; ++i) { out[i * 2] = rows[i].cause; out[i * 2 + 1] = rows[i].heir; }
    jintArray arr = env->NewIntArray(n * 2);
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, n * 2, out.data());
    return arr;
}

// Devolve longs [a, b, greetingSeed, claimRaw, flags] por encontro; doubles (6 por encontro) em outD.
// flags: bit0 laço criado, bit1 confiança geral confirmada, bit2 comércio.
JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeStepSocial(
    JNIEnv* env, jclass, jlong seed, jlong tick, jobjectArray ids, jobjectArray groups, jdoubleArray rowD,
    jintArray tieI, jdoubleArray tieD, jdoubleArray outD) {
    auto names = jstrings(env, ids);
    auto gs = jstrings(env, groups);
    auto D = read<jdouble>(env, rowD);
    auto TI = read<jint>(env, tieI);
    auto TD = read<jdouble>(env, tieD);
    const int n = static_cast<int>(names.size());
    std::vector<SocialRow> rows(n);
    for (int i = 0; i < n; ++i) {
        const double* p = &D[static_cast<size_t>(i) * kSocialD];
        auto& r = rows[i];
        r.id = names[i]; r.group = gs[i];
        for (int k = 0; k < kTraits; ++k) r.traits[k] = p[k];
        r.mood = p[7]; r.valence = p[8]; r.opinion = p[9]; r.intrinsic = p[10]; r.fome = p[11]; r.food = p[12]; r.credit = p[13];
    }
    std::vector<SocialTie> ties(TI.size() / 2);
    for (size_t t = 0; t < ties.size(); ++t) {
        auto& x = ties[t];
        x.from = TI[t * 2]; x.to = TI[t * 2 + 1];
        const double* p = &TD[t * 7];
        x.affinity = p[0]; x.familiarity = p[1]; x.alphaGen = p[2]; x.betaGen = p[3];
        x.currentOpinion = p[4]; x.intrinsic = p[5]; x.susceptibility = p[6];
    }
    auto res = stepSocial(static_cast<uint64_t>(seed), tick, rows, ties);
    std::vector<jlong> L(res.size() * 5);
    std::vector<jdouble> O(res.size() * 6);
    for (size_t k = 0; k < res.size(); ++k) {
        const auto& it = res[k];
        L[k * 5] = it.a; L[k * 5 + 1] = it.b;
        L[k * 5 + 2] = static_cast<jlong>(it.greetingSeed); L[k * 5 + 3] = static_cast<jlong>(it.claimRaw);
        L[k * 5 + 4] = (it.tieCreated ? 1 : 0) | (it.trustOk ? 2 : 0) | (it.trade ? 4 : 0);
        O[k * 6] = it.affinity; O[k * 6 + 1] = it.familiarity; O[k * 6 + 2] = it.alphaGen;
        O[k * 6 + 3] = it.betaGen; O[k * 6 + 4] = it.currentOpinion; O[k * 6 + 5] = it.aOpinion;
    }
    if (!O.empty() && env->GetArrayLength(outD) >= static_cast<jsize>(O.size()))
        env->SetDoubleArrayRegion(outD, 0, static_cast<jsize>(O.size()), O.data());
    jlongArray arr = env->NewLongArray(static_cast<jsize>(L.size()));
    if (!arr) return nullptr;
    if (!L.empty()) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(L.size()), L.data());
    return arr;
}

// Ciclo diário. Devolve [cota, empurrões Heider, bits(opinião média), nascimentos, (a, b, semente)…];
// atualiza no lugar meritL/meritD (filhos, cooldown, θ/θ0, janela IRT), tieD (afinidade, familiaridade)
// e items (a[5], b[5]).
JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeStepDaily(
    JNIEnv* env, jclass, jlong seed, jlong tick, jint housing, jint admissionCap, jdouble commonsFood,
    jobjectArray ids, jint nAlive, jlongArray meritL, jdoubleArray meritD, jintArray tieI, jdoubleArray tieD, jdoubleArray itemsArr) {
    auto names = jstrings(env, ids);
    auto L = read<jlong>(env, meritL);
    auto D = read<jdouble>(env, meritD);
    auto TI = read<jint>(env, tieI);
    auto TD = read<jdouble>(env, tieD);
    auto IT = read<jdouble>(env, itemsArr);
    auto rows = unpackMerit(names, L, D, nAlive);
    DailyInput in;
    in.worldSeed = static_cast<uint64_t>(seed); in.tick = tick; in.housing = housing; in.admissionCap = admissionCap;
    in.commonsFood = commonsFood;
    in.extraIds.assign(names.begin() + nAlive, names.end());
    std::vector<DailyTie> ties(TI.size() / 2);
    for (size_t t = 0; t < ties.size(); ++t)
        ties[t] = DailyTie{TI[t * 2], TI[t * 2 + 1], TD[t * 4], TD[t * 4 + 1], TD[t * 4 + 2], TD[t * 4 + 3]};
    IrtItems items;
    for (int j = 0; j < kIrtItems && IT.size() >= 10; ++j) { items.a[j] = IT[j]; items.b[j] = IT[5 + j]; }
    auto out = stepDaily(in, rows, ties, items);
    packMerit(rows, L, D);
    if (!L.empty()) env->SetLongArrayRegion(meritL, 0, static_cast<jsize>(L.size()), L.data());
    if (!D.empty()) env->SetDoubleArrayRegion(meritD, 0, static_cast<jsize>(D.size()), D.data());
    for (size_t t = 0; t < ties.size(); ++t) { TD[t * 4] = ties[t].affinity; TD[t * 4 + 1] = ties[t].familiarity; }
    if (!TD.empty()) env->SetDoubleArrayRegion(tieD, 0, static_cast<jsize>(TD.size()), TD.data());
    if (IT.size() >= 10) {
        for (int j = 0; j < kIrtItems; ++j) { IT[j] = items.a[j]; IT[5 + j] = items.b[j]; }
        env->SetDoubleArrayRegion(itemsArr, 0, 10, IT.data());
    }
    std::vector<jlong> res = {out.newQuota, out.heiderNudges, 0, static_cast<jlong>(out.births.size())};
    uint64_t bits;
    std::memcpy(&bits, &out.meanOpinion, 8);
    res[2] = static_cast<jlong>(bits);
    for (const auto& b : out.births) { res.push_back(b.a); res.push_back(b.b); res.push_back(static_cast<jlong>(b.seed)); }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(res.size()));
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(res.size()), res.data());
    return arr;
}

// Por cidadão: [bits do gatilho, ETA em ticks]
JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeMeritCitizens(
    JNIEnv* env, jclass, jobjectArray ids, jlongArray meritL, jdoubleArray meritD, jlong tick) {
    auto names = jstrings(env, ids);
    auto rows = unpackMerit(names, read<jlong>(env, meritL), read<jdouble>(env, meritD), static_cast<int>(names.size()));
    std::vector<jlong> out(rows.size() * 2);
    for (size_t i = 0; i < rows.size(); ++i) {
        const uint32_t c = meritCheck(rows[i], tick);
        out[i * 2] = c;
        out[i * 2 + 1] = citizenEtaTicks(rows[i], tick, c);
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(out.size()));
    if (!arr) return nullptr;
    if (!out.empty()) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

// [status, ticks, a, b, próxima geração, bloqueios]
JNIEXPORT jlongArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeMeritEstimate(
    JNIEnv* env, jclass, jobjectArray ids, jlongArray meritL, jdoubleArray meritD, jdouble commonsFood, jlong tick) {
    auto names = jstrings(env, ids);
    auto rows = unpackMerit(names, read<jlong>(env, meritL), read<jdouble>(env, meritD), static_cast<int>(names.size()));
    const Eta e = estimate(rows, commonsFood, tick);
    const jlong out[6] = {e.status, e.ticks, e.a, e.b, e.nextGeneration, static_cast<jlong>(e.blockers)};
    jlongArray arr = env->NewLongArray(6);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 6, out);
    return arr;
}

JNIEXPORT jintArray JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativePerSample(
    JNIEnv* env, jclass, jdoubleArray priorities, jint batch, jdouble alpha, jdouble beta, jlong seed, jdoubleArray weightsOut) {
    auto p = read<jdouble>(env, priorities);
    auto b = perSample(std::span<const double>(p.data(), p.size()), batch, alpha, beta, static_cast<uint64_t>(seed));
    const jsize n = static_cast<jsize>(b.idx.size());
    if (n > 0 && env->GetArrayLength(weightsOut) >= n) env->SetDoubleArrayRegion(weightsOut, 0, n, b.weights.data());
    jintArray arr = env->NewIntArray(n);
    if (!arr) return nullptr;
    if (n > 0) env->SetIntArrayRegion(arr, 0, n, reinterpret_cast<const jint*>(b.idx.data()));
    return arr;
}

JNIEXPORT jdouble JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeIrtProbability(
    JNIEnv*, jclass, jdouble theta, jdouble a, jdouble b) {
    return irtProbability(theta, a, b);
}

JNIEXPORT jlong JNICALL Java_com_enhikyy_girassol_core_world_NativeWorldTick_nativeParitySignature(JNIEnv*, jclass) {
    return static_cast<jlong>(paritySignature());
}

}  // extern "C"
