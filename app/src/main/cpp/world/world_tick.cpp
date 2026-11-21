#include "world_tick.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <map>
#include <numeric>

#include "../common/sha256.hpp"

namespace girassol::world {
namespace {

constexpr double kBaseDecay[kNeeds] = {0.0016, 0.0011, 0.0010, 0.0008, 0.0007, 0.0007};
enum { FOME = 0, ENERGIA = 1, SOCIAL = 2, DIVERSAO = 3, HIGIENE = 4, PROPOSITO = 5 };
enum { T_METAB = 0, T_SOCIAB = 1, T_CURIOS = 2, T_APTID = 3, T_CONSC = 4, T_LONGEV = 5, T_AMAB = 6 };

inline double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline double trustGeneral(double a, double b) { return a / (a + b); }

// Ordem de ids idêntica a String.compareTo da JVM para ids ASCII (ids do mundo são "c0001", "m…").
inline bool idLess(const std::string& a, const std::string& b) { return a < b; }

}  // namespace

// ============================================================ sementes por fluxo
uint64_t streamSeed(uint64_t worldSeed, std::string_view agentId, int64_t tick, std::string_view stream) {
    Sha256 md;
    md.updateBe64(worldSeed);
    md.update(agentId);
    md.updateU8(0);
    md.updateBe64(static_cast<uint64_t>(tick));
    md.update(stream);
    const auto d = md.digest();
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | d[i];
    return v;
}

// ============================================================ 1. necessidades
double needDecay(const NeedsRow& r, int need) {
    const double base = kBaseDecay[need];
    switch (need) {
        case FOME: return base * (0.7 + 0.6 * r.metabolismo);
        case SOCIAL: return base * (0.5 + r.sociabilidade);
        case PROPOSITO: return base * (0.6 + 0.8 * r.conscienciosidade);
        case DIVERSAO: return base * (0.6 + 0.8 * r.curiosidade);
        default: return base;
    }
}

void stepNeeds(std::span<NeedsRow> rows, bool night) {
    for (auto& r : rows) {
        for (int n = 0; n < kNeeds; ++n) {
            double d = needDecay(r, n);
            if (n == ENERGIA && night && !r.resting) d *= 1.4;
            r.needs[n] = clamp(r.needs[n] - d, 0.0, 1.0);
        }
        if (r.needs[FOME] <= 0.0) {
            r.starvation++;
            r.starving = true;
        } else {
            r.starvation = 0;
            r.starving = false;
        }
    }
}

// ============================================================ 2. envelhecimento
double lifespanDays(double longevidade) { return 40.0 + 30.0 * longevidade; }

int stepLifecycle(std::vector<LifeRow>& rows, int64_t tick) {
    std::vector<int> order(rows.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](int x, int y) { return idLess(rows[x].id, rows[y].id); });
    int deaths = 0;
    for (int i : order) {
        auto& c = rows[i];
        if (!c.alive) continue;
        const double age = static_cast<double>(tick - c.birthTick) / static_cast<double>(kTicksPerDay);
        int cause = kAlive;
        if (c.starvation > kTicksPerDay) cause = kStarvation;
        else if (age > lifespanDays(c.longevidade)) cause = kOldAge;
        if (cause == kAlive) continue;
        c.alive = false;
        c.cause = cause;
        // herdeiro: filho vivo de menor id (avaliado após esta morte, como no mundo sequencial)
        int heir = -1;
        for (int j = 0; j < static_cast<int>(rows.size()); ++j) {
            const auto& k = rows[j];
            if (!k.alive || (k.parentA != i && k.parentB != i)) continue;
            if (heir < 0 || idLess(k.id, rows[heir].id)) heir = j;
        }
        c.heir = heir;
        ++deaths;
    }
    return deaths;
}

// ============================================================ 3. sociedade
double tieStrength(double affinity, double familiarity, double alphaGen, double betaGen) {
    return clamp(0.40 * std::fabs(affinity) + 0.35 * familiarity + 0.25 * trustGeneral(alphaGen, betaGen), 0.0, 1.0);
}

double stepHybridOpinion(SocialTie& tie, double peerOpinion) {
    const double diff = peerOpinion - tie.currentOpinion;
    const double dw = std::fabs(diff) < 0.45
        ? tie.currentOpinion + 0.25 * trustGeneral(tie.alphaGen, tie.betaGen) * diff
        : tie.currentOpinion;
    tie.currentOpinion = clamp(tie.susceptibility * dw + (1.0 - tie.susceptibility) * tie.intrinsic, -1.0, 1.0);
    return tie.currentOpinion;
}

