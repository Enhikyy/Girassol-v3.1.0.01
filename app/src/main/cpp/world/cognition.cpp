// Núcleo cognitivo nativo (C++20) — v3.8. Ver cognition.hpp.
#include "cognition.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <numeric>
#include <unordered_map>
#include <unordered_set>

#include "../common/sha256.hpp"
#include "../common/splitmix64.hpp"

namespace girassol::cognition {

namespace {

inline uint64_t doubleBits(double d) {
    if (std::isnan(d)) return 0x7ff8000000000000ULL; // Double.doubleToLongBits canoniza NaN
    uint64_t u;
    std::memcpy(&u, &d, sizeof u);
    return u;
}

inline double clampD(double v, double lo, double hi) {
    // coerceIn da JVM: NaN não é menor nem maior → permanece NaN
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

constexpr double kHawkesMu0 = 0.05;
constexpr double kHawkesAlpha = 0.6;
constexpr double kHawkesBeta = 1.0 / 480.0;
constexpr double kRrfK = 60.0;
constexpr double kBm25K1 = 1.2;
constexpr double kBm25B = 0.75;

} // namespace

int javaCompare(double a, double b) {
    if (a < b) return -1;
    if (a > b) return 1;
    const int64_t x = static_cast<int64_t>(doubleBits(a));
    const int64_t y = static_cast<int64_t>(doubleBits(b));
    return x == y ? 0 : (x < y ? -1 : 1);
}

double javaMax(double a, double b) {
    if (a != a) return a;
    if (a == 0.0 && b == 0.0 && std::signbit(a) && !std::signbit(b)) return b;
    if (a == 0.0 && b == 0.0) return a;
    return a >= b ? a : b;
}

// ------------------------------------------------------------------ grades
float Grid::read(float worldX, float worldZ) const {
    if (data == nullptr || width <= 0 || height <= 0) return 0.0f;
    // (worldX / cellSize).toInt() da JVM: truncamento em direção a zero, NaN → 0, saturação
    auto toInt = [](float f) -> int {
        if (f != f) return 0;
        if (f >= 2147483647.0f) return 2147483647;
        if (f <= -2147483648.0f) return -2147483647 - 1;
        return static_cast<int>(f);
    };
    const int gx = std::clamp(toInt(worldX / cellSize), 0, width - 1);
    const int gz = std::clamp(toInt(worldZ / cellSize), 0, height - 1);
    return data[static_cast<size_t>(gz) * static_cast<size_t>(width) + static_cast<size_t>(gx)];
}

// ------------------------------------------------------------------ 3. percepção
std::vector<Signal> perceive(const double* needs, const int32_t* codes, float food, float danger, bool drought) {
    std::vector<Signal> out;
    out.reserve(kNeeds + 3);
    for (int k = 0; k < kNeeds; ++k) out.push_back({codes[k], (1.0 - needs[k]) * 1.1});
    if (food > 0.5f) out.push_back({kFoodScent, std::min(static_cast<double>(food / 10.0f), 0.9)});
    if (danger > 0.5f) out.push_back({kDanger, std::min(static_cast<double>(danger / 5.0f), 1.0)});
    if (drought) out.push_back({kDrought, 0.5});
    return out;
}

std::vector<Signal> broadcast(std::vector<Signal> signals, int capacity) {
    std::erase_if(signals, [](const Signal& s) { return !(s.salience > 0.15); });
    std::stable_sort(signals.begin(), signals.end(),
                     [](const Signal& a, const Signal& b) { return javaCompare(b.salience, a.salience) < 0; });
    if (static_cast<int>(signals.size()) > capacity) signals.resize(static_cast<size_t>(std::max(capacity, 0)));
    return signals;
}

// ------------------------------------------------------------------ 4. memória
Bits simHash(std::span<const int64_t> featureHashes) {
    int acc[kBits] = {};
    for (int64_t f : featureHashes) {
        SplitMix64 rng(static_cast<uint64_t>(f));
        for (int w = 0; w < kWords; ++w) {
            const uint64_t r = rng.next();
            for (int b = 0; b < 64; ++b) acc[w * 64 + b] += ((r >> b) & 1ULL) ? 1 : -1;
        }
    }
    Bits out{};
    for (int i = 0; i < kBits; ++i)
        if (acc[i] > 0) out[static_cast<size_t>(i / 64)] |= (1ULL << (i % 64));
    return out;
}

int hamming(const Bits& a, const Bits& b) {
    int d = 0;
    for (int i = 0; i < kWords; ++i) d += std::popcount(a[static_cast<size_t>(i)] ^ b[static_cast<size_t>(i)]);
    return d;
}

std::array<float, kDenseDim> dense(std::span<const int64_t> featureHashes) {
    std::array<float, kDenseDim> v{};
    for (int64_t h : featureHashes) {
        const auto idx = static_cast<size_t>((static_cast<uint64_t>(h) >> 1) % kDenseDim);
        v[idx] += h < 0 ? -1.0f : 1.0f;
    }
    float n = 0.0f;
    for (float x : v) n += x * x;
    if (n > 0.0f) {
        const float inv = 1.0f / static_cast<float>(std::sqrt(static_cast<double>(n)));
        for (auto& x : v) x *= inv;
    }
    return v;
}

float cosine(const std::array<float, kDenseDim>& a, const std::array<float, kDenseDim>& b) {
    float s = 0.0f;
    for (size_t i = 0; i < a.size(); ++i) s += a[i] * b[i];
    return s;
}

double hawkesIntensity(double excitation, int64_t lastTick, int64_t now) {
    return kHawkesMu0 + excitation * std::exp(-kHawkesBeta * static_cast<double>(std::max<int64_t>(now - lastTick, 0)));
}

double hawkesExcite(double excitation, int64_t lastTick, int64_t now, double valence) {
    const double decayed = excitation * std::exp(-kHawkesBeta * static_cast<double>(std::max<int64_t>(now - lastTick, 0)));
    return decayed + kHawkesAlpha * clampD(valence, 0.0, 1.0);
}

double baseLevelActivation(std::span<const int64_t> accessTicks, int64_t createdTick, int64_t now, double decay) {
    double sum = 0.0;
    auto add = [&](int64_t t) {
        const double dt = static_cast<double>(std::max<int64_t>(now - t, 1));
        sum += std::pow(dt, -decay);
    };
    if (accessTicks.empty()) add(createdTick);
    else for (int64_t t : accessTicks) add(t);
    return std::log(std::max(sum, 1e-9));
}

namespace {

int intersectCount(const std::vector<int64_t>& a, const std::unordered_set<int64_t>& b) {
    int n = 0;
    std::unordered_set<int64_t> seen;
    for (int64_t x : a)
        if (b.contains(x) && seen.insert(x).second) ++n;
    return n;
}

std::vector<double> rrf(const std::vector<const std::vector<double>*>& lists, size_t size) {
    std::vector<double> out(size, 0.0);
    std::vector<size_t> order(size);
    for (const auto* scores : lists) {
        std::iota(order.begin(), order.end(), size_t{0});
        std::stable_sort(order.begin(), order.end(), [&](size_t x, size_t y) {
            const int c = javaCompare((*scores)[y], (*scores)[x]);
            return c != 0 ? c < 0 : x < y;
        });
        for (size_t rank = 0; rank < order.size(); ++rank)
            out[order[rank]] += 1.0 / (kRrfK + static_cast<double>(rank) + 1.0);
    }
    return out;
}

} // namespace

RetrievalOut retrieve(const MemoryQuery& q, const std::vector<MemoryNode>& nodes) {
    RetrievalOut r;
    if (nodes.empty()) return r;
    const Bits qBits = simHash(q.features);
    std::vector<int> ham(nodes.size());
    for (size_t i = 0; i < nodes.size(); ++i) ham[i] = hamming(qBits, nodes[i].bits);
    std::vector<size_t> idx(nodes.size());
    std::iota(idx.begin(), idx.end(), size_t{0});
    std::stable_sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
        if (ham[a] != ham[b]) return ham[a] < ham[b];
        return nodes[a].id > nodes[b].id;
    });
    if (idx.size() > static_cast<size_t>(kStage1K)) idx.resize(kStage1K);
    const size_t m = idx.size();
    r.stage1 = static_cast<int>(m);

