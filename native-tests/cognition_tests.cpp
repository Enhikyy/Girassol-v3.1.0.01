// Testes do núcleo cognitivo C++20 (v3.8) sob ASan + UBSan: percepção, espaço de trabalho,
// recuperação de memória em 2 estágios, inferência ativa (G), política com histerese, F,
// conservação soma-zero do ledger, cadeia SHA-256 e conversão UTF-16 ↔ UTF-8 da ponte JNI.
// O ciclo completo roda 400 ticks sintéticos duas vezes e exige saída idêntica bit a bit.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "common/jni_utf_core.hpp"
#include "common/sha256.hpp"
#include "common/splitmix64.hpp"
#include "memory/binary_episodic_index.hpp"
#include "world/cognition.hpp"

using namespace girassol::cognition;

namespace {

int gCgFailures = 0;
#define GCHECK(cond, msg)                                                          \
    do {                                                                           \
        if (!(cond)) {                                                             \
            std::fprintf(stderr, "FALHA: %s (%s:%d)\n", msg, __FILE__, __LINE__);  \
            ++gCgFailures;                                                         \
        } else {                                                                   \
            std::printf("ok   %s\n", msg);                                         \
        }                                                                          \
    } while (0)

uint64_t fnv(const std::string& s) { return girassol::memory::fnv1a64(s); }

std::string hex(std::string_view s) {
    girassol::Sha256 h;
    h.update(s);
    const auto d = h.digest();
    static constexpr char x[] = "0123456789abcdef";
    std::string out;
    for (auto b : d) { out += x[b >> 4]; out += x[b & 15]; }
    return out;
}

// Um ciclo cognitivo completo (percepção → memória → G(π) → política → F) sobre um mundo sintético.
uint64_t runCycle(uint64_t seed, int ticks) {
    girassol::SplitMix64 rng(seed);
    const int W = 24, H = 24;
    std::vector<float> food(W * H), danger(W * H), social(W * H);
    struct Cit { double needs[kNeeds]; int x, z; int last = -1; std::vector<MemoryNode> mem; };
    std::vector<Cit> cits(6);
    for (auto& c : cits) {
        for (double& v : c.needs) v = 0.4 + 0.6 * rng.nextDouble();
        c.x = static_cast<int>(rng.next() % 48); c.z = static_cast<int>(rng.next() % 48);
    }
    uint64_t h = 0xcbf29ce484222325ULL;
    auto mix = [&](uint64_t v) { h = (h ^ v) * 0x100000001b3ULL; };
    auto mixD = [&](double d) { uint64_t u; std::memcpy(&u, &d, 8); mix(u); };
    int64_t nextId = 1;
    static constexpr int32_t codes[kNeeds] = {0, 1, 2, 3, 4, 5};
    for (int t = 1; t <= ticks; ++t) {
        for (int i = 0; i < W * H; ++i) {
            food[i] = food[i] * 0.98f + (rng.next() % 50 == 0 ? 3.0f : 0.0f);
            danger[i] = danger[i] * 0.97f + (rng.next() % 90 == 0 ? 2.5f : 0.0f);
            social[i] = social[i] * 0.99f + (rng.next() % 70 == 0 ? 1.5f : 0.0f);
        }
        const Grid gF{food.data(), W, H, 2.0f}, gD{danger.data(), W, H, 2.0f}, gS{social.data(), W, H, 2.0f};
        for (auto& c : cits) {
            const float fx = static_cast<float>(c.x), fz = static_cast<float>(c.z);
            const auto b = broadcast(perceive(c.needs, codes, gF.read(fx, fz), gD.read(fx, fz), (t / 100) % 2 == 1));
            if (b.size() > static_cast<size_t>(kWorkspaceCap)) return 0;
            MemoryQuery q;
            q.tick = t; q.topK = 4;
            for (const auto& s : b) {
                const auto f = static_cast<int64_t>(fnv("sinal" + std::to_string(s.code)));
                q.features.push_back(f); q.terms.push_back(f); q.tags.push_back(s.code);
            }
            const auto r = retrieve(q, c.mem);
            for (size_t k = 0; k < r.chosen.size(); ++k) {
                auto& n = c.mem[static_cast<size_t>(r.chosen[k])];
                n.hawkesExcitation = r.newExcitation[k];
                n.hawkesLastTick = t;
                if (n.accessTicks.size() >= 16) n.accessTicks.erase(n.accessTicks.begin());
                n.accessTicks.push_back(t);
                mix(static_cast<uint64_t>(n.id));
            }
            std::vector<CandidateIn> ins;
            for (int a = 0; a < 7; ++a) {
                CandidateIn ci{};
                for (int k = 0; k < kNeeds; ++k) {
                    ci.needs[k] = c.needs[k]; ci.decay[k] = 0.0008 + 0.0002 * k;
                    ci.gains[k] = (k == a % kNeeds) ? 0.35 : -0.01;
                }
                ci.skillEff = 0.9; ci.yieldP = 0.6; ci.uncertainty = 0.3 + 0.1 * a;
                ci.travel = static_cast<int32_t>(rng.next() % 20); ci.duration = 20 + 5 * a;
                ci.cortisol = 0.3; ci.dopamine = 0.5; ci.curiosity = 0.4; ci.epistemicPolicy = 0.3;
                ci.neural = 0.2; ci.socTempC = 38.0; ci.economicBonus = a == 3 ? 0.05 : 0.0;
                ci.memQ = r.chosen.empty() ? 0.0 : 0.2; ci.novelty = 0.01 * a; ci.oxytocin = 0.4;
                ci.adapterPred = 0.1 * a - 0.3; ci.loraPred = 0.0; ci.adapterWeight = 0.05; ci.loraWeight = 0.08;
                ci.targetX = static_cast<int32_t>(rng.next() % 48); ci.targetZ = static_cast<int32_t>(rng.next() % 48);
                ci.actionOrdinal = a; ci.hasTarget = a != 6; ci.isSocial = a == 2; ci.isExplore = a == 6; ci.loraActive = t > 200;
                ins.push_back(ci);
            }
            std::vector<CandidateOut> outs;
            for (const auto& ci : ins) outs.push_back(evaluate(ci, 0.95, gD, gS));
            for (const auto& o : outs) if (!std::isfinite(o.efe)) return 0;
            const auto order = sortCandidates(outs, ins);
            std::vector<double> efes; std::vector<int32_t> ords;
            for (int32_t i : order) { efes.push_back(outs[static_cast<size_t>(i)].efe); ords.push_back(ins[static_cast<size_t>(i)].actionOrdinal); }
            const int32_t idx = selectPolicy(efes, ords, c.last, 0.05, 6.0, rng.nextDouble());
            if (idx < 0 || idx >= static_cast<int32_t>(order.size())) return 0;
            const auto& chosen = outs[static_cast<size_t>(order[static_cast<size_t>(idx)])];
            c.last = ords[static_cast<size_t>(idx)];
            for (int k = 0; k < kNeeds; ++k) c.needs[k] = 0.5 * c.needs[k] + 0.5 * chosen.projected[k];
            mixD(chosen.efe);
            mixD(variationalFreeEnergy(c.needs));
            mix(static_cast<uint64_t>(c.last));
            // grava um episódio
            MemoryNode n;
            n.id = nextId++;
            n.features = {static_cast<int64_t>(fnv("acao" + std::to_string(c.last))), static_cast<int64_t>(fnv("t" + std::to_string(t % 7)))};
            for (const auto& s : b) n.features.push_back(static_cast<int64_t>(fnv("sinal" + std::to_string(s.code))));
            n.docTerms = n.features;
            n.tags = {c.last};
            n.bits = simHash(n.features);
            n.createdTick = t;
            n.salience = 0.5; n.confidence = 0.8; n.qUtility = -chosen.efe;
            c.mem.push_back(std::move(n));
            if (c.mem.size() > 200) c.mem.erase(c.mem.begin());
            c.x = (c.x + static_cast<int>(rng.next() % 3) - 1 + 48) % 48;
            c.z = (c.z + static_cast<int>(rng.next() % 3) - 1 + 48) % 48;
        }
    }
    return h;
}

} // namespace