std::vector<Interaction> stepSocial(uint64_t worldSeed, int64_t tick, std::vector<SocialRow>& rows, std::vector<SocialTie>& ties) {
    std::vector<Interaction> out;
    if (tick % 9 != 0) return out;
    // grupos por objeto-alvo; chaves e membros em ordem lexicográfica
    std::map<std::string, std::vector<int>> groups;
    for (int i = 0; i < static_cast<int>(rows.size()); ++i) groups[rows[i].group].push_back(i);
    std::map<std::pair<int, int>, int> tieIdx;
    for (int t = 0; t < static_cast<int>(ties.size()); ++t) tieIdx[{ties[t].from, ties[t].to}] = t;
    for (auto& [key, members] : groups) {
        if (members.size() < 2) continue;
        std::stable_sort(members.begin(), members.end(), [&](int x, int y) { return idLess(rows[x].id, rows[y].id); });
        for (int a : members) {
            auto rng = streamRng(worldSeed, rows[a].id, tick, "social");
            std::vector<int> others;
            others.reserve(members.size() - 1);
            for (int m : members) if (m != a) others.push_back(m);
            const int b = others[nextInt(rng, static_cast<int>(others.size()))];
            Interaction it;
            it.a = a; it.b = b;
            it.greetingSeed = rng.next();
            auto& A = rows[a];
            auto& B = rows[b];
            int ti;
            if (auto f = tieIdx.find({a, b}); f != tieIdx.end()) {
                ti = f->second;
            } else {
                SocialTie nt{};
                nt.from = a; nt.to = b;
                nt.intrinsic = clamp(A.intrinsic, -1.0, 1.0);
                ties.push_back(nt);
                ti = static_cast<int>(ties.size()) - 1;
                tieIdx[{a, b}] = ti;
                it.tieCreated = true;
            }
            SocialTie& tie = ties[ti];
            double dist = 0.0;
            for (int k = 0; k < kTraits; ++k) dist += std::fabs(A.traits[k] - B.traits[k]);
            const double similarity = 1.0 - dist / kTraits;
            tie.familiarity = std::min(1.0, tie.familiarity + 0.06);
            tie.affinity = clamp(tie.affinity + 0.05 * (similarity - 0.45) + 0.03 * B.mood + 0.02 * A.traits[T_AMAB], -1.0, 1.0);
            it.trustOk = B.valence > -0.3;
            if (it.trustOk) tie.alphaGen += 1.0; else tie.betaGen += 2.2;
            tie.currentOpinion = A.opinion;
            A.opinion = stepHybridOpinion(tie, B.opinion);
            it.claimRaw = rng.next();
            if (A.fome < 0.45 && A.food < 1.0 && B.food > 4.0 && A.credit >= 3.0) {
                it.trade = true;
                B.food -= 2.0; A.food += 2.0;
                A.credit -= 3.0; B.credit += 3.0;
            }
            it.affinity = tie.affinity; it.familiarity = tie.familiarity;
            it.alphaGen = tie.alphaGen; it.betaGen = tie.betaGen;
            it.currentOpinion = tie.currentOpinion; it.aOpinion = A.opinion;
            out.push_back(it);
        }
    }
    return out;
}

// ============================================================ mérito
double ageDays(const MeritRow& r, int64_t tick) {
    return static_cast<double>(tick - r.birthTick) / static_cast<double>(kTicksPerDay);
}

double irtResponses(const MeritRow& r) {
    double n = 0;
    for (double v : r.irtAttempts) n += v;
    return n;
}

static double baseline(double sum, int n) { return n >= kMeritBaselineSamples ? sum / n : -1.0; }

uint32_t meritCheck(const MeritRow& c, int64_t tick) {
    uint32_t ok = 0;
    const double ab = baseline(c.acceptorBaseSum, c.acceptorBaseN);
    const double bb = baseline(c.brierBaseSum, c.brierBaseN);
    if (ageDays(c, tick) >= kAdultDays) ok |= kAdult;
    if (ab >= 0 && c.acceptorEma >= 0 && (c.acceptorEma <= kAnokhinRatio * ab || (ab < 1e-6 && c.acceptorEma < 1e-6))) ok |= kAnokhin;
    if (bb >= 0 && c.brierEma >= 0 && c.brierEma <= bb - kBrierMargin) ok |= kBrier;
    if (c.promotions > c.promotionsAtLastReproduction) ok |= kPromotion;
    if (c.meanNeed >= kWellbeingMin) ok |= kWellbeing;
    // IRT 2PL: habilidade latente θ acima da própria linha de base θ0 (evolução real, não sorte)
    if (c.theta0 > kNoTheta / 2 && c.theta >= c.theta0 + kIrtMargin) ok |= kIrt;
    return ok;
}

