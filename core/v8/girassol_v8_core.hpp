// ============================================================================
// girassol_v8_core.hpp — Núcleo do Cérebro Cortical Autônomo (Projeto Girassol)
// Versão: v8 (v3.1.0.01) — C++20, header-only
//
// Invariantes de engenharia (Axiomas A1–A4, Parte 1 §2):
//   A2: 0 instruções FMA (compilar com -ffp-contract=off), 0 chamadas a libm,
//       0 alocações dinâmicas (new/malloc) — toda memória vem de Arena64.
//   A3: -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off
//       -fno-fast-math -fno-math-errno
//   A4: paridade bit a bit x86_64 == arm64-v8a (IEEE-754 binário64, ordens de
//       redução fixas, árvore binária por blocos de 64 B em dot_tree).
//
// Layout de memória: TODAS as matrizes são COLUMN-MAJOR (coluna j contígua),
// conforme Trava 1 — o kernel E8 lê a coluna de entrada j sequencialmente e
// salta colunas inativas (s_j == 0) sem tráfego de DRAM (gating pré-sináptico).
// ============================================================================
#ifndef GIRASSOL_V8_CORE_HPP
#define GIRASSOL_V8_CORE_HPP

#include <cstdint>
#include <cstddef>
#include <cstring>

namespace grs8 {

// ---------------------------------------------------------------------------
// 0. Utilitários bitwise (determinísticos por construção)
// ---------------------------------------------------------------------------
inline double bits_to_f64(std::uint64_t u) noexcept {
    double d;
    std::memcpy(&d, &u, 8);
    return d;
}
inline std::uint64_t f64_to_bits(double d) noexcept {
    std::uint64_t u;
    std::memcpy(&u, &d, 8);
    return u;
}

// ---------------------------------------------------------------------------
// 1. FNV-1a 64 (integridade de .grs / arena — Axioma A2)
// ---------------------------------------------------------------------------
constexpr std::uint64_t FNV1A_OFFSET = 0xcbf29ce484222325ull;
constexpr std::uint64_t FNV1A_PRIME  = 0x100000001b3ull;

inline std::uint64_t fnv1a64(const void* data, std::size_t nbytes) noexcept {
    const auto* p = static_cast<const std::uint8_t*>(data);
    std::uint64_t h = FNV1A_OFFSET;
    for (std::size_t i = 0; i < nbytes; ++i) {
        h ^= static_cast<std::uint64_t>(p[i]);
        h *= FNV1A_PRIME;
    }
    return h;
}
inline std::uint64_t fnv1a64_f64(const double* v, std::size_t n) noexcept {
    return fnv1a64(v, n * sizeof(double));
}

// ---------------------------------------------------------------------------
// 2. detmath — primitivas sem libm (Parte 1, §9 TEST-V8-1)
// ---------------------------------------------------------------------------
// Constantes de Cody–Waite (Lema 4.1 revisado):
//   LN2_HI tem 32 bits significativos  => k*LN2_HI exato para |k| <= 2^20.
//   LN2_LO tem 53 bits significativos  => k*LN2_LO arredonda com erro
//   <= ulp(k*ln2_lo)/2 ~ 2^-75 ≈ 2,6e-23 (|k| <= 1023), e o resíduo total
//   |hi+lo-ln2| = 1,16e-26 contribui <= 1,2e-23 — erro total de det_exp
//   permanece < 4,5e-16 (medido: 2,21e-16).
constexpr double LN2_HI  = 0x1.62e42fee00000p-1;
constexpr double LN2_LO  = 0x1.a39ef35793c76p-33;
constexpr double INV_LN2 = 0x1.71547652b82fep+0;   // 1/ln(2)

// Piso determinístico; domínio garantido |x| < 2^52 pelos clampings.
// Guarda de domínio IEEE-754: se |x| >= 2^52, x já é inteiro (e o cast
// static_cast<int64_t>(x) seria comportamento indefinido) — retorna x.
inline double det_floor(double x) noexcept {
    if (!(x > -4503599627370496.0 && x < 4503599627370496.0)) return x;
    const std::int64_t i = static_cast<std::int64_t>(x);  // truncamento
    const double t = static_cast<double>(i);
    return (t > x) ? (t - 1.0) : t;
}

// Arredondamento half-away-from-zero determinístico (para o codec E8).
inline double det_round(double x) noexcept {
    const double f = det_floor(x);
    const double d = x - f;
    if (d > 0.5) return f + 1.0;
    if (d < 0.5) return f;
    return (x >= 0.0) ? (f + 1.0) : f;
}

// escalbn determinístico: x * 2^k, k em faixa garantida por clamping.
inline double det_scalbn(double x, int k) noexcept {
    if (x == 0.0) return x;
    if (k > 1023) { x = det_scalbn(x, k - 1023); k = 1023; }
    if (k < -1022) { x = det_scalbn(x, k + 1022); k = -1022; }
    const std::uint64_t bits =
        static_cast<std::uint64_t>(static_cast<std::int64_t>(k) + 1023) << 52;
    return x * bits_to_f64(bits);
}

// exp determinístico: redução Cody–Waite + Taylor grau 13 (Horner).
// Erro relativo máximo medido: 2,21e-16 (spec < 4,5e-16).
inline double det_exp(double x) noexcept {
    if (x > 709.0)  x = 709.0;    // clamping: exp(709) ~ 8,2e307 (normal)
    if (x < -708.0) x = -708.0;   // exp(-708) ~ 3,3e-308 (normal)
    const double kd = det_floor(x * INV_LN2 + 0.5);
    const int k = static_cast<int>(kd);
    const double r = (x - kd * LN2_HI) - kd * LN2_LO;   // 1ª subtração exata (Sterbenz)
    double c = 1.0;
    for (int n = 13; n >= 1; --n) {
        c = 1.0 + (r * c) / static_cast<double>(n);
    }
    return det_scalbn(c, k);
}

// log determinístico: 2*atanh((m-1)/(m+1)), série até f^23; suporta subnormais.
// m é re-ancorado a [√2/2, √2] (|f| ≤ 0,1716) — truncamento ~2,4e-18.
// Erro relativo máximo medido: 3,33e-16.
inline double det_log(double x) noexcept {
    if (!(x > 0.0)) return bits_to_f64(0x7FF8000000000000ull);  // NaN canônico
    int e_extra = 0;
    if (x < 0x1p-1022) { x *= 0x1p+53; e_extra = -53; }         // subnormal
    const std::uint64_t b = f64_to_bits(x);
    int e = static_cast<int>((b >> 52) & 0x7FFull) - 1023 + e_extra;
    double m = bits_to_f64((b & 0x800FFFFFFFFFFFFFull) | 0x3FF0000000000000ull);
    if (m > 0x1.6a09e667f3bcdp+0) { m *= 0.5; e += 1; }         // m ∈ [√2/2, √2]
    const double f  = (m - 1.0) / (m + 1.0);   // |f| <= 0,1716
    const double f2 = f * f;
    double s  = 0.0;
    double fk = f;
    for (int n = 0; n < 12; ++n) {             // f^1 .. f^23 (termos ímpares)
        s += fk / static_cast<double>(2 * n + 1);
        fk *= f2;
    }
    const double se = 2.0 * s;
    const double de = static_cast<double>(e);
    return (de * LN2_HI + se) + de * LN2_LO;   // ordem fixa (A4)
}

// sqrt determinístico: bit-hack de sqrt (divisão de expoente) + 8 Newtons.
// Erro relativo máximo medido: 2,21e-16 em varredura 1e±300 (fixpoint em f64).
inline double det_sqrt(double a) noexcept {
    if (!(a > 0.0)) return 0.0;
    std::uint64_t i = f64_to_bits(a);
    i = (i >> 1) + 0x1FF7A3BEA91D9B1Bull;
    double x = bits_to_f64(i);
    for (int n = 0; n < 8; ++n) x = 0.5 * (x + a / x);
    return x;
}

// tanh determinístico via det_exp (usado nos canais neuromoduladores).
inline double det_tanh(double x) noexcept {
    const bool neg = (x < 0.0);
    const double ax = neg ? -x : x;
    if (ax > 20.0) return neg ? -1.0 : 1.0;
    const double e2 = det_exp(-2.0 * ax);
    const double t = (1.0 - e2) / (1.0 + e2);
    return neg ? -t : t;
}

// log_sum_exp deslocado pelo máximo (v5). Ordem de soma sequencial fixa.
inline double det_lse(const double* v, std::size_t n) noexcept {
    double m = v[0];
    for (std::size_t i = 1; i < n; ++i) m = (v[i] > m) ? v[i] : m;
    double s = 0.0;
    for (std::size_t i = 0; i < n; ++i) s += det_exp(v[i] - m);
    return m + det_log(s);
}

// ---------------------------------------------------------------------------
// 3. Arena64 — bump allocator sobre buffer estático (0 heap no hot path)
// ---------------------------------------------------------------------------
struct Arena64 {
    std::uint8_t* base = nullptr;
    std::size_t   cap  = 0;
    std::size_t   off  = 0;

