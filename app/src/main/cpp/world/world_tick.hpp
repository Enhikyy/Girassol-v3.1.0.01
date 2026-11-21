#pragma once
// WorldTick nativo (C++20, determinístico) — v3.7.
//
// Estágios do tick que deixaram de existir em Kotlin e rodam aqui, chamados pelo
// WorldCore via JNI (core/world/NativeWorldTick.kt) a cada tick / virada de dia:
//   1. necessidades homeostáticas (decaimento por traço, noite, inanição);
//   2. envelhecimento e morte (inanição, velhice) com herdeiro dos bens (filho vivo ou comuns);
//   3. atualização social (encontros na praça: familiaridade, afinidade, confiança Beta,
//      opinião híbrida Deffuant–Weisbuch + Friedkin–Johnsen, comércio alimento×crédito);
//   4. ciclo diário: calibração IRT 2PL, reprodução por mérito (Anokhin + Brier + promoção +
//      bem-estar + IRT 2PL) com superávit na reserva comum, equilíbrio de Heider, decaimento
//      de familiaridade e assembleia de Ostrom (cota dos comuns);
//   5. Prioritized Experience Replay (sum-tree, α/β) do buffer de experiências de cada agente.
//
// Sem estado global: cada função recebe linhas compactas e devolve resultados. O estado
// persistente continua no WorldState (SQLite WAL + .grs) — o kernel é puro e reprodutível:
// mesma entrada → mesma saída bit a bit (sem FMA: -ffp-contract=off).
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "../common/splitmix64.hpp"