static int64_t seriesEta(int n, double base, double ema, int64_t baselineTick, double target, int64_t tick) {
    if (n < kMeritBaselineSamples || baselineTick < 0) return -1;
    const int64_t elapsed = std::max<int64_t>(1, tick - baselineTick);
    const double rate = (base - ema) / static_cast<double>(elapsed);
    if (rate <= 1e-9) return -1;
    return static_cast<int64_t>(std::ceil(std::max(0.0, ema - target) / rate));
}

int64_t citizenEtaTicks(const MeritRow& c, int64_t tick, uint32_t check) {
    int64_t worst = 0;
    auto take = [&](int64_t t) {
        if (worst < 0) return;
        worst = t < 0 ? -1 : std::max(worst, t);
    };
    if (!(check & kAdult)) take(static_cast<int64_t>(std::ceil((kAdultDays - ageDays(c, tick)) * kTicksPerDay)));
    const double ab = baseline(c.acceptorBaseSum, c.acceptorBaseN);
    const double bb = baseline(c.brierBaseSum, c.brierBaseN);
    if (!(check & kAnokhin)) take(seriesEta(c.acceptorBaseN, ab, c.acceptorEma, c.acceptorBaselineTick, kAnokhinRatio * ab, tick));
    if (!(check & kBrier)) take(seriesEta(c.brierBaseN, bb, c.brierEma, c.brierBaselineTick, bb - kBrierMargin, tick));
    if (!(check & kPromotion)) take(kTicksPerDay - (tick % kTicksPerDay));
    if (!(check & kWellbeing)) take(-1);
    if (!(check & kIrt)) {
        if (c.theta0 <= kNoTheta / 2 || c.theta0Tick < 0) {
            take(-1);
        } else {
            const int64_t elapsed = std::max<int64_t>(1, tick - c.theta0Tick);
            const double rate = (c.theta - c.theta0) / static_cast<double>(elapsed);
            if (rate <= 1e-9) take(-1);
            else {
                // a calibração roda na virada do dia: arredonda para o próximo dia
                const int64_t raw = static_cast<int64_t>(std::ceil((c.theta0 + kIrtMargin - c.theta) / rate));
                take(((raw + kTicksPerDay - 1) / kTicksPerDay) * kTicksPerDay);
            }
        }
    }
    return worst;
}

bool compatible(const MeritRow& a, const MeritRow& b) { return a.sex == 0 || b.sex == 0 || a.sex != b.sex; }

int64_t reproductionCooldown(const MeritRow& r) { return r.superacao ? kTicksPerDay * 4 : kTicksPerDay * 6; }

static double etaRank(const Eta& e) {
    if (e.status == kPronto) return -1.0;
    if (e.ticks >= 0) return static_cast<double>(e.ticks);
    return 1e12 + std::popcount(e.blockers);
}