    void init(void* buf, std::size_t bytes) noexcept {
        base = static_cast<std::uint8_t*>(buf);
        cap  = bytes;
        off  = 0;
    }
    // Aloca 'bytes' alinhados a 64 B; retorna nullptr (sem exceção) se esgotar.
    void* alloc(std::size_t bytes) noexcept {
        const std::size_t aligned = (off + 63u) & ~static_cast<std::size_t>(63u);
        if (aligned + bytes > cap) return nullptr;
        off = aligned + bytes;
        return base + aligned;
    }
    template <typename T>
    T* alloc_array(std::size_t n) noexcept {
        return static_cast<T*>(alloc(n * sizeof(T)));
    }
    std::size_t used() const noexcept { return off; }
    std::uint64_t hash() const noexcept { return fnv1a64(base, off); }
};

// ---------------------------------------------------------------------------
// 4. Árvore de redução única (A4): dot em blocos de 8 doubles (64 B),
//    combinação binária. TODAS as reduções do núcleo passam por aqui —
//    inclusive os dois ramos de NS5 (Trava 4: tall e wide usam a mesma
//    árvore => NS(A^T) == NS(A)^T bit a bit).
// ---------------------------------------------------------------------------
inline double reduce_tree(const double* vals, std::size_t n) noexcept {
    double blk[128];                       // suporta n <= 1024 por chamada
    std::size_t nb = 0;
    std::size_t i = 0;
    while (i < n) {
        const std::size_t len = (n - i >= 8) ? 8 : (n - i);
        double s = 0.0;
        for (std::size_t k = 0; k < len; ++k) s += vals[i + k];
        blk[nb++] = s;
        i += len;
    }
    if (nb == 0) return 0.0;
    while (nb > 1) {
        std::size_t w = 0;
        for (std::size_t k = 0; k + 1 < nb; k += 2) blk[w++] = blk[k] + blk[k + 1];
        if (nb & 1u) blk[w++] = blk[nb - 1];
        nb = w;
    }
    return blk[0];
}

// Produto interno com strides (mesma ordem de redução em tall/wide).
// n <= 1024 por chamada (dimensões do núcleo respeitam esse teto).
inline double dot_tree(const double* a, std::size_t sa,
                       const double* b, std::size_t sb,
                       std::size_t n) noexcept {
    double prod[1024];
    for (std::size_t k = 0; k < n; ++k) prod[k] = a[k * sa] * b[k * sb];
    return reduce_tree(prod, n);
}

// Norma Frobenius determinística (col-major m×n).
inline double frob_norm(const double* A, std::size_t mn) noexcept {
    double sq[1024];
    double total = 0.0;
    std::size_t i = 0;
    while (i < mn) {
        const std::size_t len = (mn - i >= 1024) ? 1024 : (mn - i);
        for (std::size_t k = 0; k < len; ++k) sq[k] = A[i + k] * A[i + k];
        total += reduce_tree(sq, len);   // soma de blocos em ordem fixa
        i += len;
    }
    return det_sqrt(total);
}

// ---------------------------------------------------------------------------
// 5. NS5 — Newton–Schulz polar (v5; Parte 1, Lema 4.2)
//    Matrizes COLUMN-MAJOR. Pré-escala 1/sqrt(tr(G0)) omitida em warm-start
//    quando ||I - G0||_F < 1. Os ramos tall (m > n) e wide (m < n) usam
//    EXATAMENTE a mesma árvore de redução (dot_tree) => paridade de
//    transposição bit a bit (Trava 4).
// ---------------------------------------------------------------------------
struct Ns5Stats {
    double frob_ImG0 = 0.0;   // ||I - G0||_F medido na entrada
    double frob_ImG5 = 0.0;   // ||I - G5||_F após 5 iterações
    bool   prescaled = false;
};

namespace detail {

// G = XᵀX (col-major m×n; G n×n simétrica bit a bit).
inline void gram_cols(const double* X, std::size_t m, std::size_t n,
                      double* G) noexcept {
    for (std::size_t j = 0; j < n; ++j) {
        for (std::size_t i = 0; i < n; ++i) {
            G[i + j * n] = dot_tree(X + i * m, 1, X + j * m, 1, m);
        }
    }
}

// G = XXᵀ (col-major m×n; G m×m). Linha i de X = elementos X[i + k*m].
// G_ij = <row_i, row_j> — mesma árvore de redução de gram_cols.
inline void gram_rows(const double* X, std::size_t m, std::size_t n,
                      double* G) noexcept {
    for (std::size_t j = 0; j < m; ++j) {
        for (std::size_t i = 0; i < m; ++i) {
            G[i + j * m] = dot_tree(X + i, m, X + j, m, n);
        }
    }
}

inline double frob_ImG(const double* G, std::size_t n) noexcept {
    // Soma de quadrados em blocos de <= 1024 (mesma disciplina de frob_norm)
    double buf[1024];
    double total = 0.0;
    std::size_t cnt = 0;
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < n; ++i) {
            const double d = G[i + j * n] - ((i == j) ? 1.0 : 0.0);
            buf[cnt++] = d * d;
            if (cnt == 1024) { total += reduce_tree(buf, cnt); cnt = 0; }
        }
    if (cnt > 0) total += reduce_tree(buf, cnt);
    return det_sqrt(total);
}

// X <- 0.5 * X * (3I - G)   (ramo tall; X m×n col-major, G n×n)
// X'_ij = 0.5 * sum_k X_ik * M_kj — redução via dot_tree com strides.
inline void ns_step_tall(double* X, std::size_t m, std::size_t n,
                         const double* G, double* Xout, double* M) noexcept {
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < n; ++i)
            M[i + j * n] = ((i == j) ? 3.0 : 0.0) - G[i + j * n];
    // X'_ij = 0.5 * <row_i(X), col_j(M)>; row_i(X): stride m; col_j(M): stride 1
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < m; ++i)
            Xout[i + j * m] = 0.5 * dot_tree(X + i, m, M + j * n, 1, n);
    std::memcpy(X, Xout, m * n * sizeof(double));
}

