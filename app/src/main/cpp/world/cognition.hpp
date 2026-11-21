#pragma once
// Núcleo cognitivo nativo (C++20, determinístico) — v3.8.
//
// Os estágios 3–7 do WorldTick deixaram de rodar em Kotlin e passaram a este kernel,
// chamado em LOTE (uma travessia JNI por estágio por tick, não uma por cidadão):
//   3. Percepção: sinais de necessidade, estigmergia (comida/perigo nas grades de feromônio)
//      e clima → competição do espaço de trabalho global (capacidade 3, saliência > 0,15).
//   4. Memória: recuperação em 2 estágios (Hamming bit[256] SimHash → RRF de cosseno denso
//      float32, BM25, ativação ACT-R/MemRL/A-MEM e intensidade de Hawkes) + evento de Hawkes.
//   6. Inferência ativa: necessidades projetadas, pesos homeostáticos, G(π) = risco KL +
//      ambiguidade − ganho epistêmico + custo neural térmico, termos econômicos/sociais/LoRA.
//   7. Política: ordenação estável (G, ordinal) e softmax de precisão γ com histerese;
//      energia livre variacional F por cidadão.
//   Ledger: conservação soma-zero em micro-unidades (com detecção de estouro) e verificação
//   em lote da cadeia SHA-256 prev_hash/event_hash.
//
// Sem estado global; mesma entrada → mesma saída bit a bit (-ffp-contract=off, sem fast-math).
// Comparações de double seguem Double.compare da JVM (−0,0 < 0,0; NaN maior que tudo).
#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace girassol::cognition {

inline constexpr int kNeeds = 6;          // FOME, ENERGIA, SOCIAL, DIVERSAO, HIGIENE, PROPOSITO
inline constexpr int kWorkspaceCap = 3;
inline constexpr int kStage1K = 24;
inline constexpr int kBits = 256;
inline constexpr int kWords = 4;
inline constexpr int kDenseDim = 256;

// Códigos de sinal perceptual: 0..5 = necessidades; depois estigmergia e clima.
enum SignalCode : int32_t { kFoodScent = 6, kDanger = 7, kDrought = 8 };

/** Double.compare da JVM. */
int javaCompare(double a, double b);
/** Math.max da JVM (NaN propaga; +0,0 > −0,0). */
double javaMax(double a, double b);

// ------------------------------------------------------------------ grades de feromônio
struct Grid {
    const float* data = nullptr; // nullptr → canal inexistente (lê 0)
    int width = 0;
    int height = 0;
    float cellSize = 2.0f;
    float read(float worldX, float worldZ) const;
};

// ------------------------------------------------------------------ 3. percepção
struct Signal {
    int32_t code;
    double salience;
};
/** needs/codes na ordem de iteração do mapa de necessidades do cidadão (desempate estável). */
std::vector<Signal> perceive(const double* needs, const int32_t* codes, float food, float danger, bool drought);
/** Espaço de trabalho global: filtra > 0,15, ordena por saliência desc. (estável), corta em cap. */
std::vector<Signal> broadcast(std::vector<Signal> signals, int capacity = kWorkspaceCap);

// ------------------------------------------------------------------ 4. memória
using Bits = std::array<uint64_t, kWords>;
Bits simHash(std::span<const int64_t> featureHashes);
int hamming(const Bits& a, const Bits& b);
std::array<float, kDenseDim> dense(std::span<const int64_t> featureHashes);
float cosine(const std::array<float, kDenseDim>& a, const std::array<float, kDenseDim>& b);
double hawkesIntensity(double excitation, int64_t lastTick, int64_t now);
double hawkesExcite(double excitation, int64_t lastTick, int64_t now, double valence);
double baseLevelActivation(std::span<const int64_t> accessTicks, int64_t createdTick, int64_t now, double decay = 0.5);