    // cosseno denso
    const auto qDense = dense(q.features);
    std::vector<double> cos(m);
    for (size_t i = 0; i < m; ++i) cos[i] = static_cast<double>(cosine(qDense, dense(nodes[idx[i]].features)));

    // BM25 com estatísticas do corpus inteiro
    const std::unordered_set<int64_t> qset(q.terms.begin(), q.terms.end());
    const int n = std::max<int>(static_cast<int>(nodes.size()), 1);
    int64_t totalLen = 0;
    std::unordered_map<int64_t, int> df;
    for (const auto& node : nodes) {
        totalLen += static_cast<int64_t>(node.docTerms.size());
        std::unordered_set<int64_t> seen;
        for (int64_t t : node.docTerms)
            if (qset.contains(t) && seen.insert(t).second) ++df[t];
    }
    const double avgdl = static_cast<double>(static_cast<int32_t>(totalLen)) / n;
    std::vector<double> bm25(m);
    for (size_t i = 0; i < m; ++i) {
        const auto& doc = nodes[idx[i]].docTerms;
        std::unordered_map<int64_t, int> tf;
        for (int64_t t : doc)
            if (qset.contains(t)) ++tf[t];
        double s = 0.0;
        for (int64_t t : q.terms) { // ordem do SortedSet da JVM
            const auto it = tf.find(t);
            if (it == tf.end()) continue;
            const int f = it->second;
            const auto dit = df.find(t);
            const int d = dit == df.end() ? 0 : dit->second;
            const double idf = std::log(1.0 + (static_cast<double>(n - d) + 0.5) / (static_cast<double>(d) + 0.5));
            const double len = static_cast<double>(static_cast<int32_t>(doc.size()));
            s += idf * static_cast<double>(f) * (kBm25K1 + 1) /
                 (static_cast<double>(f) + kBm25K1 * (1 - kBm25B + kBm25B * len / std::max(avgdl, 1e-9)));
        }
        bm25[i] = s;
    }