// X <- 0.5 * (3I - G) * X   (ramo wide; X m×n col-major, G m×m)
// X'_ij = 0.5 * sum_k M_ik * X_kj — MESMA árvore de redução do ramo tall:
// pares (M_ik, X_kj) na ordem k = 0..m-1, idêntica à de ns_step_tall com
// operandos transpostos => NS(Aᵀ) == NS(A)ᵀ bit a bit (prova: Parte 1, §5/
// Trava 4 — gram_rows(Aᵀ) == gram_cols(A) bitwise; M simétrica bitwise).
inline void ns_step_wide(double* X, std::size_t m, std::size_t n,
                         const double* G, double* Xout, double* M) noexcept {
    for (std::size_t j = 0; j < m; ++j)
        for (std::size_t i = 0; i < m; ++i)
            M[i + j * m] = ((i == j) ? 3.0 : 0.0) - G[i + j * m];
    // X'_ij = 0.5 * <row_i(M), col_j(X)>; row_i(M): stride m; col_j(X): stride 1
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < m; ++i)
            Xout[i + j * m] = 0.5 * dot_tree(M + i, m, X + j * m, 1, m);
    std::memcpy(X, Xout, m * n * sizeof(double));
}

} // namespace detail

// NS genérico: X m×n col-major, 'iters' iterações. tall = (m > n).
// NS5 (v5) = ns_iters(...,5) em warm-start (‖I−G0‖_F < 1, sem pré-escala);
// cold-start exige ~12 iterações (Lema 4.2: contração linear 1,5× por iteração
// nos valores singulares pequenos antes do regime quadrático).
// Scratch: G(min(m,n)²), Xout(m*n), M(min(m,n)²) — fatiados de Arena64.
inline Ns5Stats ns_iters(double* X, std::size_t m, std::size_t n,
                         double* G, double* Xout, double* M,
                         int iters) noexcept {
    Ns5Stats st{};
    const bool tall = (m > n);
    const std::size_t g = tall ? n : m;
    if (tall) detail::gram_cols(X, m, n, G);
    else      detail::gram_rows(X, m, n, G);
    st.frob_ImG0 = detail::frob_ImG(G, g);
    if (st.frob_ImG0 >= 1.0) {                 // cold-start: pré-escala 1/sqrt(tr G0)
        double tr = 0.0;
        for (std::size_t i = 0; i < g; ++i) tr += G[i + i * g];
        const double s = 1.0 / det_sqrt(tr);
        for (std::size_t i = 0; i < m * n; ++i) X[i] *= s;
        st.prescaled = true;
    }
    for (int it = 0; it < iters; ++it) {
        if (tall) {
            detail::gram_cols(X, m, n, G);
            detail::ns_step_tall(X, m, n, G, Xout, M);
        } else {
            detail::gram_rows(X, m, n, G);
            detail::ns_step_wide(X, m, n, G, Xout, M);
        }
    }
    if (tall) detail::gram_cols(X, m, n, G);
    else      detail::gram_rows(X, m, n, G);
    st.frob_ImG5 = detail::frob_ImG(G, g);
    return st;
}

inline Ns5Stats ns5(double* X, std::size_t m, std::size_t n,
                    double* G, double* Xout, double* M) noexcept {
    return ns_iters(X, m, n, G, Xout, M, 5);
}

// ---------------------------------------------------------------------------
// 6. Simplex entrópico (v5; Parte 1, Lema 4.3): mirror ascent proximal,
//    2000 iterações fixas (determinismo), piso anti-colapso verificado.
// ---------------------------------------------------------------------------
struct EntropicResult {
    double min_pi = 0.0;
    double sum_pi = 0.0;
    double floor_bound = 0.0;   // e^(-Δ/τ)/n
    bool   floor_ok = false;
};

// s: scores (n), K: n×n col-major simétrica, saída: pi (n).
// Scratch: g(n). Iterações fixas => tempo constante (A2).
inline EntropicResult solve_entropic_simplex(const double* s, const double* K,
                                             double beta, double tau,
                                             double* pi, double* g,
                                             std::size_t n) noexcept {
    double kappa = 0.0;
    for (std::size_t i = 0; i < n * n; ++i) {
        const double a = (K[i] < 0.0) ? -K[i] : K[i];
        if (a > kappa) kappa = a;
    }
    if (kappa == 0.0) kappa = 1.0;
    const double eta = 1.0 / kappa;
    const double ln_n = det_log(static_cast<double>(n));
    double lnpi[1024];
    for (std::size_t i = 0; i < n; ++i) { lnpi[i] = -ln_n; pi[i] = 1.0 / static_cast<double>(n); }
    for (int it = 0; it < 2000; ++it) {
        for (std::size_t i = 0; i < n; ++i)
            g[i] = beta * s[i] - dot_tree(K + i, n, pi, 1, n);   // (Kπ)_i, row i
        for (std::size_t i = 0; i < n; ++i)
            lnpi[i] = (lnpi[i] + eta * g[i]) / (1.0 + eta * tau);
        const double lse = det_lse(lnpi, n);
        for (std::size_t i = 0; i < n; ++i) pi[i] = det_exp(lnpi[i] - lse);
        for (std::size_t i = 0; i < n; ++i) lnpi[i] -= lse;
    }
    EntropicResult r{};
    r.min_pi = pi[0];
    for (std::size_t i = 1; i < n; ++i) if (pi[i] < r.min_pi) r.min_pi = pi[i];
    r.sum_pi = reduce_tree(pi, n);
    double smin = s[0], smax = s[0];
    for (std::size_t i = 1; i < n; ++i) {
        if (s[i] < smin) smin = s[i];
        if (s[i] > smax) smax = s[i];
    }
    double kspan_max = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        double lo = K[i * n], hi = K[i * n];
        for (std::size_t j = 1; j < n; ++j) {
            const double v = K[j + i * n];
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        if (hi - lo > kspan_max) kspan_max = hi - lo;
    }
    const double delta = ((beta < 0.0) ? -beta : beta) * (smax - smin) + kspan_max;
    r.floor_bound = det_exp(-delta / tau) / static_cast<double>(n);
    r.floor_ok = (r.min_pi >= r.floor_bound);
    return r;
}

