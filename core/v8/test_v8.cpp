// ============================================================================
// test_v8.cpp — TEST-V8-1 .. TEST-V8-5 (Projeto Girassol v8)
// Fixtures 100% determinísticas: PRNG explícito (SplitMix64 -> Xoshiro256**)
// com seeds fixas (Trava 2). Imprime os hashes FNV-1a-64 canônicos e os
// compara com as âncoras nativas x86_64/arm64-v8a (reancorados na execução
// nativa C++20 — ver §9.6 do tratado, revisão Parte 2).
// Build: g++ -std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off
//        -fno-fast-math -fno-math-errno test_v8.cpp -o test_v8
// ============================================================================
#include "girassol_v8_core.hpp"
#include <cstdio>
#include <cstring>

namespace {

// ---------------------------------------------------------------------------
// PRNG determinístico (Trava 2): SplitMix64 (sementeira) + Xoshiro256**
// ---------------------------------------------------------------------------
struct SplitMix64 {
    std::uint64_t state;
    explicit SplitMix64(std::uint64_t seed) : state(seed) {}
    std::uint64_t next() noexcept {
        std::uint64_t z = (state += 0x9E3779B97F4A7C15ull);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
};

struct Xoshiro256ss {
    std::uint64_t s[4];
    explicit Xoshiro256ss(std::uint64_t seed) noexcept {
        SplitMix64 sm(seed);
        for (auto& v : s) v = sm.next();
    }
    static std::uint64_t rotl(std::uint64_t x, int k) noexcept {
        return (x << k) | (x >> (64 - k));
    }
    std::uint64_t next() noexcept {
        const std::uint64_t r = rotl(s[1] * 5ull, 7) * 9ull;
        const std::uint64_t t = s[1] << 17;
        s[2] ^= s[0]; s[3] ^= s[1]; s[1] ^= s[2]; s[0] ^= s[3];
        s[2] ^= t;
        s[3] = rotl(s[3], 45);
        return r;
    }
    // Uniforme em [0,1): 53 bits (binário64 exato, sem libm)
    double uniform() noexcept {
        return static_cast<double>(next() >> 11) * 0x1p-53;
    }
    // Normal ~N(0,1) por Irwin–Hall (12 uniformes - 6): determinístico, 0 libm
    double normal() noexcept {
        double acc = 0.0;
        for (int i = 0; i < 12; ++i) acc += uniform();
        return acc - 6.0;
    }
};

void print_hash(const char* name, std::uint64_t h) noexcept {
    std::printf("%s: FNV-1a64 = 0x%016llx\n", name,
                static_cast<unsigned long long>(h));
}

bool check(const char* what, bool ok) noexcept {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    return ok;
}

int failures = 0;
void expect(bool ok, const char* what) noexcept {
    if (!check(what, ok)) ++failures;
}

// ---------------------------------------------------------------------------
// TEST-V8-1 — detmath: det_exp / det_log / det_lse
// ---------------------------------------------------------------------------
std::uint64_t test_v8_1() noexcept {
    std::printf("TEST-V8-1: detmath\n");
    Xoshiro256ss rng(0x67A550ull);
    // Amostra determinística em [-80, 80] e [-700, 700]
    double worst_exp = 0.0;
    for (int i = 0; i < 20000; ++i) {
        const double x = -80.0 + 160.0 * rng.uniform();
        const double v = grs8::det_exp(x);
        // referência: det_exp é auto-consistência; mede erro vs identidade log∘exp
        const double back = grs8::det_log(v);
        double err = (back - x >= 0.0) ? (back - x) : -(back - x);
        err /= ((x >= 0.0) ? x : -x) + 1.0;
        if (err > worst_exp) worst_exp = err;
    }
    constexpr double kLseVec[3] = {1.0, 2.0, 3.0};
    double vec[3];
    vec[0] = grs8::det_exp(0.27174669264696208);
    vec[1] = grs8::det_log(3.141592653589793);
    vec[2] = grs8::det_lse(kLseVec, 3);
    expect(worst_exp < 4.5e-16, "erro det_exp < 4.5e-16 (round-trip log)");
    expect(vec[1] > 1.14472988584940 && vec[1] < 1.14472988584941,
           "det_log(pi) ≈ 1.1447298858494002");
    expect(vec[2] > 3.40760596444438 && vec[2] < 3.40760596444439,
           "det_lse([1,2,3]) ≈ 3.4076059644443803");
    std::printf("  erro round-trip máx = %.3e\n", worst_exp);
    return grs8::fnv1a64_f64(vec, 3);
}

// ---------------------------------------------------------------------------
// TEST-V8-2 — NS5: warm-start quadrático + paridade NS(Aᵀ) = NS(A)ᵀ bit a bit
// ---------------------------------------------------------------------------
std::uint64_t test_v8_2() noexcept {
    std::printf("TEST-V8-2: Newton–Schulz polar (NS5)\n");
    constexpr std::size_t M = 256, N = 64;
    static double A[M * N], X[M * N], G[N * N], Xo[M * N], Mt[N * N];
    static double At[N * M], Xt[N * M], G2[M * M], Xo2[N * M], Mt2[M * M];
    Xoshiro256ss rng(0xBEEFull);
    for (std::size_t i = 0; i < M * N; ++i) A[i] = rng.normal();
    // Fase 1 — cold-start: NS com 12 iterações (Lema 4.2: contração linear
    // 1,5× nos σ pequenos antes do regime quadrático)
    for (std::size_t i = 0; i < M * N; ++i) X[i] = A[i];
    grs8::Ns5Stats st = grs8::ns_iters(X, M, N, G, Xo, Mt, 12);
    std::printf("  cold (12 iters): ||I−G12||_F = %.3e  prescaled=%d\n",
                st.frob_ImG5, static_cast<int>(st.prescaled));
    expect(st.frob_ImG5 < 3e-15, "NS 12 iters: ||I−G12||_F < 3e-15");
    // Fase 2 — warm-start (regime v5): perturbação ε=0,002 => ‖I−G0‖_F < 1,
    // NS5 (5 iterações, sem pré-escala) deve bastar
    for (std::size_t i = 0; i < M * N; ++i) X[i] += 0.002 * rng.normal();
    st = grs8::ns5(X, M, N, G, Xo, Mt);
    std::printf("  warm (NS5): ||I−G0||_F = %.4f  prescaled=%d  ||I−G5||_F = %.3e\n",
                st.frob_ImG0, static_cast<int>(st.prescaled), st.frob_ImG5);
    expect(!st.prescaled, "warm-start: pré-escala omitida (‖I−G0‖_F < 1)");
    expect(st.frob_ImG5 < 3e-15, "NS5 warm: ||I−G5||_F < 3e-15");
    // Paridade de transposição (Trava 4): MESMA entrada, MESMAS 5 iterações —
    // NS(Aᵀ) deve ser EXATAMENTE NS(A)ᵀ, pois tall/wide compartilham a árvore
    // de redução (dot_tree) e a mesma ordem de acumulação.
    for (std::size_t j = 0; j < N; ++j)
        for (std::size_t i = 0; i < M; ++i) At[j + i * N] = A[i + j * M];
    static double Xp[M * N];
    for (std::size_t i = 0; i < M * N; ++i) Xp[i] = A[i];
    grs8::ns_iters(Xp, M, N, G, Xo, Mt, 5);               // tall
    for (std::size_t i = 0; i < N * M; ++i) Xt[i] = At[i];
    grs8::ns_iters(Xt, N, M, G2, Xo2, Mt2, 5);            // wide
    double maxdiff = 0.0;
    for (std::size_t j = 0; j < N; ++j)
        for (std::size_t i = 0; i < M; ++i) {
            const double d = Xt[j + i * N] - Xp[i + j * M];
            const double ad = (d < 0.0) ? -d : d;
            if (ad > maxdiff) maxdiff = ad;
        }
    expect(maxdiff == 0.0, "NS(Aᵀ) == NS(A)ᵀ bit a bit (diff = 0)");
    std::printf("  paridade transposição: max diff = %.3e\n", maxdiff);
    return grs8::fnv1a64_f64(X, M * N);
}

// ---------------------------------------------------------------------------
// TEST-V8-3 — Simplex entrópico: piso anti-colapso e soma exata
// ---------------------------------------------------------------------------
std::uint64_t test_v8_3() noexcept {
    std::printf("TEST-V8-3: Consolidação entrópica\n");
    constexpr std::size_t n = 64;
    static double s[n], K[n * n], pi[n], g[n];
    Xoshiro256ss rng(0xC0FFEEull);
    for (std::size_t i = 0; i < n; ++i) s[i] = rng.normal();
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < n; ++i)
            K[i + j * n] = rng.normal();
    // simetriza K (bit a bit): K <- K + Kᵀ
    for (std::size_t j = 0; j < n; ++j)
        for (std::size_t i = 0; i < n; ++i)
            K[i + j * n] = K[i + j * n] + K[j + i * n];
    const double beta = 3.0, tau = 0.05;
    grs8::EntropicResult r = grs8::solve_entropic_simplex(s, K, beta, tau, pi, g, n);
    std::printf("  min π* = %.6e  piso = %.6e  Σπ = %.15f\n",
                r.min_pi, r.floor_bound, r.sum_pi);
    expect(r.floor_ok, "piso anti-colapso: min π* >= e^(−Δ/τ)/n");
    expect(r.sum_pi > 0.999999999999999 && r.sum_pi < 1.000000000000001,
           "Σπ == 1 (binário64)");
    return grs8::fnv1a64_f64(pi, n);
}

// ---------------------------------------------------------------------------
// TEST-V8-4 — ED-SDPC: esparsidade ≤ 5%, plasticidade confinada, E8/Zador
// ---------------------------------------------------------------------------
std::uint64_t test_v8_4() noexcept {
    std::printf("TEST-V8-4: ED-SDPC (esparsidade + plasticidade 3 fatores + E8)\n");
    constexpr std::size_t N = 256;
    constexpr int T = 2000;
    static std::uint8_t arena_buf[64 * 1024 * 1024];
    grs8::Arena64 ar;
    ar.init(arena_buf, sizeof(arena_buf));
    grs8::Cortex cx;
    if (!cx.init(ar, N)) {
        std::printf("  [FAIL] arena insuficiente\n");
        ++failures;
        return 0;
    }
    Xoshiro256ss rng(0xC0A1ull);
    // Inicialização determinística de W^b (fraca, gaussiana)
    for (std::size_t i = 0; i < N * N; ++i) cx.Wb[i] = 0.1 * rng.normal();
    for (std::size_t i = 0; i < N; ++i) cx.Wb[i + i * N] = 0.0;
    for (std::size_t i = 0; i < N * N; ++i) cx.Wa[i] = 0.05 * rng.normal();
    static double x_in[N], p[N];
    double act_sum = 0.0, act_max = 0.0;
    constexpr int WIN = 500;
    for (int t = 0; t < T; ++t) {
        for (std::size_t i = 0; i < N; ++i)
            x_in[i] = (rng.uniform() < 0.03) ? 1.0 : 0.0;
        for (std::size_t i = 0; i < N; ++i)
            p[i] = (rng.uniform() < 0.05) ? 1.0 : 0.0;
        const double act = cx.step(x_in, p);
        if (t >= T - WIN) {
            act_sum += act;
            if (act > act_max) act_max = act;
        }
    }
    const double act_mean = act_sum / WIN;
    double wmax = 0.0;
    for (std::size_t i = 0; i < N * N; ++i) {
        const double aw = (cx.Wb[i] < 0.0) ? -cx.Wb[i] : cx.Wb[i];
        if (aw > wmax) wmax = aw;
    }
    std::printf("  esparsidade média (%d finais) = %.4f%%  máx = %.4f%%\n",
                WIN, act_mean * 100.0, act_max * 100.0);
    std::printf("  |w|_max = %.4f  DA = %.4f  NA = %.4f  ACh = %.4f  5HT = %.4f\n",
                wmax, cx.nm.DA, cx.nm.NA, cx.nm.ACh, cx.nm.HT5);
    expect(act_mean <= 0.055, "esparsidade média <= 5,5% (guarda F1, alvo 5%)");
    expect(act_mean >= 0.030, "esparsidade média >= 3,0% (rede viva)");
    expect(wmax <= cx.P.w_max, "pesos confinados em [−w_max, w_max] (T1.ii)");
    // Codec E8: ida-e-volta em bloco canônico (guarda F8)
    double w8[8] = {0.31, -1.02, 0.77, 0.05, -0.44, 1.20, -0.88, 0.66};
    std::uint32_t payload; std::uint16_t sc;
    grs8::E8Codec::encode_block(w8, payload, sc);
    double back[8];
    grs8::E8Codec::decode_block(payload, sc, back);
    double qerr = 0.0;
    for (int i = 0; i < 8; ++i) {
        const double d = w8[i] - back[i];
        qerr += d * d;
    }
    std::printf("  E8 round-trip: payload=0x%07x  erro quad. = %.4e\n",
                payload, qerr);
    expect(qerr < 0.20, "E8 round-trip: erro quadrático < 0.20 (8 pesos)");
    // Hash do estado final: esparsidade + |w|max + neuromoduladores
    double out[6] = {act_mean, wmax, cx.nm.DA, cx.nm.NA, cx.nm.ACh, cx.nm.HT5};
    (void)ar;
    return grs8::fnv1a64_f64(out, 6);
}

// ---------------------------------------------------------------------------
// TEST-V8-5 — Roofline (C2, largura explícita) + Não-Interferência (T4)
// ---------------------------------------------------------------------------
std::uint64_t test_v8_5() noexcept {
    std::printf("TEST-V8-5: Roofline + Não-Interferência OSN-ESC\n");
    // --- Roofline com largura de acumulação explícita (Trava 3) ---
    grs8::RooflineParams P;   // Tier 1 (A1): 11,09 GB/s; 17,6/35,2 GFLOP/s
    constexpr std::uint64_t Mn = 4096;
    const std::uint64_t bytes_fp16 = 2 * Mn * Mn + 2 * Mn + 2 * Mn;
    const std::uint64_t flops_fp16 = 2 * Mn * Mn;
    const double t_fp16 = grs8::roofline_estimate_ms(bytes_fp16, flops_fp16,
                                                     grs8::AccWidth::Binary64, P);
    // E8 + gating ρ = 5%: 6 B/bloco de 8, decode 40 + 16 FLOPs por bloco ativo
    const std::uint64_t blocks_total = (Mn * Mn) / 8;
    const std::uint64_t blocks_active = blocks_total / 20;   // ρ = 5%
    const std::uint64_t bytes_e8 = blocks_active * 6 + (Mn * Mn) / 64 +
                                   2 * Mn + 2 * Mn;
    const std::uint64_t flops_e8 = blocks_active * 56;
    const double t_e8 = grs8::roofline_estimate_ms(bytes_e8, flops_e8,
                                                   grs8::AccWidth::Binary64, P);
    const double speedup = t_fp16 / t_e8;
    std::printf("  FP16: %.3f ms | E8+gating: %.4f ms | speedup = %.2f×\n",
                t_fp16, t_e8, speedup);
    expect(speedup >= 4.0, "speedup Roofline >= 4× (T2.b, binary64)");
    // binary32 deve ser mais rápido no regime compute-bound (C2 explícito)
    const double t_e8_32 = grs8::roofline_estimate_ms(bytes_e8, flops_e8,
                                                      grs8::AccWidth::Binary32, P);
    expect(t_e8_32 <= t_e8, "binary32 <= binary64 em regime compute-bound");
    // --- Não-interferência (T4) com guardas de runtime (Trava 4) ---
    constexpr std::size_t d = 64, r_old = 16, r_new = 4;
    static double Uold[d * r_old], Vold[d * r_old];
    static double Zraw[d * r_new], Unew[d * r_new];
    static double Z[d * r_new], G[r_new * r_new], Xout[d * r_new], M2[r_new * r_new];
    static double proj[r_old * r_new];
    static double Gs[r_old * r_old], Xos[d * r_old], Ms[r_old * r_old];
    Xoshiro256ss rng(0x05ull);
    for (std::size_t i = 0; i < d * r_old; ++i) Uold[i] = rng.normal();
    grs8::ns_iters(Uold, d, r_old, Gs, Xos, Ms, 12);   // cold-start convergido
    for (std::size_t i = 0; i < d * r_old; ++i) Vold[i] = rng.normal();
    grs8::ns_iters(Vold, d, r_old, Gs, Xos, Ms, 12);   // (premissa de T4)
    for (std::size_t i = 0; i < d * r_new; ++i) Zraw[i] = rng.normal();
    grs8::NeurogenesisResult nr = grs8::neurogenese(
        Uold, d, r_old, r_new, Zraw, Unew, Z, G, Xout, M2, proj);
    std::printf("  neurogênese: status=%d  ||Z||_F=%.4f  ||U_newᵀU_old||_max=%.3e\n",
                static_cast<int>(nr.status), nr.frob_Z, nr.orth_max);
    expect(nr.status == grs8::NeurogenesisStatus::Ok,
           "neurogênese OK (guardas G1/G2/C4 satisfeitas)");
    // Guarda G1: r_old + r_new > d deve abortar
    grs8::NeurogenesisResult sat = grs8::neurogenese(
        Uold, d, d - 2, r_new, Zraw, Unew, Z, G, Xout, M2, proj);
    expect(sat.status == grs8::NeurogenesisStatus::RankSaturated,
           "guarda G1: rank saturado -> aborta p/ consolidação");
    // Guarda G2: Z nulo deve abortar
    static double Znull[d * r_new];
    for (std::size_t i = 0; i < d * r_new; ++i) Znull[i] = 0.0;
    grs8::NeurogenesisResult deg = grs8::neurogenese(
        Uold, d, r_old, r_new, Znull, Unew, Z, G, Xout, M2, proj);
    expect(deg.status == grs8::NeurogenesisStatus::DegenerateSpace,
           "guarda G2: ||Z||_F < 1e-6 -> aborta p/ consolidação");
    // Esquecimento: x_old ∈ span(V_old); ΔW = Unew diag(Sn) Vnewᵀ
    static double alpha[r_old], x_old[d];
    for (std::size_t i = 0; i < r_old; ++i) alpha[i] = rng.normal();
    for (std::size_t i = 0; i < d; ++i)
        x_old[i] = grs8::dot_tree(Vold + i, d, alpha, 1, r_old);
    // ΔW = Unew diag(Sn) Vnewᵀ com Vnew ⊥ Vold (neurogênese bilateral)
    static double Vraw[d * r_new], Vnew[d * r_new];
    for (std::size_t i = 0; i < d * r_new; ++i) Vraw[i] = rng.normal();
    grs8::NeurogenesisResult nr2 = grs8::neurogenese(
        Vold, d, r_old, r_new, Vraw, Vnew, Z, G, Xout, M2, proj);
    expect(nr2.status == grs8::NeurogenesisStatus::Ok,
           "neurogênese bilateral (lado V) OK");
    double Sn[r_new];
    for (std::size_t i = 0; i < r_new; ++i) Sn[i] = 0.5 + rng.uniform();
    // Δf = Unew (Sn ⊙ (Vnewᵀ x_old)) — deve ser ~0 pois Vnewᵀ Vold = 0
    double vt2[r_new];
    for (std::size_t i = 0; i < r_new; ++i)
        vt2[i] = grs8::dot_tree(Vnew + i * d, 1, x_old, 1, d);
    for (std::size_t i = 0; i < r_new; ++i) vt2[i] *= Sn[i];
    static double df[d];
    for (std::size_t i = 0; i < d; ++i)
        df[i] = grs8::dot_tree(Unew + i, d, vt2, 1, r_new);
    double forget = 0.0;
    for (std::size_t i = 0; i < d; ++i) {
        const double av = (df[i] < 0.0) ? -df[i] : df[i];
        if (av > forget) forget = av;
    }
    std::printf("  ‖Δf_old‖∞ = %.3e (ε_forget, piso de máquina)\n", forget);
    expect(forget < 1e-12, "‖Δf_old‖∞ < 1e-12 (T4: sem interferência)");
    // Gate PC-SM: mutação boa (ΔL_G ≤ 0) aceita; sabotagem rejeitada (T5)
    static double qbuf[8], pbuf[8];
    for (std::size_t i = 0; i < 8; ++i) { qbuf[i] = 0.125; pbuf[i] = 0.125; }
    const double LG0 = grs8::kl_replay(qbuf, pbuf, 8);
    for (std::size_t i = 0; i < 8; ++i) pbuf[i] = (i == 0) ? 0.5 : 0.07142857142857142;
    const double LG1 = grs8::kl_replay(qbuf, pbuf, 8);   // aumenta KL
    grs8::GateInput gi{};
    gi.LG_before = LG1; gi.LG_after = LG0;               // mutação boa: ΔL_G < 0
    gi.bytes = bytes_e8; gi.flops = flops_e8; gi.accw = grs8::AccWidth::Binary64;
    gi.orth_max = nr.orth_max;
    const std::uint64_t harena = grs8::fnv1a64(Uold, sizeof(Uold));
    gi.hash_expected = harena; gi.hash_actual = harena;
    grs8::GateCertificate cert = grs8::pcsm_verify(gi, P);
    expect(cert.accept(), "PC-SM: mutação legítima ACEITA (C1–C4)");
    gi.LG_before = LG0; gi.LG_after = LG1;               // sabotagem: ΔL_G > 0
    grs8::GateCertificate bad = grs8::pcsm_verify(gi, P);
    expect(!bad.accept() && !bad.c1_lyapunov,
           "PC-SM: sabotagem REJEITADA por C1 (wireheading bloqueado)");
    double out[4] = {speedup, forget, nr.orth_max, LG0};
    return grs8::fnv1a64_f64(out, 4);
}

} // namespace

