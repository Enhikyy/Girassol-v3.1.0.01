// JNI do Orçamento Sináptico (libgirassol_sb.so) — registro explícito via RegisterNatives.
//
// * Classe Kotlin: com.enhikyy.girassol.nativebridge.SynapticBudgetNative (object, @JvmStatic).
//   Os nomes/assinaturas abaixo são preservados pelo R8 (proguard-rules.pro) e verificados no CI
//   por scripts/check_r8_jni.py.
// * Estado: buffer direto do Kotlin (ByteBuffer.allocateDirect), alinhado a 64 bytes pelo
//   deslocamento `off` calculado por nativeAlignOffset. Zero alocação dinâmica no núcleo.
// * Todas as operações sobre estados passam por um único mutex global: a thread do motor Kotlin
//   (pilha de 64 MB) e a thread de treino LoRA (ponte girassol_sb_bridge.h) nunca disputam o
//   mesmo estado sem exclusão.
// * Compilado com -std=c++20 -O2 -ffp-contract=off -fno-fast-math (isolado de flags globais).
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

#include "girassol_sb_bridge.h"
#include "sb_core.hpp"

extern "C" int sb_run_selftests(unsigned long long* t13_hash, int* checks, int* fails, int* per_test_fails) noexcept;
extern "C" const char* sb_selftest_log() noexcept;

namespace {

using girassol::sb::Status;
using girassol::sb::SynapticBudget;

constexpr const char* kClass = "com/enhikyy/girassol/nativebridge/SynapticBudgetNative";
constexpr int kStageRows = 256;

std::mutex g_mu;
void* g_active = nullptr;     // protegido por g_mu
int g_active_dim = 0;         // protegido por g_mu
std::atomic<int64_t> g_projected_blocks{0};
// Área de preparação de ativações (máx. 256 linhas de D=256) — estática, sem heap.
alignas(64) double g_stage[kStageRows * 256];
int g_stage_rows = 0;         // protegido por g_mu
int g_stage_dim = 0;          // protegido por g_mu

bool valid_dim(int d) { return d == 64 || d == 128 || d == 256; }

void* state_ptr(JNIEnv* env, jobject buf, jint off, jint dim) {
  if (buf == nullptr || !valid_dim(dim) || off < 0) return nullptr;
  auto* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(buf));
  const jlong cap = env->GetDirectBufferCapacity(buf);
  if (base == nullptr || cap < 0) return nullptr;
  size_t need = 0, align = 64;
  switch (dim) {
    case 64: need = sb64_state_bytes(); align = sb64_state_align(); break;
    case 128: need = sb128_state_bytes(); align = sb128_state_align(); break;
    default: need = sb256_state_bytes(); align = sb256_state_align(); break;
  }
  if (static_cast<size_t>(off) + need > static_cast<size_t>(cap)) return nullptr;
  unsigned char* p = base + off;
  if ((reinterpret_cast<uintptr_t>(p) % (align > 64 ? align : 64)) != 0) return nullptr;
  return p;
}

#define SB_DISPATCH(dim, call64, call128, call256) \
  ((dim) == 64 ? (call64) : (dim) == 128 ? (call128) : (call256))

// ------------------------------------------------------------------ ponte para o LoRA
int64_t bridge_project_f32(float* G, int64_t rows, int64_t cols) {
  if (G == nullptr || rows <= 0 || cols <= 0) return 0;
  std::lock_guard<std::mutex> lk(g_mu);
  if (g_active == nullptr) return 0;
  const int D = g_active_dim;
  const int rank = SB_DISPATCH(D, sb64_rank(g_active), sb128_rank(g_active), sb256_rank(g_active));
  if (rank <= 0) return 0;
  const int64_t blocks = cols / D;
  double tmp[256];
  int64_t done = 0;
  for (int64_t r = 0; r < rows; ++r) {
    float* row = G + r * cols;
    for (int64_t b = 0; b < blocks; ++b) {
      float* g = row + b * D;
      for (int i = 0; i < D; ++i) tmp[i] = static_cast<double>(g[i]);
      if (D == 64) sb64_project(g_active, tmp, 1);
      else if (D == 128) sb128_project(g_active, tmp, 1);
      else sb256_project(g_active, tmp, 1);
      for (int i = 0; i < D; ++i) g[i] = static_cast<float>(tmp[i]);
      ++done;
    }
  }
  g_projected_blocks.fetch_add(done, std::memory_order_relaxed);
  return done;
}

