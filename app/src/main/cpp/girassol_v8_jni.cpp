// ============================================================================
// girassol_v8_jni.cpp — Bindings JNI do núcleo Girassol v8 (Android)
// Alvo: arm64-v8a (Tier 1: Moto G62 5G) — mesmas invariantes A2/A3/A4:
//   0 FMA, 0 libm, 0 heap no hot path (Arena64 sobre buffer estático BSS).
// Build (NDK r26+):
//   aarch64-linux-android24-clang++ -std=c++20 -O2 -Wall -Wextra -Werror
//     -ffp-contract=off -fno-fast-math -fno-math-errno -fPIC -shared
//     -Icore/v8 girassol_v8_jni.cpp -o libgirassol_v8.so
// ============================================================================
#include <jni.h>
#include <mutex>
#include "girassol_v8_core.hpp"

namespace {

// Buffer estático (BSS — não infla o APK): 26 MiB de arena para o córtex.
// MAX_NEURONS = 1024 consome ~24,06 MiB no pior caso (matrizes col-major E8,
// traços de plasticidade, janelas e buffers do hot path).
alignas(64) std::uint8_t g_arena_buf[26 * 1024 * 1024];
grs8::Arena64 g_arena;
grs8::Cortex  g_cortex;
bool          g_ready = false;

// Serializa o acesso ao córtex: nativeInitBrain/nativeStep/nativeNeuromod
// podem ser chamados da thread de UI/render e do ciclo SWR em paralelo.
std::mutex g_cortex_mtx;

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// Inicializa o córtex com n neurônios. Retorna 1 em sucesso, 0 em falha
// (arena insuficiente / n fora do envelope). Idempotente.
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_girassol_brain_GirassolV8_nativeInitBrain(JNIEnv* env, jclass,
                                                   jint n) noexcept {
    (void)env;
    if (n < 64 || n > 1024) return 0;   // envelope do hot path (A1: L1d 64 KiB)
    std::lock_guard<std::mutex> lock(g_cortex_mtx);
    g_arena.init(g_arena_buf, sizeof(g_arena_buf));
    if (!g_cortex.init(g_arena, static_cast<std::size_t>(n))) {
        g_ready = false;
        return 0;
    }
    g_ready = true;
    return 1;
}

// ---------------------------------------------------------------------------
// Um passo ED-SDPC. xIn/p: doubleArray[n] esparsos (bottom-up / top-down).
// Retorna a taxa de disparo do passo (ou -1.0 se não inicializado).
// ---------------------------------------------------------------------------
JNIEXPORT jdouble JNICALL
Java_com_girassol_brain_GirassolV8_nativeStep(JNIEnv* env, jclass,
                                              jdoubleArray xIn,
                                              jdoubleArray p) noexcept {
    std::lock_guard<std::mutex> lock(g_cortex_mtx);
    if (!g_ready || xIn == nullptr || p == nullptr) return -1.0;
    const jsize nx = env->GetArrayLength(xIn);
    const jsize np = env->GetArrayLength(p);
    if (nx != static_cast<jsize>(g_cortex.n) ||
        np != static_cast<jsize>(g_cortex.n)) return -1.0;
    jdouble* x = env->GetDoubleArrayElements(xIn, nullptr);
    jdouble* q = env->GetDoubleArrayElements(p, nullptr);
    if (x == nullptr || q == nullptr) {
        if (x != nullptr) env->ReleaseDoubleArrayElements(xIn, x, JNI_ABORT);
        if (q != nullptr) env->ReleaseDoubleArrayElements(p, q, JNI_ABORT);
        return -1.0;
    }
    const double act = g_cortex.step(x, q);
    env->ReleaseDoubleArrayElements(xIn, x, JNI_ABORT);
    env->ReleaseDoubleArrayElements(p, q, JNI_ABORT);
    return act;
}

// ---------------------------------------------------------------------------
// Neuromoduladores atuais: doubleArray de saída [DA, NA, ACh, 5HT] (len 4).
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_girassol_brain_GirassolV8_nativeNeuromod(JNIEnv* env, jclass,
                                                  jdoubleArray out) noexcept {
    std::lock_guard<std::mutex> lock(g_cortex_mtx);
    if (!g_ready || out == nullptr) return 0;
    if (env->GetArrayLength(out) != 4) return 0;
    jdouble* o = env->GetDoubleArrayElements(out, nullptr);
    if (o == nullptr) return 0;
    o[0] = g_cortex.nm.DA;
    o[1] = g_cortex.nm.NA;
    o[2] = g_cortex.nm.ACh;
    o[3] = g_cortex.nm.HT5;
    env->ReleaseDoubleArrayElements(out, o, 0);
    return 1;
}

// ---------------------------------------------------------------------------
// Estimativa Roofline do gate PC-SM (C2). accw: 64 = binary64, 32 = binary32.
// Retorna latência estimada em ms (função pura — determinística, A4).
// ---------------------------------------------------------------------------
JNIEXPORT jdouble JNICALL
Java_com_girassol_brain_GirassolV8_nativeRooflineMs(JNIEnv* env, jclass,
                                                    jlong bytes, jlong flops,
                                                    jint accw) noexcept {
    (void)env;
    const grs8::RooflineParams P;   // Tier 1 (A1)
    const grs8::AccWidth w = (accw == 32) ? grs8::AccWidth::Binary32
                                          : grs8::AccWidth::Binary64;
    return grs8::roofline_estimate_ms(static_cast<std::uint64_t>(bytes),
                                      static_cast<std::uint64_t>(flops), w, P);
}

// ---------------------------------------------------------------------------
// Verificação do certificado PC-SM (T5). Retorna bitmask:
//   bit0 = C1 (ΔL_G ≤ 0), bit1 = C2 (Roofline), bit2 = C3 (FNV-1a),
//   bit3 = C4 (ortogonalidade), bit4 = ACCEPT (C1∧C2∧C3∧C4).
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_girassol_brain_GirassolV8_nativeGateVerify(
        JNIEnv* env, jclass,
        jdouble lgBefore, jdouble lgAfter,
        jlong bytes, jlong flops, jint accw,
        jlong hashExpected, jlong hashActual, jdouble orthMax) noexcept {
    (void)env;
    grs8::GateInput gi{};
    gi.LG_before = lgBefore;
    gi.LG_after = lgAfter;
    gi.bytes = static_cast<std::uint64_t>(bytes);
    gi.flops = static_cast<std::uint64_t>(flops);
    gi.accw = (accw == 32) ? grs8::AccWidth::Binary32 : grs8::AccWidth::Binary64;
    gi.hash_expected = static_cast<std::uint64_t>(hashExpected);
    gi.hash_actual = static_cast<std::uint64_t>(hashActual);
    gi.orth_max = orthMax;
    const grs8::RooflineParams P;
    const grs8::GateCertificate c = grs8::pcsm_verify(gi, P);
    jint mask = 0;
    if (c.c1_lyapunov)      mask |= 1;
    if (c.c2_roofline)      mask |= 2;
    if (c.c3_integrity)     mask |= 4;
    if (c.c4_orthogonality) mask |= 8;
    if (c.accept())         mask |= 16;
    return mask;
}

// ---------------------------------------------------------------------------
// Autoteste embarcado (boot): executa o vetor canônico TEST-V8-1 e retorna o
// hash FNV-1a-64. Kotlin compara com EXPECTED_SELF_TEST_HASH (A4/C3).
// ---------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_girassol_brain_GirassolV8_nativeSelfTest(JNIEnv* env, jclass) noexcept {
    (void)env;
    constexpr double kLseVec[3] = {1.0, 2.0, 3.0};
    double vec[3];
    vec[0] = grs8::det_exp(0.27174669264696208);
    vec[1] = grs8::det_log(3.141592653589793);
    vec[2] = grs8::det_lse(kLseVec, 3);
    return static_cast<jlong>(grs8::fnv1a64_f64(vec, 3));
}

// ---------------------------------------------------------------------------
// Versão do núcleo e auditoria de invariantes (para telemetria do app).
// Retorna string "v8.0.0-nofma-nolibm".
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_girassol_brain_GirassolV8_nativeCoreVersion(JNIEnv* env,
                                                     jclass) noexcept {
    return env->NewStringUTF("v8.0.0-nofma-nolibm");
}

} // extern "C"