// ---------------------------------------------------------------------------
// 7. FP16 determinístico (escala por bloco GRV6 + outliers) — lógica inteira
// ---------------------------------------------------------------------------
inline std::uint16_t f64_to_f16(double x) noexcept {
    const std::uint64_t b = f64_to_bits(x);
    const std::uint64_t sign = (b >> 63) & 1ull;
    std::int64_t e = static_cast<std::int64_t>((b >> 52) & 0x7FFull) - 1023;
    std::uint64_t mant = b & 0xFFFFFFFFFFFFFull;
    std::uint16_t hsign = static_cast<std::uint16_t>(sign << 15);
    if (e == 1024) {  // inf/NaN -> saturados (nunca ocorrem: escalas finitas)
        return static_cast<std::uint16_t>(hsign | 0x7C00u);
    }
    if (e > 15) return static_cast<std::uint16_t>(hsign | 0x7C00u);   // overflow -> inf
    if (e < -24) return hsign;                                        // underflow -> 0
    if (e >= -14) {  // normal: mantissa 52 -> 10 bits, round-to-nearest-even
        std::uint64_t m10 = mant >> 42;
        const std::uint64_t rem = mant & 0x3FFFFFFFFFFull;
        const std::uint64_t half = 1ull << 41;
        if (rem > half || (rem == half && (m10 & 1ull))) ++m10;
        if (m10 >> 10) { ++e; m10 = 0; if (e > 15) return static_cast<std::uint16_t>(hsign | 0x7C00u); }
        return static_cast<std::uint16_t>(hsign |
               (static_cast<std::uint16_t>(e + 15) << 10) |
               static_cast<std::uint16_t>(m10));
    }
    // subnormal fp16: valor = mant/2^52 * 2^(e), e em [-24,-15]
    const std::uint64_t full = (1ull << 52) | mant;   // 53 bits
    const int shift = static_cast<int>(-14 - e) + 42; // alinha ao quantum 2^-24
    std::uint64_t m = full >> shift;
    const std::uint64_t rem = full & ((1ull << shift) - 1ull);
    const std::uint64_t half = 1ull << (shift - 1);
    if (rem > half || (rem == half && (m & 1ull))) ++m;
    return static_cast<std::uint16_t>(hsign | static_cast<std::uint16_t>(m));
}

inline double f16_to_f64(std::uint16_t h) noexcept {
    const std::uint64_t sign = static_cast<std::uint64_t>(h >> 15) << 63;
    const std::uint32_t e = (h >> 10) & 0x1Fu;
    const std::uint32_t m = h & 0x3FFu;
    if (e == 0) {  // subnormal fp16 (ou zero): valor = m * 2^-24, exato em f64
        double v = static_cast<double>(m) * 0x1p-24;
        std::uint64_t b = f64_to_bits(v) | sign;
        return bits_to_f64(b);
    }
    if (e == 31) return bits_to_f64(sign | 0x7FF0000000000000ull);    // inf
    const std::uint64_t e64 = static_cast<std::uint64_t>(static_cast<int>(e) - 15 + 1023);
    return bits_to_f64(sign | (e64 << 52) | (static_cast<std::uint64_t>(m) << 42));
}

// ---------------------------------------------------------------------------
// 8. Codec GRV6 (E8 sem LUT, ~40 FLOPs/bloco; Parte 1, Lema 4.4)
//    Bloco = 8 pesos. Payload: 25 bits = tipo(1) | 8 índices base-8 (24).
//      tipo 0 (D8):        c_i inteiros  em {-4..3}, soma par
//      tipo 1 (D8 + 1/2):  c_i ∈ {-3.5..2.5} (2c_i ímpar), soma par automática
//    Escala por bloco: fp16. Layout SoA COLUMN-MAJOR (Trava 1):
//      bloco b da coluna j em idx[j * (rows/8) + b] — leitura sequencial.
// ---------------------------------------------------------------------------
struct E8Codec {
    // Decodificador de Conway–Sloane restrito ao codebook representável.
    // Retorna o ponto E8 mais próximo de y[8] (tipo 0/1), sem LUT.
    static void nearest(const double* y, double* c, int& tipo) noexcept {
        // --- candidato D8 (inteiros, soma par) ---
        double z[8];
        double err[8];
        std::int64_t isum = 0;
        for (int i = 0; i < 8; ++i) {
            z[i] = det_round(y[i]);
            if (z[i] > 3.0)  z[i] = 3.0;
            if (z[i] < -4.0) z[i] = -4.0;
            err[i] = (y[i] - z[i] >= 0.0) ? (y[i] - z[i]) : -(y[i] - z[i]);
            isum += static_cast<std::int64_t>(z[i]);
        }
        if (isum & 1) {  // corrige paridade: flipa o pior arredondamento
            int w = 0;
            for (int i = 1; i < 8; ++i) if (err[i] > err[w]) w = i;
            z[w] += (y[w] >= z[w]) ? 1.0 : -1.0;
            if (z[w] > 3.0)  z[w] = 3.0;
            if (z[w] < -4.0) z[w] = -4.0;
        }
        // --- candidato D8 + 1/2 (meios-inteiros) ---
        double z2[8];
        std::int64_t isum2 = 0;
        for (int i = 0; i < 8; ++i) {
            const double y2 = y[i] - 0.5;
            double r = det_round(y2);
            if (r > 2.0)  r = 2.0;
            if (r < -4.0) r = -4.0;
            z2[i] = r + 0.5;                    // ∈ {-3.5..2.5}
            isum2 += static_cast<std::int64_t>(r);
        }
        if (isum2 & 1) {  // soma de r par <=> ponto em D8 + 1/2 válido
            int w = 0;
            for (int i = 1; i < 8; ++i) {
                const double d = (y[i] - z2[i] >= 0.0) ? (y[i] - z2[i]) : -(y[i] - z2[i]);
                const double dw = (y[w] - z2[w] >= 0.0) ? (y[w] - z2[w]) : -(y[w] - z2[w]);
                if (d > dw) w = i;
            }
            z2[w] += (y[w] >= z2[w]) ? 1.0 : -1.0;
            if (z2[w] > 2.5)  z2[w] = 2.5;
            if (z2[w] < -3.5) z2[w] = -3.5;
        }
        // --- escolhe pelo erro quadrático (ordem fixa) ---
        double e1 = 0.0, e2 = 0.0;
        for (int i = 0; i < 8; ++i) {
            const double d1 = y[i] - z[i];
            const double d2 = y[i] - z2[i];
            e1 += d1 * d1;
            e2 += d2 * d2;
        }
        if (e1 <= e2) { for (int i = 0; i < 8; ++i) c[i] = z[i];  tipo = 0; }
        else          { for (int i = 0; i < 8; ++i) c[i] = z2[i]; tipo = 1; }
    }