int64_t bridge_stage_f32(const float* X, int64_t rows, int64_t cols) {
  if (X == nullptr || rows <= 0 || cols <= 0) return 0;
  std::lock_guard<std::mutex> lk(g_mu);
  if (g_active == nullptr) return 0;
  const int D = g_active_dim;
  if (g_stage_dim != D) { g_stage_dim = D; g_stage_rows = 0; }
  const int64_t blocks = cols / D;
  int64_t staged = 0;
  // amostragem determinística: percorre linhas com passo fixo para cobrir toda a sequência
  const int64_t want = kStageRows - g_stage_rows;
  if (want <= 0 || blocks <= 0) return 0;
  const int64_t total = rows * blocks;
  const int64_t step = total > want ? total / want : 1;
  for (int64_t k = 0; k < total && g_stage_rows < kStageRows; k += step) {
    const int64_t r = k / blocks, b = k % blocks;
    const float* x = X + r * cols + b * D;
    bool finite = true;
    for (int i = 0; i < D; ++i) {
      const float v = x[i];
      if (!(v == v) || v > 3.0e38f || v < -3.0e38f) { finite = false; break; }
    }
    if (!finite) continue;
    double* dst = g_stage + static_cast<size_t>(g_stage_rows) * D;
    for (int i = 0; i < D; ++i) dst[i] = static_cast<double>(x[i]);
    ++g_stage_rows;
    ++staged;
  }
  return staged;
}

int32_t bridge_active_dim() {
  std::lock_guard<std::mutex> lk(g_mu);
  return g_active == nullptr ? 0 : g_active_dim;
}

const GirassolSbBridge g_bridge = {GIRASSOL_SB_BRIDGE_MAGIC, GIRASSOL_SB_BRIDGE_VERSION, &bridge_project_f32,
                                   &bridge_stage_f32, &bridge_active_dim};

// ------------------------------------------------------------------ métodos JNI
jlong JNICALL nStateBytes(JNIEnv*, jclass, jint dim) {
  if (!valid_dim(dim)) return -1;
  return static_cast<jlong>(SB_DISPATCH(dim, sb64_state_bytes(), sb128_state_bytes(), sb256_state_bytes()));
}

jlong JNICALL nSerializedBytes(JNIEnv*, jclass, jint dim) {
  if (!valid_dim(dim)) return -1;
  return static_cast<jlong>(SB_DISPATCH(dim, sb64_serialized_bytes(), sb128_serialized_bytes(), sb256_serialized_bytes()));
}

jint JNICALL nAlignOffset(JNIEnv* env, jclass, jobject buf) {
  if (buf == nullptr) return -1;
  auto* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(buf));
  if (base == nullptr) return -1;
  const uintptr_t a = reinterpret_cast<uintptr_t>(base);
  return static_cast<jint>((64 - (a % 64)) % 64);
}

jint JNICALL nInit(JNIEnv* env, jclass, jobject buf, jint off, jint dim) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return static_cast<jint>(Status::kBadArg);
  std::lock_guard<std::mutex> lk(g_mu);
  return SB_DISPATCH(dim, sb64_init(st), sb128_init(st), sb256_init(st));
}

