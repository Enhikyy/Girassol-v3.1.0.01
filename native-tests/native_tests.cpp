// Testes nativos em host (Linux x86_64) com AddressSanitizer + UBSan.
// Executados pelo CI antes do build Android.
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "audio/binaural_hrtf_spatializer.hpp"
#include "cognitive/tsetlin_machine.hpp"
#include "common/splitmix64.hpp"
#include "evolution/map_elites_aurora.hpp"
#include "audio/ambient_binaural_synth.hpp"
#include "forge/lora_trainer_realtime.hpp"
#include "hardware/soc_hardware_probe.hpp"
#include "llm/llm_engine.hpp"
#include "navigation/grid_astar.hpp"
#include <cstring>

static int gFailures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) {                                                \
            std::fprintf(stderr, "FALHA: %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            ++gFailures;                                              \
        } else {                                                      \
            std::printf("ok   %s\n", msg);                            \
        }                                                             \
    } while (0)

// Mesmo procedimento de KotlinTsetlinParityTest (core/src/test): o checksum FNV-1a
// do blob GRST deve coincidir com o valor dourado calculado na JVM.
uint64_t paritySignature() {
    girassol::tsetlin::PersistentTsetlinMachine tm(16, 20, 64, 3.5f, 8, 2026ULL);
    girassol::SplitMix64 data(99ULL);
    std::vector<uint8_t> f(16);
    for (int i = 0; i < 300; ++i) {
        for (auto& b : f) b = static_cast<uint8_t>((data.next() >> 63) & 1ULL);
        tm.trainStep(f, f[0] == 1 && f[5] == 0);
    }
    uint64_t h = 1469598103934665603ULL;
    for (uint8_t b : tm.serializeBinary()) h = (h ^ b) * 1099511628211ULL;
    return h;
}

// ---- Vetores de paridade: mesmos geradores de core/.../ports/ParityVectors.kt ----
static uint64_t fnv(uint64_t h, uint64_t v) {
    for (int b = 0; b < 8; ++b) { h ^= (v >> (b * 8)) & 0xFFULL; h *= 0x100000001b3ULL; }
    return h;
}
static const uint64_t FNV_OFFSET = 0xcbf29ce484222325ULL;

uint64_t navSignature(int* foundOut) {
    girassol::SplitMix64 rng(2026ULL);
    const int w = 40;
    std::vector<int32_t> tiles(w * w);
    std::vector<float> trail(w * w), danger(w * w);
    for (int i = 0; i < w * w; ++i) {
        const int r = static_cast<int>((rng.next() >> 1) % 100ULL);
        tiles[i] = r < 18 ? 3 : r < 22 ? 6 : r < 40 ? 1 : r < 48 ? 2 : r < 60 ? 4 : 0;
        trail[i] = rng.nextFloat() * 12.0f;
        danger[i] = ((rng.next() >> 1) % 10ULL == 0ULL) ? rng.nextFloat() * 8.0f : 0.0f;
    }
    std::vector<int32_t> q(64 * 4);
    for (auto& v : q) v = static_cast<int32_t>((rng.next() >> 1) % static_cast<uint64_t>(w));
    girassol::nav::GridAStar astar;
    uint64_t h = FNV_OFFSET;
    int found = 0;
    for (int k = 0; k < 64; ++k) {
        const auto p = astar.findPath(w, tiles.data(), q[k * 4], q[k * 4 + 1], q[k * 4 + 2], q[k * 4 + 3], trail.data(), danger.data());
        if (!p.empty()) ++found;
        h = fnv(h, static_cast<uint64_t>(static_cast<int64_t>(p.size())));
        for (int32_t v : p) h = fnv(h, static_cast<uint64_t>(static_cast<int64_t>(v)));
    }
    if (foundOut) *foundOut = found;
    return h;
}

uint64_t loraSignature(float* mseFirst, float* mseLast) {
    using girassol::forge::LoraMath;
    using girassol::forge::LoraState;
    LoraState s(18, 1, 4, 8.0f / 4.0f);
    LoraMath::initA(s.a, 18, 1234ULL);
    s.anchorA = s.a;
    for (auto& v : s.fisherA) v = 0.01f;
    for (auto& v : s.fisherB) v = 0.02f;
    girassol::SplitMix64 rng(77ULL);
    std::vector<float> xs(64 * 18, 0.0f), ys(64);
    for (int i = 0; i < 64; ++i) {
        const int action = static_cast<int>((rng.next() >> 1) % 13ULL);
        const int tick = static_cast<int>((rng.next() >> 1) % 480ULL);
        float* x = xs.data() + i * 18;
        x[action] = 1.0f;
        x[13 + tick * 4 / 480] = 1.0f;
        x[17] = 1.0f;
        ys[i] = rng.nextFloat() * 2.0f - 1.0f;
    }
    const auto mse = LoraMath::trainEpochs(s, xs.data(), ys.data(), 64, 3, 0.01f, 15.0f);
    if (mseFirst) *mseFirst = mse.first;
    if (mseLast) *mseLast = mse.second;
    uint64_t h = FNV_OFFSET;
    for (float v : s.pack()) { uint32_t u; std::memcpy(&u, &v, 4); h = fnv(h, u); }
    return fnv(h, static_cast<uint64_t>(static_cast<int64_t>(s.step)));
}

// Gramática idêntica à gerada por NeuralProtocol.grammar(["COMER","DESCANSAR","SOCIALIZAR"])
static const char* kGrammar =
    "root ::= \"{\" ws \"\\\"acao\\\":\" ws acao \",\" ws \"\\\"pensamento\\\":\" ws texto \",\" ws \"\\\"fala\\\":\" ws texto ws \"}\"\n"
    "acao ::= \"\\\"COMER\\\"\" | \"\\\"DESCANSAR\\\"\" | \"\\\"SOCIALIZAR\\\"\"\n"
    "texto ::= \"\\\"\" ch{1,72} \"\\\"\"\n"
    "ch ::= [a-zA-Z0-9 ,.!?;:()áàâãéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÇ-]\n"
    "ws ::= \" \"?\n";

static void llmTest() {
    const char* path = std::getenv("GIRASSOL_TEST_GGUF");
    if (!girassol::llm::LlmEngine::available()) { std::printf("skip llm (compilado sem llama.cpp)\n"); return; }
    if (!path || !*path) { std::printf("skip llm (GIRASSOL_TEST_GGUF não definido)\n"); return; }
    girassol::llm::LlmEngine eng;
    std::string err;
    CHECK(eng.load(path, 2, 512, &err), ("llama.cpp carrega GGUF via mmap " + err).c_str());
    if (!eng.loaded()) return;
    std::printf("modelo: %s (%llu bytes)\n", eng.description().c_str(), static_cast<unsigned long long>(eng.modelBytes()));
    const auto r1 = eng.generate("Voce e um cidadao.", "Escolha uma acao.", kGrammar, 96);
    const auto r2 = eng.generate("Voce e um cidadao.", "Escolha uma acao.", kGrammar, 96);
    std::printf("llm saída: %s | erro=%s | %d+%d tokens, %.1f tok/s\n", r1.text.c_str(), r1.error.c_str(), r1.promptTokens, r1.genTokens, r1.tokensPerSecond);
    CHECK(r1.ok, "geração sob gramática GBNF concluída");
    CHECK(r1.text.rfind("{\"acao\":", 0) == 0 || r1.text.rfind("{ \"acao\":", 0) == 0, "saída começa com objeto JSON da gramática");
    CHECK(!r1.text.empty() && r1.text.back() == '}', "saída termina o objeto JSON");
    const bool actionOk = r1.text.find("\"COMER\"") != std::string::npos || r1.text.find("\"DESCANSAR\"") != std::string::npos ||
                          r1.text.find("\"SOCIALIZAR\"") != std::string::npos;
    CHECK(actionOk, "ação restrita às opções da gramática");
    CHECK(r1.text == r2.text, "decodificação gulosa reprodutível (mesma entrada → mesma saída)");
    const auto bad = eng.generate("x", "y", "root ::= ((", 8);
    CHECK(!bad.ok, "gramática inválida é rejeitada");
    eng.unload();
    CHECK(!eng.loaded(), "descarregamento do modelo");
}

int main() {
    const std::string self = girassol::hw::runSelfTest();
    CHECK(self.empty(), ("autoteste nativo " + self).c_str());

    const uint64_t sig = paritySignature();
    std::printf("assinatura_paridade_tsetlin=0x%016llx\n", static_cast<unsigned long long>(sig));
    // Valor dourado também verificado em core/src/test/.../TsetlinParityTest.kt
    CHECK(sig == 0x28e9517335a66aeaULL, "paridade bit a bit com KotlinTsetlinMachine");

    auto caps = girassol::hw::probeSoc();
    std::printf("hardware: %s\n", caps.toCompactString().c_str());
    CHECK(caps.cpuCount >= 1, "sonda de hardware");

    uint64_t c1 = 0, c2 = 0;
    const double ips = girassol::hw::benchmarkTsetlin(5ULL, 20000, &c1);
    girassol::hw::benchmarkTsetlin(5ULL, 20000, &c2);
    std::printf("tsetlin inferências/s=%.0f\n", ips);
    CHECK(c1 == c2, "benchmark determinístico (mesmo checksum)");

    girassol::qd::MapElitesArchive archive(8);
    girassol::qd::EliteNicheCell a;
    a.citizenId = 1; a.fitness = 0.5f; a.behaviorDescriptor = {0.1f, 0.2f, 0.3f};
    CHECK(archive.submitCandidate(a), "MAP-Elites aceita nicho novo");
    girassol::qd::EliteNicheCell worse = a; worse.fitness = 0.1f;
    CHECK(!archive.submitCandidate(worse), "MAP-Elites rejeita elite pior no mesmo nicho");
    CHECK(archive.occupiedNiches() == 1, "MAP-Elites ocupação");

    int found = 0;
    const uint64_t nav = navSignature(&found);
    std::printf("assinatura_paridade_nav=0x%016llx (caminhos=%d)\n", static_cast<unsigned long long>(nav), found);
    CHECK(nav == 0x4c5f15c838631491ULL, "navegação C++ idêntica ao Pathfinding Kotlin");
    CHECK(found > 32, "vetor de navegação exercita caminhos reais");

    float m0 = 0, m1 = 0;
    const uint64_t lora = loraSignature(&m0, &m1);
    std::printf("assinatura_paridade_lora=0x%016llx mse %.5f -> %.5f\n", static_cast<unsigned long long>(lora), m0, m1);
    CHECK(lora == 0xed47248ecc78c3ccULL, "LoRA C++ idêntico ao LoraMath Kotlin (bit a bit)");
    CHECK(std::isfinite(m1) && m1 < m0, "LoRA reduz MSE");

    girassol::audio::AmbientBinauralSynth synth(48000);
    girassol::audio::AmbientParams ap; ap.stability = 0.2f; ap.masterGain = 0.5f;
    synth.setParams(ap);
    synth.pushSpeech(3.0f, 1.0f, 0.4f);
    std::vector<float> buf(4800 * 2);
    for (int i = 0; i < 20; ++i) synth.render(buf.data(), 4800);
    double sum = 0; float peak = 0; bool finite = true;
    for (float v : buf) { sum += v * v; peak = std::max(peak, std::fabs(v)); finite = finite && std::isfinite(v); }
    std::printf("audio rms=%.4f pico=%.4f\n", std::sqrt(sum / buf.size()), peak);
    CHECK(finite && peak <= 1.0f && sum > 0, "sintetizador binaural gera sinal finito e limitado");
    float diffLR = 0;
    for (size_t i = 0; i + 1 < buf.size(); i += 2) diffLR += std::fabs(buf[i] - buf[i + 1]);
    CHECK(diffLR > 0.01f, "canais esquerdo/direito diferem (batimento binaural)");

    llmTest();

    std::printf(gFailures == 0 ? "TODOS OS TESTES NATIVOS PASSARAM\n" : "%d FALHA(S)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
