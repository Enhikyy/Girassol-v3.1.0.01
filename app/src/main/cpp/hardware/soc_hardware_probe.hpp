#pragma once
#include <cstdint>
#include <string>

namespace girassol::hw {

// Capacidades detectadas em tempo de execução. Nada é ligado estaticamente a
// drivers de GPU: OpenCL/Vulkan são apenas sondados via dlopen.
struct SocCapabilities {
    std::string abi;
    bool neon = false;        // ASIMD (arm64 sempre tem)
    bool dotProd = false;     // ASIMDDP
    bool i8mm = false;        // FEAT_I8MM
    bool sve = false;
    bool sve2 = false;
    bool fp16 = false;        // FPHP/ASIMDHP
    bool avx2 = false;        // x86_64
    bool sse42 = false;
    bool openClAvailable = false;
    bool vulkanAvailable = false;
    int cpuCount = 1;

    std::string toCompactString() const;
};

SocCapabilities probeSoc();

// Benchmark curto e determinístico do núcleo nativo (Tsetlin + SplitMix64).
// Retorna o número de inferências por segundo e preenche um checksum de paridade.
double benchmarkTsetlin(uint64_t seed, int iterations, uint64_t* checksumOut);

// Autoteste: valida PRNG, serialização GRST e aprendizado do Tsetlin.
// Retorna string vazia em caso de sucesso, ou descrição da falha.
std::string runSelfTest();

}  // namespace girassol::hw
