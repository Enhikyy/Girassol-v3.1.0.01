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
#include "memory/binary_episodic_index.hpp"
#include "evolution/map_elites_aurora.hpp"
#include "audio/ambient_binaural_synth.hpp"
#include "forge/lora_trainer_realtime.hpp"
#include "hardware/cpu_features.hpp"
#include "hardware/proc_memory.hpp"
#include "hardware/soc_hardware_probe.hpp"
#include "common/utf8.hpp"
#include "llm/llm_engine.hpp"
#include "llm/semantic_entropy.hpp"
#include <filesystem>
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


    // --- v3.4: prefixo imutável + slots por cidadão + fast-forward + temperatura semeada
    {
        const std::string slotDir = (std::filesystem::temp_directory_path() / "girassol_slots_test").string();
        std::filesystem::remove_all(slotDir);
        girassol::llm::LlmEngine e2;
        girassol::llm::LlmLoadConfig cfg;
        cfg.nThreads = 2; cfg.nCtx = 512; cfg.flashAttn = true; cfg.kvType = girassol::llm::KvCacheType::Q8_0;
        cfg.slotDir = slotDir; cfg.maxSlots = 2;
        CHECK(e2.load(path, cfg, &err), ("carrega com flash attention + KV Q8_0 " + err).c_str());
        std::printf("config efetiva: %s\n", e2.runtimeConfig().c_str());
        CHECK(!e2.runtimeConfig().empty(), "configuração de runtime reportada");
        const std::string world = "Regras imutaveis da cidade de Girassol: responda em JSON curto com acao, pensamento e fala.";
        girassol::llm::LlmRequest q;
        q.system = world + " Voce e Ana."; q.worldPrefix = world; q.user = "Escolha uma acao."; q.grammar = kGrammar;
        q.jsonKeys = {"acao", "pensamento", "fala"}; q.slotKey = "cidadao-1"; q.maxTokens = 320;
        q.temperature = 0.9f; q.seed = 1234;
        const auto a1 = e2.generate(q);
        std::printf("ff saída: %s | forçados=%d reaproveitados=%d gen=%d\n", a1.text.c_str(), a1.forcedTokens, a1.reusedTokens, a1.genTokens);
        CHECK(a1.ok && a1.text.rfind("{\"acao\":\"", 0) == 0 && a1.text.back() == '}', "fast-forward produz JSON válido");
        CHECK(a1.forcedTokens > 0, "fast-forward injeta tokens fixos da gramática");
        const auto a2 = e2.generate(q);
        CHECK(a2.reusedTokens > 0, "prefixo do mundo reaproveitado do KV-cache");
        CHECK(a1.text == a2.text, "temperatura + semente fixa → saída reprodutível");
        girassol::llm::LlmRequest qb = q; qb.system = world + " Voce e Bento."; qb.slotKey = "cidadao-2";
        const auto b1 = e2.generate(qb);
        CHECK(b1.ok && b1.reusedTokens > 0, "outro cidadão reaproveita só o prefixo imutável");
        girassol::llm::LlmRequest qc = q; qc.system = world + " Voce e Carla."; qc.slotKey = "cidadao-3";
        e2.generate(qc);
        CHECK(e2.slotCount() == 2, "LRU limita slots por cidadão");
        girassol::llm::LlmRequest qa = q; qa.slotKey = "cidadao-2"; qa.system = world + " Voce e Bento.";
        const auto r3 = e2.generate(qa);
        CHECK(r3.slotRestored && r3.reusedTokens > 0, "slot do cidadão restaurado com llama_state_seq_load_file");
        size_t files = 0;
        for (const auto& f : std::filesystem::directory_iterator(slotDir)) files += f.path().extension() == ".slot";
        CHECK(files == 2, "slots persistidos em disco (.slot)");
        e2.clearSlots();
        CHECK(e2.slotCount() == 0, "disjuntor limpa os slots");
        e2.unload();
        std::filesystem::remove_all(slotDir);
    }

    // benchmark tokens/s (pp + tg), mesmo caminho usado pelo botão da aba Sistema
    girassol::llm::LlmEngine eb;
    CHECK(eb.load(path, 2, 512, &err), "recarrega para benchmark");
    const auto bm = eb.bench(32, 16);
    std::printf("bench: pp=%.1f tok/s tg=%.1f tok/s (%d+%d) %s\n", bm.promptTps, bm.genTps, bm.nPrompt, bm.nGen, bm.error.c_str());
    CHECK(bm.ok && bm.promptTps > 0 && bm.genTps > 0, "benchmark de tokens/s do llama.cpp");
    const std::string si = girassol::llm::LlmEngine::systemInfo();
    std::printf("llama system_info: %s\n", si.c_str());
    CHECK(!si.empty(), "llama_print_system_info disponível");
    eb.unload();
}