jint JNICALL nObserve(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdoubleArray x, jint n, jdouble w) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || x == nullptr || n <= 0) return static_cast<jint>(Status::kBadArg);
  if (env->GetArrayLength(x) < static_cast<jsize>(n) * dim) return static_cast<jint>(Status::kBadArg);
  jdouble* px = env->GetDoubleArrayElements(x, nullptr);
  if (px == nullptr) return static_cast<jint>(Status::kBadArg);
  int rc;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    rc = SB_DISPATCH(dim, sb64_observe(st, px, n, w), sb128_observe(st, px, n, w), sb256_observe(st, px, n, w));
  }
  env->ReleaseDoubleArrayElements(x, px, JNI_ABORT);
  return rc;
}

jint JNICALL nCommit(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jint B) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return static_cast<jint>(Status::kBadArg);
  std::lock_guard<std::mutex> lk(g_mu);
  return SB_DISPATCH(dim, sb64_commit(st, B), sb128_commit(st, B), sb256_commit(st, B));
}

jint JNICALL nCommitForTail(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdouble frac, jint maxRank) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return static_cast<jint>(Status::kBadArg);
  std::lock_guard<std::mutex> lk(g_mu);
  return SB_DISPATCH(dim, sb64_commit_for_tail(st, frac, maxRank), sb128_commit_for_tail(st, frac, maxRank),
                     sb256_commit_for_tail(st, frac, maxRank));
}

void JNICALL nRelease(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jint count) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return;
  std::lock_guard<std::mutex> lk(g_mu);
  if (dim == 64) sb64_release(st, count);
  else if (dim == 128) sb128_release(st, count);
  else sb256_release(st, count);
}

jint JNICALL nProject(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdoubleArray g, jint m) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || g == nullptr || m <= 0) return static_cast<jint>(Status::kBadArg);
  if (env->GetArrayLength(g) < static_cast<jsize>(m) * dim) return static_cast<jint>(Status::kBadArg);
  jdouble* pg = env->GetDoubleArrayElements(g, nullptr);
  if (pg == nullptr) return static_cast<jint>(Status::kBadArg);
  {
    std::lock_guard<std::mutex> lk(g_mu);
    if (dim == 64) sb64_project(st, pg, m);
    else if (dim == 128) sb128_project(st, pg, m);
    else sb256_project(st, pg, m);
  }
  env->ReleaseDoubleArrayElements(g, pg, 0);
  return 0;
}

// out[0..9] = rank, tasks, free_fraction, total_energy, current_eps, eps_count,
//             weighted_tail(rank), projected_blocks (LoRA), staged_rows, active(0/1)
jint JNICALL nTelemetry(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdoubleArray out) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || out == nullptr || env->GetArrayLength(out) < 10) return static_cast<jint>(Status::kBadArg);
  double v[10];
  {
    std::lock_guard<std::mutex> lk(g_mu);
    const int rank = SB_DISPATCH(dim, sb64_rank(st), sb128_rank(st), sb256_rank(st));
    v[0] = rank;
    v[1] = SB_DISPATCH(dim, sb64_tasks(st), sb128_tasks(st), sb256_tasks(st));
    v[2] = SB_DISPATCH(dim, sb64_free_fraction(st), sb128_free_fraction(st), sb256_free_fraction(st));
    v[3] = SB_DISPATCH(dim, sb64_total_energy(st), sb128_total_energy(st), sb256_total_energy(st));
    v[4] = SB_DISPATCH(dim, sb64_current_epsilon(st), sb128_current_epsilon(st), sb256_current_epsilon(st));
    v[5] = SB_DISPATCH(dim, sb64_eps_history(st, nullptr, 0), sb128_eps_history(st, nullptr, 0), sb256_eps_history(st, nullptr, 0));
    v[6] = SB_DISPATCH(dim, sb64_weighted_tail(st, rank), sb128_weighted_tail(st, rank), sb256_weighted_tail(st, rank));
    v[7] = static_cast<double>(g_projected_blocks.load(std::memory_order_relaxed));
    v[8] = (g_stage_dim == dim) ? g_stage_rows : 0;
    v[9] = (g_active == st) ? 1.0 : 0.0;
  }
  env->SetDoubleArrayRegion(out, 0, 10, v);
  return 0;
}