struct MemoryNode {
    int64_t id = 0;
    Bits bits{};
    std::vector<int64_t> features; // termos + #tags + @entidades (FNV-1a 64)
    std::vector<int64_t> docTerms; // termos + tags minúsculas (documento BM25)
    std::vector<int64_t> tags;     // tags (conjunto)
    std::vector<int64_t> entities; // entidades (conjunto)
    std::vector<int64_t> accessTicks;
    int64_t createdTick = 0;
    int64_t hawkesLastTick = 0;
    bool zettel = false;           // ligado a algo que está na memória de trabalho
    double salience = 0, confidence = 0, qUtility = 0, hawkesExcitation = 0;
};
struct MemoryQuery {
    int topK = 4;
    int64_t tick = 0;
    std::vector<int64_t> features;
    std::vector<int64_t> terms;    // termos distintos, já na ordem lexicográfica (SortedSet da JVM)
    std::vector<int64_t> tags;     // conjunto
    std::vector<int64_t> entities; // conjunto
};
struct RetrievalOut {
    int stage1 = 0;
    std::vector<int32_t> chosen;           // índices em nodes, na ordem de recuperação
    std::vector<double> newExcitation;     // estado de Hawkes após o evento de reforço
};
RetrievalOut retrieve(const MemoryQuery& q, const std::vector<MemoryNode>& nodes);

// ------------------------------------------------------------------ 6–7. inferência ativa
struct CandidateIn {
    double needs[kNeeds];
    double decay[kNeeds];
    double gains[kNeeds];          // ganho bruto do perfil da ação por necessidade
    double skillEff, yieldP, uncertainty;
    int32_t travel, duration;      // ticks (inteiros, como na JVM)
    double cortisol, dopamine, curiosity, epistemicPolicy;
    double neural, socTempC;       // custo neural do gate (antes do fator 0,05) e temperatura do SoC
    double economicBonus, memQ, novelty;
    double oxytocin;
    double adapterPred, loraPred;  // predições brutas (o kernel trunca em [−1, 1])
    double adapterWeight, loraWeight;
    int32_t targetX, targetZ;      // tile do objeto-alvo (−1 se não há objeto)
    int32_t actionOrdinal;
    uint8_t hasTarget, isSocial, isExplore, loraActive;
};
struct CandidateOut {
    double efe;
    double projected[kNeeds];
};
double entropyOf(double u);
double bernoulliKl(double q, double p);
double bernoulliEntropy(double p);
double neuralThermalCost(double base, double socTempC);
CandidateOut evaluate(const CandidateIn& c, double preferred, const Grid& danger, const Grid& social,
                      double riskScale = 0.35, double ambiguityScale = 0.04);
/** Ordenação estável por (G, ordinal da ação). */
std::vector<int32_t> sortCandidates(std::span<const CandidateOut> outs, std::span<const CandidateIn> ins);
/** Softmax de precisão γ sobre −G com custo de troca (histerese). */
int32_t selectPolicy(std::span<const double> efes, std::span<const int32_t> ordinals, int32_t currentOrdinal,
                     double switchCost, double gamma, double uniform);
double variationalFreeEnergy(const double* needs);

// ------------------------------------------------------------------ ledger
/** Soma-zero por recurso em micro-unidades. Devolve bitmask: bit r = recurso r viola (soma≠0 ou estouro). */
std::vector<uint8_t> ledgerZeroSum(std::span<const int64_t> balances, std::span<const int32_t> resourceIdx, int nResources);

struct ChainEvent {
    int64_t seq, tick;
    int32_t rulesVersion;
    std::string type, actor; // actor vazio + hasActor=false → "-"
    bool hasActor;
    std::string payload, prevHash, hash;
};
enum ChainReason : int32_t { kChainOk = 0, kChainEmpty = 1, kChainAnchor = 2, kChainGap = 3, kChainPrev = 4, kChainTick = 5, kChainHash = 6 };
struct ChainReport {
    int32_t reason = kChainOk;
    int32_t checked = 0;
    int64_t firstBadSeq = 0;
    int32_t headFrom = 0; // 0 = âncora, 1 = hash do evento anterior, 2 = prev_hash do evento, 3 = hash do último
};
std::string chainBody(const ChainEvent& e);
ChainReport verifyChain(std::span<const ChainEvent> events, const std::string* anchor, std::string_view genesis);

/** Assinatura dourada do kernel (vetores sintéticos) — igual nos testes nativos e na JVM. */
int64_t paritySignature();

} // namespace girassol::cognition
