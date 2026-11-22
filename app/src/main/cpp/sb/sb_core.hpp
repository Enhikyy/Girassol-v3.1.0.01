// Projeto Girassol — Orçamento Sináptico (sb) — núcleo determinístico C++20
//
// Garantias de projeto:
//  * Zero alocação dinâmica: todo estado é std::array / pilha / buffer do chamador.
//  * Determinismo bit-a-bit: somente + - * / sqrt (IEEE-754 binary64, corretamente
//    arredondados), frexp/ldexp (exatos), ordem de redução fixa, sem threads.
//    exp/ln próprios (det_exp/det_ln). Compilar com -ffp-contract=off -fno-fast-math
//    e alvo SSE2/NEON-double (arm64, x86_64). x87 (x86-32) NÃO é suportado.
//  * Complexidades (D = dim. de entrada, n = amostras, B = posto protegido):
//      observe():       tempo O(n D^2)       espaço O(D^2)
//      commit():        tempo O(S D^3)       S = varreduras Jacobi (<= 64, tipicamente 6-12)
//      project_rows():  tempo O(m D B)       espaço O(D)
//    O estado total do SynapticBudget<D> é ~ 2 D^2 doubles (A e autovetores) + O(D):
//    NÃO cresce com o número de tarefas T (GPM clássico: O(T k D)).
//  * sizeof(SynapticBudget<64>) ≈ 66 KB; sizeof(SynapticBudget<256>) ≈ 1 MB:
//    coloque em armazenamento estático/campo de struct, nunca na pilha da thread principal.
#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>