jint JNICALL nEigenvalues(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdoubleArray out) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || out == nullptr) return 0;
  const jsize cap = env->GetArrayLength(out);
  double tmp[256];
  int k;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    const int c = cap < 256 ? static_cast<int>(cap) : 256;
    k = SB_DISPATCH(dim, sb64_eigenvalues(st, tmp, c), sb128_eigenvalues(st, tmp, c), sb256_eigenvalues(st, tmp, c));
  }
  if (k > 0) env->SetDoubleArrayRegion(out, 0, k, tmp);
  return k;
}

void JNICALL nEnableEpsHistory(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jboolean on) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return;
  std::lock_guard<std::mutex> lk(g_mu);
  if (dim == 64) sb64_enable_eps_history(st, on ? 1 : 0);
  else if (dim == 128) sb128_enable_eps_history(st, on ? 1 : 0);
  else sb256_enable_eps_history(st, on ? 1 : 0);
}

jint JNICALL nEpsHistory(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdoubleArray out) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return 0;
  double tmp[SynapticBudget<64>::kEpsHistoryCap];
  int cnt;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    cnt = SB_DISPATCH(dim, sb64_eps_history(st, tmp, SynapticBudget<64>::kEpsHistoryCap),
                      sb128_eps_history(st, tmp, SynapticBudget<64>::kEpsHistoryCap),
                      sb256_eps_history(st, tmp, SynapticBudget<64>::kEpsHistoryCap));
  }
  if (out != nullptr) {
    const jsize cap = env->GetArrayLength(out);
    const int k = cnt < cap ? cnt : static_cast<int>(cap);
    if (k > 0) env->SetDoubleArrayRegion(out, 0, k, tmp);
  }
  return cnt;
}

jdouble JNICALL nForgettingBound(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdouble Finf, jdouble c) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return -1.0;
  std::lock_guard<std::mutex> lk(g_mu);
  return SB_DISPATCH(dim, sb64_forgetting_bound(st, Finf, c), sb128_forgetting_bound(st, Finf, c),
                     sb256_forgetting_bound(st, Finf, c));
}

jint JNICALL nRankForTail(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jdouble frac) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return -1;
  std::lock_guard<std::mutex> lk(g_mu);
  return SB_DISPATCH(dim, sb64_rank_for_tail(st, frac), sb128_rank_for_tail(st, frac), sb256_rank_for_tail(st, frac));
}

jint JNICALL nSerialize(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jbyteArray out) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || out == nullptr) return -1;
  const size_t need = SB_DISPATCH(dim, sb64_serialized_bytes(), sb128_serialized_bytes(), sb256_serialized_bytes());
  if (static_cast<size_t>(env->GetArrayLength(out)) < need) return -1;
  jbyte* p = env->GetByteArrayElements(out, nullptr);
  if (p == nullptr) return -1;
  long long n;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    auto* u = reinterpret_cast<unsigned char*>(p);
    n = SB_DISPATCH(dim, sb64_serialize(st, u, need), sb128_serialize(st, u, need), sb256_serialize(st, u, need));
  }
  env->ReleaseByteArrayElements(out, p, 0);
  return static_cast<jint>(n);
}

jint JNICALL nDeserialize(JNIEnv* env, jclass, jobject buf, jint off, jint dim, jbyteArray in) {
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr || in == nullptr) return static_cast<jint>(Status::kBadArg);
  const jsize len = env->GetArrayLength(in);
  jbyte* p = env->GetByteArrayElements(in, nullptr);
  if (p == nullptr) return static_cast<jint>(Status::kBadArg);
  int rc;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    const auto* u = reinterpret_cast<const unsigned char*>(p);
    const size_t n = static_cast<size_t>(len);
    rc = SB_DISPATCH(dim, sb64_deserialize(st, u, n), sb128_deserialize(st, u, n), sb256_deserialize(st, u, n));
  }
  env->ReleaseByteArrayElements(in, p, JNI_ABORT);
  return rc;
}