    // Codifica 8 pesos -> (payload 25 bits, escala fp16).
    static void encode_block(const double* w, std::uint32_t& payload,
                             std::uint16_t& scale_f16) noexcept {
        double amax = 0.0;
        for (int i = 0; i < 8; ++i) {
            const double a = (w[i] < 0.0) ? -w[i] : w[i];
            if (a > amax) amax = a;
        }
        const double s = (amax > 0.0) ? (amax / 3.5) : 1.0;
        double y[8], c[8];
        for (int i = 0; i < 8; ++i) y[i] = w[i] / s;
        int tipo = 0;
        nearest(y, c, tipo);
        std::uint32_t p = static_cast<std::uint32_t>(tipo) << 24;
        for (int i = 0; i < 8; ++i) {
            std::uint32_t idx;
            if (tipo == 0) idx = static_cast<std::uint32_t>(static_cast<int>(c[i]) + 4);
            else           idx = static_cast<std::uint32_t>(static_cast<int>(c[i] + 3.5));
            p |= (idx & 7u) << (3 * i);
        }
        payload = p;
        scale_f16 = f64_to_f16(s);
    }

    // Decodifica (payload, escala) -> 8 pesos. ~40 FLOPs inteiros, 0 LUT.
    static void decode_block(std::uint32_t payload, std::uint16_t scale_f16,
                             double* out8) noexcept {
        const double s = f16_to_f64(scale_f16);
        const std::uint32_t tipo = payload >> 24;
        if (tipo == 0) {
            for (int i = 0; i < 8; ++i) {
                const int idx = static_cast<int>((payload >> (3 * i)) & 7u);
                out8[i] = s * static_cast<double>(idx - 4);
            }
        } else {
            for (int i = 0; i < 8; ++i) {
                const int idx = static_cast<int>((payload >> (3 * i)) & 7u);
                out8[i] = s * (static_cast<double>(idx) - 3.5);
            }
        }
    }
};

// Outlier esparso row-major (v6): {row, col, val_f16} = 6 bytes.
struct Grv6Outlier {
    std::uint16_t row;
    std::uint16_t col;
    std::uint16_t val_f16;
};
static_assert(sizeof(Grv6Outlier) == 6, "Grv6Outlier deve ocupar 6 bytes");

// Matriz GRV6 COLUMN-MAJOR (Trava 1): rows % 8 == 0.
// Bloco (j, b) = coluna j, linhas [8b, 8b+8): idx[j * bpc + b], bpc = rows/8.
struct Grv6Mat {
    std::size_t rows = 0, cols = 0;
    const std::uint32_t* idx = nullptr;     // cols * bpc
    const std::uint16_t* scale = nullptr;   // cols * bpc
    const Grv6Outlier* outliers = nullptr;
    std::size_t n_outliers = 0;
    std::size_t bpc() const noexcept { return rows / 8; }
};

struct KernelStats {
    std::uint64_t bytes_dram = 0;   // contabilização exata p/ Roofline (C2)
    std::uint64_t flops = 0;
    std::uint64_t cols_active = 0;
    std::uint64_t cols_skipped = 0;
};

// Kernel fundido DecodeE8+MatVec com gating pré-sináptico (Trava 1):
// coluna j com s[j] == 0 salta SEM leitura de DRAM. acc[i] += Σ_j W_ij s_j,
// ordem de acumulação fixa: j crescente, depois bloco, depois k.
// bytes/bloco ativo: 4 (idx) + 2 (scale) = 6 B; ~40 FLOPs decode + 16 MAC.
inline void matvec_e8_gated(const Grv6Mat& W, const double* s, double* acc,
                            KernelStats& st) noexcept {
    const std::size_t bpc = W.bpc();
    double p[8];
    for (std::size_t j = 0; j < W.cols; ++j) {
        if (s[j] == 0.0) { ++st.cols_skipped; continue; }   // gating: 0 DRAM
        ++st.cols_active;
        const double sj = s[j];
        const std::uint32_t* cidx = W.idx + j * bpc;
        const std::uint16_t* csc  = W.scale + j * bpc;
        for (std::size_t b = 0; b < bpc; ++b) {
            E8Codec::decode_block(cidx[b], csc[b], p);
            const std::size_t base = b * 8;
            for (std::size_t k = 0; k < 8; ++k) {
                acc[base + k] += p[k] * sj;   // mul e add separados (sem FMA)
            }
            st.bytes_dram += 6;
            st.flops += 40 + 16;
        }
    }
    for (std::size_t o = 0; o < W.n_outliers; ++o) {
        const Grv6Outlier& t = W.outliers[o];
        if (s[t.col] == 0.0) continue;
        acc[t.row] += f16_to_f64(t.val_f16) * s[t.col];
        st.bytes_dram += 6;
        st.flops += 2;
    }
}

// ---------------------------------------------------------------------------
// 9. ED-SDPC — Arquitetura Cortical Preditiva Esparsa Orientada a Eventos
//    (Parte 1, Defs. 3.1–3.3, Teorema T1). Matrizes W^b, W^a COLUMN-MAJOR.
//    Sinapses monitoradas: linha de índice MON_ROW (traço de elegibilidade
//    completo n×n em arena para auditoria; em produção, subamostradas).
// ---------------------------------------------------------------------------
struct NeuroMod {
    double DA = 0.0;    // dopamina: erro de predição / surpresa
    double NA = 0.0;    // noradrenalina: volatilidade / reset
    double ACh = 0.0;   // acetilcolina: precisão sensorial vs. memória
    double HT5 = 0.0;   // serotonina: horizonte de desconto
    double M() const noexcept {   // vetor efetivo (pesos fixos, Def. 3.3)
        return 1.0 * DA + 0.5 * NA + 0.25 * ACh + 0.25 * HT5;
    }
};

struct CortexParams {
    double sigma_b = 0.5, sigma_a = 0.5;   // decaimento compartimental
    double beta_r = 0.02;                  // EMA da taxa de disparo
    double beta_theta = 0.1;               // passo homeostático
    double rho_target = 0.05;              // alvo de esparsidade (5%)
    double lambda_e = 0.95;                // decaimento do traço
    double eta_pl = 1e-4;                  // taxa de plasticidade (< beta_theta: T1.i)
    double w_max = 2.0;                    // compacto de pesos (T1.ii)
    double na_reset = 0.9;                 // limiar de reset de crenças (NA)
    double theta_min = 0.05;               // piso do limiar homeostático
    double gamma_norm = 1.0;               // escalonamento sináptico (Turrigiano)
    double burst_trig = 0.15;              // guarda F1: dispara se rate > 3ρ*
    double burst_mult = 4.0;               // guarda F1: multiplicador do passo θ
};

