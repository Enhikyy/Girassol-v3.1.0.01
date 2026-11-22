// Harness de verificação do Orçamento Sináptico.
// Build: g++ -std=c++20 -O2 -ffp-contract=off -fno-fast-math -Wall -Wextra sb_tests.cpp sb_core.cpp -o sb_tests && ./sb_tests
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <cstdarg>

#include "sb_core.hpp"

using namespace girassol::sb;

// Saída: stdout no executável; buffer estático (sem heap) quando compilado como biblioteca
// (SB_TESTS_AS_LIBRARY) para o autoteste via JNI no aparelho (SynapticBudgetArm64Test).
#ifdef SB_TESTS_AS_LIBRARY
static char g_log[32768];
static size_t g_log_len = 0;
static void sb_log_append(const char* fmt, ...) {
  if (g_log_len + 1 >= sizeof(g_log)) return;
  va_list ap;
  va_start(ap, fmt);
  const int w = std::vsnprintf(g_log + g_log_len, sizeof(g_log) - g_log_len, fmt, ap);
  va_end(ap);
  if (w > 0) {
    g_log_len += static_cast<size_t>(w);
    if (g_log_len >= sizeof(g_log)) g_log_len = sizeof(g_log) - 1;
  }
}
#define SB_LOG(...) sb_log_append(__VA_ARGS__)
#else
#define SB_LOG(...) std::printf(__VA_ARGS__)
#endif

static uint64_t g_t13_hash = 0;