namespace girassol::sb {

// ---------------------------------------------------------------- matemática
double det_exp(double x) noexcept;   // |erro rel| <~ 3 ulp em [-700, 700], só ops IEEE básicas
double det_ln(double x) noexcept;    // |erro rel| <~ 3 ulp em (0, inf)

// Estimadores do comprimento de decaimento tau (espectro exponencial, lambda_i ~ e^{-i/tau})
// e do expoente alpha (lei de potência, lambda_i ~ (i+1)^{-alpha}) por mínimos quadrados em
// [i0, i1). Eliminam "rho" como número mágico: rho == tau.  O(i1-i0).
double fit_decay_length(const double* lam, int i0, int i1) noexcept;
double fit_powerlaw_alpha(const double* lam, int i0, int i1) noexcept;

// Plasticidade hiperbólica (Wiener): a(f) = f / (f + kappa), a'(f) = kappa / (f + kappa)^2
inline double plasticity(double f, double kappa) noexcept { return f / (f + kappa); }
inline double plasticity_slope(double f, double kappa) noexcept {
  const double d = f + kappa;
  return kappa / (d * d);
}

// P5 — cota de esquecimento cumulativo sob rotações não-aninhadas de subespaço:
//   F_T <= F_inf * (1 - prod_t max(0, 1 - c * eps^{(t)}))
// eps^{(t)} = tr(Sigma^ (I - P_t)) da (pseudo)tarefa após o t-ésimo commit. Ordem fixa,
// só * e -, portanto bit-a-bit reprodutível. count <= 0 => 0. Resultado em [0, F_inf].
double cumulative_forgetting_bound(double Finf, double c, const double* eps_history, int count) noexcept;

// Diagnóstico de regime espectral: ajustes log-lineares (exponencial: ln lam ~ i;
// lei de potência: ln lam ~ ln(i+1)) com R^2 de ambos. O(i1-i0), sem alocação.
enum class Regime : int { kUnknown = 0, kExponential = 1, kPowerLaw = 2 };
struct SpectrumFitOut {
  double tau = 0.0;      // comprimento de decaimento (exponencial)
  double alpha = 0.0;    // expoente (lei de potência)
  double r2_exp = 0.0;   // R^2 do ajuste ln lam vs i
  double r2_pow = 0.0;   // R^2 do ajuste ln lam vs ln(i+1)
  Regime regime = Regime::kUnknown;
  int n = 0;             // pontos usados
};
enum class Status : int {
  kOk = 0,
  kDegenerateInput = 1,   // entrada identicamente nula
  kNonFinite = 2,         // NaN/Inf na entrada (estado NÃO é modificado)
  kNotConverged = 3,      // Jacobi atingiu max_sweeps (resultado ainda utilizável; ver off2)
  kBadArg = 4
};

// Retorna kOk, ou kBadArg (intervalo inválido / n > 512) e kDegenerateInput (lam <= 0).
Status analyze_spectrum(const double* lam, int i0, int i1, SpectrumFitOut& out) noexcept;

// ---------------------------------------------------------------- Jacobi cíclico
template <int N>
struct Eig {
  std::array<double, N> lam{};        // decrescente (empate: menor índice original primeiro)
  std::array<double, N * N> vt{};     // linha k = k-ésimo autovetor unitário
  int sweeps = 0;
  double off2 = 0.0;                  // soma dos quadrados fora da diagonal ao parar
  bool converged = false;
};

// Autodecomposição de matriz simétrica. Tempo O(S N^3), S <= max_sweeps. Espaço O(N^2).
// Ordem de rotações fixa (p<q lexicográfica) => resultado bit-a-bit reprodutível.
template <int N>
void jacobi_eig(const std::array<double, N * N>& Ain, Eig<N>& out, int max_sweeps = 64) noexcept {
  std::array<double, N * N> a = Ain;
  std::array<double, N * N> vt{};
  for (int i = 0; i < N; ++i) vt[i * N + i] = 1.0;
  for (int i = 0; i < N; ++i)
    for (int j = i + 1; j < N; ++j) {
      const double m = 0.5 * (a[i * N + j] + a[j * N + i]);
      a[i * N + j] = m;
      a[j * N + i] = m;
    }
  double tot = 0.0;
  for (int i = 0; i < N * N; ++i) tot += a[i] * a[i];

  out.converged = false;
  out.sweeps = 0;
  double off2 = 0.0;
  for (int sw = 0;; ++sw) {
    off2 = 0.0;
    for (int p = 0; p < N; ++p)
      for (int q = p + 1; q < N; ++q) off2 += a[p * N + q] * a[p * N + q];
    if (tot == 0.0 || off2 <= 1e-30 * tot) { out.converged = true; break; }
    if (sw == max_sweeps) break;
    out.sweeps = sw + 1;
    for (int p = 0; p < N; ++p) {
      for (int q = p + 1; q < N; ++q) {
        const double apq = a[p * N + q];
        if (apq == 0.0) continue;
        const double theta = (a[q * N + q] - a[p * N + p]) / (2.0 * apq);
        const double at = std::fabs(theta);
        double t;
        if (at > 1e150) {
          t = 0.5 / theta;
        } else {
          const double sg = (theta >= 0.0) ? 1.0 : -1.0;
          t = sg / (at + std::sqrt(theta * theta + 1.0));
        }
        const double c = 1.0 / std::sqrt(t * t + 1.0);
        const double s = t * c;
        for (int k = 0; k < N; ++k) {
          const double akp = a[k * N + p], akq = a[k * N + q];
          a[k * N + p] = c * akp - s * akq;
          a[k * N + q] = s * akp + c * akq;
        }
        for (int k = 0; k < N; ++k) {
          const double apk = a[p * N + k], aqk = a[q * N + k];
          a[p * N + k] = c * apk - s * aqk;
          a[q * N + k] = s * apk + c * aqk;
        }
        for (int k = 0; k < N; ++k) {
          const double vp = vt[p * N + k], vq = vt[q * N + k];
          vt[p * N + k] = c * vp - s * vq;
          vt[q * N + k] = s * vp + c * vq;
        }
        a[p * N + q] = 0.0;
        a[q * N + p] = 0.0;
      }
    }
  }
  out.off2 = off2;
  std::array<int, N> idx{};
  for (int i = 0; i < N; ++i) idx[i] = i;
  for (int i = 1; i < N; ++i) {            // inserção estável, decrescente
    const int key = idx[i];
    const double kv = a[key * N + key];
    int j = i - 1;
    while (j >= 0 && a[idx[j] * N + idx[j]] < kv) { idx[j + 1] = idx[j]; --j; }
    idx[j + 1] = key;
  }
  for (int k = 0; k < N; ++k) {
    out.lam[k] = a[idx[k] * N + idx[k]];
    for (int i = 0; i < N; ++i) out.vt[k * N + i] = vt[idx[k] * N + i];
  }
}

// ---------------------------------------------------------------- Protetor Ky-Fan
// Estado: A = sum_s w_s * Sigma_s / tr(Sigma_s)  (mistura de "estados de densidade").
// Teorema (Ky Fan): entre todos os projetores P de posto B, o que minimiza
//   sum_s w_s * eps_s(P),  eps_s(P) = tr(Sigma^_s (I-P)),
// é o projetor sobre os B autovetores dominantes de A; o valor mínimo é sum_{i>=B} lam_i(A).
template <int D>
class SynapticBudget {
 public:
  static constexpr int kDim = D;

