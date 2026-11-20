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
#include "forge/lora_trainer_realtime.hpp"
#include "hardware/soc_hardware_probe.hpp"
#include "navigation/recast_rvo2_nav.hpp"

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

    girassol::nav::DistrictNavMesh nav(24, 24, 1.0f, 0.0f, 0.0f);
    std::vector<girassol::nav::ObstacleBox2D> obstacles{{8.0f, 0.0f, 9.0f, 18.0f}};
    nav.bakeObstacles(obstacles);
    const auto path = nav.findStraightPath({1.0f, 1.0f}, {20.0f, 2.0f});
    CHECK(!path.empty(), "navegação encontra caminho contornando obstáculo");

    girassol::forge::RealTimeLoraAdapter lora(8, 4, 2, 16.0f, 7ULL);
    std::vector<float> x(8, 0.5f), base(4, 0.0f), target{1.0f, -1.0f, 0.5f, 0.0f};
    float first = lora.trainStepMse(base, x, target, 5e-3f);
    float last = first;
    for (int i = 0; i < 200; ++i) last = lora.trainStepMse(base, x, target, 5e-3f);
    std::printf("lora mse %.4f -> %.4f\n", first, last);
    CHECK(std::isfinite(last) && last < first, "LoRA reduz MSE");
    lora.consolidateEwcAnchor();

    std::printf(gFailures == 0 ? "TODOS OS TESTES NATIVOS PASSARAM\n" : "%d FALHA(S)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