Eta estimate(const std::vector<MeritRow>& alive, double commonsFood, int64_t tick) {
    Eta none;
    if (alive.empty()) return none;
    std::vector<int> sorted(alive.size());
    std::iota(sorted.begin(), sorted.end(), 0);
    std::stable_sort(sorted.begin(), sorted.end(), [&](int x, int y) { return idLess(alive[x].id, alive[y].id); });
    if (alive.size() < 2) {
        Eta e; e.status = kPrecisaPar; e.a = 0; e.nextGeneration = alive[0].generation + 1; return e;
    }
    std::vector<uint32_t> checks(alive.size());
    std::vector<int64_t> etas(alive.size());
    for (size_t i = 0; i < alive.size(); ++i) {
        checks[i] = meritCheck(alive[i], tick);
        etas[i] = citizenEtaTicks(alive[i], tick, checks[i]);
    }
    const bool surplus = commonsFood >= static_cast<double>(alive.size()) * kCommonsFoodPerCapita;
    bool have = false;
    Eta best;
    for (size_t i = 0; i < sorted.size(); ++i) for (size_t j = i + 1; j < sorted.size(); ++j) {
        const int a = sorted[i], b = sorted[j];
        const auto& A = alive[a];
        const auto& B = alive[b];
        if (!compatible(A, B) || A.children >= 4 || B.children >= 4) continue;
        const int64_t ticks = (etas[a] < 0 || etas[b] < 0) ? -1 : std::max(etas[a], etas[b]);
        Eta cand;
        cand.blockers = (~checks[a] & kAllCitizenBits) | (~checks[b] & kAllCitizenBits) | (surplus ? 0u : kCommons);
        const bool ready = (checks[a] & kAllCitizenBits) == kAllCitizenBits && (checks[b] & kAllCitizenBits) == kAllCitizenBits && surplus;
        cand.status = ready ? kPronto : kAguardando;
        cand.ticks = ready ? 0 : ticks;
        cand.a = a; cand.b = b;
        cand.nextGeneration = std::max(A.generation, B.generation) + 1;
        if (!have || etaRank(cand) < etaRank(best)) { best = cand; have = true; }
    }
    if (!have) {
        Eta e; e.status = kPrecisaPar; e.a = sorted[0]; e.nextGeneration = alive[sorted[0]].generation + 1; return e;
    }
    return best;
}

// ============================================================ IRT 2PL
double irtProbability(double theta, double a, double b) {
    const double z = clamp(a * (theta - b), -30.0, 30.0);
    return 1.0 / (1.0 + std::exp(-z));
}

void calibrateIrt(std::vector<MeritRow>& rows, IrtItems& items, int iterations) {
    constexpr double kVarTheta = 1.0, kVarB = 2.25, kVarLogA = 0.25;
    double logA[kIrtItems];
    for (int j = 0; j < kIrtItems; ++j) logA[j] = std::log(clamp(items.a[j], 0.2, 4.0));
    for (int it = 0; it < iterations; ++it) {
        // passo θ (cada cidadão, itens fixos)
        for (auto& r : rows) {
            double g = -r.theta / kVarTheta, h = -1.0 / kVarTheta;
            for (int j = 0; j < kIrtItems; ++j) {
                const double n = r.irtAttempts[j];
                if (n <= 0) continue;
                const double a = std::exp(logA[j]);
                const double p = irtProbability(r.theta, a, items.b[j]);
                g += a * (r.irtSuccesses[j] - n * p);
                h -= a * a * n * p * (1.0 - p);
            }
            r.theta = clamp(r.theta - clamp(g / h, -1.0, 1.0), -4.0, 4.0);
        }
        // passo itens (b e log a; habilidades fixas)
        for (int j = 0; j < kIrtItems; ++j) {
            const double a = std::exp(logA[j]);
            double gb = -items.b[j] / kVarB, hb = -1.0 / kVarB;
            double ga = -logA[j] / kVarLogA, ha = -1.0 / kVarLogA;
            for (const auto& r : rows) {
                const double n = r.irtAttempts[j];
                if (n <= 0) continue;
                const double p = irtProbability(r.theta, a, items.b[j]);
                const double resid = r.irtSuccesses[j] - n * p;
                const double w = n * p * (1.0 - p);
                gb += -a * resid;
                hb -= a * a * w;
                const double d = r.theta - items.b[j];
                ga += a * d * resid;
                ha -= a * a * d * d * w;
            }
            items.b[j] = clamp(items.b[j] - clamp(gb / hb, -1.0, 1.0), -4.0, 4.0);
            logA[j] = clamp(logA[j] - clamp(ga / ha, -0.5, 0.5), std::log(0.2), std::log(4.0));
        }
    }
    for (int j = 0; j < kIrtItems; ++j) items.a[j] = std::exp(logA[j]);
    for (auto& r : rows) {
        double info = 1.0 / kVarTheta;
        for (int j = 0; j < kIrtItems; ++j) {
            const double n = r.irtAttempts[j];
            if (n <= 0) continue;
            const double p = irtProbability(r.theta, items.a[j], items.b[j]);
            info += items.a[j] * items.a[j] * n * p * (1.0 - p);
        }
        r.thetaSe = 1.0 / std::sqrt(info);
    }
}