  // X: n x D, row-major. Pré-escala por potência de 2 exata => sem overflow/underflow.
  // Se a entrada for rejeitada, o estado permanece intacto.
  Status observe(const double* X, int n, double w) noexcept {
    if (X == nullptr || n <= 0 || !(w > 0.0) || !std::isfinite(w)) return Status::kBadArg;
    double mx = 0.0;
    const long total = static_cast<long>(n) * D;
    for (long i = 0; i < total; ++i) {
      const double v = X[i];
      if (!std::isfinite(v)) return Status::kNonFinite;
      const double av = std::fabs(v);
      if (av > mx) mx = av;
    }
    if (mx == 0.0) return Status::kDegenerateInput;
    int ex = 0;
    (void)std::frexp(mx, &ex);
    std::array<double, D * D> S{};
    std::array<double, D> xs{};
    for (int r = 0; r < n; ++r) {
      for (int i = 0; i < D; ++i) xs[i] = std::ldexp(X[static_cast<long>(r) * D + i], -ex);
      for (int i = 0; i < D; ++i) {
        const double xi = xs[i];
        for (int j = 0; j <= i; ++j) S[i * D + j] += xi * xs[j];
      }
    }
    return accumulate_lower(S, w);
  }

  // Interface direta por covariância (simétrica PSD). Normaliza o traço para 1.
  Status observe_covariance(const std::array<double, D * D>& Sigma, double w) noexcept {
    if (!(w > 0.0) || !std::isfinite(w)) return Status::kBadArg;
    double mx = 0.0;
    for (int i = 0; i < D * D; ++i) {
      if (!std::isfinite(Sigma[i])) return Status::kNonFinite;
      const double av = std::fabs(Sigma[i]);
      if (av > mx) mx = av;
    }
    if (mx == 0.0) return Status::kDegenerateInput;
    int ex = 0;
    (void)std::frexp(mx, &ex);
    std::array<double, D * D> S{};
    for (int i = 0; i < D; ++i)
      for (int j = 0; j <= i; ++j) S[i * D + j] = std::ldexp(Sigma[i * D + j], -ex);
    return accumulate_lower(S, w);
  }

  // Fixa o posto protegido B e calcula a autobase de A.
  Status commit(int B) noexcept {
    if (B < 0 || B > D || ntasks_ == 0) return Status::kBadArg;
    jacobi_eig<D>(A_, eig_);
    B_ = B;
    if (eps_record_) record_epsilon();
    return eig_.converged ? Status::kOk : Status::kNotConverged;
  }

  // ---- P5: histórico opcional de eps^{(t)} ao longo de commits sucessivos -------------
  // eps^{(t)} = cauda ponderada / soma dos pesos = tr(Abar (I - P_t)), Abar = A / sum w:
  // é exatamente o eps da pseudo-tarefa "mistura" (linearidade do traço). Não altera A nem
  // a autobase (o hash T13 é preservado). Capacidade fixa; ao estourar, a cota degrada para
  // o valor conservador F_inf (nunca subestima).
  static constexpr int kEpsHistoryCap = 128;
  void enable_epsilon_history(bool on) noexcept { eps_record_ = on; }
  bool epsilon_history_enabled() const noexcept { return eps_record_; }
  void clear_epsilon_history() noexcept { eps_count_ = 0; eps_overflow_ = false; eps_hist_.fill(0.0); }
  int epsilon_count() const noexcept { return eps_count_; }
  bool epsilon_overflow() const noexcept { return eps_overflow_; }
  const double* epsilon_history() const noexcept { return eps_hist_.data(); }
  double current_epsilon() const noexcept { return wsum_ > 0.0 ? weighted_tail(B_) / wsum_ : 0.0; }
  double forgetting_bound(double Finf, double c) const noexcept {
    if (eps_overflow_) return Finf;
    return cumulative_forgetting_bound(Finf, c, eps_hist_.data(), eps_count_);
  }