    // ativação composta ACT-R / MemRL / A-MEM
    const std::unordered_set<int64_t> qTags(q.tags.begin(), q.tags.end());
    const std::unordered_set<int64_t> qEnts(q.entities.begin(), q.entities.end());
    std::vector<double> composite(m);
    std::vector<double> hawkes(m);
    for (size_t i = 0; i < m; ++i) {
        const auto& node = nodes[idx[i]];
        const double normAct = 1.0 / (1.0 + std::exp(-baseLevelActivation(node.accessTicks, node.createdTick, q.tick)));
        const double zettel = node.zettel ? 0.25 : 0.0;
        const double tagScore = qTags.empty() ? 0.0 : static_cast<double>(intersectCount(node.tags, qTags)) / static_cast<double>(qTags.size());
        const double entScore = qEnts.empty() ? 0.0 : static_cast<double>(intersectCount(node.entities, qEnts)) / static_cast<double>(qEnts.size());
        composite[i] = 0.30 * normAct + 0.25 * (node.salience * node.confidence) +
                       0.25 * clampD(0.55 * tagScore + 0.45 * entScore, 0.0, 1.0) +
                       0.20 * (node.qUtility + 1.0) * 0.5 + zettel;
        hawkes[i] = hawkesIntensity(node.hawkesExcitation, node.hawkesLastTick, q.tick);
    }
    const auto fused = rrf({&cos, &bm25, &composite, &hawkes}, m);
    std::vector<size_t> order(m);
    std::iota(order.begin(), order.end(), size_t{0});
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        const int c = javaCompare(fused[b], fused[a]);
        if (c != 0) return c < 0;
        return nodes[idx[a]].id > nodes[idx[b]].id;
    });
    const size_t k = std::min(order.size(), static_cast<size_t>(std::max(q.topK, 0)));
    for (size_t i = 0; i < k; ++i) {
        const auto& node = nodes[idx[order[i]]];
        r.chosen.push_back(static_cast<int32_t>(idx[order[i]]));
        r.newExcitation.push_back(hawkesExcite(node.hawkesExcitation, node.hawkesLastTick, q.tick, node.salience));
    }
    return r;
}