// ---------------------------------------------------------------------------
// Âncoras nativas (Trava 2): reancorados da execução nativa C++20 x86_64
// (g++ -O2 -ffp-contract=off). O CI valida os MESMOS valores em arm64-v8a
// via aarch64-linux-gnu + QEMU (paridade bit a bit, Axioma A4).
// ---------------------------------------------------------------------------
constexpr std::uint64_t GRS8_ANCHOR_V8_1 = 0x278c01e818c47c50ull;
constexpr std::uint64_t GRS8_ANCHOR_V8_2 = 0xbb17432487009526ull;
constexpr std::uint64_t GRS8_ANCHOR_V8_3 = 0x391b489d80f47c53ull;
constexpr std::uint64_t GRS8_ANCHOR_V8_4 = 0x4bd5071f29dd7cffull;
constexpr std::uint64_t GRS8_ANCHOR_V8_5 = 0x5ed527e9088b664eull;

int main() {
    std::printf("=== Girassol v8 — bateria TEST-V8 (C++20 nativo) ===\n");
    const std::uint64_t h1 = test_v8_1();
    const std::uint64_t h2 = test_v8_2();
    const std::uint64_t h3 = test_v8_3();
    const std::uint64_t h4 = test_v8_4();
    const std::uint64_t h5 = test_v8_5();
    print_hash("TEST-V8-1", h1);
    print_hash("TEST-V8-2", h2);
    print_hash("TEST-V8-3", h3);
    print_hash("TEST-V8-4", h4);
    print_hash("TEST-V8-5", h5);

    expect(h1 == GRS8_ANCHOR_V8_1, "âncora FNV-1a TEST-V8-1 (paridade A4)");
    expect(h2 == GRS8_ANCHOR_V8_2, "âncora FNV-1a TEST-V8-2 (paridade A4)");
    expect(h3 == GRS8_ANCHOR_V8_3, "âncora FNV-1a TEST-V8-3 (paridade A4)");
    expect(h4 == GRS8_ANCHOR_V8_4, "âncora FNV-1a TEST-V8-4 (paridade A4)");
    expect(h5 == GRS8_ANCHOR_V8_5, "âncora FNV-1a TEST-V8-5 (paridade A4)");

    if (failures == 0) {
        std::printf("=== RESULTADO: TODOS OS TESTES PASSARAM ===\n");
        return 0;
    }
    std::printf("=== RESULTADO: %d FALHA(S) ===\n", failures);
    return 1;
}