// ============================================================ 4. ciclo diário
DailyOutput stepDaily(const DailyInput& in, std::vector<MeritRow>& alive, std::vector<DailyTie>& ties, IrtItems& items) {
    DailyOutput out;
    const int64_t tick = in.tick;
    const int n = static_cast<int>(alive.size());
    auto idOf = [&](int idx) -> const std::string& {
        return idx < n ? alive[idx].id : in.extraIds[idx - n];
    };

    // 4.1 IRT 2PL: calibra θ de cada cidadão e os itens (a, b) da sociedade; janela decai 0,85/dia
    calibrateIrt(alive, items);
    for (auto& r : alive) {
        if (r.theta0 <= kNoTheta / 2 && irtResponses(r) >= kIrtMinResponses) { r.theta0 = r.theta; r.theta0Tick = tick; }
        for (int j = 0; j < kIrtItems; ++j) { r.irtAttempts[j] *= kIrtWindowDecay; r.irtSuccesses[j] *= kIrtWindowDecay; }
    }

    // laços por dono na ordem sortedTies() (alvo por id)
    std::vector<std::vector<int>> byOwner(n);
    for (int t = 0; t < static_cast<int>(ties.size()); ++t) if (ties[t].owner >= 0 && ties[t].owner < n) byOwner[ties[t].owner].push_back(t);
    for (auto& v : byOwner) std::stable_sort(v.begin(), v.end(), [&](int x, int y) { return idLess(idOf(ties[x].target), idOf(ties[y].target)); });
    auto strength = [&](int t) { return tieStrength(ties[t].affinity, ties[t].familiarity, ties[t].alphaGen, ties[t].betaGen); };

    // 4.2 reprodução por mérito (ambos os pais) com superávit nos comuns
    int population = n;
    for (int a = 0; a < n; ++a) {
        auto& A = alive[a];
        if (population >= in.admissionCap || population >= in.housing) break;
        if (ageDays(A, tick) < kAdultDays || A.children >= 4) continue;
        if (tick - A.lastReproductionTick < reproductionCooldown(A)) continue;
        if ((meritCheck(A, tick) & kAllCitizenBits) != kAllCitizenBits) continue;
        if (in.commonsFood < static_cast<double>(population) * kCommonsFoodPerCapita) break;
        std::vector<int> cands;
        for (int t : byOwner[a]) if (ties[t].affinity > 0.0) cands.push_back(t);
        std::stable_sort(cands.begin(), cands.end(), [&](int x, int y) { return strength(x) > strength(y); });
        int partner = -1;
        for (int t : cands) {
            const int p = ties[t].target;
            if (p >= n) continue;
            const auto& P = alive[p];
            if (compatible(A, P) && P.children < 4 && tick - P.lastReproductionTick >= reproductionCooldown(P) &&
                (meritCheck(P, tick) & kAllCitizenBits) == kAllCitizenBits) { partner = p; break; }
        }
        if (partner < 0) continue;
        auto& B = alive[partner];
        out.births.push_back({a, partner, streamSeed(in.worldSeed, A.id + "+" + B.id, tick, "reproducao")});
        for (MeritRow* p : {&A, &B}) {
            p->children++;
            p->lastReproductionTick = tick;
            p->promotionsAtLastReproduction = p->promotions;
            // a próxima geração exige nova evolução sobre a habilidade atual
            p->theta0 = p->theta;
            p->theta0Tick = tick;
        }
        population++;
    }

    // 4.3 Heider nas tríades dos 4 laços mais fortes (somente cidadãos vivos)
    std::vector<std::map<int, int>> lookup(n);
    for (int o = 0; o < n; ++o) for (int t : byOwner[o]) lookup[o][ties[t].target] = t;
    for (int a = 0; a < n; ++a) {
        std::vector<int> top = byOwner[a];
        std::stable_sort(top.begin(), top.end(), [&](int x, int y) { return strength(x) > strength(y); });
        if (top.size() > 4) top.resize(4);
        for (size_t i = 0; i < top.size(); ++i) for (size_t j = i + 1; j < top.size(); ++j) {
            const int b = ties[top[i]].target;
            if (b >= n) continue;
            auto f = lookup[b].find(ties[top[j]].target);
            if (f == lookup[b].end()) continue;
            const double ij = ties[top[i]].affinity, jk = ties[f->second].affinity;
            auto& ik = ties[top[j]].affinity;
            if (ij * jk * ik < 0.0) {
                ik = clamp(ik + (ij * jk > 0.0 ? 0.03 : -0.03), -1.0, 1.0);
                out.heiderNudges++;
            }
        }
        for (int t : byOwner[a]) ties[t].familiarity *= std::exp(-0.0004 * static_cast<double>(kTicksPerDay));
    }

    // 4.4 assembleia de Ostrom
    if (n > 0) {
        double s = 0;
        for (const auto& r : alive) s += r.opinion;
        out.meanOpinion = s / n;
        const double q = std::floor(2.0 * (out.meanOpinion + 1.0) + 0.5);  // Math.round
        out.newQuota = static_cast<int32_t>(clamp(2.0 + q, 2.0, 6.0));
    }
    return out;
}