  // ---- Serialização bruta (little-endian nativo; arm64 e x86_64 são LE) --------------
  static constexpr size_t kPayloadBytes =
      sizeof(double) * (static_cast<size_t>(D) * D * 2 + D + 2 + kEpsHistoryCap) + sizeof(int32_t) * 8;
  void write_payload(unsigned char* out) const noexcept {
    size_t o = 0;
    auto put = [&](const void* p, size_t n) { std::memcpy(out + o, p, n); o += n; };
    put(A_.data(), sizeof(double) * D * D);
    put(eig_.lam.data(), sizeof(double) * D);
    put(eig_.vt.data(), sizeof(double) * D * D);
    put(&eig_.off2, sizeof(double));
    put(&wsum_, sizeof(double));
    put(eps_hist_.data(), sizeof(double) * kEpsHistoryCap);
    const int32_t ints[8] = {eig_.sweeps, eig_.converged ? 1 : 0, ntasks_, B_, eps_count_,
                             eps_record_ ? 1 : 0, eps_overflow_ ? 1 : 0, D};
    put(ints, sizeof(ints));
  }
  // Pré-condição: bytes já validados (checksum). Rejeita campos fora de faixa sem tocar o estado.
  Status read_payload(const unsigned char* in) noexcept {
    int32_t ints[8];
    const size_t off_ints = kPayloadBytes - sizeof(ints);
    std::memcpy(ints, in + off_ints, sizeof(ints));
    if (ints[7] != D || ints[2] < 0 || ints[3] < 0 || ints[3] > D || ints[4] < 0 || ints[4] > kEpsHistoryCap)
      return Status::kBadArg;
    size_t o = 0;
    auto get = [&](void* p, size_t n) { std::memcpy(p, in + o, n); o += n; };
    get(A_.data(), sizeof(double) * D * D);
    get(eig_.lam.data(), sizeof(double) * D);
    get(eig_.vt.data(), sizeof(double) * D * D);
    get(&eig_.off2, sizeof(double));
    get(&wsum_, sizeof(double));
    get(eps_hist_.data(), sizeof(double) * kEpsHistoryCap);
    eig_.sweeps = ints[0];
    eig_.converged = ints[1] != 0;
    ntasks_ = ints[2];
    B_ = ints[3];
    eps_count_ = ints[4];
    eps_record_ = ints[5] != 0;
    eps_overflow_ = ints[6] != 0;
    return Status::kOk;
  }

  // Commit com posto escolhido pela cauda: uma única autodecomposição, B = min(max_rank,
  // rank_for_tail(frac)). Registra eps^{(t)} se o histórico estiver ligado.
  Status commit_for_tail(double frac, int max_rank) noexcept {
    if (ntasks_ == 0 || !(frac >= 0.0) || !(frac <= 1.0) || max_rank < 0) return Status::kBadArg;
    jacobi_eig<D>(A_, eig_);
    const int b = rank_for_tail(frac);
    const int cap = max_rank < D ? max_rank : D;
    B_ = b < cap ? b : cap;
    if (eps_record_) record_epsilon();
    return eig_.converged ? Status::kOk : Status::kNotConverged;
  }

  // Libera as 'count' direções de menor valor (as de menor lambda entre as protegidas).
  // Custo exato no objetivo ponderado: soma dos lambda liberados.
  void release(int count) noexcept {
    B_ = (count >= B_) ? 0 : B_ - count;
  }

  int rank() const noexcept { return B_; }
  int tasks() const noexcept { return ntasks_; }
  double free_fraction() const noexcept { return 1.0 - static_cast<double>(B_) / D; }
  double weight_sum() const noexcept { return wsum_; }
  const std::array<double, D * D>& accumulator() const noexcept { return A_; }
  const Eig<D>& eig() const noexcept { return eig_; }
  const double* basis_row(int k) const noexcept { return &eig_.vt[k * D]; }

  // sum_{i>=B} max(lam_i,0) == sum_s w_s eps_s  (identidade exata no ótimo Ky-Fan)
  double weighted_tail(int B) const noexcept {
    double t = 0.0;
    for (int i = B; i < D; ++i) t += (eig_.lam[i] > 0.0 ? eig_.lam[i] : 0.0);
    return t;
  }
  double total_energy() const noexcept { return weighted_tail(0); }

  // Menor B tal que cauda ponderada <= frac * energia total (frac in [0,1]).
  int rank_for_tail(double frac) const noexcept {
    const double tot = total_energy();
    for (int b = 0; b <= D; ++b)
      if (weighted_tail(b) <= frac * tot) return b;
    return D;
  }

  // eps(P) = tr(Sigma^ (I-P)) para Sigma^ simétrica com traço 1. O(B D^2).
  double epsilon_of(const std::array<double, D * D>& Sh) const noexcept {
    double tr = 0.0;
    for (int i = 0; i < D; ++i) tr += Sh[i * D + i];
    double cap = 0.0;
    for (int k = 0; k < B_; ++k) {
      const double* u = &eig_.vt[k * D];
      double q = 0.0;
      for (int i = 0; i < D; ++i) {
        double row = 0.0;
        for (int j = 0; j < D; ++j) row += Sh[i * D + j] * u[j];
        q += u[i] * row;
      }
      cap += q;
    }
    const double e = tr - cap;
    return e > 0.0 ? e : 0.0;
  }

