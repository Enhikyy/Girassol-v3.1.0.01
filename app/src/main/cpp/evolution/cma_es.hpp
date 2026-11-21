#pragma once
// CMA-ES compacto — gêmeo C++20 de core/rsi/CmaEs.kt (paridade bit a bit; -ffp-contract=off).
// Normais Irwin–Hall, pesos lineares, exp determinístico (Taylor 20) e Jacobi cíclico:
// apenas +, −, ×, ÷ e sqrt (IEEE-754 exatos), logo idênticos na JVM e no ARM64.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <numeric>
#include <vector>

#include "../common/splitmix64.hpp"

namespace girassol::cma {

struct State {
    int n = 0, lambda = 0, generation = 0;
    double sigma = 0.2;
    std::vector<double> mean, c, pc, ps, b, d, bestX, pendingY;
    double bestCost = std::numeric_limits<double>::max();

    static State initial(const std::vector<double>& x0, double sigma0, int lambda) {
        State s;
        s.n = static_cast<int>(x0.size()); s.lambda = lambda; s.sigma = sigma0; s.mean = x0;
        s.c.assign(s.n * s.n, 0.0);
        for (int i = 0; i < s.n; ++i) s.c[i * s.n + i] = 1.0;
        s.b = s.c; s.pc.assign(s.n, 0.0); s.ps.assign(s.n, 0.0); s.d.assign(s.n, 1.0);
        return s;
    }
};

inline double normal(SplitMix64& rng) {
    double s = 0.0;
    for (int i = 0; i < 12; ++i) s += rng.nextDouble();
    return s - 6.0;
}

inline double detExp(double x) {
    if (x > 700.0) return std::numeric_limits<double>::max();
    if (x < -700.0) return 0.0;
    const double ln2 = 0.6931471805599453;
    int64_t k = static_cast<int64_t>(x / ln2);
    double r = x - static_cast<double>(k) * ln2;
    if (r > ln2 / 2) { r -= ln2; ++k; } else if (r < -ln2 / 2) { r += ln2; --k; }
    double term = 1.0, sum = 1.0;
    for (int i = 1; i <= 20; ++i) { term = term * r / static_cast<double>(i); sum += term; }
    double p = 1.0;
    if (k >= 0) { for (int64_t i = 0; i < k; ++i) p *= 2.0; } else { for (int64_t i = 0; i < -k; ++i) p *= 0.5; }
    return sum * p;
}

inline std::vector<double> weights(const State& s) {
    const int mu = s.lambda / 2;
    std::vector<double> w(mu);
    double sum = 0.0;
    for (int i = 0; i < mu; ++i) { w[i] = static_cast<double>(mu - i); }
    for (double v : w) sum += v;
    for (double& v : w) v = v / sum;
    return w;
}

inline std::vector<std::vector<double>> ask(State& s, SplitMix64& rng) {
    const int n = s.n;
    s.pendingY.assign(s.lambda * n, 0.0);
    std::vector<std::vector<double>> out;
    for (int k = 0; k < s.lambda; ++k) {
        std::vector<double> z(n), x(n);
        for (int i = 0; i < n; ++i) z[i] = normal(rng);
        for (int i = 0; i < n; ++i) {
            double y = 0.0;
            for (int j = 0; j < n; ++j) y += s.b[i * n + j] * (s.d[j] * z[j]);
            s.pendingY[k * n + i] = y;
            x[i] = s.mean[i] + s.sigma * y;
        }
        out.push_back(std::move(x));
    }
    return out;
}

inline void jacobiEigen(const std::vector<double>& m, int n, std::vector<double>& vals, std::vector<double>& v, int sweeps = 24) {
    std::vector<double> a = m;
    v.assign(n * n, 0.0);
    for (int i = 0; i < n; ++i) v[i * n + i] = 1.0;
    for (int sweep = 0; sweep < sweeps; ++sweep) {
        double off = 0.0;
        for (int p = 0; p < n; ++p) for (int q = p + 1; q < n; ++q) off += a[p * n + q] * a[p * n + q];
        if (off < 1e-30) break;
        for (int p = 0; p < n; ++p) for (int q = p + 1; q < n; ++q) {
            const double apq = a[p * n + q];
            if (apq == 0.0) continue;
            const double app = a[p * n + p], aqq = a[q * n + q];
            const double theta = (aqq - app) / (2.0 * apq);
            const double t = (theta >= 0.0 ? 1.0 : -1.0) / ((theta >= 0.0 ? theta : -theta) + std::sqrt(theta * theta + 1.0));
            const double cth = 1.0 / std::sqrt(t * t + 1.0);
            const double sth = t * cth;
            for (int k = 0; k < n; ++k) {
                const double akp = a[k * n + p], akq = a[k * n + q];
                a[k * n + p] = cth * akp - sth * akq;
                a[k * n + q] = sth * akp + cth * akq;
            }
            for (int k = 0; k < n; ++k) {
                const double apk = a[p * n + k], aqk = a[q * n + k];
                a[p * n + k] = cth * apk - sth * aqk;
                a[q * n + k] = sth * apk + cth * aqk;
            }
            for (int k = 0; k < n; ++k) {
                const double vkp = v[k * n + p], vkq = v[k * n + q];
                v[k * n + p] = cth * vkp - sth * vkq;
                v[k * n + q] = sth * vkp + cth * vkq;
            }
        }
    }
    vals.resize(n);
    for (int i = 0; i < n; ++i) vals[i] = a[i * n + i];
}

inline void tell(State& s, const std::vector<double>& costs) {
    const int n = s.n;
    std::vector<int> order(s.lambda);
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](int x, int y) { return costs[x] < costs[y] || (costs[x] == costs[y] && x < y); });
    const auto w = weights(s);
    double sw = 0.0; for (double v : w) sw += v * v;
    const double mueff = 1.0 / sw;
    const double nd = static_cast<double>(n);
    const double cc = (4.0 + mueff / nd) / (nd + 4.0 + 2.0 * mueff / nd);
    const double cs = (mueff + 2.0) / (nd + mueff + 5.0);
    const double c1 = 2.0 / ((nd + 1.3) * (nd + 1.3) + mueff);
    const double cmuRaw = 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((nd + 2.0) * (nd + 2.0) + mueff);
    const double cmu = cmuRaw < 1.0 - c1 ? cmuRaw : 1.0 - c1;
    const double inner = std::sqrt((mueff - 1.0) / (nd + 1.0)) - 1.0;
    const double damps = 1.0 + 2.0 * (inner > 0.0 ? inner : 0.0) + cs;
    const double chiN = std::sqrt(nd) * (1.0 - 1.0 / (4.0 * nd) + 1.0 / (21.0 * nd * nd));

    const int best = order[0];
    if (costs[best] < s.bestCost) {
        s.bestCost = costs[best];
        s.bestX.resize(n);
        for (int i = 0; i < n; ++i) s.bestX[i] = s.mean[i] + s.sigma * s.pendingY[best * n + i];
    }
    std::vector<double> yw(n, 0.0);
    for (size_t i = 0; i < w.size(); ++i) { const int k = order[i]; for (int j = 0; j < n; ++j) yw[j] += w[i] * s.pendingY[k * n + j]; }
    for (int j = 0; j < n; ++j) s.mean[j] += s.sigma * yw[j];
    std::vector<double> bty(n), cInvY(n);
    for (int i = 0; i < n; ++i) { double acc = 0.0; for (int j = 0; j < n; ++j) acc += s.b[j * n + i] * yw[j]; bty[i] = acc / s.d[i]; }
    for (int i = 0; i < n; ++i) { double acc = 0.0; for (int j = 0; j < n; ++j) acc += s.b[i * n + j] * bty[j]; cInvY[i] = acc; }
    const double csf = std::sqrt(cs * (2.0 - cs) * mueff);
    double psNorm2 = 0.0;
    for (int i = 0; i < n; ++i) { s.ps[i] = (1.0 - cs) * s.ps[i] + csf * cInvY[i]; psNorm2 += s.ps[i] * s.ps[i]; }
    const double psNorm = std::sqrt(psNorm2);
    s.generation++;
    double pw = 1.0; for (int i = 0; i < 2 * s.generation; ++i) pw *= (1.0 - cs);
    const double decay = 1.0 - pw;
    const bool hsig = psNorm / std::sqrt(decay > 1e-300 ? decay : 1e-300) / chiN < 1.4 + 2.0 / (nd + 1.0);
    const double ccf = std::sqrt(cc * (2.0 - cc) * mueff);
    for (int i = 0; i < n; ++i) s.pc[i] = (1.0 - cc) * s.pc[i] + (hsig ? ccf * yw[i] : 0.0);
    const double deltaH = hsig ? 0.0 : cc * (2.0 - cc);
    for (int i = 0; i < n; ++i) for (int j = 0; j < n; ++j) {
        double rankMu = 0.0;
        for (size_t q = 0; q < w.size(); ++q) { const int k = order[q]; rankMu += w[q] * s.pendingY[k * n + i] * s.pendingY[k * n + j]; }
        const int idx = i * n + j;
        s.c[idx] = (1.0 - c1 - cmu) * s.c[idx] + c1 * (s.pc[i] * s.pc[j] + deltaH * s.c[idx]) + cmu * rankMu;
    }
    s.sigma = s.sigma * detExp((cs / damps) * (psNorm / chiN - 1.0));
    if (s.sigma > 0.5) s.sigma = 0.5;
    if (s.sigma < 1e-4) s.sigma = 1e-4;
    for (int i = 0; i < n; ++i) for (int j = i + 1; j < n; ++j) {
        const double v = 0.5 * (s.c[i * n + j] + s.c[j * n + i]);
        s.c[i * n + j] = v; s.c[j * n + i] = v;
    }
    std::vector<double> vals, vecs;
    jacobiEigen(s.c, n, vals, vecs);
    s.b = vecs;
    for (int i = 0; i < n; ++i) { const double v = vals[i]; s.d[i] = std::sqrt(v > 1e-20 ? v : 1e-20); }
    s.pendingY.clear();
}

inline uint64_t paritySignature() {
    State s = State::initial(std::vector<double>(6, 0.5), 0.2, 8);
    SplitMix64 rng(2001ULL);
    for (int g = 0; g < 30; ++g) {
        auto xs = ask(s, rng);
        std::vector<double> costs(xs.size());
        for (size_t k = 0; k < xs.size(); ++k) {
            double f = 0.0;
            for (int i = 0; i < 6; ++i) { const double d = xs[k][i] - 0.2 - 0.1 * i; f += (1.0 + i) * d * d; }
            costs[k] = f;
        }
        tell(s, costs);
    }
    uint64_t h = 0xcbf29ce484222325ULL;
    auto mix = [&h](uint64_t v) { for (int b = 0; b < 8; ++b) { h ^= (v >> (b * 8)) & 0xFFULL; h *= 0x100000001b3ULL; } };
    for (double v : s.mean) { uint64_t bits; std::memcpy(&bits, &v, 8); mix(bits); }
    { uint64_t bits; std::memcpy(&bits, &s.sigma, 8); mix(bits); }
    mix(static_cast<uint64_t>(s.generation));
    return h;
}

}  // namespace girassol::cma