// ------------------------------------------------------------------ 6–7. inferência ativa
double entropyOf(double u) {
    const double p = clampD(0.5 * u, 1e-6, 0.5);
    return -(p * std::log(p) + (1 - p) * std::log(1 - p));
}

double bernoulliKl(double q, double p) {
    const double qq = clampD(q, 1e-6, 1 - 1e-6);
    const double pp = clampD(p, 1e-6, 1 - 1e-6);
    return qq * std::log(qq / pp) + (1 - qq) * std::log((1 - qq) / (1 - pp));
}

double bernoulliEntropy(double p) {
    const double q = clampD(p, 1e-9, 1 - 1e-9);
    return -(q * std::log(q) + (1 - q) * std::log(1 - q));
}

double neuralThermalCost(double base, double socTempC) {
    return base * (1.0 + 2.0 / (1.0 + std::exp(-(socTempC - 40.0) / 2.5)));
}

CandidateOut evaluate(const CandidateIn& c, double preferred, const Grid& danger, const Grid& social,
                      double riskScale, double ambiguityScale) {
    CandidateOut o{};
    const int32_t span = c.travel + c.duration;
    for (int k = 0; k < kNeeds; ++k) {
        const double g = c.gains[k];
        const double gain = g * (g > 0 ? c.skillEff * std::min(0.4 + 0.6 * c.yieldP / 0.6, 1.4) : 1.0);
        o.projected[k] = clampD(c.needs[k] - c.decay[k] * static_cast<double>(span) + gain, 0.0, 1.0);
    }
    const double stress = 0.8 + 0.4 * c.cortisol;
    double risk = 0.0;
    for (int k = 0; k < kNeeds; ++k) {
        const double deficit = 1.0 - o.projected[k];
        const double w = (k == 0 || k == 1) ? 1.6 : 1.0;
        const double weight = w * stress * (deficit > 0.7 ? 1.8 : 1.0);
        risk += weight * bernoulliKl(o.projected[k], preferred);
    }
    const double priorEntropy = entropyOf(c.uncertainty);
    const double posteriorEntropy = entropyOf(c.uncertainty * 0.5);
    double epistemicWeight = c.epistemicPolicy * (0.5 + c.curiosity);
    if (c.isExplore) epistemicWeight *= 1.2;
    const double ew = epistemicWeight * 0.15 * (0.8 + 0.4 * c.dopamine);
    const double ambiguity = ambiguityScale * bernoulliEntropy(0.5 + 0.5 * (1.0 - clampD(c.uncertainty, 0.0, 1.0)));
    const double gainE = ew * std::max(priorEntropy - posteriorEntropy, 0.0);
    const double total = riskScale * risk + ambiguity - gainE + neuralThermalCost(c.neural * 0.05, c.socTempC);
    double efe = total - c.economicBonus - 0.12 * c.memQ - c.novelty + 0.002 * static_cast<double>(c.travel);
    if (c.hasTarget) {
        const float d = danger.read(static_cast<float>(c.targetX), static_cast<float>(c.targetZ));
        if (d > 2.0f) efe += 0.05;
        if (c.isSocial) {
            const float s = std::min(social.read(static_cast<float>(c.targetX), static_cast<float>(c.targetZ)), 5.0f);
            efe -= 0.02 * static_cast<double>(s);
        }
    }
    if (c.isSocial) efe -= 0.04 * c.oxytocin;
    efe -= c.adapterWeight * clampD(c.adapterPred, -1.0, 1.0);
    if (c.loraActive) efe -= c.loraWeight * clampD(c.loraPred, -1.0, 1.0);
    o.efe = efe;
    return o;
}