  // G (m x D, row-major) <- G (I - P). Invariante: G' u_k = 0 para todo k < B (até O(u)).
  void project_rows(double* G, int m) const noexcept {
    std::array<double, D> coef{};
    for (int r = 0; r < m; ++r) {
      double* g = G + static_cast<long>(r) * D;
      for (int k = 0; k < B_; ++k) {
        const double* u = &eig_.vt[k * D];
        double acc = 0.0;
        for (int i = 0; i < D; ++i) acc += g[i] * u[i];
        coef[k] = acc;
      }
      for (int k = 0; k < B_; ++k) {
        const double* u = &eig_.vt[k * D];
        const double ck = coef[k];
        for (int i = 0; i < D; ++i) g[i] -= ck * u[i];
      }
    }
  }

 private:
  Status accumulate_lower(const std::array<double, D * D>& S, double w) noexcept {
    double tr = 0.0;
    for (int i = 0; i < D; ++i) tr += S[i * D + i];
    if (!(tr > 0.0)) return Status::kDegenerateInput;
    const double scale = w / tr;
    for (int i = 0; i < D; ++i)
      for (int j = 0; j <= i; ++j) {
        const double v = A_[i * D + j] + scale * S[i * D + j];
        A_[i * D + j] = v;
        A_[j * D + i] = v;
      }
    wsum_ += w;
    ++ntasks_;
    return Status::kOk;
  }

  void record_epsilon() noexcept {
    if (eps_count_ >= kEpsHistoryCap) { eps_overflow_ = true; return; }
    eps_hist_[eps_count_++] = current_epsilon();
  }

  std::array<double, D * D> A_{};
  Eig<D> eig_{};
  double wsum_ = 0.0;
  int ntasks_ = 0;
  int B_ = 0;
  std::array<double, kEpsHistoryCap> eps_hist_{};
  int eps_count_ = 0;
  bool eps_record_ = false;
  bool eps_overflow_ = false;
};

// ---------------------------------------------------------------- P6: orçamento multicamada
// Drift profundo: e_L <= sum_l a_l sqrt(eps_l)  e, por Cauchy-Schwarz,
//   e_L^2 <= (sum_l a_l sqrt(eps_l))^2 <= L * sum_l a_l^2 eps_l = L * sum_l w_l eps_l,
// a_l = L_sigma * ||dW_l||_op * sqrt(tr Sigma_l),  w_l = a_l^2.
// As camadas funcionam como pseudo-tarefas de peso w_l. Como eps_l(B) = cauda_l(B)/W_l é
// convexa e não-crescente em B (autovalores ordenados), a alocação gulosa por maior ganho
// marginal w_l * lam_{l,B_l} / W_l é EXATAMENTE ótima para min sum_l w_l eps_l(B_l)
// sujeito a sum_l B_l = B_total, 0 <= B_l <= D (problema separável convexo discreto).
template <int D, int MaxLayers>
class MultiLayerBudget {
 public:
  static_assert(MaxLayers >= 1, "MaxLayers >= 1");
  Status set_layers(int L) noexcept {
    if (L < 1 || L > MaxLayers) return Status::kBadArg;
    L_ = L;
    return Status::kOk;
  }
  int layers() const noexcept { return L_; }
  Status observe(int l, const double* X, int n, double w) noexcept {
    if (l < 0 || l >= L_) return Status::kBadArg;
    return layer_[l].observe(X, n, w);
  }
  Status observe_covariance(int l, const std::array<double, D * D>& S, double w) noexcept {
    if (l < 0 || l >= L_) return Status::kBadArg;
    return layer_[l].observe_covariance(S, w);
  }
  // w_l = (L_sigma * ||dW_l||_op * sqrt(tr Sigma_l))^2 = L_sigma^2 ||dW_l||_op^2 tr Sigma_l.
  Status set_layer_weight(int l, double Lsigma, double opnorm, double trSigma) noexcept {
    if (l < 0 || l >= L_ || !(Lsigma >= 0.0) || !(opnorm >= 0.0) || !(trSigma >= 0.0) ||
        !std::isfinite(Lsigma) || !std::isfinite(opnorm) || !std::isfinite(trSigma))
      return Status::kBadArg;
    w_[l] = Lsigma * Lsigma * opnorm * opnorm * trSigma;
    return Status::kOk;
  }
  Status set_layer_weight_raw(int l, double w) noexcept {
    if (l < 0 || l >= L_ || !(w >= 0.0) || !std::isfinite(w)) return Status::kBadArg;
    w_[l] = w;
    return Status::kOk;
  }
  double layer_weight(int l) const noexcept { return (l >= 0 && l < L_) ? w_[l] : 0.0; }