struct Cortex {
    std::size_t n = 0;
    // arena slices (column-major n×n para Wb/Wa; vetores n)
    double* Wb = nullptr;
    double* Wa = nullptr;
    double* b = nullptr;
    double* a = nullptr;
    double* u = nullptr;
    double* s = nullptr;
    double* sprev = nullptr;
    double* theta = nullptr;
    double* rate = nullptr;
    double* etr = nullptr;      // traço de elegibilidade n×n (auditoria)
    double* drive = nullptr;    // scratch n
    NeuroMod nm{};
    CortexParams P{};

    bool init(Arena64& ar, std::size_t n_) noexcept {
        n = n_;
        Wb = ar.alloc_array<double>(n * n);
        Wa = ar.alloc_array<double>(n * n);
        b = ar.alloc_array<double>(n);
        a = ar.alloc_array<double>(n);
        u = ar.alloc_array<double>(n);
        s = ar.alloc_array<double>(n);
        sprev = ar.alloc_array<double>(n);
        theta = ar.alloc_array<double>(n);
        rate = ar.alloc_array<double>(n);
        etr = ar.alloc_array<double>(n * n);
        drive = ar.alloc_array<double>(n);
        if (!Wb || !Wa || !b || !a || !u || !s || !sprev || !theta || !rate ||
            !etr || !drive) return false;
        for (std::size_t i = 0; i < n * n; ++i) { Wb[i] = 0.0; Wa[i] = 0.0; etr[i] = 0.0; }
        for (std::size_t i = 0; i < n; ++i) {
            b[i] = a[i] = u[i] = s[i] = sprev[i] = drive[i] = 0.0;
            theta[i] = 0.3;
            rate[i] = 0.05;   // init em ρ*: evita overshoot de θ (transiente
        }                     // de ~600 passos medido na validação nativa)
        return true;
    }

    // Um passo orientado a eventos. x_in: entrada sensorial esparsa (n),
    // p: predição top-down (n). Retorna taxa de disparo do passo.
    double step(const double* x_in, const double* p) noexcept {
        const std::size_t nn = n;
        // (1) correntes basais: drive = W^b · sprev (gated: coluna morta salta)
        for (std::size_t i = 0; i < nn; ++i) drive[i] = 0.0;
        for (std::size_t j = 0; j < nn; ++j) {
            const double sj = sprev[j];
            if (sj == 0.0) continue;
            const double* col = Wb + j * nn;
            for (std::size_t i = 0; i < nn; ++i) drive[i] += col[i] * sj;
        }
        for (std::size_t i = 0; i < nn; ++i)
            b[i] = P.sigma_b * b[i] + drive[i] + x_in[i];
        // (2) correntes apicais: W^a · p (gated)
        for (std::size_t i = 0; i < nn; ++i) drive[i] = 0.0;
        for (std::size_t j = 0; j < nn; ++j) {
            const double pj = p[j];
            if (pj == 0.0) continue;
            const double* col = Wa + j * nn;
            for (std::size_t i = 0; i < nn; ++i) drive[i] += col[i] * pj;
        }
        for (std::size_t i = 0; i < nn; ++i)
            a[i] = P.sigma_a * a[i] + drive[i];
        // (3) integração não-linear + disparo + homeostase (Defs. 3.1–3.2,
        //     guarda de rajada F1 da matriz de falseabilidade)
        double act = 0.0;
        for (std::size_t i = 0; i < nn; ++i) {
            u[i] = b[i] * (1.0 + nm.ACh * det_tanh(a[i]));
            s[i] = (u[i] > theta[i]) ? 1.0 : 0.0;
            act += s[i];
            rate[i] = (1.0 - P.beta_r) * rate[i] + P.beta_r * s[i];
            const double mult = (rate[i] > P.burst_trig) ? P.burst_mult : 1.0;
            theta[i] += P.beta_theta * mult * (rate[i] - P.rho_target);
            if (theta[i] < P.theta_min) theta[i] = P.theta_min;
        }
        act /= static_cast<double>(nn);
        // (4) neuromoduladores (dinâmica EMA determinística)
        double pred_act = 0.0, x_act = 0.0;
        for (std::size_t i = 0; i < nn; ++i) {
            if (p[i] != 0.0) pred_act += 1.0;
            if (x_in[i] != 0.0) x_act += 1.0;
        }
        pred_act /= static_cast<double>(nn);
        x_act /= static_cast<double>(nn);
        const double err = (x_act - pred_act >= 0.0) ? (x_act - pred_act)
                                                     : -(x_act - pred_act);
        nm.DA = 0.9 * nm.DA + 0.1 * det_tanh(10.0 * err);
        nm.NA = 0.95 * nm.NA + 0.05 * det_tanh(20.0 * err * err);
        nm.ACh = 0.9 * nm.ACh + 0.1 * det_tanh(5.0 * (x_act - nm.HT5));
        nm.HT5 = 0.98 * nm.HT5 + 0.02 * det_tanh(nm.DA);
        if (nm.NA > P.na_reset) {   // reset de crenças (volatilidade extrema)
            for (std::size_t i = 0; i < nn; ++i) { b[i] = 0.0; a[i] = 0.0; }
            nm.NA = 0.0;
        }
        // (5) plasticidade de 3 fatores (Def. 3.3): só sinapses ativas
        const double M = nm.M();
        const double lam = P.lambda_e;
        for (std::size_t j = 0; j < nn; ++j) {
            const double sj = sprev[j];
            if (sj == 0.0) continue;
            for (std::size_t i = 0; i < nn; ++i) {
                if (s[i] == 0.0) continue;
                double& e = etr[i + j * nn];
                e = lam * e + sj * s[i];
                double& w = Wb[i + j * nn];
                double dw = P.eta_pl * M * e;
                if (M < 0.0) dw -= P.eta_pl * 0.1 * w;   // decaimento anti-espúrio
                w += dw;
                if (w > P.w_max) w = P.w_max;
                if (w < -P.w_max) w = -P.w_max;
            }
        }
        // (6) escalonamento sináptico homeostático (Turrigiano; necessário
        // contra explosão Hebbiana recorrente medida na validação nativa):
        // coluna j de W^b com ‖col‖₂ > γ é reescalada para γ — limita o
        // drive por Cauchy–Schwarz e mantém T1(i) alcançável.
        for (std::size_t j = 0; j < nn; ++j) {
            double* col = Wb + j * nn;
            const double nrm = det_sqrt(dot_tree(col, 1, col, 1, nn));
            if (nrm > P.gamma_norm) {
                const double f = P.gamma_norm / nrm;
                for (std::size_t i = 0; i < nn; ++i) col[i] *= f;
            }
        }
        // (7) avanço temporal
        double* tmp = sprev; sprev = s; s = tmp;
        return act;
    }
};

// ---------------------------------------------------------------------------
// 10. H-SSM-Sparse (Parte 1, Def. 3.5, Teorema T2): A diagonal (|α| < 1),
//     janela local w, top-K. O(L·d) por sequência.
// ---------------------------------------------------------------------------
struct Hssm {
    std::size_t d = 0;
    std::size_t w = 0;      // janela local
    std::size_t K = 0;      // top-K
    double alpha = 0.9;     // polo diagonal uniforme (config. canônica)
    // arena slices
    double* h = nullptr;          // estado d
    double* Bm = nullptr;         // d×d col-major
    double* Cm = nullptr;         // d×d col-major
    double* win = nullptr;        // janela w×d (linha t mod w)
    double* scores = nullptr;     // scratch w