namespace girassol::world {

inline constexpr int64_t kTicksPerDay = 480;
inline constexpr double kAdultDays = 8.0;
inline constexpr int kNeeds = 6;  // FOME, ENERGIA, SOCIAL, DIVERSAO, HIGIENE, PROPOSITO
inline constexpr int kTraits = 7; // metabolismo, sociabilidade, curiosidade, aptidao, conscienciosidade, longevidade, amabilidade
inline constexpr int kIrtItems = 5; // COLHER, RECICLAR, MANTER_USINA, PESQUISAR, CONSTRUIR
inline constexpr double kNoTheta = -1.0e9;

// Mérito reprodutivo (espelha as constantes documentadas na UI).
inline constexpr int kMeritBaselineSamples = 10;
inline constexpr double kAnokhinRatio = 0.90;
inline constexpr double kBrierMargin = 0.005;
inline constexpr double kWellbeingMin = 0.70;
inline constexpr double kCommonsFoodPerCapita = 2.0;
inline constexpr double kIrtMinResponses = 12.0;
inline constexpr double kIrtMargin = 0.10;
inline constexpr double kIrtWindowDecay = 0.85;

enum MeritBit : uint32_t {
    kAdult = 1u, kAnokhin = 2u, kBrier = 4u, kPromotion = 8u, kWellbeing = 16u, kIrt = 32u, kCommons = 64u
};
inline constexpr uint32_t kAllCitizenBits = kAdult | kAnokhin | kBrier | kPromotion | kWellbeing | kIrt;

// ------------------------------------------------------------------ sementes por fluxo
uint64_t streamSeed(uint64_t worldSeed, std::string_view agentId, int64_t tick, std::string_view stream);
inline SplitMix64 streamRng(uint64_t worldSeed, std::string_view agentId, int64_t tick, std::string_view stream) {
    return SplitMix64(streamSeed(worldSeed, agentId, tick, stream));
}
// Mesmo mapeamento de SplitMix64.nextInt da JVM: ((x >>> 33) % bound).
inline int nextInt(SplitMix64& r, int bound) {
    return static_cast<int>((r.next() >> 33) % static_cast<uint64_t>(bound));
}

// ------------------------------------------------------------------ 1. necessidades
struct NeedsRow {
    double needs[kNeeds];
    double metabolismo, sociabilidade, conscienciosidade, curiosidade;
    bool resting = false;   // intenção atual = DESCANSAR
    int32_t starvation = 0; // ticks consecutivos com saciedade 0
    bool starving = false;  // saída: deposita feromônio de perigo
};
double needDecay(const NeedsRow& r, int need);
void stepNeeds(std::span<NeedsRow> rows, bool night);

// ------------------------------------------------------------------ 2. envelhecimento
enum DeathCause : int { kAlive = 0, kStarvation = 1, kOldAge = 2 };
struct LifeRow {
    std::string id;
    int32_t starvation = 0;
    int64_t birthTick = 0;
    double longevidade = 0.5;
    int32_t parentA = -1, parentB = -1;  // índices na mesma lista (−1 = fora da lista)
    bool alive = true;
    int cause = kAlive;  // saída
    int heir = -1;       // saída: índice do herdeiro (−1 = reserva comum)
};
double lifespanDays(double longevidade);
int stepLifecycle(std::vector<LifeRow>& rows, int64_t tick);

// ------------------------------------------------------------------ 3. sociedade
struct SocialRow {
    std::string id;
    std::string group;  // objeto-alvo do encontro ("" = sem objeto)
    double traits[kTraits];
    double mood, valence, opinion, intrinsic, fome, food, credit;
};
struct SocialTie {
    int from, to;
    double affinity = 0.0, familiarity = 0.05, alphaGen = 1.5, betaGen = 1.5;
    double currentOpinion = 0.0, intrinsic = 0.0, susceptibility = 0.65;
};
struct Interaction {
    int a = -1, b = -1;
    uint64_t greetingSeed = 0, claimRaw = 0;
    bool tieCreated = false, trustOk = false, trade = false;
    double affinity = 0, familiarity = 0, alphaGen = 0, betaGen = 0, currentOpinion = 0, aOpinion = 0;
};
double tieStrength(double affinity, double familiarity, double alphaGen, double betaGen);
double stepHybridOpinion(SocialTie& tie, double peerOpinion);
std::vector<Interaction> stepSocial(uint64_t worldSeed, int64_t tick, std::vector<SocialRow>& rows, std::vector<SocialTie>& ties);

// ------------------------------------------------------------------ mérito + IRT 2PL
struct MeritRow {
    std::string id;
    int64_t birthTick = 0;
    int32_t children = 0;
    int64_t lastReproductionTick = -100000;
    bool superacao = false;
    int32_t sex = 0;  // 0 = desconhecido, 1 = F, 2 = M
    double acceptorBaseSum = 0; int32_t acceptorBaseN = 0; double acceptorEma = -1; int64_t acceptorBaselineTick = -1;
    double brierBaseSum = 0; int32_t brierBaseN = 0; double brierEma = -1; int64_t brierBaselineTick = -1;
    int32_t promotions = 0, promotionsAtLastReproduction = 0;
    double meanNeed = 1.0;
    int32_t generation = 0;
    double opinion = 0.0;
    double irtAttempts[kIrtItems] = {0, 0, 0, 0, 0};
    double irtSuccesses[kIrtItems] = {0, 0, 0, 0, 0};
    double theta = 0.0, thetaSe = 1.0, theta0 = kNoTheta;
    int64_t theta0Tick = -1;
};
double ageDays(const MeritRow& r, int64_t tick);
double irtResponses(const MeritRow& r);
uint32_t meritCheck(const MeritRow& r, int64_t tick);
int64_t citizenEtaTicks(const MeritRow& r, int64_t tick, uint32_t check);
bool compatible(const MeritRow& a, const MeritRow& b);
int64_t reproductionCooldown(const MeritRow& r);

enum EtaStatus : int { kSemPopulacao = 0, kPrecisaPar = 1, kPronto = 2, kAguardando = 3 };
struct Eta { int status = kSemPopulacao; int64_t ticks = -1; int a = -1, b = -1; int nextGeneration = 0; uint32_t blockers = 0; };
Eta estimate(const std::vector<MeritRow>& alive, double commonsFood, int64_t tick);

struct IrtItems { double a[kIrtItems] = {1, 1, 1, 1, 1}; double b[kIrtItems] = {0, 0, 0, 0, 0}; };
// MAP conjunto (JML com priors θ~N(0,1), b~N(0,1.5²), log a~N(0,0.5²)), Newton amortecido.
void calibrateIrt(std::vector<MeritRow>& rows, IrtItems& items, int iterations = 25);
double irtProbability(double theta, double a, double b);

// ------------------------------------------------------------------ 4. ciclo diário
struct DailyTie {
    int owner;   // índice em `alive`
    int target;  // índice na tabela de ids (>= alive.size() → cidadão não vivo)
    double affinity, familiarity, alphaGen, betaGen;
};
struct Birth { int a, b; uint64_t seed; };
struct DailyInput {
    uint64_t worldSeed = 0;
    int64_t tick = 0;
    int32_t housing = 0;
    int32_t admissionCap = 150;
    double commonsFood = 0.0;
    std::vector<std::string> extraIds;  // ids não vivos citados por laços
};
struct DailyOutput {
    std::vector<Birth> births;
    int32_t newQuota = 0;
    double meanOpinion = 0.0;
    int32_t heiderNudges = 0;
};
DailyOutput stepDaily(const DailyInput& in, std::vector<MeritRow>& alive, std::vector<DailyTie>& ties, IrtItems& items);

// ------------------------------------------------------------------ 5. PER
struct PerBatch { std::vector<int32_t> idx; std::vector<double> weights; };
inline constexpr double kPerAlpha = 0.6;
inline constexpr double kPerEps = 1e-3;
double perPriority(double tdError);
double perBeta(int64_t replays);  // β anelado 0,4 → 1,0
// Amostragem estratificada proporcional a p^α numa sum-tree; pesos IS normalizados (máx = 1).
PerBatch perSample(std::span<const double> priorities, int batch, double alpha, double beta, uint64_t seed);

// Assinatura FNV-1a de uma simulação sintética completa (testes nativos e JVM).
uint64_t paritySignature();

}  // namespace girassol::world