  // Distribui B_total entre as camadas minimizando a cota do drift profundo.
  Status allocate(int B_total) noexcept {
    if (B_total < 0 || B_total > L_ * D) return Status::kBadArg;
    Status st = Status::kOk;
    for (int l = 0; l < L_; ++l) {
      if (layer_[l].tasks() == 0) return Status::kBadArg;
      const Status s = layer_[l].commit(D);
      if (s != Status::kOk) st = s;
      B_[l] = 0;
    }
    for (int step = 0; step < B_total; ++step) {
      int best = -1;
      double bv = -1.0;
      for (int l = 0; l < L_; ++l) {
        if (B_[l] >= D) continue;
        const double ws = layer_[l].weight_sum();
        const double lam = layer_[l].eig().lam[B_[l]];
        const double g = (ws > 0.0) ? w_[l] * (lam > 0.0 ? lam : 0.0) / ws : 0.0;
        if (g > bv) { bv = g; best = l; }
      }
      ++B_[best];
    }
    for (int l = 0; l < L_; ++l) layer_[l].release(D - B_[l]);
    return st;
  }
  int layer_rank(int l) const noexcept { return (l >= 0 && l < L_) ? B_[l] : 0; }
  double layer_epsilon(int l) const noexcept {
    if (l < 0 || l >= L_) return 0.0;
    const double ws = layer_[l].weight_sum();
    return ws > 0.0 ? layer_[l].weighted_tail(B_[l]) / ws : 0.0;
  }
  // sum_l w_l eps_l (objetivo minimizado)
  double weighted_objective() const noexcept {
    double s = 0.0;
    for (int l = 0; l < L_; ++l) s += w_[l] * layer_epsilon(l);
    return s;
  }
  // Cota de Cauchy-Schwarz: e_L <= sqrt(L * sum_l w_l eps_l)
  double deep_drift_bound() const noexcept { return std::sqrt(static_cast<double>(L_) * weighted_objective()); }
  // Cota triangular (mais apertada): e_L <= sum_l a_l sqrt(eps_l)
  double deep_drift_triangle() const noexcept {
    double s = 0.0;
    for (int l = 0; l < L_; ++l) s += std::sqrt(w_[l]) * std::sqrt(layer_epsilon(l));
    return s;
  }
  void project(int l, double* G, int m) const noexcept {
    if (l >= 0 && l < L_ && G != nullptr) layer_[l].project_rows(G, m);
  }
  const SynapticBudget<D>& layer(int l) const noexcept { return layer_[l]; }

 private:
  std::array<SynapticBudget<D>, MaxLayers> layer_{};
  std::array<double, MaxLayers> w_{};
  std::array<int, MaxLayers> B_{};
  int L_ = 1;
};

// ---------------------------------------------------------------- serialização GRSB
// Formato: "GRSB" | u32 versão=1 | u32 D | u32 reservado=0 | u64 bytes do payload |
//          payload | u64 FNV-1a 64 de (cabeçalho + payload). Little-endian.
constexpr uint32_t kGrsbVersion = 1;
constexpr size_t kGrsbHeaderBytes = 24;
uint64_t fnv1a64(const unsigned char* p, size_t n, uint64_t h = 1469598103934665603ULL) noexcept;

template <int D>
constexpr size_t grsb_bytes() noexcept { return kGrsbHeaderBytes + SynapticBudget<D>::kPayloadBytes + 8; }

template <int D>
long long grsb_serialize(const SynapticBudget<D>& sb, unsigned char* out, size_t cap) noexcept {
  const size_t total = grsb_bytes<D>();
  if (out == nullptr || cap < total) return -1;
  const uint32_t hdr32[4] = {0, kGrsbVersion, static_cast<uint32_t>(D), 0};
  std::memcpy(out, hdr32, sizeof(hdr32));
  out[0] = 'G'; out[1] = 'R'; out[2] = 'S'; out[3] = 'B';
  const uint64_t plen = SynapticBudget<D>::kPayloadBytes;
  std::memcpy(out + 16, &plen, 8);
  sb.write_payload(out + kGrsbHeaderBytes);
  const uint64_t h = fnv1a64(out, kGrsbHeaderBytes + plen);
  std::memcpy(out + kGrsbHeaderBytes + plen, &h, 8);
  return static_cast<long long>(total);
}

// Valida tudo antes de escrever: em falha o estado permanece intacto.
template <int D>
Status grsb_deserialize(SynapticBudget<D>& sb, const unsigned char* in, size_t len) noexcept {
  if (in == nullptr || len != grsb_bytes<D>()) return Status::kBadArg;
  if (in[0] != 'G' || in[1] != 'R' || in[2] != 'S' || in[3] != 'B') return Status::kBadArg;
  uint32_t ver = 0, dim = 0;
  uint64_t plen = 0, h = 0;
  std::memcpy(&ver, in + 4, 4);
  std::memcpy(&dim, in + 8, 4);
  std::memcpy(&plen, in + 16, 8);
  if (ver != kGrsbVersion || dim != static_cast<uint32_t>(D) || plen != SynapticBudget<D>::kPayloadBytes)
    return Status::kBadArg;
  std::memcpy(&h, in + kGrsbHeaderBytes + plen, 8);
  if (h != fnv1a64(in, kGrsbHeaderBytes + plen)) return Status::kNonFinite;
  return sb.read_payload(in + kGrsbHeaderBytes);
}

// ---------------------------------------------------------------- Cruz hiperbólica
// Proteção em base Kronecker (geometria da informação): drift de KL ~ 1/2 tr(F dW Sigma dW^T)
// = 1/2 sum_{a,j} lamF_a lamS_j (v_a^T dW u_j)^2. Protege-se o conjunto de pares (a,j) de maior
// produto lamF_a*lamS_j (B pares); o resto é livre. Seleção exata (bathtub) em O(B*DO).
template <int DI, int DO>
class CrossProtector {
 public:
  Status build(const std::array<double, DI * DI>& Sigma, const std::array<double, DO * DO>& Fisher,
               int B) noexcept {
    if (B < 0 || B > DI * DO) return Status::kBadArg;
    jacobi_eig<DI>(Sigma, es_);
    jacobi_eig<DO>(Fisher, ef_);
    mask_.fill(0);
    std::array<int, DO> nxt{};
    for (int step = 0; step < B; ++step) {
      int best = -1;
      double bv = -1.0;
      for (int a = 0; a < DO; ++a) {
        if (nxt[a] >= DI) continue;
        const double v = pos(ef_.lam[a]) * pos(es_.lam[nxt[a]]);
        if (v > bv) { bv = v; best = a; }
      }
      mask_[best * DI + nxt[best]] = 1;
      ++nxt[best];
    }
    mu_cut_ = 0.0;
    for (int a = 0; a < DO; ++a)
      if (nxt[a] < DI) {
        const double v = pos(ef_.lam[a]) * pos(es_.lam[nxt[a]]);
        if (v > mu_cut_) mu_cut_ = v;
      }
    B_ = B;
    return (es_.converged && ef_.converged) ? Status::kOk : Status::kNotConverged;
  }