std::vector<int32_t> sortCandidates(std::span<const CandidateOut> outs, std::span<const CandidateIn> ins) {
    std::vector<int32_t> idx(outs.size());
    std::iota(idx.begin(), idx.end(), 0);
    std::stable_sort(idx.begin(), idx.end(), [&](int32_t a, int32_t b) {
        const int c = javaCompare(outs[static_cast<size_t>(a)].efe, outs[static_cast<size_t>(b)].efe);
        if (c != 0) return c < 0;
        return ins[static_cast<size_t>(a)].actionOrdinal < ins[static_cast<size_t>(b)].actionOrdinal;
    });
    return idx;
}

int32_t selectPolicy(std::span<const double> efes, std::span<const int32_t> ordinals, int32_t currentOrdinal,
                     double switchCost, double gamma, double uniform) {
    if (efes.empty()) return -1;
    if (efes.size() == 1) return 0;
    std::vector<double> u(efes.size());
    for (size_t i = 0; i < efes.size(); ++i) {
        const double penalty = (currentOrdinal >= 0 && ordinals[i] != currentOrdinal) ? switchCost : 0.0;
        u[i] = -efes[i] - penalty;
    }
    double maxU = u[0];
    for (size_t i = 1; i < u.size(); ++i) maxU = javaMax(maxU, u[i]);
    std::vector<double> w(u.size());
    double total = 0.0;
    for (size_t i = 0; i < u.size(); ++i) {
        w[i] = std::exp(clampD(gamma * (u[i] - maxU), -40.0, 0.0));
        total += w[i];
    }
    double acc = 0.0;
    const double target = clampD(uniform, 0.0, 1.0) * total;
    for (size_t i = 0; i < w.size(); ++i) {
        acc += w[i];
        if (target <= acc) return static_cast<int32_t>(i);
    }
    return static_cast<int32_t>(w.size() - 1);
}

double variationalFreeEnergy(const double* needs) {
    double deficits[kNeeds];
    double sum = 0.0;
    for (int k = 0; k < kNeeds; ++k) { deficits[k] = (1.0 - needs[k]) + 1e-3; sum += deficits[k]; }
    double kl = 0.0, ell = 0.0;
    for (int k = 0; k < kNeeds; ++k) {
        const double q = std::max(deficits[k] / sum, 1e-9);
        const double p = std::max(1.0 / 6, 1e-9);
        const double lik = std::max(std::max(needs[k], 0.02), 1e-9);
        kl += q * std::log(q / p);
        ell += q * std::log(lik);
    }
    return kl - ell;
}

// ------------------------------------------------------------------ ledger
std::vector<uint8_t> ledgerZeroSum(std::span<const int64_t> balances, std::span<const int32_t> resourceIdx, int nResources) {
    std::vector<int64_t> sums(static_cast<size_t>(std::max(nResources, 0)), 0);
    std::vector<uint8_t> bad(sums.size(), 0);
    const size_t n = std::min(balances.size(), resourceIdx.size());
    for (size_t i = 0; i < n; ++i) {
        const int32_t r = resourceIdx[i];
        if (r < 0 || r >= nResources) continue;
        int64_t out;
        if (__builtin_add_overflow(sums[static_cast<size_t>(r)], balances[i], &out)) bad[static_cast<size_t>(r)] = 1;
        sums[static_cast<size_t>(r)] = out;
    }
    for (size_t r = 0; r < sums.size(); ++r) if (sums[r] != 0) bad[r] = 1;
    return bad;
}

