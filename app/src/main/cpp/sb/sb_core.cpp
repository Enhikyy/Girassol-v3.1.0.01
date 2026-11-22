// Projeto Girassol — Orçamento Sináptico (sb): implementação não-template.
// Compilar: g++ -std=c++20 -O2 -ffp-contract=off -fno-fast-math -c sb_core.cpp
#include "sb_core.hpp"

#include <limits>

namespace girassol::sb {

namespace {
constexpr double kLn2Hi = 6.93147180369123816490e-01;   // 0x3fe62e42fee00000 (32 bits baixos nulos)
constexpr double kLn2Lo = 1.90821492927058770002e-10;
constexpr double kInvLn2 = 1.44269504088896338700e+00;
constexpr double kSqrtHalf = 0.70710678118654752440;
}  // namespace

// e^x = 2^k * e^r, r = x - k ln2, |r| <= 0.35; Taylor de grau 20 em Horner (resto < 1e-27).
double det_exp(double x) noexcept {
  if (!(x == x)) return x;
  if (x > 709.0) return std::numeric_limits<double>::infinity();
  if (x < -708.0) return 0.0;
  double kf = x * kInvLn2;
  kf = (kf >= 0.0) ? kf + 0.5 : kf - 0.5;
  const int k = static_cast<int>(kf);
  const double kd = static_cast<double>(k);
  const double r = (x - kd * kLn2Hi) - kd * kLn2Lo;
  double p = 1.0;
  for (int i = 20; i >= 1; --i) p = 1.0 + r * p / static_cast<double>(i);
  return std::ldexp(p, k);
}

// ln x = e ln2 + ln m, m in [sqrt(1/2), sqrt 2); ln m = 2 s sum_j s^{2j}/(2j+1), s=(m-1)/(m+1).
double det_ln(double x) noexcept {
  if (!(x == x) || x < 0.0) return std::numeric_limits<double>::quiet_NaN();
  if (x == 0.0) return -std::numeric_limits<double>::infinity();
  if (std::isinf(x)) return x;
  int e = 0;
  double m = std::frexp(x, &e);
  if (m < kSqrtHalf) { m *= 2.0; --e; }
  const double f = m - 1.0;
  const double s = f / (2.0 + f);
  const double z = s * s;
  double q = 0.0;
  for (int j = 14; j >= 0; --j) q = 1.0 / static_cast<double>(2 * j + 1) + z * q;
  const double ed = static_cast<double>(e);
  return ed * kLn2Hi + (ed * kLn2Lo + 2.0 * s * q);
}

namespace {
// Regressão linear y = a + b t em i in [i0,i1). Retorna b.
double lsq_slope(const double* t, const double* y, int n) noexcept {
  double st = 0.0, sy = 0.0;
  for (int i = 0; i < n; ++i) { st += t[i]; sy += y[i]; }
  const double mt = st / n, my = sy / n;
  double num = 0.0, den = 0.0;
  for (int i = 0; i < n; ++i) { num += (t[i] - mt) * (y[i] - my); den += (t[i] - mt) * (t[i] - mt); }
  return den > 0.0 ? num / den : 0.0;
}
}  // namespace

double fit_decay_length(const double* lam, int i0, int i1) noexcept {
  constexpr int kMax = 4096;
  const int n = i1 - i0;
  if (n < 2 || n > kMax) return 0.0;
  double t[kMax > 512 ? 512 : kMax];
  double y[kMax > 512 ? 512 : kMax];
  if (n > 512) return 0.0;
  for (int i = 0; i < n; ++i) {
    if (!(lam[i0 + i] > 0.0)) return 0.0;
    t[i] = static_cast<double>(i0 + i);
    y[i] = det_ln(lam[i0 + i]);
  }
  const double b = lsq_slope(t, y, n);
  return b < 0.0 ? -1.0 / b : 0.0;
}

double fit_powerlaw_alpha(const double* lam, int i0, int i1) noexcept {
  const int n = i1 - i0;
  if (n < 2 || n > 512) return 0.0;
  double t[512], y[512];
  for (int i = 0; i < n; ++i) {
    if (!(lam[i0 + i] > 0.0)) return 0.0;
    t[i] = det_ln(static_cast<double>(i0 + i + 1));
    y[i] = det_ln(lam[i0 + i]);
  }
  const double b = lsq_slope(t, y, n);
  return -b;
}

// ------------------------------------------------------------------ P5
double cumulative_forgetting_bound(double Finf, double c, const double* eps_history, int count) noexcept {
  if (count <= 0 || eps_history == nullptr || !(Finf > 0.0)) return 0.0;
  double prod = 1.0;
  for (int t = 0; t < count; ++t) {
    double e = eps_history[t];
    if (!(e == e)) return Finf;                 // NaN => conservador
    double f = 1.0 - c * e;
    if (f < 0.0) f = 0.0;
    if (f > 1.0) f = 1.0;                       // eps < 0 não "desfaz" esquecimento
    prod *= f;
  }
  return Finf * (1.0 - prod);
}

// ------------------------------------------------------------------ regime espectral
namespace {
struct LineFit { double slope = 0.0, intercept = 0.0, r2 = 0.0; };
LineFit lsq_fit(const double* t, const double* y, int n) noexcept {
  LineFit f;
  double st = 0.0, sy = 0.0;
  for (int i = 0; i < n; ++i) { st += t[i]; sy += y[i]; }
  const double mt = st / n, my = sy / n;
  double sxy = 0.0, sxx = 0.0, syy = 0.0;
  for (int i = 0; i < n; ++i) {
    const double dt = t[i] - mt, dy = y[i] - my;
    sxy += dt * dy; sxx += dt * dt; syy += dy * dy;
  }
  if (!(sxx > 0.0)) return f;
  f.slope = sxy / sxx;
  f.intercept = my - f.slope * mt;
  if (!(syy > 0.0)) { f.r2 = 1.0; return f; }   // dados exatamente constantes: ajuste perfeito
  double sse = 0.0;
  for (int i = 0; i < n; ++i) {
    const double r = y[i] - (f.intercept + f.slope * t[i]);
    sse += r * r;
  }
  f.r2 = 1.0 - sse / syy;
  return f;
}
}  // namespace

Status analyze_spectrum(const double* lam, int i0, int i1, SpectrumFitOut& out) noexcept {
  out = SpectrumFitOut{};
  const int n = i1 - i0;
  if (lam == nullptr || i0 < 0 || n < 3 || n > 512) return Status::kBadArg;
  double ti[512], tl[512], y[512];
  for (int i = 0; i < n; ++i) {
    const double v = lam[i0 + i];
    if (!(v > 0.0) || !std::isfinite(v)) return Status::kDegenerateInput;
    ti[i] = static_cast<double>(i0 + i);
    tl[i] = det_ln(static_cast<double>(i0 + i + 1));
    y[i] = det_ln(v);
  }
  const LineFit fe = lsq_fit(ti, y, n);
  const LineFit fp = lsq_fit(tl, y, n);
  out.n = n;
  out.tau = fe.slope < 0.0 ? -1.0 / fe.slope : 0.0;
  out.alpha = -fp.slope;
  out.r2_exp = fe.r2;
  out.r2_pow = fp.r2;
  if (fe.slope >= 0.0 && fp.slope >= 0.0) out.regime = Regime::kUnknown;
  else if (fe.r2 >= fp.r2) out.regime = Regime::kExponential;
  else out.regime = Regime::kPowerLaw;
  return Status::kOk;
}

uint64_t fnv1a64(const unsigned char* p, size_t n, uint64_t h) noexcept {
  for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
  return h;
}

// ------------------------------------------------------------------ planejador
namespace {
inline double u_cap(const PlanParams& p) noexcept { return (1.0 - p.f_min) * p.d / p.rho; }
}  // namespace

double plan_objective(const PlanParams& p, const double* u) noexcept {
  double J = 0.0, cum = 0.0;
  for (int t = 0; t < p.H; ++t) {
    const double f = 1.0 - (p.rho / p.d) * cum;
    J += plasticity(f, p.kappa);
    J -= p.Finf * p.c * static_cast<double>(p.H - 1 - t) * det_exp(-u[t]);
    cum += u[t];
  }
  return J;
}

// dJ/du_t = - (rho/d) sum_{r>t} a'(f_r) + Finf c (H-1-t) e^{-u_t}   (índices 0-based)
double plan_gradient(const PlanParams& p, const double* u, int t) noexcept {
  double cum = 0.0;
  for (int s = 0; s <= t; ++s) cum += u[s];
  double sum_a = 0.0;
  for (int r = t + 1; r < p.H; ++r) {
    const double f = 1.0 - (p.rho / p.d) * cum;
    sum_a += plasticity_slope(f, p.kappa);
    cum += u[r];
  }
  return -(p.rho / p.d) * sum_a + p.Finf * p.c * static_cast<double>(p.H - 1 - t) * det_exp(-u[t]);
}

Status plan_protection(const PlanParams& p, PlanOut& out) noexcept {
  if (p.H < 2 || p.H > kMaxHorizon || !(p.d > 0.0) || !(p.rho > 0.0) || !(p.kappa > 0.0) ||
      !(p.Finf >= 0.0) || !(p.c >= 0.0) || !(p.f_min >= 0.0) || !(p.f_min < 1.0))
    return Status::kBadArg;
  for (int t = 0; t < kMaxHorizon; ++t) out.u[t] = 0.0;
  const double cap = u_cap(p);
  const int n = p.H - 1;                              // a última tarefa não protege ninguém: u_H = 0
  int sweep = 0;
  for (; sweep < 4000; ++sweep) {
    double maxdelta = 0.0;
    // (1) passo coordenado: maximiza J em u_t com os demais fixos (côncavo 1-D, bisseção exata)
    for (int t = 0; t < n; ++t) {
      double others = 0.0;
      for (int s = 0; s < n; ++s) if (s != t) others += out.u[s];
      double hi = cap - others;
      if (hi < 0.0) hi = 0.0;
      if (hi > 600.0) hi = 600.0;
      const double old = out.u[t];
      out.u[t] = 0.0;
      double ut;
      if (plan_gradient(p, out.u, t) <= 0.0) {
        ut = 0.0;
      } else {
        out.u[t] = hi;
        if (plan_gradient(p, out.u, t) >= 0.0) {
          ut = hi;
        } else {
          double lo = 0.0, hh = hi;
          for (int it = 0; it < 100; ++it) {
            const double mid = 0.5 * (lo + hh);
            out.u[t] = mid;
            if (plan_gradient(p, out.u, t) > 0.0) lo = mid; else hh = mid;
          }
          ut = 0.5 * (lo + hh);
        }
      }
      out.u[t] = ut;
      const double dlt = std::fabs(ut - old);
      if (dlt > maxdelta) maxdelta = dlt;
    }
    // (2) troca de massa entre pares (soma fixa): necessário quando o orçamento está ativo,
    //     pois o passo coordenado sozinho estaciona em pontos viáveis não-KKT.
    double used = 0.0;
    for (int t = 0; t < n; ++t) used += out.u[t];
    if (cap - used <= 1e-9 * (cap > 1.0 ? cap : 1.0)) {
      for (int t = 0; t < n; ++t) {
        for (int s = t + 1; s < n; ++s) {
          const double ut0 = out.u[t], us0 = out.u[s];
          const double dlo = -ut0, dhi = us0;           // delta in [-u_t, u_s]
          if (dhi - dlo <= 0.0) continue;
          auto gp = [&](double d) {                     // g'(delta) = grad_t - grad_s (decrescente)
            out.u[t] = ut0 + d; out.u[s] = us0 - d;
            return plan_gradient(p, out.u, t) - plan_gradient(p, out.u, s);
          };
          double d;
          if (gp(dlo) <= 0.0) d = dlo;
          else if (gp(dhi) >= 0.0) d = dhi;
          else {
            double lo = dlo, hh = dhi;
            for (int it = 0; it < 70; ++it) { const double mid = 0.5 * (lo + hh); if (gp(mid) > 0.0) lo = mid; else hh = mid; }
            d = 0.5 * (lo + hh);
          }
          out.u[t] = ut0 + d; out.u[s] = us0 - d;
          if (out.u[t] < 0.0) out.u[t] = 0.0;
          if (out.u[s] < 0.0) out.u[s] = 0.0;
          const double dd = std::fabs(d);
          if (dd > maxdelta) maxdelta = dd;
        }
      }
    }
    if (maxdelta < 1e-12) { ++sweep; break; }
  }
  out.J = plan_objective(p, out.u);
  out.sweeps = sweep;
  return Status::kOk;
}

}  // namespace girassol::sb