// ============================================================ 5. PER
double perPriority(double tdError) { return std::fabs(tdError) + kPerEps; }

double perBeta(int64_t replays) {
    return std::min(1.0, 0.4 + 0.6 * static_cast<double>(std::max<int64_t>(0, replays)) / 2000.0);
}

PerBatch perSample(std::span<const double> priorities, int batch, double alpha, double beta, uint64_t seed) {
    PerBatch out;
    const int n = static_cast<int>(priorities.size());
    if (n == 0 || batch <= 0) return out;
    int cap = 1;
    while (cap < n) cap <<= 1;
    std::vector<double> tree(2 * cap, 0.0);  // sum-tree: folhas em [cap, cap+n)
    for (int i = 0; i < n; ++i) tree[cap + i] = std::pow(std::max(priorities[i], kPerEps), alpha);
    for (int i = cap - 1; i >= 1; --i) tree[i] = tree[2 * i] + tree[2 * i + 1];
    const double total = tree[1];
    SplitMix64 rng(seed);
    double maxW = 0.0;
    for (int k = 0; k < batch; ++k) {
        double u = (static_cast<double>(k) + rng.nextDouble()) / batch * total;
        int node = 1;
        while (node < cap) {
            if (u < tree[2 * node] || tree[2 * node + 1] <= 0.0) node = 2 * node;
            else { u -= tree[2 * node]; node = 2 * node + 1; }
        }
        int leaf = std::min(node - cap, n - 1);
        const double p = tree[cap + leaf] / total;
        const double w = std::pow(static_cast<double>(n) * p, -beta);
        out.idx.push_back(leaf);
        out.weights.push_back(w);
        maxW = std::max(maxW, w);
    }
    for (auto& w : out.weights) w /= maxW;
    return out;
}

// ============================================================ assinatura sintética
uint64_t paritySignature() {
    uint64_t h = 1469598103934665603ULL;
    auto mixD = [&](double v) {
        uint64_t b;
        std::memcpy(&b, &v, 8);
        for (int i = 0; i < 8; ++i) { h ^= (b >> (8 * i)) & 0xFF; h *= 1099511628211ULL; }
    };
    auto mixU = [&](uint64_t v) { for (int i = 0; i < 8; ++i) { h ^= (v >> (8 * i)) & 0xFF; h *= 1099511628211ULL; } };
    mixU(streamSeed(42, "c0001", 7, "social"));
    NeedsRow nr{};
    for (int k = 0; k < kNeeds; ++k) nr.needs[k] = 0.5 + 0.05 * k;
    nr.metabolismo = 0.3; nr.sociabilidade = 0.7; nr.conscienciosidade = 0.4; nr.curiosidade = 0.6;
    std::vector<NeedsRow> rows(3, nr);
    for (int t = 0; t < 50; ++t) stepNeeds(rows, t % 2 == 0);
    for (const auto& r : rows) for (double v : r.needs) mixD(v);
    std::vector<MeritRow> m(4);
    for (int i = 0; i < 4; ++i) {
        m[i].id = "c000" + std::to_string(i + 1);
        for (int j = 0; j < kIrtItems; ++j) { m[i].irtAttempts[j] = 3 + i + j; m[i].irtSuccesses[j] = (1 + i) * 0.5 + j * 0.3; }
    }
    IrtItems items;
    calibrateIrt(m, items);
    for (const auto& r : m) { mixD(r.theta); mixD(r.thetaSe); }
    for (int j = 0; j < kIrtItems; ++j) { mixD(items.a[j]); mixD(items.b[j]); }
    const double pr[5] = {0.1, 2.0, 0.5, 0.01, 1.0};
    auto b = perSample(pr, 4, kPerAlpha, 0.4, 7);
    for (size_t k = 0; k < b.idx.size(); ++k) { mixU(static_cast<uint64_t>(b.idx[k])); mixD(b.weights[k]); }
    return h;
}

}  // namespace girassol::world