int runCognitionTests() {
    // javaCompare: semântica de Double.compare
    GCHECK(javaCompare(-0.0, 0.0) < 0 && javaCompare(0.0, -0.0) > 0, "Double.compare: −0,0 < 0,0");
    const double nan = std::numeric_limits<double>::quiet_NaN();
    GCHECK(javaCompare(nan, 1e300) > 0 && javaCompare(nan, nan) == 0, "Double.compare: NaN é o maior e igual a si");
    GCHECK(std::isnan(javaMax(nan, 1.0)) && !std::signbit(javaMax(-0.0, 0.0)), "Math.max: NaN propaga, +0 > −0");

    // percepção + espaço de trabalho
    {
        const double needs[kNeeds] = {0.2, 0.9, 0.5, 0.95, 0.8, 0.1};
        static constexpr int32_t codes[kNeeds] = {0, 1, 2, 3, 4, 5};
        auto s = perceive(needs, codes, 12.0f, 3.0f, true);
        GCHECK(s.size() == 9, "percepção: 6 necessidades + comida + perigo + seca");
        GCHECK(s[6].code == kFoodScent && s[6].salience == 0.9, "cheiro de comida saturado em 0,9");
        GCHECK(s[7].code == kDanger && s[7].salience == static_cast<double>(3.0f / 5.0f), "perigo = feromônio/5 (float)");
        auto b = broadcast(s);
        GCHECK(b.size() == 3 && b[0].code == 5 && b[1].code == kFoodScent && b[2].code == 0, "espaço de trabalho: top-3 por saliência");
        const double tie[kNeeds] = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        const int32_t perm[kNeeds] = {3, 1, 4, 0, 5, 2};
        auto bt = broadcast(perceive(tie, perm, 0.0f, 0.0f, false));
        GCHECK(bt.size() == 3 && bt[0].code == 3 && bt[1].code == 1 && bt[2].code == 4, "empate preserva a ordem do mapa (estável)");
        float g[4] = {1, 2, 3, 4};
        Grid grid{g, 2, 2, 2.0f};
        GCHECK(grid.read(3.9f, 0.0f) == 2.0f && grid.read(-5.0f, 9.0f) == 3.0f && grid.read(100.0f, 100.0f) == 4.0f, "grade: truncamento e saturação de célula");
        Grid none;
        GCHECK(none.read(1, 1) == 0.0f, "canal inexistente lê zero");
    }

    // memória: SimHash nativo == índice binário legado (mesmos bits do Kotlin)
    {
        const std::vector<std::string> feats = {"horta", "#comer", "@c1", "colheita"};
        std::vector<int64_t> hs;
        for (const auto& f : feats) hs.push_back(static_cast<int64_t>(fnv(f)));
        const auto a = simHash(hs);
        const auto b = girassol::memory::simHash(feats);
        bool same = true;
        for (int i = 0; i < kWords; ++i) same = same && a[static_cast<size_t>(i)] == b[static_cast<size_t>(i)];
        GCHECK(same, "SimHash bit[256] por hashes FNV == SimHash por strings");
        const auto d = dense(hs);
        float n = 0; for (float x : d) n += x * x;
        GCHECK(std::fabs(n - 1.0f) < 1e-5f && std::fabs(cosine(d, d) - 1.0f) < 1e-5f, "embedding denso normalizado L2");
        GCHECK(hawkesIntensity(0.0, 0, 100) == 0.05, "Hawkes: μ0 sem excitação");
        GCHECK(std::fabs(hawkesExcite(1.0, 0, 480, 2.0) - (std::exp(-1.0) + 0.6)) < 1e-12, "Hawkes: decaimento e^{−βΔ} + α·v (v truncado)");
        const int64_t acc[] = {90, 99};
        GCHECK(std::fabs(baseLevelActivation(acc, 0, 100) - std::log(std::pow(10.0, -0.5) + 1.0)) < 1e-12, "ACT-R: ativação de nível base");

        std::vector<MemoryNode> nodes;
        for (int i = 0; i < 40; ++i) {
            MemoryNode m;
            m.id = i + 1;
            m.features = {static_cast<int64_t>(fnv(i % 2 ? "horta" : "usina")), static_cast<int64_t>(fnv("x" + std::to_string(i)))};
            m.docTerms = m.features;
            m.tags = {i % 2 ? 11 : 22};
            m.bits = simHash(m.features);
            m.createdTick = i;
            m.salience = 0.5; m.confidence = 0.5;
            nodes.push_back(m);
        }
        MemoryQuery q;
        q.tick = 100; q.topK = 4;
        q.features = {static_cast<int64_t>(fnv("horta"))};
        q.terms = q.features;
        q.tags = {11};
        const auto r = retrieve(q, nodes);
        bool allHorta = r.chosen.size() == 4;
        for (int32_t i : r.chosen) allHorta = allHorta && (nodes[static_cast<size_t>(i)].id % 2 == 0);
        GCHECK(r.stage1 == kStage1K && allHorta, "recuperação 2 estágios: Hamming top-24 → RRF escolhe memórias da horta");
        const auto r2 = retrieve(q, nodes);
        GCHECK(r2.chosen == r.chosen && r2.newExcitation == r.newExcitation, "recuperação determinística");
        MemoryQuery bad = q; bad.topK = -3;
        GCHECK(retrieve(bad, nodes).chosen.empty() && retrieve(q, {}).stage1 == 0, "topK negativo e corpus vazio são seguros");
    }

    // inferência ativa e política
    {
        CandidateIn c{};
        for (int k = 0; k < kNeeds; ++k) { c.needs[k] = 0.5; c.decay[k] = 0.001; c.gains[k] = 0.0; }
        c.gains[0] = 0.4; c.skillEff = 1.0; c.yieldP = 0.6; c.uncertainty = 1.0; c.travel = 10; c.duration = 30;
        c.cortisol = 0.5; c.dopamine = 0.5; c.curiosity = 0.5; c.epistemicPolicy = 0.3; c.neural = 0.0; c.socTempC = 30.0;
        Grid none;
        const auto o = evaluate(c, 0.95, none, none);
        GCHECK(std::fabs(o.projected[0] - 0.86) < 1e-12 && std::fabs(o.projected[1] - 0.46) < 1e-12, "necessidades projetadas (ganho × eficiência − decaimento)");
        CandidateIn starving = c;
        starving.gains[0] = 0.0;
        GCHECK(evaluate(starving, 0.95, none, none).efe > o.efe, "G(π) penaliza não comer com fome");
        GCHECK(neuralThermalCost(1.0, 40.0) == 2.0 && neuralThermalCost(1.0, 100.0) > 2.99, "custo neural térmico sigmoide em 40 °C");
        GCHECK(std::fabs(bernoulliKl(0.5, 0.5)) < 1e-15 && bernoulliEntropy(0.5) == std::log(2.0), "KL e entropia de Bernoulli");
        const double efes[] = {0.10, 0.11, 0.50};
        const int32_t ords[] = {3, 1, 2};
        GCHECK(selectPolicy(efes, ords, -1, 0.0, 6.0, 0.0) == 0, "softmax: amostra 0 → melhor G");
        GCHECK(selectPolicy(efes, ords, 1, 0.5, 60.0, 0.5) == 1, "histerese mantém a intenção corrente");
        GCHECK(selectPolicy(std::span<const double>(), std::span<const int32_t>(), -1, 0, 6, 0.5) == -1, "sem opções → −1");
        std::vector<CandidateOut> outs = {{0.2, {}}, {0.1, {}}, {0.2, {}}};
        std::vector<CandidateIn> ins(3);
        ins[0].actionOrdinal = 5; ins[1].actionOrdinal = 9; ins[2].actionOrdinal = 2;
        const auto ord = sortCandidates(outs, ins);
        GCHECK(ord == std::vector<int32_t>({1, 2, 0}), "ordenação por (G, ordinal)");
        const double needs[kNeeds] = {0.2, 0.3, 0.9, 0.9, 0.9, 0.9};
        GCHECK(std::isfinite(variationalFreeEnergy(needs)) && variationalFreeEnergy(needs) > 0, "energia livre variacional finita");
    }

    // ledger e cadeia SHA-256
    {
        const int64_t bal[] = {-1000, 400, 600, -5, 5, std::numeric_limits<int64_t>::max(), 1, -1};
        const int32_t res[] = {0, 0, 0, 1, 1, 2, 2, 2};
        const auto bad = ledgerZeroSum(bal, res, 3);
        GCHECK(bad.size() == 3 && !bad[0] && !bad[1] && bad[2], "soma-zero por recurso + detecção de estouro");
        const std::string genesis(64, '0');
        std::vector<ChainEvent> ev;
        std::string prev = genesis;
        for (int i = 1; i <= 20; ++i) {
            ChainEvent e{i, i / 3, 38, "TESTE", i % 4 ? "C" + std::to_string(i) : "", i % 4 != 0, "x=" + std::to_string(i) + ";nome=Ana ☀️", prev, ""};
            e.hash = hex(chainBody(e));
            prev = e.hash;
            ev.push_back(e);
        }
        GCHECK(chainBody(ev[3]).find("|-|") != std::string::npos, "ator nulo serializado como '-'");
        auto r = verifyChain(ev, nullptr, genesis);
        GCHECK(r.reason == kChainOk && r.checked == 20 && r.headFrom == 3, "cadeia íntegra (20 eventos, âncora GENESIS)");
        auto t = ev; t[7].payload = "adulterado";
        r = verifyChain(t, nullptr, genesis);
        GCHECK(r.reason == kChainHash && r.firstBadSeq == 8, "event_hash adulterado detectado");
        t = ev; t.erase(t.begin() + 5);
        GCHECK(verifyChain(t, nullptr, genesis).reason == kChainGap, "lacuna de sequência detectada");
        t = ev; t[10].tick = 0; t[10].hash = hex(chainBody(t[10]));
        GCHECK(verifyChain(t, nullptr, genesis).reason != kChainOk, "tick regredindo / reencadeamento detectado");
        const std::string wrong(64, 'f');
        GCHECK(verifyChain(std::span<const ChainEvent>(ev.data() + 4, 5), &wrong, genesis).reason == kChainAnchor, "âncora prev_hash errada");
        GCHECK(verifyChain(std::span<const ChainEvent>(ev.data() + 4, 5), &ev[3].hash, genesis).reason == kChainOk, "janela ancorada no evento anterior");
        GCHECK(verifyChain({}, nullptr, genesis).reason == kChainEmpty, "janela vazia");
    }

    // UTF-16 ↔ UTF-8 da ponte JNI (emoji, U+0000, surrogate isolado)
    {
        const std::u16string s = u"C1-\U0001F33B-ç\0x";
        const std::u16string withNul(s.data(), 8);
        const std::string u8 = girassol::jniutf::utf16ToUtf8(withNul.data(), withNul.size());
        GCHECK(u8 == std::string("C1-\xF0\x9F\x8C\xBB-\xC3\xA7\0", 11), "UTF-16 → UTF-8 padrão (emoji 4 bytes, NUL de 1 byte)");
        GCHECK(girassol::jniutf::utf8ToUtf16(u8) == withNul, "UTF-8 → UTF-16 ida e volta");
        const char16_t lone[] = {u'a', static_cast<char16_t>(0xD800), u'b'};
        GCHECK(girassol::jniutf::utf16ToUtf8(lone, 3) == "a?b", "surrogate isolado → '?' (igual a toByteArray(UTF_8))");
        GCHECK(girassol::jniutf::utf8ToUtf16("a\xC0\xAF" "b\xF0\x9F") == u"ab", "UTF-8 inválido/overlong/truncado descartado");
    }

    // ciclo completo determinístico
    const uint64_t c1 = runCycle(42, 400), c2 = runCycle(42, 400), c3 = runCycle(43, 400);
    std::printf("ciclo cognitivo: 0x%016llx\n", static_cast<unsigned long long>(c1));
    GCHECK(c1 != 0 && c1 == c2, "ciclo cognitivo de 400 ticks determinístico bit a bit");
    GCHECK(c1 != c3, "semente diferente → trajetória diferente");

    const int64_t sig = paritySignature();
    std::printf("assinatura_cognicao=0x%016llx\n", static_cast<unsigned long long>(sig));
    // Mesmo valor dourado em core/src/test/.../NativeCognitionTest.kt (JVM chamando o .so de host).
    GCHECK(sig == 0x947acd1a35ffe663LL, "assinatura dourada do núcleo cognitivo");
    return gCgFailures;
}