  // dW (DO x DI, row-major) <- V^T( mask-livre( V dW U^T ) ) U. Tempo O(DO*DI*(DO+DI)).
  void project(double* dW) const noexcept {
    std::array<double, DO * DI> t1{}, t2{};
    for (int o = 0; o < DO; ++o)
      for (int j = 0; j < DI; ++j) {
        double acc = 0.0;
        for (int i = 0; i < DI; ++i) acc += dW[o * DI + i] * es_.vt[j * DI + i];
        t1[o * DI + j] = acc;
      }
    for (int a = 0; a < DO; ++a)
      for (int j = 0; j < DI; ++j) {
        double acc = 0.0;
        for (int o = 0; o < DO; ++o) acc += ef_.vt[a * DO + o] * t1[o * DI + j];
        t2[a * DI + j] = mask_[a * DI + j] ? 0.0 : acc;
      }
    for (int o = 0; o < DO; ++o)
      for (int j = 0; j < DI; ++j) {
        double acc = 0.0;
        for (int a = 0; a < DO; ++a) acc += ef_.vt[a * DO + o] * t2[a * DI + j];
        t1[o * DI + j] = acc;
      }
    for (int o = 0; o < DO; ++o)
      for (int i = 0; i < DI; ++i) {
        double acc = 0.0;
        for (int j = 0; j < DI; ++j) acc += t1[o * DI + j] * es_.vt[j * DI + i];
        dW[o * DI + i] = acc;
      }
  }

  int pairs() const noexcept { return B_; }
  double mu_cut() const noexcept { return mu_cut_; }
  bool is_protected(int a, int j) const noexcept { return mask_[a * DI + j] != 0; }
  const double* in_vec(int j) const noexcept { return &es_.vt[j * DI]; }
  const double* out_vec(int a) const noexcept { return &ef_.vt[a * DO]; }
  double lam_in(int j) const noexcept { return es_.lam[j]; }
  double lam_out(int a) const noexcept { return ef_.lam[a]; }