    bool init(Arena64& ar, std::size_t d_, std::size_t w_, std::size_t K_) noexcept {
        d = d_; w = w_; K = K_;
        h = ar.alloc_array<double>(d);
        Bm = ar.alloc_array<double>(d * d);
        Cm = ar.alloc_array<double>(d * d);
        win = ar.alloc_array<double>(w * d);
        scores = ar.alloc_array<double>(w);
        if (!h || !Bm || !Cm || !win || !scores) return false;
        for (std::size_t i = 0; i < d; ++i) h[i] = 0.0;
        for (std::size_t i = 0; i < w * d; ++i) win[i] = 0.0;
        return true;
    }

    // Um token: h <- α·h + B·x; y = C·h + atenção top-K na janela.
    void step(const double* x, double* y) noexcept {
        // h <- α h + B x (B col-major; redução por dot_tree)
        double hn[1024];
        for (std::size_t i = 0; i < d; ++i)
            hn[i] = alpha * h[i] + dot_tree(Bm + i, d, x, 1, d);
        for (std::size_t i = 0; i < d; ++i) h[i] = hn[i];
        // empurra x na janela (slot circular determinístico: slot 0 sempre)
        for (std::size_t t = w - 1; t >= 1; --t)
            for (std::size_t i = 0; i < d; ++i) win[t * d + i] = win[(t - 1) * d + i];
        for (std::size_t i = 0; i < d; ++i) win[i] = x[i];
        // scores = h · win_t
        for (std::size_t t = 0; t < w; ++t)
            scores[t] = dot_tree(h, 1, win + t * d, 1, d);
        // top-K determinístico: K seleções por máximo (ordem fixa)
        double attn[1024];
        for (std::size_t i = 0; i < d; ++i) attn[i] = 0.0;
        for (std::size_t kk = 0; kk < K; ++kk) {
            std::size_t best = 0;
            for (std::size_t t = 1; t < w; ++t)
                if (scores[t] > scores[best]) best = t;
            for (std::size_t i = 0; i < d; ++i) attn[i] += win[best * d + i];
            scores[best] = -1.0e300;   // exclui da próxima seleção
        }
        // y = C h + attn (pesos uniformes top-K — parte da especificação)
        for (std::size_t i = 0; i < d; ++i)
            y[i] = dot_tree(Cm + i, d, h, 1, d) + attn[i];
    }
};

// ---------------------------------------------------------------------------
// 11. OSN-ESC — Neurogênese Ortogonal de Subespaços (Parte 1, Def. 3.10,
//     Teorema T4) com guardas de runtime (Trava 4):
//       G1: r_old + r_new <= d   (senão: rank saturado -> consolidação/nova camada)
//       G2: ||Z||_F >= 1e-6      (senão: subespaço degenerado -> consolidação)
// ---------------------------------------------------------------------------
enum class NeurogenesisStatus : int {
    Ok = 0,
    RankSaturated = 1,     // G1 violado: abortar p/ consolidação (Jacobi)
    DegenerateSpace = 2,   // G2 violado: Z quase nulo
    OrthogonalityFail = 3  // C4: ||U_newᵀ U_old||_max > 64 ulp
};

struct NeurogenesisResult {
    NeurogenesisStatus status = NeurogenesisStatus::Ok;
    double frob_Z = 0.0;
    double orth_max = 0.0;   // ||U_newᵀ U_old||_max medido
};

constexpr double ORTH_TOL = 64.0 * 0x1p-52;   // 64 ulp (C4)

// Uold: d×r_old col-major; candidatos Zraw: d×r_new col-major (entrada).
// Saída: Unew d×r_new col-major (ortonormal via NS5).
// Scratch: Z(d*r_new), G(r_new²), Xout(d*r_new), M(r_new²), proj(r_old*r_new).
inline NeurogenesisResult neurogenese(const double* Uold, std::size_t d,
                                      std::size_t r_old, std::size_t r_new,
                                      const double* Zraw, double* Unew,
                                      double* Z, double* G, double* Xout,
                                      double* M, double* proj) noexcept {
    NeurogenesisResult res{};
    // --- G1: guarda de rank (Trava 4) ---
    if (r_old + r_new > d) {
        res.status = NeurogenesisStatus::RankSaturated;
        return res;
    }
    // Z = Zraw - Uold (Uoldᵀ Zraw)  (projeção complementar exata)
    for (std::size_t j = 0; j < r_new; ++j)
        for (std::size_t i = 0; i < r_old; ++i)
            proj[i + j * r_old] = dot_tree(Uold + i * d, 1, Zraw + j * d, 1, d);
    for (std::size_t j = 0; j < r_new; ++j)
        for (std::size_t i = 0; i < d; ++i)
            Z[i + j * d] = Zraw[i + j * d] -
                           dot_tree(Uold + i, d, proj + j * r_old, 1, r_old);
    // --- G2: guarda de degenerescência (Trava 4) ---
    res.frob_Z = frob_norm(Z, d * r_new);
    if (res.frob_Z < 1e-6) {
        res.status = NeurogenesisStatus::DegenerateSpace;
        return res;
    }
    // U_new = NS(Z) cold-start: 12 iterações (Lema 4.2: contração linear 1,5×
    // nos σ pequenos antes do regime quadrático; 5 iterações só bastam em
    // warm-start ‖I−G0‖_F < 1 — medido: 12 iters => orth_max ~ 2e-16)
    for (std::size_t i = 0; i < d * r_new; ++i) Unew[i] = Z[i];
    ns_iters(Unew, d, r_new, G, Xout, M, 12);
    // --- C4: verificação de ortogonalidade ---
    res.orth_max = 0.0;
    for (std::size_t j = 0; j < r_new; ++j)
        for (std::size_t i = 0; i < r_old; ++i) {
            const double v = dot_tree(Unew + j * d, 1, Uold + i * d, 1, d);
            const double av = (v < 0.0) ? -v : v;
            if (av > res.orth_max) res.orth_max = av;
        }
    if (res.orth_max > ORTH_TOL) res.status = NeurogenesisStatus::OrthogonalityFail;
    return res;
}