// ------------------------------------------------------------------ ABI C
#define GIRASSOL_SB_DEFINE_ABI(N)                                                                  \
  size_t sb##N##_state_bytes() noexcept { return sizeof(girassol::sb::SynapticBudget<N>); }         \
  size_t sb##N##_state_align() noexcept { return alignof(girassol::sb::SynapticBudget<N>); }        \
  int sb##N##_init(void* mem) noexcept {                                                           \
    if (mem == nullptr) return static_cast<int>(Status::kBadArg);                                  \
    ::new (mem) girassol::sb::SynapticBudget<N>();                                                 \
    return static_cast<int>(Status::kOk);                                                          \
  }                                                                                                \
  int sb##N##_observe(void* st, const double* X, int n, double w) noexcept {                       \
    if (st == nullptr) return static_cast<int>(Status::kBadArg);                                   \
    return static_cast<int>(static_cast<girassol::sb::SynapticBudget<N>*>(st)->observe(X, n, w));  \
  }                                                                                                \
  int sb##N##_commit(void* st, int B) noexcept {                                                   \
    if (st == nullptr) return static_cast<int>(Status::kBadArg);                                   \
    return static_cast<int>(static_cast<girassol::sb::SynapticBudget<N>*>(st)->commit(B));         \
  }                                                                                                \
  int sb##N##_commit_for_tail(void* st, double frac, int max_rank) noexcept {                      \
    if (st == nullptr) return static_cast<int>(Status::kBadArg);                                   \
    return static_cast<int>(static_cast<girassol::sb::SynapticBudget<N>*>(st)->commit_for_tail(frac, max_rank)); \
  }                                                                                                \
  void sb##N##_release(void* st, int count) noexcept {                                             \
    if (st != nullptr && count > 0) static_cast<girassol::sb::SynapticBudget<N>*>(st)->release(count); \
  }                                                                                                \
  void sb##N##_project(const void* st, double* G, int m) noexcept {                                \
    if (st == nullptr || G == nullptr || m <= 0) return;                                           \
    static_cast<const girassol::sb::SynapticBudget<N>*>(st)->project_rows(G, m);                   \
  }                                                                                                \
  double sb##N##_free_fraction(const void* st) noexcept {                                          \
    return st == nullptr ? 0.0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->free_fraction(); \
  }                                                                                                \
  int sb##N##_rank(const void* st) noexcept {                                                      \
    return st == nullptr ? 0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->rank();    \
  }                                                                                                \
  int sb##N##_tasks(const void* st) noexcept {                                                     \
    return st == nullptr ? 0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->tasks();   \
  }                                                                                                \
  double sb##N##_weighted_tail(const void* st, int B) noexcept {                                   \
    if (st == nullptr || B < 0 || B > N) return 0.0;                                               \
    return static_cast<const girassol::sb::SynapticBudget<N>*>(st)->weighted_tail(B);              \
  }                                                                                                \
  double sb##N##_total_energy(const void* st) noexcept {                                           \
    return st == nullptr ? 0.0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->total_energy(); \
  }                                                                                                \
  int sb##N##_rank_for_tail(const void* st, double frac) noexcept {                                \
    return st == nullptr ? 0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->rank_for_tail(frac); \
  }                                                                                                \
  int sb##N##_eigenvalues(const void* st, double* out, int cap) noexcept {                         \
    if (st == nullptr || out == nullptr || cap <= 0) return 0;                                     \
    const auto& e = static_cast<const girassol::sb::SynapticBudget<N>*>(st)->eig();                \
    const int k = cap < N ? cap : N;                                                               \
    for (int i = 0; i < k; ++i) out[i] = e.lam[i];                                                 \
    return k;                                                                                      \
  }                                                                                                \
  void sb##N##_enable_eps_history(void* st, int on) noexcept {                                     \
    if (st != nullptr) static_cast<girassol::sb::SynapticBudget<N>*>(st)->enable_epsilon_history(on != 0); \
  }                                                                                                \
  int sb##N##_eps_history(const void* st, double* out, int cap) noexcept {                         \
    if (st == nullptr) return 0;                                                                   \
    const auto* s = static_cast<const girassol::sb::SynapticBudget<N>*>(st);                       \
    const int cnt = s->epsilon_count();                                                            \
    if (out != nullptr) {                                                                          \
      const int k = cap < cnt ? cap : cnt;                                                         \
      for (int i = 0; i < k; ++i) out[i] = s->epsilon_history()[i];                                \
    }                                                                                              \
    return cnt;                                                                                    \
  }                                                                                                \
  double sb##N##_current_epsilon(const void* st) noexcept {                                        \
    return st == nullptr ? 0.0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->current_epsilon(); \
  }                                                                                                \
  double sb##N##_forgetting_bound(const void* st, double Finf, double c) noexcept {                \
    return st == nullptr ? 0.0 : static_cast<const girassol::sb::SynapticBudget<N>*>(st)->forgetting_bound(Finf, c); \
  }                                                                                                \
  size_t sb##N##_serialized_bytes() noexcept { return girassol::sb::grsb_bytes<N>(); }             \
  long long sb##N##_serialize(const void* st, unsigned char* out, size_t cap) noexcept {           \
    if (st == nullptr) return -1;                                                                  \
    return girassol::sb::grsb_serialize<N>(*static_cast<const girassol::sb::SynapticBudget<N>*>(st), out, cap); \
  }                                                                                                \
  int sb##N##_deserialize(void* st, const unsigned char* in, size_t len) noexcept {                \
    if (st == nullptr) return static_cast<int>(Status::kBadArg);                                   \
    return static_cast<int>(girassol::sb::grsb_deserialize<N>(*static_cast<girassol::sb::SynapticBudget<N>*>(st), in, len)); \
  }