static void semanticEntropyTests() {
    using girassol::llm::SemanticEntropyController;
    SemanticEntropyController rep;
    for (int i = 0; i < 6; ++i) rep.push("preciso comer alguma coisa agora");
    SemanticEntropyController var;
    var.push("preciso comer alguma coisa agora"); var.push("a horta da praca floresceu hoje cedo");
    var.push("vou descansar perto do rio azul"); var.push("ouvi boatos sobre o conselho novo");
    var.push("quero trocar sementes com a vizinha"); var.push("o mercado esta cheio de gente feliz");
    std::printf("entropia: repetitiva=%.3f variada=%.3f T=%.3f/%.3f\n", rep.ngramEntropy(), var.ngramEntropy(), rep.temperature(), var.temperature());
    CHECK(rep.ngramEntropy() < var.ngramEntropy(), "falas repetitivas têm menor entropia de 3-gramas");
    CHECK(rep.temperature() > var.temperature(), "repetição eleva a temperatura");
    CHECK(rep.temperature() <= SemanticEntropyController::kMaxT && var.temperature() >= SemanticEntropyController::kMinT, "T limitada a [0.45, 1.15]");
    SemanticEntropyController w;
    for (int i = 0; i < 10; ++i) w.push("x y z");
    CHECK(w.size() == SemanticEntropyController::kWindow, "janela das últimas 6 falas");
    CHECK(SemanticEntropyController::temperatureFor("a b c d\na b c d\na b c d") > 0.70f, "temperatureFor separa por linha");
    CHECK(SemanticEntropyController::words("Olá, MUNDO!") == std::vector<std::string>({"olá", "mundo"}), "tokenização em palavras minúsculas");
}