std::string chainBody(const ChainEvent& e) {
    std::string s;
    s.reserve(e.payload.size() + e.type.size() + 128);
    s += std::to_string(e.seq); s += '|';
    s += std::to_string(e.tick); s += '|';
    s += std::to_string(e.rulesVersion); s += '|';
    s += e.type; s += '|';
    s += e.hasActor ? e.actor : std::string("-"); s += '|';
    s += e.payload; s += '|';
    s += e.prevHash;
    return s;
}

namespace {
std::string sha256Hex(std::string_view s) {
    Sha256 h;
    h.update(s);
    const auto d = h.digest();
    static constexpr char hex[] = "0123456789abcdef";
    std::string out(64, '0');
    for (size_t i = 0; i < d.size(); ++i) { out[2 * i] = hex[d[i] >> 4]; out[2 * i + 1] = hex[d[i] & 15]; }
    return out;
}
} // namespace

ChainReport verifyChain(std::span<const ChainEvent> events, const std::string* anchor, std::string_view genesis) {
    ChainReport r;
    if (events.empty()) { r.reason = kChainEmpty; r.headFrom = 0; return r; }
    const auto& first = events.front();
    std::string anchorValue;
    bool hasAnchor = false;
    if (anchor) { anchorValue = *anchor; hasAnchor = true; }
    else if (first.seq == 1) { anchorValue = std::string(genesis); hasAnchor = true; }
    if (hasAnchor && first.prevHash != anchorValue) {
        r.reason = kChainAnchor; r.checked = 0; r.firstBadSeq = first.seq; r.headFrom = 0; return r;
    }
    for (size_t i = 0; i < events.size(); ++i) {
        const auto& e = events[i];
        if (i > 0) {
            const auto& p = events[i - 1];
            if (e.seq != p.seq + 1) { r = {kChainGap, static_cast<int32_t>(i), e.seq, 1}; return r; }
            if (e.prevHash != p.hash) { r = {kChainPrev, static_cast<int32_t>(i), e.seq, 1}; return r; }
            if (e.tick < p.tick) { r = {kChainTick, static_cast<int32_t>(i), e.seq, 1}; return r; }
        }
        if (sha256Hex(chainBody(e)) != e.hash) { r = {kChainHash, static_cast<int32_t>(i), e.seq, i > 0 ? 1 : 2}; return r; }
    }
    r = {kChainOk, static_cast<int32_t>(events.size()), 0, 3};
    return r;
}