jint JNICALL nPlanProtection(JNIEnv* env, jclass, jint H, jdouble d, jdouble rho, jdouble kappa, jdouble Finf, jdouble c,
                             jdouble fmin, jdoubleArray uOut, jdoubleArray jOut) {
  if (uOut == nullptr || H < 1 || H > girassol::sb::kMaxHorizon || env->GetArrayLength(uOut) < H)
    return static_cast<jint>(Status::kBadArg);
  double u[girassol::sb::kMaxHorizon];
  double J = 0.0;
  int sweeps = 0;
  const int rc = sb_plan_protection(H, d, rho, kappa, Finf, c, fmin, u, &J, &sweeps);
  env->SetDoubleArrayRegion(uOut, 0, H, u);
  if (jOut != nullptr && env->GetArrayLength(jOut) >= 2) {
    const double js[2] = {J, static_cast<double>(sweeps)};
    env->SetDoubleArrayRegion(jOut, 0, 2, js);
  }
  return rc;
}

jint JNICALL nAnalyzeSpectrum(JNIEnv* env, jclass, jdoubleArray lam, jint i0, jint i1, jdoubleArray out5) {
  if (lam == nullptr || out5 == nullptr || env->GetArrayLength(out5) < 5 || i1 > env->GetArrayLength(lam))
    return static_cast<jint>(Status::kBadArg);
  jdouble* pl = env->GetDoubleArrayElements(lam, nullptr);
  if (pl == nullptr) return static_cast<jint>(Status::kBadArg);
  double o[5];
  const int rc = sb_analyze_spectrum(pl, i0, i1, o);
  env->ReleaseDoubleArrayElements(lam, pl, JNI_ABORT);
  env->SetDoubleArrayRegion(out5, 0, 5, o);
  return rc;
}

jdouble JNICALL nCumulativeBound(JNIEnv* env, jclass, jdouble Finf, jdouble c, jdoubleArray eps, jint count) {
  if (eps == nullptr || count <= 0) return 0.0;
  if (env->GetArrayLength(eps) < count) return -1.0;
  jdouble* pe = env->GetDoubleArrayElements(eps, nullptr);
  if (pe == nullptr) return -1.0;
  const double r = sb_cumulative_forgetting_bound(Finf, c, pe, count);
  env->ReleaseDoubleArrayElements(eps, pe, JNI_ABORT);
  return r;
}

jlong JNICALL nFnv1a64(JNIEnv* env, jclass, jbyteArray data, jint len) {
  if (data == nullptr || len < 0 || len > env->GetArrayLength(data)) return 0;
  jbyte* p = env->GetByteArrayElements(data, nullptr);
  if (p == nullptr) return 0;
  const uint64_t h = sb_fnv1a64(reinterpret_cast<const unsigned char*>(p), static_cast<size_t>(len));
  env->ReleaseByteArrayElements(data, p, JNI_ABORT);
  return static_cast<jlong>(h);
}

jint JNICALL nSetActive(JNIEnv* env, jclass, jobject buf, jint off, jint dim) {
  std::lock_guard<std::mutex> lk(g_mu);
  if (buf == nullptr) { g_active = nullptr; g_active_dim = 0; return 0; }
  // state_ptr não toca g_mu
  void* st = state_ptr(env, buf, off, dim);
  if (st == nullptr) return static_cast<jint>(Status::kBadArg);
  g_active = st;
  g_active_dim = dim;
  return 0;
}

jlong JNICALL nBridgeHandle(JNIEnv*, jclass) { return static_cast<jlong>(reinterpret_cast<uintptr_t>(&g_bridge)); }