static int g_fail = 0, g_checks = 0;
#define CHECK(cond)                                                          \
  do {                                                                       \
    ++g_checks;                                                              \
    if (!(cond)) { ++g_fail; SB_LOG("    FAIL L%d: %s\n", __LINE__, #cond); } \
  } while (0)

struct Rng {
  uint64_t s;
  uint64_t next() { s ^= s >> 12; s ^= s << 25; s ^= s >> 27; return s * 2685821657736338717ULL; }
  double uni() { return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0); }
  double gauss() { double a = 0.0; for (int i = 0; i < 12; ++i) a += uni(); return a - 6.0; }
};

// k linhas ortonormais em R^D (CGS com reortogonalização: "twice is enough")
template <int D>
static void rand_orth(Rng& rng, double* rows, int k) {
  for (int i = 0; i < k; ++i) {
    double* v = rows + i * D;
    for (int c = 0; c < D; ++c) v[c] = rng.gauss();
    for (int pass = 0; pass < 2; ++pass)
      for (int j = 0; j < i; ++j) {
        const double* u = rows + j * D;
        double d = 0.0;
        for (int c = 0; c < D; ++c) d += v[c] * u[c];
        for (int c = 0; c < D; ++c) v[c] -= d * u[c];
      }
    double nn = 0.0;
    for (int c = 0; c < D; ++c) nn += v[c] * v[c];
    nn = std::sqrt(nn);
    for (int c = 0; c < D; ++c) v[c] /= nn;
  }
}

// Sigma^ = sum_i lam_i q_i q_i^T / sum lam, lam_i = exp(-i/tau) (traço 1)
template <int D>
static void make_sigma(const double* Q, double tau, std::array<double, D * D>& S) {
  S.fill(0.0);
  double z = 0.0;
  for (int i = 0; i < D; ++i) z += std::exp(-i / tau);
  for (int i = 0; i < D; ++i) {
    const double l = std::exp(-i / tau) / z;
    for (int a = 0; a < D; ++a)
      for (int b = 0; b < D; ++b) S[a * D + b] += l * Q[i * D + a] * Q[i * D + b];
  }
}

static double ulp_rel(double a, double b) { return std::fabs(a - b) / (std::fabs(b) > 1e-300 ? std::fabs(b) : 1e-300); }

// ------------------------------------------------------------------ T1
static void t_det_math() {
  SB_LOG("[T1] det_exp / det_ln vs libm\n");
  double worst_e = 0.0, worst_l = 0.0;
  for (int i = -7000; i <= 7000; ++i) {
    const double x = i * 0.1;
    const double e = ulp_rel(det_exp(x), std::exp(x));
    if (e > worst_e) worst_e = e;
  }
  for (int i = 1; i <= 20000; ++i) {
    const double x = std::exp((i - 10000) * 0.07);
    const double ref = std::log(x);
    const double err = std::fabs(det_ln(x) - ref) / (std::fabs(ref) > 1e-3 ? std::fabs(ref) : 1.0);
    if (err > worst_l) worst_l = err;
  }
  SB_LOG("    pior erro rel exp=%.3e  ln=%.3e\n", worst_e, worst_l);
  CHECK(worst_e < 2e-15);
  CHECK(worst_l < 2e-15);
  CHECK(det_exp(1000.0) > 1e308 && std::isinf(det_exp(1000.0)));
  CHECK(det_exp(-1000.0) == 0.0);
  CHECK(std::isinf(det_ln(0.0)) && det_ln(0.0) < 0.0);
  CHECK(std::isnan(det_ln(-1.0)));
  CHECK(det_ln(1.0) == 0.0);
}

// ------------------------------------------------------------------ T2
static void t_jacobi() {
  SB_LOG("[T2] Jacobi: resíduo, ortogonalidade, mal-condicionamento\n");
  constexpr int N = 16;
  Rng rng{88172645463325252ULL};
  std::array<double, N * N> A{};
  for (int i = 0; i < N; ++i)
    for (int j = 0; j <= i; ++j) { const double v = rng.gauss(); A[i * N + j] = v; A[j * N + i] = v; }
  Eig<N> e;
  jacobi_eig<N>(A, e);
  CHECK(e.converged);
  double res = 0.0, orth = 0.0, nA = 0.0;
  for (int k = 0; k < N; ++k) {
    for (int i = 0; i < N; ++i) {
      double av = 0.0;
      for (int j = 0; j < N; ++j) av += A[i * N + j] * e.vt[k * N + j];
      const double r = av - e.lam[k] * e.vt[k * N + i];
      res += r * r;
    }
    for (int l = 0; l < N; ++l) {
      double d = 0.0;
      for (int i = 0; i < N; ++i) d += e.vt[k * N + i] * e.vt[l * N + i];
      orth += (d - (k == l ? 1.0 : 0.0)) * (d - (k == l ? 1.0 : 0.0));
    }
  }
  for (int i = 0; i < N * N; ++i) nA += A[i] * A[i];
  SB_LOG("    sweeps=%d resíduo_rel=%.2e  ||VV^T-I||_F=%.2e\n", e.sweeps, std::sqrt(res / nA), std::sqrt(orth));
  CHECK(std::sqrt(res / nA) < 1e-13);
  CHECK(std::sqrt(orth) < 1e-13);
  for (int k = 1; k < N; ++k) CHECK(e.lam[k - 1] >= e.lam[k]);

  constexpr int M = 12;  // Hilbert: cond ~ 1e16
  std::array<double, M * M> Hm{};
  for (int i = 0; i < M; ++i) for (int j = 0; j < M; ++j) Hm[i * M + j] = 1.0 / (i + j + 1);
  Eig<M> h;
  jacobi_eig<M>(Hm, h);
  double rr = 0.0;
  for (int k = 0; k < M; ++k)
    for (int i = 0; i < M; ++i) {
      double av = 0.0;
      for (int j = 0; j < M; ++j) av += Hm[i * M + j] * h.vt[k * M + j];
      const double r = av - h.lam[k] * h.vt[k * M + i];
      rr += r * r;
    }
  SB_LOG("    Hilbert(12): converged=%d  resíduo_abs=%.2e  lam_max=%.6f\n", h.converged, std::sqrt(rr), h.lam[0]);
  CHECK(h.converged);
  CHECK(std::sqrt(rr) < 1e-13);
  CHECK(std::fabs(h.lam[0] - 1.7953720) < 1e-6);
}

// ------------------------------------------------------------------ T3
static void t_degenerate() {
  SB_LOG("[T3] Degenerescência: espectro isotrópico (empates totais)\n");
  constexpr int D = 16;
  SynapticBudget<D> sb;
  std::array<double, D * D> I{};
  for (int i = 0; i < D; ++i) I[i * D + i] = 1.0 / D;
  CHECK(sb.observe_covariance(I, 1.0) == Status::kOk);
  CHECK(sb.commit(6) == Status::kOk);
  const double eps = sb.epsilon_of(I);
  SB_LOG("    eps=%.15f  esperado=%.15f\n", eps, 10.0 / 16.0);
  CHECK(std::fabs(eps - 10.0 / 16.0) < 1e-14);   // independe do desempate: depende só dos autovalores
  Eig<D> e1 = sb.eig();
  SynapticBudget<D> sb2;
  CHECK(sb2.observe_covariance(I, 1.0) == Status::kOk);
  CHECK(sb2.commit(6) == Status::kOk);
  CHECK(std::memcmp(e1.vt.data(), sb2.eig().vt.data(), sizeof(double) * D * D) == 0);  // bit-a-bit
}

// ------------------------------------------------------------------ T4
static void t_invariance_bound() {
  SB_LOG("[T4] Lema 1: invariância exata em range(P) e cota de drift\n");
  constexpr int D = 24, M = 5;
  Rng rng{1234567ULL};
  std::array<double, D * D> Q{};
  rand_orth<D>(rng, Q.data(), D);
  std::array<double, D * D> Sh{};
  make_sigma<D>(Q.data(), 3.0, Sh);
  SynapticBudget<D> sb;
  CHECK(sb.observe_covariance(Sh, 1.0) == Status::kOk);
  CHECK(sb.commit(8) == Status::kOk);
  const double eps = sb.epsilon_of(Sh);
  // dW = -eta * G (I-P)
  std::array<double, M * D> G{};
  for (auto& g : G) g = rng.gauss();
  sb.project_rows(G.data(), M);
  double inv = 0.0, nrm = 0.0;
  for (int k = 0; k < 8; ++k)
    for (int r = 0; r < M; ++r) {
      double d = 0.0;
      for (int i = 0; i < D; ++i) d += G[r * D + i] * sb.basis_row(k)[i];
      if (std::fabs(d) > inv) inv = std::fabs(d);
    }
  for (double g : G) nrm += g * g;
  SB_LOG("    max|dW u_k|=%.2e (||dW||_F=%.2f)\n", inv, std::sqrt(nrm));
  CHECK(inv < 1e-13 * std::sqrt(nrm));
  // tr(dW Sigma dW^T) <= ||dW||_op^2 * eps * trSigma  (trSigma=1)
  double drift = 0.0;
  for (int r = 0; r < M; ++r)
    for (int a = 0; a < D; ++a)
      for (int b = 0; b < D; ++b) drift += G[r * D + a] * Sh[a * D + b] * G[r * D + b];
  std::array<double, D * D> GtG{};
  for (int a = 0; a < D; ++a) for (int b = 0; b < D; ++b) {
    double s = 0.0; for (int r = 0; r < M; ++r) s += G[r * D + a] * G[r * D + b];
    GtG[a * D + b] = s;
  }
  Eig<D> e; jacobi_eig<D>(GtG, e);
  const double bound = e.lam[0] * eps;
  SB_LOG("    drift=%.5f  cota=||dW||_op^2*eps=%.5f  eps=%.5f\n", drift, bound, eps);
  CHECK(drift <= bound * (1.0 + 1e-12));
  CHECK(drift > 0.0);
}

// ------------------------------------------------------------------ T5
static void t_lyapunov() {
  SB_LOG("[T5] Lyapunov: descida monótona do gradiente projetado + invariância exata (eps=0)\n");
  constexpr int D = 20, M = 3, B = 6, N = 200;
  Rng rng{99991ULL};
  std::array<double, D * D> Q{};
  rand_orth<D>(rng, Q.data(), D);
  // tarefa antiga: dados exatamente em span(Q[0..B)) => posto B => eps = 0
  std::array<double, N * D> Xold{};
  for (int n = 0; n < N; ++n)
    for (int k = 0; k < B; ++k) {
      const double z = rng.gauss();
      for (int i = 0; i < D; ++i) Xold[n * D + i] += z * Q[k * D + i];
    }
  SynapticBudget<D> sb;
  CHECK(sb.observe(Xold.data(), N, 1.0) == Status::kOk);
  CHECK(sb.commit(B) == Status::kOk);
  std::array<double, D * D> Sold{};
  for (int a = 0; a < D; ++a) for (int b = 0; b < D; ++b) {
    double s = 0.0; for (int n = 0; n < N; ++n) s += Xold[n * D + a] * Xold[n * D + b];
    Sold[a * D + b] = s / N;
  }
  double tr = 0.0; for (int i = 0; i < D; ++i) tr += Sold[i * D + i];
  for (auto& v : Sold) v /= tr;
  CHECK(sb.epsilon_of(Sold) < 1e-13);

  // tarefa nova: regressão linear
  std::array<double, N * D> X{};
  std::array<double, N * M> Y{};
  std::array<double, M * D> Wstar{};
  for (auto& w : Wstar) w = rng.gauss();
  for (int n = 0; n < N; ++n) {
    for (int i = 0; i < D; ++i) X[n * D + i] = rng.gauss() * (1.0 + 0.1 * i);
    for (int m = 0; m < M; ++m) {
      double s = 0.0; for (int i = 0; i < D; ++i) s += Wstar[m * D + i] * X[n * D + i];
      Y[n * M + m] = s + 0.01 * rng.gauss();
    }
  }
  std::array<double, D * D> Sig{};
  for (int a = 0; a < D; ++a) for (int b = 0; b < D; ++b) {
    double s = 0.0; for (int n = 0; n < N; ++n) s += X[n * D + a] * X[n * D + b];
    Sig[a * D + b] = s / N;
  }
  Eig<D> es; jacobi_eig<D>(Sig, es);
  const double beta = es.lam[0];
  const double eta = 1.0 / beta;
  std::array<double, M * D> W{};
  // saída antiga em cada x_old antes
  std::array<double, N * M> out0{};
  auto loss = [&]() {
    double L = 0.0;
    for (int n = 0; n < N; ++n) for (int m = 0; m < M; ++m) {
      double s = 0.0; for (int i = 0; i < D; ++i) s += W[m * D + i] * X[n * D + i];
      L += (s - Y[n * M + m]) * (s - Y[n * M + m]);
    }
    return 0.5 * L / N;
  };
  for (int n = 0; n < N; ++n) for (int m = 0; m < M; ++m) {
    double s = 0.0; for (int i = 0; i < D; ++i) s += W[m * D + i] * Xold[n * D + i];
    out0[n * M + m] = s;
  }
  double Lprev = loss();
  const double L0 = Lprev;
  bool mono = true;
  double gnorm = 0.0, pathlen = 0.0;
  for (int it = 0; it < 400; ++it) {
    std::array<double, M * D> g{};
    for (int n = 0; n < N; ++n) for (int m = 0; m < M; ++m) {
      double s = 0.0; for (int i = 0; i < D; ++i) s += W[m * D + i] * X[n * D + i];
      const double r = s - Y[n * M + m];
      for (int i = 0; i < D; ++i) g[m * D + i] += r * X[n * D + i] / N;
    }
    { double raw = 0.0; for (double v : g) raw += v * v; pathlen += eta * std::sqrt(raw); }
    sb.project_rows(g.data(), M);
    gnorm = 0.0; for (double v : g) gnorm += v * v;
    for (int k = 0; k < M * D; ++k) W[k] -= eta * g[k];
    const double L = loss();
    if (L > Lprev * (1.0 + 1e-12) + 1e-18) mono = false;
    Lprev = L;
  }
  SB_LOG("    L: %.5f -> %.5f  monótona=%d  ||grad_proj||=%.2e\n", L0, Lprev, mono, std::sqrt(gnorm));
  CHECK(mono);
  CHECK(Lprev < L0);
  CHECK(std::sqrt(gnorm) < 1e-3);
  double maxdrift = 0.0;
  for (int n = 0; n < N; ++n) for (int m = 0; m < M; ++m) {
    double s = 0.0; for (int i = 0; i < D; ++i) s += W[m * D + i] * Xold[n * D + i];
    const double d = std::fabs(s - out0[n * M + m]);
    if (d > maxdrift) maxdrift = d;
  }
  // vazamento por passo <= c*D*u*||g_bruto||; acumulado <= c*D*u * (comprimento do caminho) * max||x_old||
  double xmax = 0.0;
  for (int n = 0; n < N; ++n) { double s2 = 0.0; for (int i = 0; i < D; ++i) s2 += Xold[n * D + i] * Xold[n * D + i]; xmax = std::fmax(xmax, std::sqrt(s2)); }
  const double leak_bound = 4.0 * D * 1.1102230246251565e-16 * pathlen * xmax;
  SB_LOG("    max |saída_antiga' - saída_antiga| = %.2e   cota de arredondamento O(D u L ||x||) = %.2e\n", maxdrift, leak_bound);
  CHECK(maxdrift <= leak_bound);
}

// ------------------------------------------------------------------ T6
static void t_kyfan() {
  SB_LOG("[T6] Teorema Ky-Fan: ótimo global vs 2000 projetores aleatórios e vs GPM com divisão igual\n");
  constexpr int D = 24, S = 3, B = 6;
  Rng rng{424242ULL};
  const double taus[S] = {2.0, 4.0, 8.0};
  const double ws[S] = {1.0, 2.5, 0.7};
  std::array<std::array<double, D * D>, S> Sh{};
  std::array<std::array<double, D * D>, S> Qs{};
  SynapticBudget<D> sb;
  for (int s = 0; s < S; ++s) {
    rand_orth<D>(rng, Qs[s].data(), D);
    make_sigma<D>(Qs[s].data(), taus[s], Sh[s]);
    CHECK(sb.observe_covariance(Sh[s], ws[s]) == Status::kOk);
  }
  CHECK(sb.commit(B) == Status::kOk);
  auto value = [&](const double* U, int k) {   // tr(P A) = sum_u u^T A u
    double v = 0.0;
    for (int j = 0; j < k; ++j) {
      const double* u = U + j * D;
      for (int a = 0; a < D; ++a) {
        double row = 0.0;
        for (int b = 0; b < D; ++b) row += sb.accumulator()[a * D + b] * u[b];
        v += u[a] * row;
      }
    }
    return v;
  };
  double opt = 0.0;
  for (int i = 0; i < B; ++i) opt += sb.eig().lam[i];
  CHECK(std::fabs(value(sb.eig().vt.data(), B) - opt) < 1e-12);
  double best_rand = 0.0;
  std::array<double, B * D> U{};
  for (int it = 0; it < 2000; ++it) {
    rand_orth<D>(rng, U.data(), B);
    const double v = value(U.data(), B);
    if (v > best_rand) best_rand = v;
  }
  // GPM: top-2 de cada tarefa, união ortonormalizada
  std::array<double, B * D> W{};
  for (int s = 0; s < S; ++s) {
    SynapticBudget<D> one;
    one.observe_covariance(Sh[s], 1.0);
    one.commit(2);
    for (int k = 0; k < 2; ++k) for (int i = 0; i < D; ++i) W[(2 * s + k) * D + i] = one.basis_row(k)[i];
  }
  for (int i = 0; i < B; ++i) {
    double* v = W.data() + i * D;
    for (int pass = 0; pass < 2; ++pass)
      for (int j = 0; j < i; ++j) {
        const double* u = W.data() + j * D;
        double d = 0.0; for (int c = 0; c < D; ++c) d += v[c] * u[c];
        for (int c = 0; c < D; ++c) v[c] -= d * u[c];
      }
    double nn = 0.0; for (int c = 0; c < D; ++c) nn += v[c] * v[c];
    nn = std::sqrt(nn);
    for (int c = 0; c < D; ++c) v[c] /= nn;
  }
  const double gpm = value(W.data(), B);
  SB_LOG("    tr(PA): ótimo=%.6f  melhor_aleatório=%.6f  GPM_igual=%.6f\n", opt, best_rand, gpm);
  CHECK(opt >= best_rand - 1e-12);
  CHECK(opt >= gpm - 1e-12);
  // identidade da cauda: sum_s w_s eps_s == sum_{i>=B} lam_i
  double lhs = 0.0;
  for (int s = 0; s < S; ++s) lhs += ws[s] * sb.epsilon_of(Sh[s]);
  const double rhs = sb.weighted_tail(B);
  SB_LOG("    sum w_s eps_s=%.12f  cauda espectral=%.12f\n", lhs, rhs);
  CHECK(std::fabs(lhs - rhs) < 1e-12);
  CHECK(std::fabs(sb.total_energy() - (1.0 + 2.5 + 0.7)) < 1e-12);   // invariante tr(A)=sum w
}

// ------------------------------------------------------------------ T7
static void t_alloc_law() {
  SB_LOG("[T7] Lei 3 (caso exponencial, suportes disjuntos): eps_1/eps_2 = w_2/w_1\n");
  constexpr int D = 64, H = 32;
  const double tau = 4.0;
  std::array<double, D * D> S1{}, S2{};
  double z = 0.0;
  for (int i = 0; i < H; ++i) z += std::exp(-i / tau);
  for (int i = 0; i < H; ++i) {
    S1[i * D + i] = std::exp(-i / tau) / z;
    S2[(H + i) * D + (H + i)] = std::exp(-i / tau) / z;
  }
  SynapticBudget<D> sb;
  sb.observe_covariance(S1, 4.0);
  sb.observe_covariance(S2, 1.0);
  for (int B : {10, 16, 22}) {
    CHECK(sb.commit(B) == Status::kOk);
    const double e1 = sb.epsilon_of(S1), e2 = sb.epsilon_of(S2);
    const double lr = std::log(e1 / e2);
    SB_LOG("    B=%2d eps1=%.5f eps2=%.5f  ln(e1/e2)=%.3f (alvo %.3f, tol %.3f)\n", B, e1, e2, lr,
                -std::log(4.0), 1.0 / tau);
    CHECK(std::fabs(lr + std::log(4.0)) <= 1.0 / tau);
  }
}

// ------------------------------------------------------------------ T8
static void t_release() {
  SB_LOG("[T8] Operador de liberação: eps' = eps + u^T Sigma^ u (identidade exata)\n");
  constexpr int D = 24, S = 3;
  Rng rng{777ULL};
  std::array<std::array<double, D * D>, S> Sh{};
  SynapticBudget<D> sb;
  for (int s = 0; s < S; ++s) {
    std::array<double, D * D> Q{};
    rand_orth<D>(rng, Q.data(), D);
    make_sigma<D>(Q.data(), 2.0 + 3.0 * s, Sh[s]);
    sb.observe_covariance(Sh[s], 1.0 + s);
  }
  sb.commit(8);
  double before[S];
  for (int s = 0; s < S; ++s) before[s] = sb.epsilon_of(Sh[s]);
  std::array<double, D> u{};
  for (int i = 0; i < D; ++i) u[i] = sb.basis_row(7)[i];
  sb.release(1);
  CHECK(sb.rank() == 7);
  double worst = 0.0;
  for (int s = 0; s < S; ++s) {
    double q = 0.0;
    for (int a = 0; a < D; ++a) for (int b = 0; b < D; ++b) q += u[a] * Sh[s][a * D + b] * u[b];
    const double e = std::fabs(sb.epsilon_of(Sh[s]) - (before[s] + q));
    if (e > worst) worst = e;
  }
  SB_LOG("    erro máx da identidade = %.2e\n", worst);
  CHECK(worst < 1e-13);
}

// ------------------------------------------------------------------ T9
static void t_scaling() {
  SB_LOG("[T9] Overflow/underflow, pré-escala exata, rejeição sem corromper estado\n");
  constexpr int D = 8, N = 40;
  Rng rng{31337ULL};
  std::array<double, N * D> X{}, Xs{}, Xbig{}, Xtiny{};
  for (int i = 0; i < N * D; ++i) {
    X[i] = rng.gauss();
    Xs[i] = std::ldexp(X[i], -700);
    Xbig[i] = X[i] * 1e200;
    Xtiny[i] = X[i] * 1e-200;
  }
  SynapticBudget<D> a, b, c, d;
  CHECK(a.observe(X.data(), N, 1.0) == Status::kOk);
  CHECK(b.observe(Xs.data(), N, 1.0) == Status::kOk);
  CHECK(std::memcmp(a.accumulator().data(), b.accumulator().data(), sizeof(double) * D * D) == 0);
  CHECK(c.observe(Xbig.data(), N, 1.0) == Status::kOk);
  CHECK(d.observe(Xtiny.data(), N, 1.0) == Status::kOk);
  double dc = 0.0, dd = 0.0;
  for (int i = 0; i < D * D; ++i) {
    dc = std::fmax(dc, std::fabs(c.accumulator()[i] - a.accumulator()[i]));
    dd = std::fmax(dd, std::fabs(d.accumulator()[i] - a.accumulator()[i]));
  }
  SB_LOG("    pot.2 exata: iguais bit-a-bit; 1e200: %.2e; 1e-200: %.2e\n", dc, dd);
  CHECK(dc < 1e-14 && dd < 1e-14);
  std::array<double, N * D> Z{};
  CHECK(a.observe(Z.data(), N, 1.0) == Status::kDegenerateInput);
  X[3] = std::nan("");
  const auto snap = a.accumulator();
  CHECK(a.observe(X.data(), N, 1.0) == Status::kNonFinite);
  CHECK(std::memcmp(snap.data(), a.accumulator().data(), sizeof(double) * D * D) == 0);
  CHECK(a.tasks() == 1);
}

// ------------------------------------------------------------------ T10
static void t_robust() {
  SB_LOG("[T10] Robustez: tr(P^ A) >= tr(P* A) - 2 B ||E||_2  (sem hipótese de gap)\n");
  constexpr int D = 20, B = 5;
  Rng rng{2024ULL};
  std::array<double, D * D> Q{};
  rand_orth<D>(rng, Q.data(), D);
  std::array<double, D * D> A{};
  make_sigma<D>(Q.data(), 6.0, A);
  int ok = 0;
  for (int trial = 0; trial < 50; ++trial) {
    std::array<double, D * D> E{}, AE{};
    const double amp = 0.001 * (1 + trial % 10);
    for (int i = 0; i < D; ++i) for (int j = 0; j <= i; ++j) { const double v = amp * rng.gauss(); E[i * D + j] = v; E[j * D + i] = v; }
    for (int i = 0; i < D * D; ++i) AE[i] = A[i] + E[i];
    Eig<D> eA, eAE, eE;
    jacobi_eig<D>(A, eA); jacobi_eig<D>(AE, eAE); jacobi_eig<D>(E, eE);
    const double nE = std::fmax(std::fabs(eE.lam[0]), std::fabs(eE.lam[D - 1]));
    double opt = 0.0;
    for (int i = 0; i < B; ++i) opt += eA.lam[i];
    double got = 0.0;
    for (int k = 0; k < B; ++k) for (int a = 0; a < D; ++a) {
      double row = 0.0; for (int b = 0; b < D; ++b) row += A[a * D + b] * eAE.vt[k * D + b];
      got += eAE.vt[k * D + a] * row;
    }
    if (got >= opt - 2.0 * B * nE - 1e-13) ++ok;
  }
  SB_LOG("    %d/50 ensaios satisfazem a cota\n", ok);
  CHECK(ok == 50);
}

// ------------------------------------------------------------------ T11
static void t_planner() {
  SB_LOG("[T11] Planejador ótimo: concavidade, KKT, viabilidade, estática comparativa\n");
  PlanParams p;
  p.H = 12; p.d = 64.0; p.rho = 2.0; p.kappa = 0.046; p.Finf = 0.59; p.c = 1.0; p.f_min = 0.10;
  PlanOut out;
  CHECK(plan_protection(p, out) == Status::kOk);
  double used = 0.0;
  for (int t = 0; t < p.H - 1; ++t) used += out.u[t];
  const double fH = 1.0 - (p.rho / p.d) * used;
  SB_LOG("    sweeps=%d  J*=%.5f  f_final=%.4f (>= f_min=%.2f)\n", out.sweeps, out.J, fH, p.f_min);
  CHECK(fH >= p.f_min - 1e-12);
  CHECK(out.u[p.H - 1] == 0.0);
  // KKT com restrição ativa: todos u_t>0 têm o MESMO gradiente nu>=0; u_t=0 tem gradiente <= nu.
  {
    double nu = -1.0, spread = 0.0; int npos = 0;
    for (int t = 0; t < p.H - 1; ++t) if (out.u[t] > 1e-9) { const double g = plan_gradient(p, out.u, t); if (nu < 0.0) nu = g; spread = std::fmax(spread, std::fabs(g - nu)); ++npos; }
    bool zeros_ok = true;
    for (int t = 0; t < p.H - 1; ++t) if (out.u[t] <= 1e-9 && plan_gradient(p, out.u, t) > nu + 1e-8) zeros_ok = false;
    SB_LOG("    restrição ATIVA: %d coords>0, multiplicador nu=%.6f, dispersão do gradiente=%.2e\n", npos, nu, spread);
    CHECK(fH <= p.f_min + 1e-6);          // de fato ativa
    CHECK(npos > 0 && nu > 0.0);
    CHECK(spread < 1e-8);
    CHECK(zeros_ok);
  }
  {   // restrição INATIVA: Pontryagin interior, grad = 0 e eps_t = rho*sum_{r>t}a'(f_r) / (d*Finf*c*(H-t))
    PlanParams q = p; q.d = 512.0; q.f_min = 0.0;
    PlanOut o; plan_protection(q, o);
    double used2 = 0.0; for (int t = 0; t < q.H - 1; ++t) used2 += o.u[t];
    double kkt = 0.0;
    for (int t = 0; t < q.H - 1; ++t) kkt = std::fmax(kkt, std::fabs(plan_gradient(q, o.u, t)));
    SB_LOG("    restrição INATIVA: f_final=%.4f  max|grad|=%.2e\n", 1.0 - (q.rho / q.d) * used2, kkt);
    CHECK(1.0 - (q.rho / q.d) * used2 > 0.5);
    CHECK(kkt < 1e-9);
  }
  // otimalidade vs 300 perturbações viáveis
  Rng rng{5ULL};
  bool never_better = true;
  const double cap = (1.0 - p.f_min) * p.d / p.rho;
  for (int it = 0; it < 300; ++it) {
    double v[kMaxHorizon] = {};
    double s = 0.0;
    for (int t = 0; t < p.H - 1; ++t) { v[t] = rng.uni() * 2.0 * cap / p.H; s += v[t]; }
    if (s > cap) for (int t = 0; t < p.H - 1; ++t) v[t] *= cap / s;
    if (plan_objective(p, v) > out.J + 1e-9) never_better = false;
  }
  CHECK(never_better);
  // baselines extremos
  double zero[kMaxHorizon] = {};
  CHECK(plan_objective(p, zero) <= out.J + 1e-12);
  // ótimo com orçamento ativo deve superar o ponto onde a ascensão coordenada estacionava (J=8.000736)
  SB_LOG("    J* (com trocas de massa)=%.6f  (coordenada pura estacionava em 8.000736)\n", out.J);
  CHECK(out.J > 8.000736 + 1e-6);
  // estática comparativa: eps*_1 em função do horizonte
  SB_LOG("    eps*_1(H):");
  double prev = 0.0; bool inc = true;
  for (int H : {4, 8, 12, 16, 24}) {
    PlanParams q = p; q.H = H;
    PlanOut o; plan_protection(q, o);
    const double e1 = det_exp(-o.u[0]);
    SB_LOG(" H=%d:%.4f", H, e1);
    if (e1 < prev - 1e-12) inc = false;
    prev = e1;
  }
  SB_LOG("  (crescente=%d)\n", inc);
  CHECK(inc);
  // lei do esquecimento linearizada é cota superior do esquecimento exato
  bool ub = true;
  for (int m = 1; m < 40; ++m) for (double e : {0.01, 0.1, 0.5}) {
    const double exact = 1.0 - std::pow(1.0 - p.c * e, m);
    if (exact > m * p.c * e + 1e-15) ub = false;
  }
  CHECK(ub);
}

// ------------------------------------------------------------------ T12
static void t_cross() {
  SB_LOG("[T12] Cruz hiperbólica Kronecker: zeros exatos, cota mu_cut, ganho de capacidade\n");
  constexpr int DI = 32, DO = 32;
  Rng rng{8675309ULL};
  std::array<double, DI * DI> Qi{};
  std::array<double, DO * DO> Qo{};
  rand_orth<DI>(rng, Qi.data(), DI);
  rand_orth<DO>(rng, Qo.data(), DO);
  std::array<double, DI * DI> Sig{};
  std::array<double, DO * DO> F{};
  make_sigma<DI>(Qi.data(), 2.0, Sig);
  make_sigma<DO>(Qo.data(), 2.0, F);
  // captura de energia 95%: cruz vs unilateral
  std::array<double, DI> ls{};
  std::array<double, DO> lf{};
  { Eig<DI> e; jacobi_eig<DI>(Sig, e); for (int i = 0; i < DI; ++i) ls[i] = e.lam[i]; }
  { Eig<DO> e; jacobi_eig<DO>(F, e); for (int i = 0; i < DO; ++i) lf[i] = e.lam[i]; }
  int k1 = 0; double acc = 0.0;
  while (acc < 0.95) acc += ls[k1++];
  const int pairs_one = k1 * DO;
  int Bc = 0;
  { CrossProtector<DI, DO> cp; double cap = 0.0;
    for (Bc = 1; Bc <= DI * DO; ++Bc) { cp.build(Sig, F, Bc); cap = 0.0;
      for (int a = 0; a < DO; ++a) for (int j = 0; j < DI; ++j) if (cp.is_protected(a, j)) cap += lf[a] * ls[j];
      if (cap >= 0.95) break; } }
  SB_LOG("    energia F⊗Sigma >= 95%%: pares cruz=%d  vs unilateral=%d  (razão %.2fx)\n", Bc, pairs_one,
              static_cast<double>(pairs_one) / Bc);
  CHECK(Bc < pairs_one);
  CrossProtector<DI, DO> cp;
  CHECK(cp.build(Sig, F, Bc) == Status::kOk);
  std::array<double, DO * DI> dW{}, dW0{};
  for (auto& v : dW0) v = rng.gauss();
  dW = dW0;
  cp.project(dW.data());
  double zmax = 0.0;
  for (int a = 0; a < DO; ++a) for (int j = 0; j < DI; ++j) if (cp.is_protected(a, j)) {
    double s = 0.0;
    for (int o = 0; o < DO; ++o) for (int i = 0; i < DI; ++i) s += cp.out_vec(a)[o] * dW[o * DI + i] * cp.in_vec(j)[i];
    zmax = std::fmax(zmax, std::fabs(s));
  }
  double q = 0.0, fro = 0.0;
  for (int o = 0; o < DO; ++o) for (int i = 0; i < DI; ++i) {
    double fs = 0.0;
    for (int p2 = 0; p2 < DI; ++p2) {   // (dW Sigma)_{o,p2}
      double t = 0.0; for (int i2 = 0; i2 < DI; ++i2) t += dW[o * DI + i2] * Sig[i2 * DI + p2];
      fs += t * 0.0 + 0.0;
      (void)t;
    }
    (void)fs;
    fro += dW[o * DI + i] * dW[o * DI + i];
  }
  // tr(F dW Sigma dW^T)
  std::array<double, DO * DI> T1{};
  for (int o = 0; o < DO; ++o) for (int j = 0; j < DI; ++j) { double s = 0.0; for (int i = 0; i < DI; ++i) s += dW[o * DI + i] * Sig[i * DI + j]; T1[o * DI + j] = s; }
  for (int o = 0; o < DO; ++o) for (int o2 = 0; o2 < DO; ++o2) {
    double s = 0.0; for (int j = 0; j < DI; ++j) s += T1[o * DI + j] * dW[o2 * DI + j];
    q += F[o * DO + o2] * s;
  }
  SB_LOG("    max|v_a^T dW u_j| nos pares protegidos=%.2e   tr(F dW S dW^T)=%.3e <= mu_cut*||dW||_F^2=%.3e\n", zmax, q, cp.mu_cut() * fro);
  CHECK(zmax < 1e-12);
  CHECK(q <= cp.mu_cut() * fro * (1.0 + 1e-10));
  // projetor idempotente: P(P(x)) = P(x)
  std::array<double, DO * DI> dW2 = dW;
  cp.project(dW2.data());
  double idem = 0.0;
  for (int i = 0; i < DO * DI; ++i) idem = std::fmax(idem, std::fabs(dW2[i] - dW[i]));
  CHECK(idem < 1e-13);
}

// ------------------------------------------------------------------ T13
static uint64_t fnv(uint64_t h, const void* p, size_t n) {
  const unsigned char* c = static_cast<const unsigned char*>(p);
  for (size_t i = 0; i < n; ++i) { h ^= c[i]; h *= 1099511628211ULL; }
  return h;
}
static uint64_t pipeline() {
  constexpr int D = 16, N = 64, M = 4;
  Rng rng{20261121ULL};
  static SynapticBudget<D> sb;
  sb = SynapticBudget<D>();
  uint64_t h = 1469598103934665603ULL;
  for (int t = 0; t < 5; ++t) {
    std::array<double, N * D> X{};
    for (auto& v : X) v = rng.gauss() * (1.0 + 0.3 * t);
    sb.observe(X.data(), N, 1.0 + t);
  }
  sb.commit(7);
  std::array<double, M * D> G{};
  for (auto& g : G) g = rng.gauss();
  sb.project_rows(G.data(), M);
  h = fnv(h, sb.accumulator().data(), sizeof(double) * D * D);
  h = fnv(h, sb.eig().vt.data(), sizeof(double) * D * D);
  h = fnv(h, G.data(), sizeof(double) * M * D);
  PlanParams p; p.H = 10; p.d = 64; p.rho = 2; p.kappa = 0.046; p.Finf = 0.59; p.c = 1.0; p.f_min = 0.1;
  PlanOut o; plan_protection(p, o);
  h = fnv(h, o.u, sizeof(double) * 10);
  return h;
}
static void t_determinism() {
  SB_LOG("[T13] Determinismo bit-a-bit (pipeline completo, 2 execuções)\n");
  const uint64_t h1 = pipeline(), h2 = pipeline();
  g_t13_hash = h1;
  SB_LOG("    hash=%016llx  %s\n", static_cast<unsigned long long>(h1), h1 == h2 ? "(idêntico)" : "(DIFERENTE)");
  CHECK(h1 == h2);
}

// ------------------------------------------------------------------ T14
static void t_haar() {
  SB_LOG("[T14] Lema da plasticidade: E[energia capturada pelo subespaço livre] = f (rotação Haar)\n");
  constexpr int D = 32, B = 24;
  Rng rng{1357ULL};
  std::array<double, D> lam{};
  double z = 0.0;
  for (int i = 0; i < D; ++i) { lam[i] = std::exp(-i / 6.0); z += lam[i]; }
  double mean = 0.0;
  const int R = 600;
  std::array<double, D * D> Q{};
  for (int r = 0; r < R; ++r) {
    rand_orth<D>(rng, Q.data(), D);
    double cap = 0.0;
    for (int i = 0; i < D; ++i) {                    // Sigma_new = Q^T diag(lam) Q ; livres = últimas D-B coords
      double diag = 0.0;
      for (int k = 0; k < D; ++k) diag += lam[k] * Q[k * D + i] * Q[k * D + i];
      if (i >= B) cap += diag;
    }
    mean += cap / z;
  }
  mean /= R;
  SB_LOG("    média=%.4f  esperado f=%.4f\n", mean, 1.0 - static_cast<double>(B) / D);
  CHECK(std::fabs(mean - 0.25) < 0.03);
}

// ------------------------------------------------------------------ T15
static void t_spectrum_fit() {
  SB_LOG("[T15] Estimadores de espectro (rho == tau; alpha de lei de potência)\n");
  double lam[40], pw[40];
  for (int i = 0; i < 40; ++i) { lam[i] = det_exp(-i / 5.0); pw[i] = det_exp(-1.7 * det_ln(i + 1.0)); }
  const double tau = fit_decay_length(lam, 0, 40);
  const double al = fit_powerlaw_alpha(pw, 0, 40);
  SB_LOG("    tau=%.6f (5)  alpha=%.6f (1.7)\n", tau, al);
  CHECK(std::fabs(tau - 5.0) < 1e-9);
  CHECK(std::fabs(al - 1.7) < 1e-9);
  // analyze_spectrum: R^2 de ambos os ajustes e classificação de regime
  SpectrumFitOut fe, fp;
  CHECK(analyze_spectrum(lam, 0, 40, fe) == Status::kOk);
  CHECK(analyze_spectrum(pw, 0, 40, fp) == Status::kOk);
  SB_LOG("    exp: tau=%.6f R2e=%.9f R2p=%.6f  pot: alpha=%.6f R2e=%.6f R2p=%.9f\n", fe.tau, fe.r2_exp, fe.r2_pow,
         fp.alpha, fp.r2_exp, fp.r2_pow);
  CHECK(std::fabs(fe.tau - tau) < 1e-12 && std::fabs(fp.alpha - al) < 1e-12);
  CHECK(fe.r2_exp > 1.0 - 1e-12 && fe.r2_pow < fe.r2_exp && fe.regime == Regime::kExponential);
  CHECK(fp.r2_pow > 1.0 - 1e-12 && fp.r2_exp < fp.r2_pow && fp.regime == Regime::kPowerLaw);
  double out5[5];
  CHECK(sb_analyze_spectrum(pw, 0, 40, out5) == 0 && out5[4] == 2.0);
  double bad[4] = {1.0, 0.5, 0.0, 0.1};
  SpectrumFitOut fb;
  CHECK(analyze_spectrum(bad, 0, 4, fb) == Status::kDegenerateInput);
  CHECK(analyze_spectrum(lam, 5, 6, fb) == Status::kBadArg);
}

// ------------------------------------------------------------------ T16
static void t_c_api() {
  SB_LOG("[T16] ABI C (JNI): placement-new em buffer do chamador\n");
  alignas(64) static unsigned char buf[1 << 17];
  CHECK(sb64_state_bytes() <= sizeof(buf));
  CHECK(sb64_init(buf) == 0);
  Rng rng{4242ULL};
  std::array<double, 50 * 64> X{};
  for (auto& v : X) v = rng.gauss();
  CHECK(sb64_observe(buf, X.data(), 50, 1.0) == 0);
  CHECK(sb64_commit(buf, 20) == 0);
  CHECK(std::fabs(sb64_free_fraction(buf) - (1.0 - 20.0 / 64.0)) < 1e-15);
  std::array<double, 2 * 64> G{};
  for (auto& v : G) v = rng.gauss();
  sb64_project(buf, G.data(), 2);
  SB_LOG("    sizeof(estado D=64)=%zu bytes\n", sb64_state_bytes());

  // Serialização GRSB (cabeçalho + FNV-1a 64) e restauração bit-a-bit em outro buffer.
  static unsigned char ser[grsb_bytes<64>()];
  alignas(64) static unsigned char buf2[1 << 17];
  CHECK(sb64_serialized_bytes() == sizeof(ser));
  CHECK(sb64_serialize(buf, ser, sizeof(ser)) == static_cast<long long>(sizeof(ser)));
  CHECK(ser[0] == 'G' && ser[1] == 'R' && ser[2] == 'S' && ser[3] == 'B');
  CHECK(sb64_init(buf2) == 0);
  CHECK(sb64_deserialize(buf2, ser, sizeof(ser)) == 0);
  CHECK(std::memcmp(static_cast<SynapticBudget<64>*>(static_cast<void*>(buf))->accumulator().data(),
                    static_cast<SynapticBudget<64>*>(static_cast<void*>(buf2))->accumulator().data(),
                    sizeof(double) * 64 * 64) == 0);
  std::array<double, 2 * 64> G1{}, G2{};
  for (int i = 0; i < 2 * 64; ++i) G1[i] = G2[i] = rng.gauss();
  sb64_project(buf, G1.data(), 2);
  sb64_project(buf2, G2.data(), 2);
  CHECK(std::memcmp(G1.data(), G2.data(), sizeof(G1)) == 0);
  CHECK(sb64_rank(buf2) == 20 && sb64_tasks(buf2) == 1);
  // commit_for_tail: menor B com cauda <= 10% (limitado a max_rank), uma só autodecomposição
  CHECK(sb64_commit_for_tail(buf2, 0.10, 64) == 0);
  CHECK(sb64_rank(buf2) == sb64_rank_for_tail(buf2, 0.10));
  CHECK(sb64_weighted_tail(buf2, sb64_rank(buf2)) <= 0.10 * sb64_total_energy(buf2) + 1e-15);
  CHECK(sb64_commit_for_tail(buf2, 0.10, 5) == 0 && sb64_rank(buf2) == 5);
  // corrupção de 1 bit => checksum rejeita e o estado de destino fica intacto
  ser[100] ^= 0x01;
  alignas(64) static unsigned char buf3[1 << 17];
  CHECK(sb64_init(buf3) == 0);
  CHECK(sb64_deserialize(buf3, ser, sizeof(ser)) == static_cast<int>(Status::kNonFinite));
  CHECK(sb64_tasks(buf3) == 0 && sb64_rank(buf3) == 0);
  ser[100] ^= 0x01;
  CHECK(sb64_deserialize(buf3, ser, sizeof(ser) - 1) == static_cast<int>(Status::kBadArg));
  SB_LOG("    GRSB D=64: %zu bytes, fnv1a64(payload)=%016llx\n", sizeof(ser),
         static_cast<unsigned long long>(sb_fnv1a64(ser, sizeof(ser) - 8)));

  // D = 128 e D = 256 pela mesma ABI (estado em armazenamento estático, sem heap)
  alignas(64) static unsigned char b128[sizeof(SynapticBudget<128>) + 64];
  alignas(64) static unsigned char b256[sizeof(SynapticBudget<256>) + 64];
  CHECK(sb128_state_bytes() == sizeof(SynapticBudget<128>) && sb128_state_align() <= 64);
  CHECK(sb256_state_bytes() == sizeof(SynapticBudget<256>) && sb256_state_align() <= 64);
  CHECK(sb128_init(b128) == 0 && sb256_init(b256) == 0);
  static double X128[40 * 128], X256[40 * 256];
  for (auto& v : X128) v = rng.gauss();
  for (auto& v : X256) v = rng.gauss();
  CHECK(sb128_observe(b128, X128, 40, 1.0) == 0);
  CHECK(sb256_observe(b256, X256, 40, 1.0) == 0);
  CHECK(sb128_commit(b128, 30) == 0);
  CHECK(sb256_commit(b256, 30) == 0);
  static double G256[256];
  for (auto& v : G256) v = rng.gauss();
  sb256_project(b256, G256, 1);
  double worst = 0.0;
  const auto* s256 = static_cast<const SynapticBudget<256>*>(static_cast<const void*>(b256));
  for (int k = 0; k < 30; ++k) {
    double d = 0.0;
    for (int i = 0; i < 256; ++i) d += G256[i] * s256->basis_row(k)[i];
    worst = std::fmax(worst, std::fabs(d));
  }
  CHECK(worst < 1e-12);
  // 40 amostras => posto(A) <= 40: cauda além de 40 é ~0 e energia total = 1 (traço normalizado)
  CHECK(std::fabs(sb256_total_energy(b256) - 1.0) < 1e-12);
  CHECK(sb256_weighted_tail(b256, 40) < 1e-12);
  SB_LOG("    D=128: %zu B, D=256: %zu B, |G'u|max(256)=%.2e, GRSB(256)=%zu B\n", sb128_state_bytes(),
         sb256_state_bytes(), worst, sb256_serialized_bytes());
}

// ------------------------------------------------------------------ T17
// P5: cota de esquecimento cumulativo sob rotações NÃO-aninhadas de subespaço.
// Cada tarefa t tem Sigma_t = Q_t diag(e^{-i/tau}) Q_t^T com Q_t Haar independente; após cada
// commit(B) medimos eps_s^{(t)} = tr(Sigma^_s (I - P_t)) de toda tarefa passada s <= t.
// Modelo multiplicativo de retenção: F_s = F_inf (1 - prod_{t>=s} (1 - c eps_s^{(t)})).
static void t_forgetting_bound() {
  SB_LOG("[T17] P5: cota de esquecimento cumulativo (rotações não-aninhadas)\n");
  constexpr int D = 24, T = 8;
  constexpr double Finf = 0.59, c = 0.8;
  Rng rng{17171717ULL};
  static SynapticBudget<D> sb;
  sb = SynapticBudget<D>();
  sb.enable_epsilon_history(true);
  static std::array<double, D * D> Sig[T];
  static double Q[D * D];
  static double eps_task[T][T];          // eps_task[s][t], t >= s
  static double eps_max[T];
  std::array<double, D * D> prevP{};
  double min_non_nested = 1e300;
  int exact_ok = 0;
  for (int t = 0; t < T; ++t) {
    rand_orth<D>(rng, Q, D);
    make_sigma<D>(Q, 2.5, Sig[t]);
    CHECK(sb.observe_covariance(Sig[t], 1.0 + 0.25 * t) == Status::kOk);
    const int B = 3 + 2 * t;              // posto crescente: subespaço protegido gira e cresce
    CHECK(sb.commit(B) == Status::kOk);
    // identidade exata: eps registrado == tr(Abar (I-P)), Abar = A / sum w
    std::array<double, D * D> Abar = sb.accumulator();
    for (auto& v : Abar) v /= sb.weight_sum();
    const double eps_rec = sb.epsilon_history()[t];
    const double eps_dir = sb.epsilon_of(Abar);
    if (std::fabs(eps_rec - eps_dir) <= 1e-12 * (eps_dir > 1e-300 ? eps_dir : 1.0) + 1e-15) ++exact_ok;
    eps_max[t] = 0.0;
    for (int s = 0; s <= t; ++s) {
      eps_task[s][t] = sb.epsilon_of(Sig[s]);
      if (eps_task[s][t] > eps_max[t]) eps_max[t] = eps_task[s][t];
    }
    // não-aninhamento: ||P_{t-1} (I - P_t)||_F^2 > 0 (subespaço antigo NÃO contido no novo)
    std::array<double, D * D> P{};
    for (int k = 0; k < B; ++k)
      for (int a = 0; a < D; ++a)
        for (int b = 0; b < D; ++b) P[a * D + b] += sb.basis_row(k)[a] * sb.basis_row(k)[b];
    if (t > 0) {
      double nn = 0.0;
      for (int a = 0; a < D; ++a)
        for (int b = 0; b < D; ++b) {
          double v = prevP[a * D + b];
          for (int k = 0; k < D; ++k) v -= prevP[a * D + k] * P[k * D + b];
          nn += v * v;
        }
      if (nn < min_non_nested) min_non_nested = nn;
    }
    prevP = P;
  }
  CHECK(exact_ok == T);
  CHECK(sb.epsilon_count() == T && !sb.epsilon_overflow());
  CHECK(min_non_nested > 1e-3);
  // (a) por tarefa: F_s simulado == cota com o próprio histórico (igualdade do modelo) e
  //     <= cota com o histórico do pior caso eps_max^{(t)} (monotonicidade)
  int dom = 0;
  double worst_gap = 0.0, meanF = 0.0, wsum = 0.0;
  for (int s = 0; s < T; ++s) {
    double ret = 1.0;
    for (int t = s; t < T; ++t) ret *= (1.0 - c * eps_task[s][t] > 0.0 ? 1.0 - c * eps_task[s][t] : 0.0);
    const double Fs = Finf * (1.0 - ret);
    double hist[T];
    int cnt = 0;
    for (int t = s; t < T; ++t) hist[cnt++] = eps_task[s][t];
    const double b_own = cumulative_forgetting_bound(Finf, c, hist, cnt);
    const double b_max = cumulative_forgetting_bound(Finf, c, eps_max + s, T - s);
    if (std::fabs(Fs - b_own) < 1e-15 && Fs <= b_max + 1e-15) ++dom;
    worst_gap = std::fmax(worst_gap, b_max - Fs);
    const double w = 1.0 + 0.25 * s;
    meanF += w * Fs; wsum += w;
  }
  meanF /= wsum;
  CHECK(dom == T);
  // (b) mistura (pseudo-tarefa Abar): cota com o histórico gravado pelo SynapticBudget está
  //     em [0, F_inf], é monótona em t e domina a média ponderada via cota da união.
  double sum_eps = 0.0;
  for (int t = 0; t < T; ++t) sum_eps += sb.epsilon_history()[t];
  const double bound_rec = sb.forgetting_bound(Finf, c);
  const double union_b = Finf * (c * sum_eps < 1.0 ? c * sum_eps : 1.0);
  CHECK(bound_rec >= 0.0 && bound_rec <= Finf && bound_rec <= union_b + 1e-15);
  double prev = 0.0;
  bool mono = true;
  for (int k = 1; k <= T; ++k) {
    const double b = cumulative_forgetting_bound(Finf, c, sb.epsilon_history(), k);
    if (b + 1e-18 < prev) mono = false;
    prev = b;
  }
  CHECK(mono);
  // union bound (sum_t eps^{(t)} das tarefas) domina a média ponderada de F_s
  double union_tasks = 0.0;
  for (int s = 0; s < T; ++s) {
    double se = 0.0;
    for (int t = s; t < T; ++t) se += eps_task[s][t];
    union_tasks += (1.0 + 0.25 * s) * Finf * (c * se < 1.0 ? c * se : 1.0);
  }
  CHECK(meanF <= union_tasks / wsum + 1e-15);
  // (c) casos de borda: eps >= 1/c satura em F_inf; vazio => 0; NaN => conservador
  const double sat[2] = {2.0, 0.1};
  CHECK(cumulative_forgetting_bound(Finf, c, sat, 2) == Finf);
  CHECK(cumulative_forgetting_bound(Finf, c, sat, 0) == 0.0);
  const double nanv[1] = {std::nan("")};
  CHECK(cumulative_forgetting_bound(Finf, c, nanv, 1) == Finf);
  // (d) estouro do histórico => cota conservadora F_inf
  static SynapticBudget<4> small;
  small = SynapticBudget<4>();
  small.enable_epsilon_history(true);
  std::array<double, 16> I4{};
  for (int i = 0; i < 4; ++i) I4[i * 4 + i] = 1.0;
  small.observe_covariance(I4, 1.0);
  for (int k = 0; k <= SynapticBudget<4>::kEpsHistoryCap; ++k) small.commit(1);
  CHECK(small.epsilon_overflow() && small.forgetting_bound(Finf, c) == Finf);
  SB_LOG("    ||P_{t-1}(I-P_t)||^2 min=%.4f  cota(mistura)=%.6f  união=%.6f  média F_s=%.6f  folga máx=%.4f\n",
         min_non_nested, bound_rec, union_b, meanF, worst_gap);
}

// ------------------------------------------------------------------ T18
// P6: MultiLayerBudget — alocação ótima de B_total entre camadas (pseudo-tarefas w_l = a_l^2).
static void t_multilayer() {
  SB_LOG("[T18] P6: orçamento multicamada (Cauchy-Schwarz, alocação gulosa exata)\n");
  constexpr int D = 8, L = 3;
  Rng rng{18181818ULL};
  static MultiLayerBudget<D, 4> ml;
  ml = MultiLayerBudget<D, 4>();
  CHECK(ml.set_layers(L) == Status::kOk);
  CHECK(ml.set_layers(5) == Status::kBadArg);
  const double taus[L] = {1.2, 3.0, 6.0};
  const double Ls[L] = {1.0, 0.9, 1.1}, op[L] = {0.5, 1.4, 0.8}, trS[L] = {2.0, 1.0, 3.5};
  static double Q[D * D];
  for (int l = 0; l < L; ++l) {
    for (int r = 0; r < 2; ++r) {         // 2 tarefas por camada, rotações distintas
      std::array<double, D * D> S{};
      rand_orth<D>(rng, Q, D);
      make_sigma<D>(Q, taus[l], S);
      CHECK(ml.observe_covariance(l, S, 1.0 + r) == Status::kOk);
    }
    CHECK(ml.set_layer_weight(l, Ls[l], op[l], trS[l]) == Status::kOk);
    CHECK(std::fabs(ml.layer_weight(l) - Ls[l] * Ls[l] * op[l] * op[l] * trS[l]) < 1e-15);
  }
  // eps_l(b) tabelado para a força bruta
  static double tab[L][D + 1];
  CHECK(ml.allocate(L * D) == Status::kOk);
  for (int l = 0; l < L; ++l) {
    const auto& lay = ml.layer(l);
    for (int b = 0; b <= D; ++b) tab[l][b] = lay.weighted_tail(b) / lay.weight_sum();
  }
  int opt_ok = 0, sum_ok = 0, cs_ok = 0, uni_ok = 0;
  bool monotone = true;
  double prevJ = 1e300, maxdiff = 0.0;
  for (int Bt = 0; Bt <= L * D; ++Bt) {
    CHECK(ml.allocate(Bt) == Status::kOk);
    int s = 0;
    for (int l = 0; l < L; ++l) s += ml.layer_rank(l);
    if (s == Bt) ++sum_ok;
    const double J = ml.weighted_objective();
    // força bruta sobre todas as alocações (b0,b1,b2) com soma Bt
    double best = 1e300;
    for (int b0 = 0; b0 <= D; ++b0)
      for (int b1 = 0; b1 <= D; ++b1) {
        const int b2 = Bt - b0 - b1;
        if (b2 < 0 || b2 > D) continue;
        const double v = ml.layer_weight(0) * tab[0][b0] + ml.layer_weight(1) * tab[1][b1] + ml.layer_weight(2) * tab[2][b2];
        if (v < best) best = v;
      }
    const double diff = std::fabs(J - best);
    maxdiff = std::fmax(maxdiff, diff);
    if (diff <= 1e-13 * (best > 1.0 ? best : 1.0)) ++opt_ok;
    // Cauchy-Schwarz: (sum a_l sqrt eps_l)^2 <= L sum w_l eps_l
    const double tri = ml.deep_drift_triangle(), cs = ml.deep_drift_bound();
    if (tri <= cs * (1.0 + 1e-14) + 1e-300) ++cs_ok;
    // alocação uniforme (b_l = Bt/L, resto às primeiras) nunca é melhor
    double uni = 0.0;
    for (int l = 0; l < L; ++l) {
      int b = Bt / L + (l < Bt % L ? 1 : 0);
      if (b > D) b = D;
      uni += ml.layer_weight(l) * tab[l][b];
    }
    if (J <= uni + 1e-15) ++uni_ok;
    if (J > prevJ + 1e-15) monotone = false;
    prevJ = J;
  }
  CHECK(opt_ok == L * D + 1);
  CHECK(sum_ok == L * D + 1);
  CHECK(cs_ok == L * D + 1);
  CHECK(uni_ok == L * D + 1);
  CHECK(monotone);
  // drift profundo real de um modelo linear em cascata (Lsigma=1): e_L <= sum_l a_l sqrt(eps_l)
  CHECK(ml.allocate(12) == Status::kOk);
  std::array<double, D> g{};
  for (auto& v : g) v = rng.gauss();
  ml.project(1, g.data(), 1);
  double orth = 0.0;
  for (int k = 0; k < ml.layer_rank(1); ++k) {
    double d = 0.0;
    for (int i = 0; i < D; ++i) d += g[i] * ml.layer(1).basis_row(k)[i];
    orth = std::fmax(orth, std::fabs(d));
  }
  CHECK(orth < 1e-13);
  CHECK(ml.allocate(L * D + 1) == Status::kBadArg);
  SB_LOG("    B=12: ranks=(%d,%d,%d) J=%.6f e_L<=%.6f (triangular %.6f)  |J-forçabruta|max=%.1e\n",
         ml.layer_rank(0), ml.layer_rank(1), ml.layer_rank(2), ml.weighted_objective(), ml.deep_drift_bound(),
         ml.deep_drift_triangle(), maxdiff);
}

// ------------------------------------------------------------------ execução
static int g_test_fails[19];
static void run_all() {
  using Fn = void (*)();
  static const Fn tests[18] = {t_det_math, t_jacobi, t_degenerate, t_invariance_bound, t_lyapunov, t_kyfan,
                               t_alloc_law, t_release, t_scaling, t_robust, t_planner, t_cross,
                               t_determinism, t_haar, t_spectrum_fit, t_c_api, t_forgetting_bound, t_multilayer};
  for (int i = 0; i < 18; ++i) {
    const int before = g_fail;
    tests[i]();
    g_test_fails[i + 1] = g_fail - before;
  }
  SB_LOG("\n%d verificações, %d falhas\n", g_checks, g_fail);
}

#ifdef SB_TESTS_AS_LIBRARY
// Autoteste no aparelho. per_test_fails[0..18] (índice = número do teste). Retorna nº de falhas.
// Chame de uma thread com pilha grande (>= 8 MB): T16 usa D=256.
extern "C" int sb_run_selftests(unsigned long long* t13_hash, int* checks, int* fails, int* per_test_fails) noexcept {
  g_fail = 0; g_checks = 0; g_log_len = 0; g_log[0] = 0; g_t13_hash = 0;
  for (int& f : g_test_fails) f = 0;
  run_all();
  if (t13_hash != nullptr) *t13_hash = g_t13_hash;
  if (checks != nullptr) *checks = g_checks;
  if (fails != nullptr) *fails = g_fail;
  if (per_test_fails != nullptr) for (int i = 0; i < 19; ++i) per_test_fails[i] = g_test_fails[i];
  return g_fail;
}
extern "C" const char* sb_selftest_log() noexcept { return g_log; }
#else
int main() {
  run_all();
  return g_fail == 0 ? 0 : 1;
}
#endif