 private:
  static double pos(double x) noexcept { return x > 0.0 ? x : 0.0; }
  Eig<DI> es_{};
  Eig<DO> ef_{};
  std::array<unsigned char, DO * DI> mask_{};
  int B_ = 0;
  double mu_cut_ = 0.0;
};

// ---------------------------------------------------------------- Planejador (controle ótimo)
constexpr int kMaxHorizon = 128;

struct PlanParams {
  int H = 0;          // horizonte (nº de tarefas), 2..kMaxHorizon
  double d = 0.0;     // dimensões plásticas totais
  double rho = 0.0;   // dims por unidade de ln(1/eps)  (== tau do espectro)
  double kappa = 0.0; // razão ruído/sinal da lei de plasticidade
  double Finf = 0.0;  // esquecimento máximo
  double c = 0.0;     // taxa de esquecimento por unidade de eps e por tarefa
  double f_min = 0.0; // reserva mínima de plasticidade, em [0,1)
};

struct PlanOut {
  double u[kMaxHorizon] = {};   // u_t = ln(1/eps_t); eps_t = e^{-u_t}; dims_t = rho*u_t
  double J = 0.0;               // objetivo linearizado (cota inferior do verdadeiro)
  int sweeps = 0;
};

// J(u) = sum_t a(f_t) - Finf*c*sum_t (H-t) e^{-u_t},  f_t = 1 - (rho/d) sum_{s<t} u_s.
// Côncavo em u; ascensão coordenada com bisseção exata; J monótona; viável sempre.
Status plan_protection(const PlanParams& p, PlanOut& out) noexcept;
double plan_objective(const PlanParams& p, const double* u) noexcept;
// dJ/du_t (t em [0,H-1], 0-based). Em ótimo interior vale 0 (condição de Pontryagin/KKT).
double plan_gradient(const PlanParams& p, const double* u, int t) noexcept;

}  // namespace girassol::sb

// ---------------------------------------------------------------- ABI C (JNI) para D = 64/128/256
// Sem alocação dinâmica: o chamador fornece um buffer alinhado a sbN_state_align() (64 bytes
// via ByteBuffer.allocateDirect com deslocamento de alinhamento) e sbN_init faz placement-new.
// ATENÇÃO (pilha): observe/commit usam O(D^2) doubles em pilha (D=256 => ~1,5 MB); chame-os de
// uma thread com pilha grande (o motor Kotlin usa uma Thread dedicada de 64 MB).
#define GIRASSOL_SB_DECLARE_ABI(N)                                                              \
  size_t sb##N##_state_bytes() noexcept;                                                        \
  size_t sb##N##_state_align() noexcept;                                                        \
  int sb##N##_init(void* mem) noexcept;                                                         \
  int sb##N##_observe(void* st, const double* X, int n, double w) noexcept;                     \
  int sb##N##_commit(void* st, int B) noexcept;                                                 \
  int sb##N##_commit_for_tail(void* st, double frac, int max_rank) noexcept;                    \
  void sb##N##_release(void* st, int count) noexcept;                                           \
  void sb##N##_project(const void* st, double* G, int m) noexcept;                              \
  double sb##N##_free_fraction(const void* st) noexcept;                                        \
  int sb##N##_rank(const void* st) noexcept;                                                    \
  int sb##N##_tasks(const void* st) noexcept;                                                   \
  double sb##N##_weighted_tail(const void* st, int B) noexcept;                                 \
  double sb##N##_total_energy(const void* st) noexcept;                                         \
  int sb##N##_rank_for_tail(const void* st, double frac) noexcept;                              \
  int sb##N##_eigenvalues(const void* st, double* out, int cap) noexcept;                       \
  void sb##N##_enable_eps_history(void* st, int on) noexcept;                                   \
  int sb##N##_eps_history(const void* st, double* out, int cap) noexcept;                       \
  double sb##N##_current_epsilon(const void* st) noexcept;                                      \
  double sb##N##_forgetting_bound(const void* st, double Finf, double c) noexcept;              \
  size_t sb##N##_serialized_bytes() noexcept;                                                   \
  long long sb##N##_serialize(const void* st, unsigned char* out, size_t cap) noexcept;         \
  int sb##N##_deserialize(void* st, const unsigned char* in, size_t len) noexcept;

extern "C" {
GIRASSOL_SB_DECLARE_ABI(64)
GIRASSOL_SB_DECLARE_ABI(128)
GIRASSOL_SB_DECLARE_ABI(256)
// Planejador ótimo: u_out[H] (u_t = ln 1/eps_t). Retorna Status.
int sb_plan_protection(int H, double d, double rho, double kappa, double Finf, double c, double f_min,
                       double* u_out, double* J_out, int* sweeps_out) noexcept;
// out5 = {tau, alpha, r2_exp, r2_pow, regime}. Retorna Status.
int sb_analyze_spectrum(const double* lam, int i0, int i1, double* out5) noexcept;
double sb_cumulative_forgetting_bound(double Finf, double c, const double* eps, int count) noexcept;
uint64_t sb_fnv1a64(const unsigned char* p, size_t n) noexcept;
}