// Consolida a área de preparação no estado ativo: observe(linhas, w) + commit_for_tail.
// out[0..2] = linhas observadas, posto resultante, status.
jint JNICALL nConsolidateStaged(JNIEnv* env, jclass, jdouble w, jdouble frac, jint maxRank, jintArray out) {
  int res[3] = {0, 0, static_cast<int>(Status::kBadArg)};
  {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_active != nullptr && g_stage_dim == g_active_dim && g_stage_rows > 0) {
      const int D = g_active_dim;
      int rc = SB_DISPATCH(D, sb64_observe(g_active, g_stage, g_stage_rows, w),
                           sb128_observe(g_active, g_stage, g_stage_rows, w),
                           sb256_observe(g_active, g_stage, g_stage_rows, w));
      res[0] = g_stage_rows;
      if (rc == 0)
        rc = SB_DISPATCH(D, sb64_commit_for_tail(g_active, frac, maxRank), sb128_commit_for_tail(g_active, frac, maxRank),
                         sb256_commit_for_tail(g_active, frac, maxRank));
      res[1] = SB_DISPATCH(D, sb64_rank(g_active), sb128_rank(g_active), sb256_rank(g_active));
      res[2] = rc;
      g_stage_rows = 0;
    }
  }
  if (out != nullptr && env->GetArrayLength(out) >= 3) env->SetIntArrayRegion(out, 0, 3, res);
  return res[2];
}

// Executa T1..T18 no aparelho. ints[0]=checks, ints[1]=falhas, ints[2+i]=falhas do teste i (0..18).
// hashOut[0] = hash T13. Retorna o log textual completo.
jstring JNICALL nRunSelfTests(JNIEnv* env, jclass, jintArray ints, jlongArray hashOut) {
  unsigned long long h = 0;
  int checks = 0, fails = 0, per[19] = {};
  const char* log;
  {
    std::lock_guard<std::mutex> lk(g_mu);  // os testes usam buffers estáticos próprios
    sb_run_selftests(&h, &checks, &fails, per);
    log = sb_selftest_log();
  }
  if (ints != nullptr && env->GetArrayLength(ints) >= 21) {
    jint v[21];
    v[0] = checks;
    v[1] = fails;
    for (int i = 0; i < 19; ++i) v[2 + i] = per[i];
    env->SetIntArrayRegion(ints, 0, 21, v);
  }
  if (hashOut != nullptr && env->GetArrayLength(hashOut) >= 1) {
    const jlong hv = static_cast<jlong>(h);
    env->SetLongArrayRegion(hashOut, 0, 1, &hv);
  }
  return env->NewStringUTF(log);
}

jstring JNICALL nAbi(JNIEnv* env, jclass) {
#if defined(__aarch64__)
  return env->NewStringUTF("arm64-v8a");
#elif defined(__x86_64__)
  return env->NewStringUTF("x86_64");
#elif defined(__arm__)
  return env->NewStringUTF("armeabi-v7a");
#elif defined(__i386__)
  return env->NewStringUTF("x86");
#else
  return env->NewStringUTF("desconhecida");
#endif
}

const JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeStateBytes"), const_cast<char*>("(I)J"), reinterpret_cast<void*>(nStateBytes)},
    {const_cast<char*>("nativeSerializedBytes"), const_cast<char*>("(I)J"), reinterpret_cast<void*>(nSerializedBytes)},
    {const_cast<char*>("nativeAlignOffset"), const_cast<char*>("(Ljava/nio/ByteBuffer;)I"), reinterpret_cast<void*>(nAlignOffset)},
    {const_cast<char*>("nativeInit"), const_cast<char*>("(Ljava/nio/ByteBuffer;II)I"), reinterpret_cast<void*>(nInit)},
    {const_cast<char*>("nativeObserve"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[DID)I"), reinterpret_cast<void*>(nObserve)},
    {const_cast<char*>("nativeCommit"), const_cast<char*>("(Ljava/nio/ByteBuffer;III)I"), reinterpret_cast<void*>(nCommit)},
    {const_cast<char*>("nativeCommitForTail"), const_cast<char*>("(Ljava/nio/ByteBuffer;IIDI)I"), reinterpret_cast<void*>(nCommitForTail)},
    {const_cast<char*>("nativeRelease"), const_cast<char*>("(Ljava/nio/ByteBuffer;III)V"), reinterpret_cast<void*>(nRelease)},
    {const_cast<char*>("nativeProject"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[DI)I"), reinterpret_cast<void*>(nProject)},
    {const_cast<char*>("nativeTelemetry"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[D)I"), reinterpret_cast<void*>(nTelemetry)},
    {const_cast<char*>("nativeEigenvalues"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[D)I"), reinterpret_cast<void*>(nEigenvalues)},
    {const_cast<char*>("nativeEnableEpsHistory"), const_cast<char*>("(Ljava/nio/ByteBuffer;IIZ)V"), reinterpret_cast<void*>(nEnableEpsHistory)},
    {const_cast<char*>("nativeEpsHistory"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[D)I"), reinterpret_cast<void*>(nEpsHistory)},
    {const_cast<char*>("nativeForgettingBound"), const_cast<char*>("(Ljava/nio/ByteBuffer;IIDD)D"), reinterpret_cast<void*>(nForgettingBound)},
    {const_cast<char*>("nativeRankForTail"), const_cast<char*>("(Ljava/nio/ByteBuffer;IID)I"), reinterpret_cast<void*>(nRankForTail)},
    {const_cast<char*>("nativeSerialize"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[B)I"), reinterpret_cast<void*>(nSerialize)},
    {const_cast<char*>("nativeDeserialize"), const_cast<char*>("(Ljava/nio/ByteBuffer;II[B)I"), reinterpret_cast<void*>(nDeserialize)},
    {const_cast<char*>("nativePlanProtection"), const_cast<char*>("(IDDDDDD[D[D)I"), reinterpret_cast<void*>(nPlanProtection)},
    {const_cast<char*>("nativeAnalyzeSpectrum"), const_cast<char*>("([DII[D)I"), reinterpret_cast<void*>(nAnalyzeSpectrum)},
    {const_cast<char*>("nativeCumulativeBound"), const_cast<char*>("(DD[DI)D"), reinterpret_cast<void*>(nCumulativeBound)},
    {const_cast<char*>("nativeFnv1a64"), const_cast<char*>("([BI)J"), reinterpret_cast<void*>(nFnv1a64)},
    {const_cast<char*>("nativeSetActive"), const_cast<char*>("(Ljava/nio/ByteBuffer;II)I"), reinterpret_cast<void*>(nSetActive)},
    {const_cast<char*>("nativeBridgeHandle"), const_cast<char*>("()J"), reinterpret_cast<void*>(nBridgeHandle)},
    {const_cast<char*>("nativeConsolidateStaged"), const_cast<char*>("(DDI[I)I"), reinterpret_cast<void*>(nConsolidateStaged)},
    {const_cast<char*>("nativeRunSelfTests"), const_cast<char*>("([I[J)Ljava/lang/String;"), reinterpret_cast<void*>(nRunSelfTests)},
    {const_cast<char*>("nativeAbi"), const_cast<char*>("()Ljava/lang/String;"), reinterpret_cast<void*>(nAbi)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) return JNI_ERR;
  jclass cls = env->FindClass(kClass);
  if (cls == nullptr) return JNI_ERR;
  const jint n = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
  if (env->RegisterNatives(cls, kMethods, n) != JNI_OK) {
    env->DeleteLocalRef(cls);
    return JNI_ERR;
  }
  env->DeleteLocalRef(cls);
  return JNI_VERSION_1_6;
}
