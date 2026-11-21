// Testes do WorldTick nativo (ASan/UBSan): mundo sintético de vários dias dirigido apenas pelo
// kernel C++20 — necessidades, envelhecimento, sociedade, IRT 2PL, reprodução por mérito, Heider,
// assembleia e PER — com invariantes a cada tick e determinismo bit a bit entre execuções.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "common/sha256.hpp"
#include "world/world_tick.hpp"

using namespace girassol::world;

namespace {

int gWtFailures = 0;
#define WCHECK(cond, msg)                                                          \
    do {                                                                           \
        if (!(cond)) {                                                             \
            std::fprintf(stderr, "FALHA: %s (%s:%d)\n", msg, __FILE__, __LINE__);  \
            ++gWtFailures;                                                         \
        } else {                                                                   \
            std::printf("ok   %s\n", msg);                                         \
        }                                                                          \
    } while (0)

struct Agent {
    std::string id;
    NeedsRow needs{};
    double traits[kTraits];
    double opinion, intrinsic, mood, valence, food, credit;
    MeritRow merit;
    int parentA = -1, parentB = -1;
    bool alive = true;
    double skill;  // probabilidade real de sucesso (cresce com a prática)
};

struct World {
    uint64_t seed;
    int64_t tick = 0;
    std::vector<Agent> agents;
    std::vector<DailyTie> ties;  // owner/target = índice em agents
    IrtItems items;
    double commonsFood = 400.0;
    int births = 0, deaths = 0, interactions = 0, trades = 0;
    bool invariantsOk = true;
};

std::string idFor(int k) {
    char b[16];
    std::snprintf(b, sizeof b, "c%04d", k + 1);
    return b;
}

World makeWorld(uint64_t seed, int n) {
    World w;
    w.seed = seed;
    girassol::SplitMix64 rng(seed);
    for (int k = 0; k < n; ++k) {
        Agent a;
        a.id = idFor(k);
        for (int j = 0; j < kNeeds; ++j) a.needs.needs[j] = 0.8;
        for (int j = 0; j < kTraits; ++j) a.traits[j] = rng.nextDouble();
        a.needs.metabolismo = a.traits[0]; a.needs.sociabilidade = a.traits[1];
        a.needs.curiosidade = a.traits[2]; a.needs.conscienciosidade = a.traits[4];
        a.opinion = rng.nextDouble() * 2 - 1; a.intrinsic = a.opinion;
        a.mood = 0.2; a.valence = 0.1; a.food = 3 + 3 * rng.nextDouble(); a.credit = 6;
        a.merit.id = a.id; a.merit.birthTick = -static_cast<int64_t>(10 * kTicksPerDay);
        a.merit.sex = 1 + (k % 2);
        // séries Anokhin/Brier já com linha de base e melhora sustentada (o teste exercita o kernel)
        a.merit.acceptorBaseSum = 4.0; a.merit.acceptorBaseN = kMeritBaselineSamples; a.merit.acceptorEma = 0.2; a.merit.acceptorBaselineTick = 10;
        a.merit.brierBaseSum = 2.5; a.merit.brierBaseN = kMeritBaselineSamples; a.merit.brierEma = 0.2; a.merit.brierBaselineTick = 10;
        a.merit.promotions = 1;
        a.skill = 0.25 + 0.1 * rng.nextDouble();
        w.agents.push_back(a);
    }
    return w;
}

void checkInvariants(World& w) {
    for (const auto& a : w.agents) {
        for (double v : a.needs.needs) if (!(v >= 0.0 && v <= 1.0)) w.invariantsOk = false;
        if (!(a.opinion >= -1.0 && a.opinion <= 1.0)) w.invariantsOk = false;
        if (!std::isfinite(a.merit.theta) || a.merit.thetaSe <= 0) w.invariantsOk = false;
    }
    for (const auto& t : w.ties) {
        if (!(t.affinity >= -1.0 && t.affinity <= 1.0 && t.familiarity >= 0.0 && t.familiarity <= 1.0)) w.invariantsOk = false;
        if (t.alphaGen < 1.5 || t.betaGen < 1.5) w.invariantsOk = false;
    }
}

void stepWorld(World& w) {
    w.tick++;
    std::vector<int> alive;
    for (int i = 0; i < static_cast<int>(w.agents.size()); ++i) if (w.agents[i].alive) alive.push_back(i);
    // 1. necessidades
    std::vector<NeedsRow> rows;
    for (int i : alive) rows.push_back(w.agents[i].needs);
    stepNeeds(rows, (w.tick % kTicksPerDay) >= kTicksPerDay * 3 / 4);
    for (size_t k = 0; k < alive.size(); ++k) {
        auto& a = w.agents[alive[k]];
        a.needs = rows[k];
        // ação sintética: comer / descansar repõem; prática de habilidade gera respostas IRT
        girassol::SplitMix64 r(streamSeed(w.seed, a.id, w.tick, "acao"));
        if (a.needs.needs[0] < 0.5 && w.commonsFood > 1) { a.needs.needs[0] = std::min(1.0, a.needs.needs[0] + 0.6); w.commonsFood -= 1; }
        for (int j = 1; j < kNeeds; ++j) if (a.needs.needs[j] < 0.6) a.needs.needs[j] = std::min(1.0, a.needs.needs[j] + 0.3);
        if (w.tick % 20 == 0) {
            const int item = static_cast<int>(r.next() % kIrtItems);
            const bool ok = r.nextDouble() < a.skill;
            a.merit.irtAttempts[item] += 1; if (ok) a.merit.irtSuccesses[item] += 1;
            a.skill = std::min(0.95, a.skill + 0.004);
        }
        double mn = 0; for (double v : a.needs.needs) mn += v;
        a.merit.meanNeed = mn / kNeeds;
    }
    w.commonsFood += 0.6;
    // 2. sociedade
    if (w.tick % 9 == 0) {
        std::vector<SocialRow> srows;
        for (int i : alive) {
            const auto& a = w.agents[i];
            SocialRow s{};
            s.id = a.id; s.group = (i % 3 == 0) ? std::string() : std::string("praca-") + std::to_string(i % 2);
            std::memcpy(s.traits, a.traits, sizeof s.traits);
            s.mood = a.mood; s.valence = a.valence; s.opinion = a.opinion; s.intrinsic = a.intrinsic;
            s.fome = a.needs.needs[0]; s.food = a.food; s.credit = a.credit;
            srows.push_back(s);
        }
        std::vector<SocialTie> sties;
        std::vector<int> local(w.agents.size(), -1);
        for (size_t k = 0; k < alive.size(); ++k) local[alive[k]] = static_cast<int>(k);
        for (const auto& t : w.ties) {
            if (local[t.owner] < 0 || t.target >= static_cast<int>(w.agents.size()) || local[t.target] < 0) continue;
            SocialTie s{};
            s.from = local[t.owner]; s.to = local[t.target];
            s.affinity = t.affinity; s.familiarity = t.familiarity; s.alphaGen = t.alphaGen; s.betaGen = t.betaGen;
            sties.push_back(s);
        }
        auto res = stepSocial(w.seed, w.tick, srows, sties);
        for (const auto& it : res) {
            const int a = alive[it.a], b = alive[it.b];
            DailyTie* tie = nullptr;
            for (auto& t : w.ties) if (t.owner == a && t.target == b) tie = &t;
            if (!tie) { w.ties.push_back(DailyTie{a, b, 0, 0.05, 1.5, 1.5}); tie = &w.ties.back(); }
            tie->affinity = it.affinity; tie->familiarity = it.familiarity; tie->alphaGen = it.alphaGen; tie->betaGen = it.betaGen;
            w.agents[a].opinion = it.aOpinion;
            if (it.trade) w.trades++;
            w.interactions++;
        }
        for (size_t k = 0; k < alive.size(); ++k) { w.agents[alive[k]].food = srows[k].food; w.agents[alive[k]].credit = srows[k].credit; }
    }
    // 3. envelhecimento
    std::vector<LifeRow> life;
    for (int i : alive) {
        const auto& a = w.agents[i];
        LifeRow l; l.id = a.id; l.starvation = a.needs.starvation; l.birthTick = a.merit.birthTick; l.longevidade = a.traits[5];
        life.push_back(l);
    }
    std::vector<int> local(w.agents.size(), -1);
    for (size_t k = 0; k < alive.size(); ++k) local[alive[k]] = static_cast<int>(k);
    for (size_t k = 0; k < alive.size(); ++k) {
        const auto& a = w.agents[alive[k]];
        life[k].parentA = a.parentA >= 0 ? local[a.parentA] : -1;
        life[k].parentB = a.parentB >= 0 ? local[a.parentB] : -1;
    }
    w.deaths += stepLifecycle(life, w.tick);
    for (size_t k = 0; k < alive.size(); ++k) if (!life[k].alive) w.agents[alive[k]].alive = false;
    // 4. dia
    if (w.tick % kTicksPerDay == 0) {
        std::vector<int> al;
        for (int i = 0; i < static_cast<int>(w.agents.size()); ++i) if (w.agents[i].alive) al.push_back(i);
        std::vector<int> loc(w.agents.size(), -1);
        std::vector<MeritRow> rows2;
        for (size_t k = 0; k < al.size(); ++k) { loc[al[k]] = static_cast<int>(k); rows2.push_back(w.agents[al[k]].merit); rows2.back().opinion = w.agents[al[k]].opinion; }
        DailyInput in;
        in.worldSeed = w.seed; in.tick = w.tick; in.housing = 40; in.admissionCap = 40; in.commonsFood = w.commonsFood;
        std::vector<DailyTie> dt;
        std::vector<int> map;
        for (size_t t = 0; t < w.ties.size(); ++t) {
            const auto& x = w.ties[t];
            if (loc[x.owner] < 0) continue;
            int target = loc[x.target];
            if (target < 0) { in.extraIds.push_back(w.agents[x.target].id); target = static_cast<int>(al.size() + in.extraIds.size() - 1); }
            dt.push_back(DailyTie{loc[x.owner], target, x.affinity, x.familiarity, x.alphaGen, x.betaGen});
            map.push_back(static_cast<int>(t));
        }
        auto out = stepDaily(in, rows2, dt, w.items);
        for (size_t k = 0; k < map.size(); ++k) { w.ties[map[k]].affinity = dt[k].affinity; w.ties[map[k]].familiarity = dt[k].familiarity; }
        for (size_t k = 0; k < al.size(); ++k) w.agents[al[k]].merit = rows2[k];
        for (const auto& b : out.births) {
            const int pa = al[b.a], pb = al[b.b];
            Agent child = w.agents[pa];
            girassol::SplitMix64 r(b.seed);
            child.id = idFor(static_cast<int>(w.agents.size()));
            child.merit = MeritRow{};
            child.merit.id = child.id; child.merit.birthTick = w.tick; child.merit.generation = std::max(w.agents[pa].merit.generation, w.agents[pb].merit.generation) + 1;
            child.merit.sex = 1 + static_cast<int>(r.next() % 2);
            for (int j = 0; j < kTraits; ++j) child.traits[j] = 0.5 * (w.agents[pa].traits[j] + w.agents[pb].traits[j]);
            child.parentA = pa; child.parentB = pb;
            for (double& v : child.needs.needs) v = 0.9;
            child.needs.starvation = 0;
            w.agents.push_back(child);
            w.ties.push_back(DailyTie{static_cast<int>(w.agents.size()) - 1, pa, 0.6, 0.6, 1.5, 1.5});
            w.births++;
        }
        // a próxima geração precisa de nova promoção
        for (auto& a : w.agents) if (a.alive && a.merit.promotions <= a.merit.promotionsAtLastReproduction && (w.tick / kTicksPerDay) % 2 == 0) a.merit.promotions++;
    }
    checkInvariants(w);
}

uint64_t worldHash(const World& w) {
    girassol::Sha256 md;
    for (const auto& a : w.agents) {
        md.update(a.id);
        for (double v : a.needs.needs) { uint64_t b; std::memcpy(&b, &v, 8); md.updateBe64(b); }
        uint64_t b; std::memcpy(&b, &a.opinion, 8); md.updateBe64(b);
        std::memcpy(&b, &a.merit.theta, 8); md.updateBe64(b);
        md.updateBe64(static_cast<uint64_t>(a.merit.children));
        md.updateU8(a.alive ? 1 : 0);
    }
    for (const auto& t : w.ties) { uint64_t b; std::memcpy(&b, &t.affinity, 8); md.updateBe64(b); }
    const auto d = md.digest();
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | d[i];
    return v;
}

}  // namespace