// ------------------------------------------------------------------ assinatura dourada
int64_t paritySignature() {
    uint64_t h = 0xcbf29ce484222325ULL;
    auto mix = [&](uint64_t v) { h = (h ^ v) * 0x100000001b3ULL; };
    SplitMix64 rng(20260808ULL);
    std::vector<float> food(16 * 16), danger(16 * 16), soc(16 * 16);
    for (auto& f : food) f = rng.nextFloat() * 12.0f;
    for (auto& f : danger) f = rng.nextFloat() * 6.0f;
    for (auto& f : soc) f = rng.nextFloat() * 8.0f;
    const Grid gF{food.data(), 16, 16, 2.0f}, gD{danger.data(), 16, 16, 2.0f}, gS{soc.data(), 16, 16, 2.0f};
    for (int c = 0; c < 8; ++c) {
        double needs[kNeeds];
        for (double& v : needs) v = rng.nextDouble();
        const int x = static_cast<int>(rng.next() % 32), z = static_cast<int>(rng.next() % 32);
        static constexpr int32_t codes[kNeeds] = {0, 1, 2, 3, 4, 5};
        const auto b = broadcast(perceive(needs, codes, gF.read(static_cast<float>(x), static_cast<float>(z)),
                                         gD.read(static_cast<float>(x), static_cast<float>(z)), (c & 1) != 0));
        for (const auto& s : b) { mix(static_cast<uint64_t>(s.code)); mix(doubleBits(s.salience)); }
        std::vector<CandidateIn> ins;
        for (int a = 0; a < 6; ++a) {
            CandidateIn ci{};
            for (int k = 0; k < kNeeds; ++k) { ci.needs[k] = needs[k]; ci.decay[k] = rng.nextDouble() * 0.002; ci.gains[k] = rng.nextDouble() - 0.3; }
            ci.skillEff = 0.6 + 0.6 * rng.nextDouble(); ci.yieldP = rng.nextDouble(); ci.uncertainty = rng.nextDouble();
            ci.travel = static_cast<int32_t>(rng.next() % 30); ci.duration = static_cast<int32_t>(rng.next() % 60);
            ci.cortisol = rng.nextDouble(); ci.dopamine = rng.nextDouble(); ci.curiosity = rng.nextDouble(); ci.epistemicPolicy = 0.3;
            ci.neural = rng.nextDouble(); ci.socTempC = 30.0 + 20.0 * rng.nextDouble();
            ci.economicBonus = 0.1 * rng.nextDouble(); ci.memQ = rng.nextDouble() - 0.5; ci.novelty = 0.05 * rng.nextDouble();
            ci.oxytocin = rng.nextDouble(); ci.adapterPred = 2.0 * rng.nextDouble() - 1.0; ci.loraPred = 3.0 * rng.nextDouble() - 1.5;
            ci.adapterWeight = 0.05; ci.loraWeight = 0.08;
            ci.targetX = static_cast<int32_t>(rng.next() % 32); ci.targetZ = static_cast<int32_t>(rng.next() % 32);
            ci.actionOrdinal = a; ci.hasTarget = (a % 3) != 0; ci.isSocial = a == 2; ci.isExplore = a == 4; ci.loraActive = (c & 2) != 0;
            ins.push_back(ci);
        }
        std::vector<CandidateOut> outs;
        for (const auto& ci : ins) outs.push_back(evaluate(ci, 0.85, gD, gS));
        const auto order = sortCandidates(outs, ins);
        std::vector<double> efes;
        std::vector<int32_t> ords;
        for (int32_t i : order) { efes.push_back(outs[static_cast<size_t>(i)].efe); ords.push_back(ins[static_cast<size_t>(i)].actionOrdinal); mix(doubleBits(outs[static_cast<size_t>(i)].efe)); }
        mix(static_cast<uint64_t>(selectPolicy(efes, ords, c % 6, 0.05, 6.0, rng.nextDouble())));
        mix(doubleBits(variationalFreeEnergy(needs)));
    }
    // memória
    std::vector<MemoryNode> nodes;
    for (int i = 0; i < 40; ++i) {
        MemoryNode n;
        n.id = i + 1;
        for (int f = 0; f < 5; ++f) n.features.push_back(static_cast<int64_t>(rng.next() % 64));
        n.docTerms = n.features;
        n.tags = {static_cast<int64_t>(rng.next() % 6)};
        n.entities = {static_cast<int64_t>(100 + rng.next() % 4)};
        n.bits = simHash(n.features);
        n.createdTick = static_cast<int64_t>(rng.next() % 1000);
        if (i % 3 == 0) n.accessTicks = {n.createdTick + 5, n.createdTick + 40};
        n.salience = rng.nextDouble(); n.confidence = rng.nextDouble(); n.qUtility = 2.0 * rng.nextDouble() - 1.0;
        n.hawkesExcitation = rng.nextDouble(); n.hawkesLastTick = n.createdTick;
        nodes.push_back(std::move(n));
    }
    MemoryQuery q;
    q.tick = 1500; q.topK = 4;
    q.features = {1, 2, 3, 7, 11};
    q.terms = {1, 2, 3, 7, 11};
    q.tags = {1, 2};
    q.entities = {101};
    const auto rr = retrieve(q, nodes);
    mix(static_cast<uint64_t>(rr.stage1));
    for (size_t i = 0; i < rr.chosen.size(); ++i) { mix(static_cast<uint64_t>(rr.chosen[i])); mix(doubleBits(rr.newExcitation[i])); }
    return static_cast<int64_t>(h);
}

} // namespace girassol::cognition