extern "C" {
using girassol::sb::Status;

GIRASSOL_SB_DEFINE_ABI(64)
GIRASSOL_SB_DEFINE_ABI(128)
GIRASSOL_SB_DEFINE_ABI(256)

int sb_plan_protection(int H, double d, double rho, double kappa, double Finf, double c, double f_min,
                       double* u_out, double* J_out, int* sweeps_out) noexcept {
  if (u_out == nullptr || H < 1 || H > girassol::sb::kMaxHorizon) return static_cast<int>(Status::kBadArg);
  girassol::sb::PlanParams p;
  p.H = H; p.d = d; p.rho = rho; p.kappa = kappa; p.Finf = Finf; p.c = c; p.f_min = f_min;
  girassol::sb::PlanOut o;
  const Status s = girassol::sb::plan_protection(p, o);
  for (int t = 0; t < H; ++t) u_out[t] = o.u[t];
  if (J_out != nullptr) *J_out = o.J;
  if (sweeps_out != nullptr) *sweeps_out = o.sweeps;
  return static_cast<int>(s);
}

int sb_analyze_spectrum(const double* lam, int i0, int i1, double* out5) noexcept {
  if (out5 == nullptr) return static_cast<int>(Status::kBadArg);
  girassol::sb::SpectrumFitOut f;
  const Status s = girassol::sb::analyze_spectrum(lam, i0, i1, f);
  out5[0] = f.tau; out5[1] = f.alpha; out5[2] = f.r2_exp; out5[3] = f.r2_pow;
  out5[4] = static_cast<double>(static_cast<int>(f.regime));
  return static_cast<int>(s);
}

double sb_cumulative_forgetting_bound(double Finf, double c, const double* eps, int count) noexcept {
  return girassol::sb::cumulative_forgetting_bound(Finf, c, eps, count);
}

uint64_t sb_fnv1a64(const unsigned char* p, size_t n) noexcept {
  return p == nullptr ? 14695981039346656037ULL : girassol::sb::fnv1a64(p, n);
}
}