int runWorldTickTests() {
    {
        girassol::Sha256 md;
        md.update(std::string_view("abc"));
        const auto d = md.digest();
        WCHECK(d[0] == 0xba && d[1] == 0x78 && d[2] == 0x16 && d[3] == 0xbf && d[31] == 0xad, "SHA-256(\"abc\") = ba7816bf…15ad (FIPS 180-4)");
        girassol::Sha256 m2;
        std::string big(1000, 'a');
        m2.update(big);
        const auto e = m2.digest();
        // sha256("a"×1000) = 41edece4…
        WCHECK(e[0] == 0x41 && e[1] == 0xed && e[2] == 0xec && e[3] == 0xe4, "SHA-256 multi-bloco");
    }
    {
        auto r1 = streamRng(42, "c0001", 9, "social");
        auto r2 = streamRng(42, "c0001", 9, "social");
        auto r3 = streamRng(42, "c0002", 9, "social");
        const uint64_t a = r1.next(), b = r2.next(), c = r3.next();
        WCHECK(a == b && a != c, "sementes por fluxo determinísticas e independentes por agente");
    }
    {
        // IRT 2PL: o respondente mais hábil recebe θ maior; EP diminui com mais respostas
        std::vector<MeritRow> rows(3);
        for (int i = 0; i < 3; ++i) {
            rows[i].id = idFor(i);
            for (int j = 0; j < kIrtItems; ++j) { rows[i].irtAttempts[j] = 10 * (i + 1); rows[i].irtSuccesses[j] = rows[i].irtAttempts[j] * (0.2 + 0.3 * i); }
        }
        IrtItems it;
        calibrateIrt(rows, it);
        WCHECK(rows[0].theta < rows[1].theta && rows[1].theta < rows[2].theta, "IRT 2PL ordena habilidades latentes");
        WCHECK(rows[2].thetaSe < 1.0 && rows[0].thetaSe > 0.0, "IRT 2PL erro-padrão finito");
        for (int j = 0; j < kIrtItems; ++j) WCHECK(it.a[j] >= 0.2 && it.a[j] <= 4.0 && std::isfinite(it.b[j]), "IRT 2PL parâmetros de item limitados");
    }
    {
        // PER: frequência de amostragem ∝ p^α; pesos IS ≤ 1 e o mais raro com peso máximo
        std::vector<double> pr = {0.01, 0.01, 4.0, 0.5, 0.01, 0.01, 0.01, 0.01};
        int hits[8] = {0};
        for (int s = 0; s < 200; ++s) {
            auto b = perSample(pr, 8, kPerAlpha, 1.0, 1000 + s);
            for (size_t k = 0; k < b.idx.size(); ++k) {
                hits[b.idx[k]]++;
                if (!(b.weights[k] > 0.0 && b.weights[k] <= 1.0)) ++gWtFailures;
            }
        }
        WCHECK(hits[2] > hits[3] && hits[3] > hits[0], "PER prioriza experiências com maior erro TD");
        WCHECK(perBeta(0) == 0.4 && perBeta(1000000) == 1.0, "PER β anelado 0,4 → 1,0");
        WCHECK(perPriority(-0.5) > 0.5, "PER prioridade |δ| + ε");
    }
    {
        // Heider + assembleia isolados
        std::vector<MeritRow> rows(3);
        for (int i = 0; i < 3; ++i) { rows[i].id = idFor(i); rows[i].opinion = 0.5; }
        std::vector<DailyTie> ties = {{0, 1, 0.8, 0.5, 1.5, 1.5}, {1, 2, 0.8, 0.5, 1.5, 1.5}, {0, 2, -0.5, 0.5, 1.5, 1.5}};
        DailyInput in; in.tick = kTicksPerDay; in.housing = 0; in.admissionCap = 0;
        IrtItems it;
        auto out = stepDaily(in, rows, ties, it);
        WCHECK(out.heiderNudges >= 1 && ties[2].affinity > -0.5, "equilíbrio estrutural de Heider empurra a tríade desequilibrada");
        WCHECK(out.newQuota == 5, "assembleia de Ostrom: cota = 2 + round(2(ō+1))");
        WCHECK(std::fabs(ties[0].familiarity - 0.5 * std::exp(-0.0004 * 480)) < 1e-12, "decaimento diário de familiaridade");
    }
    // ---------------- mundo completo de 12 dias, duas execuções
    uint64_t h1 = 0, h2 = 0;
    World last;
    for (int run = 0; run < 2; ++run) {
        World w = makeWorld(0xC0FFEEULL, 10);
        for (int t = 0; t < 12 * kTicksPerDay; ++t) stepWorld(w);
        (run == 0 ? h1 : h2) = worldHash(w);
        last = w;
    }
    std::printf("worldtick: nascimentos=%d mortes=%d encontros=%d comercio=%d pop=%zu hash=%016llx\n", last.births, last.deaths,
                last.interactions, last.trades, last.agents.size(), static_cast<unsigned long long>(h1));
    WCHECK(h1 == h2, "WorldTick nativo determinístico (12 dias, hash idêntico)");
    WCHECK(last.invariantsOk, "invariantes a cada tick (necessidades, opinião, laços, θ)");
    WCHECK(last.interactions > 50, "atualização social ativa");
    WCHECK(last.births >= 1, "reprodução por mérito (Anokhin + Brier + promoção + bem-estar + IRT) gera g1");
    bool thetaGate = true;
    for (const auto& a : last.agents) if (a.merit.children > 0 && a.merit.theta0Tick < 0) thetaGate = false;
    WCHECK(thetaGate, "linha de base θ0 registrada e reiniciada após a reprodução");
    int gen1 = 0;
    for (const auto& a : last.agents) if (a.merit.generation >= 1) ++gen1;
    WCHECK(gen1 == last.births, "geração g1 contabilizada");
    {
        const Eta e = estimate([&] { std::vector<MeritRow> v; for (auto& a : last.agents) if (a.alive) v.push_back(a.merit); return v; }(), last.commonsFood, last.tick);
        WCHECK(e.status >= kPrecisaPar && e.status <= kAguardando, "ETA da próxima geração calculado pelo kernel");
    }
    return gWtFailures;
}