// ---------------------------------------------------------------------------
// 12. Jacobi SVD r×r (v6, consolidação — caminho de aborto de G1/G2).
//     Varreduras fixas (8), rotações via det_sqrt (0 libm), tolerância 1e-11.
//     Autovetores de A simétrica r×r col-major; V sai em Vt (r×r col-major).
// ---------------------------------------------------------------------------
inline void jacobi_eig(double* A, double* Vt, std::size_t r) noexcept {
    for (std::size_t j = 0; j < r; ++j)
        for (std::size_t i = 0; i < r; ++i)
            Vt[i + j * r] = (i == j) ? 1.0 : 0.0;
    for (int sweep = 0; sweep < 8; ++sweep) {
        double off = 0.0;
        for (std::size_t p = 0; p < r; ++p)
            for (std::size_t q = p + 1; q < r; ++q) {
                const double v = A[p + q * r];
                off += v * v;
            }
        if (off < 1e-22) break;   // tol² = (1e-11)²
        for (std::size_t p = 0; p < r; ++p) {
            for (std::size_t q = p + 1; q < r; ++q) {
                const double apq = A[p + q * r];
                if (apq == 0.0) continue;
                const double app = A[p + p * r];
                const double aqq = A[q + q * r];
                const double tau = (aqq - app) / (2.0 * apq);
                const double atau = (tau < 0.0) ? -tau : tau;
                double t = 1.0 / ( atau + det_sqrt(atau * atau + 1.0) );
                if (tau < 0.0) t = -t;
                const double c = 1.0 / det_sqrt(t * t + 1.0);
                const double s = t * c;
                // aplica rotação J(p,q) em A (col-major, simétrica)
                for (std::size_t k = 0; k < r; ++k) {
                    const double akp = A[k + p * r];
                    const double akq = A[k + q * r];
                    A[k + p * r] = c * akp - s * akq;
                    A[k + q * r] = s * akp + c * akq;
                }
                for (std::size_t k = 0; k < r; ++k) {
                    const double apk = A[p + k * r];
                    const double aqk = A[q + k * r];
                    A[p + k * r] = c * apk - s * aqk;
                    A[q + k * r] = s * apk + c * aqk;
                }
                for (std::size_t k = 0; k < r; ++k) {   // acumula V
                    const double vkp = Vt[k + p * r];
                    const double vkq = Vt[k + q * r];
                    Vt[k + p * r] = c * vkp - s * vkq;
                    Vt[k + q * r] = s * vkp + c * vkq;
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 13. Energia livre de Lyapunov L_G simplificada p/ o gate (v7):
//       L_G = KL_replay + τ·H(π)  (termos de Fisher/energia constantes na
//     mutação local cancelam-se em ΔL_G; ver Parte 1, Teorema T5).
//     KL_replay: KL(q‖p) médio sobre o buffer de replay congelado.
// ---------------------------------------------------------------------------
inline double kl_replay(const double* q, const double* p, std::size_t n) noexcept {
    double acc[1024];
    for (std::size_t i = 0; i < n; ++i) {
        const double qi = (q[i] > 1e-300) ? q[i] : 1e-300;
        const double pi2 = (p[i] > 1e-300) ? p[i] : 1e-300;
        acc[i] = qi * det_log(qi / pi2);
    }
    return reduce_tree(acc, n);
}

// ---------------------------------------------------------------------------
// 14. PC-SM — Proof-Carrying Self-Modification (Parte 1, Def. 3.13, T5).
//     C2 usa modelo Roofline com largura de acumulação EXPLÍCITA (Trava 3):
//       Binary64: C_sust_64 = 17,6 GFLOP/s (A76, 2 mul + 2 add/ciclo, sem FMA)
//       Binary32: C_sust_32 = 35,2 GFLOP/s (4 mul + 4 add/ciclo fp32 NEON)
//     T_budget em ms. Estimativa é função pura (determinística, A4).
// ---------------------------------------------------------------------------
enum class AccWidth : int { Binary64 = 64, Binary32 = 32 };

struct RooflineParams {
    double B_sust_GBs = 11.09;        // DRAM sustentada (Tier 1, A1)
    double C_sust_GFLOPs_64 = 17.6;   // acumulador binary64 (sem FMA)
    double C_sust_GFLOPs_32 = 35.2;   // acumulador binary32 (sem FMA)
    double T_budget_ms = 4.0;         // orçamento de latência por inferência
};

inline double roofline_estimate_ms(std::uint64_t bytes, std::uint64_t flops,
                                   AccWidth accw,
                                   const RooflineParams& P) noexcept {
    const double t_mem = static_cast<double>(bytes) /
                         (P.B_sust_GBs * 1.0e9);
    const double c = (accw == AccWidth::Binary64) ? P.C_sust_GFLOPs_64
                                                  : P.C_sust_GFLOPs_32;
    const double t_cmp = static_cast<double>(flops) / (c * 1.0e9);
    const double t = (t_mem > t_cmp) ? t_mem : t_cmp;
    return t * 1.0e3;   // ms
}

struct GateCertificate {
    bool c1_lyapunov = false;     // ΔL_G <= 0
    bool c2_roofline = false;     // latência estimada <= T_budget
    bool c3_integrity = false;    // FNV-1a arena‖.grs íntegro
    bool c4_orthogonality = false;// ||U_newᵀ U_old||_max <= 64 ulp
    double delta_LG = 0.0;
    double t_est_ms = 0.0;
    std::uint64_t hash_after = 0;
    bool accept() const noexcept {
        return c1_lyapunov && c2_roofline && c3_integrity && c4_orthogonality;
    }
};

struct GateInput {
    double LG_before = 0.0;
    double LG_after = 0.0;
    std::uint64_t bytes = 0;        // custo da mutação (Roofline)
    std::uint64_t flops = 0;
    AccWidth accw = AccWidth::Binary64;
    std::uint64_t hash_expected = 0;// FNV-1a esperado da arena‖.grs pós-mutação
    std::uint64_t hash_actual = 0;
    double orth_max = 0.0;          // saída de neurogenese (0.0 se não aplicável)
};

inline GateCertificate pcsm_verify(const GateInput& in,
                                   const RooflineParams& P) noexcept {
    GateCertificate c{};
    c.delta_LG = in.LG_after - in.LG_before;
    c.c1_lyapunov = (c.delta_LG <= 0.0);
    c.t_est_ms = roofline_estimate_ms(in.bytes, in.flops, in.accw, P);
    c.c2_roofline = (c.t_est_ms <= P.T_budget_ms);
    c.hash_after = in.hash_actual;
    c.c3_integrity = (in.hash_expected == in.hash_actual) &&
                     (in.hash_expected != 0u);
    c.c4_orthogonality = (in.orth_max <= ORTH_TOL);
    return c;
}

// ---------------------------------------------------------------------------
// 15. Empoderamento determinístico (Parte 1, Def. 3.12): estimador de
//     I(A;S') por amostragem antitética com N_e fixo (tempo polinomial).
//     Entropias por histograma de bins fixos (det_log; sem libm).
// ---------------------------------------------------------------------------
inline double entropy_hist(const std::uint32_t* bins, std::size_t nb,
                           std::uint32_t total) noexcept {
    double acc[256];
    std::size_t cnt = 0;
    for (std::size_t i = 0; i < nb && cnt < 256; ++i) {
        if (bins[i] == 0) continue;
        const double pi = static_cast<double>(bins[i]) / static_cast<double>(total);
        acc[cnt++] = -(pi * det_log(pi));
    }
    return (cnt > 0) ? reduce_tree(acc, cnt) : 0.0;
}

} // namespace grs8

#endif // GIRASSOL_V8_CORE_HPP