static void cpuFeatureTests() {
    using namespace girassol::hw;
    // Snapdragon 695 (Cortex-A78 + A55) / Helio G99 (A76 + A55): ASIMD, FPHP, ASIMDHP, ASIMDDP; sem I8MM
    const uint64_t a78 = kHwcapAsimd | kHwcapFphp | kHwcapAsimdhp | kHwcapAsimddp | 1ULL; // +FP
    auto f = decodeAarch64Hwcaps(a78, 0);
    CHECK(f.neon && f.dotprod && f.fp16 && !f.i8mm, "decodifica HWCAP do Cortex-A78");
    CHECK(selectLlmVariant(f, true) == LlmVariant::DotProd, "Cortex-A78 → variante dotprod (sem i8mm → sem SIGILL)");
    auto g = decodeAarch64Hwcaps(a78 | kHwcapSve, kHwcap2I8mm | kHwcap2Sve2);
    CHECK(g.i8mm && g.sve2, "decodifica HWCAP2 I8MM/SVE2");
    CHECK(selectLlmVariant(g, true) == LlmVariant::I8mm, "ARMv9 com i8mm → variante i8mm");
    auto h = decodeAarch64Hwcaps(kHwcapAsimd | kHwcapFphp | kHwcapAsimddp, kHwcap2I8mm);
    CHECK(!h.fp16 && selectLlmVariant(h, true) == LlmVariant::Base, "FPHP sem ASIMDHP → base");
    auto a53 = decodeAarch64Hwcaps(kHwcapAsimd, 0);
    CHECK(selectLlmVariant(a53, true) == LlmVariant::Base, "Cortex-A53 → base");
    CHECK(selectLlmVariant(g, false) == LlmVariant::Base, "build não-arm64 → base");
    CHECK(std::string(cpuBackendLibrary(LlmVariant::I8mm, true)) == "libggml-cpu-android_armv8.6_1.so", "i8mm → backend armv8.6_1");
    CHECK(std::string(cpuBackendLibrary(LlmVariant::DotProd, true)) == "libggml-cpu-android_armv8.2_2.so", "dotprod → backend armv8.2_2");
    CHECK(std::string(cpuBackendLibrary(LlmVariant::Base, true)) == "libggml-cpu-android_armv8.0_1.so", "base → backend armv8.0_1");
    CHECK(describeCpuFeatures(f) == "NEON=1 DOTPROD=1 FP16=1 I8MM=0 SVE=0 SVE2=0", "descrição das flags SIMD");

    const std::string status = "Name:\tgirassol\nVmPeak:\t 9000 kB\nVmSize:\t 8000 kB\nVmHWM:\t  1900000 kB\nVmRSS:\t  1234567 kB\n"
                               "RssAnon:\t  1000 kB\nRssFile:\t 2000 kB\nVmSwap:\t 0 kB\n";
    auto m = parseProcStatus(status);
    CHECK(m.vmRssKb == 1234567 && m.vmHwmKb == 1900000 && m.vmSizeKb == 8000, "parse de /proc/self/status");
    CHECK(m.rssAnonKb == 1000 && m.rssFileKb == 2000 && m.vmSwapKb == 0, "parse RssAnon/RssFile/VmSwap");
    CHECK(parseKbField("XVmRSS: 5 kB\n", "VmRSS") == -1, "campo só casa no início de linha");
    auto self = readSelfProcMemory();
    std::printf("proc: rss=%lld kB hwm=%lld kB\n", (long long)self.vmRssKb, (long long)self.vmHwmKb);
    CHECK(self.vmRssKb > 0 && self.vmHwmKb >= self.vmRssKb, "VmRSS/VmHWM reais do processo");
    CHECK(girassol::sanitizeUtf8(std::string("ok\xff\xc3")) .find('\xff') == std::string::npos, "sanitização UTF-8");
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
    CHECK(nav == 0x30cd3f41edf40ac4ULL, "navegação C++ (A* + clearance DistrictNavMesh) idêntica ao Pathfinding Kotlin");
    CHECK(found > 32, "vetor de navegação exercita caminhos reais");

    {
        const uint64_t bin = girassol::memory::paritySignature();
        std::printf("assinatura_paridade_memoria_binaria=0x%016llx\n", static_cast<unsigned long long>(bin));
        CHECK(bin == 0x01ddb39b8e7c5fb9ULL, "SimHash bit[256] + Hamming popcount idênticos ao EpisodicIndex Kotlin");
        const auto a = girassol::memory::simHash({"colher", "horta", "#sucesso"});
        const auto b = girassol::memory::simHash({"colher", "horta", "#falha"});
        const auto c = girassol::memory::simHash({"usina", "energia", "reparo", "#manter_usina"});
        CHECK(sizeof(a) == 32, "embedding binário ocupa 32 bytes");
        CHECK(girassol::memory::hamming(a, b) < girassol::memory::hamming(a, c), "Hamming aproxima textos parecidos");
        const auto top = girassol::memory::topKHamming(a, {c, b, a}, 2);
        CHECK(top.size() == 2 && top[0] == 2 && top[1] == 1, "estágio 1 top-k por Hamming");
    }

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
    {
        girassol::audio::AmbientBinauralSynth duck(48000);
        duck.setParams(ap);
        std::vector<float> b2(480 * 2);
        for (int i = 0; i < 10; ++i) duck.render(b2.data(), 480);
        CHECK(duck.duckGain() > 0.99f, "sem fala a trilha Trautonium fica em ganho 1");
        duck.setExternalSpeech(true);
        for (int i = 0; i < 40; ++i) duck.render(b2.data(), 480);
        std::printf("ducking durante fala=%.3f\n", duck.duckGain());
        CHECK(duck.duckGain() < 0.30f, "audio ducking abaixa a trilha durante a fala (TTS)");
        duck.setExternalSpeech(false);
        for (int i = 0; i < 200; ++i) duck.render(b2.data(), 480);
        CHECK(duck.duckGain() > 0.95f, "trilha volta após a fala");
    }

    cpuFeatureTests();
    semanticEntropyTests();
    llmTest();

    std::printf(gFailures == 0 ? "TODOS OS TESTES NATIVOS PASSARAM\n" : "%d FALHA(S)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
