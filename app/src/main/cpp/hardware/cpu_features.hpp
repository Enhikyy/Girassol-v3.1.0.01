#pragma once
// Detecção de recursos SIMD ARM64 em tempo de execução (getauxval) e escolha da variante
// do motor llama.cpp. Nada aqui é compilado com flags de arquitetura além do baseline
// armv8-a: é seguro executar em qualquer arm64 antes de decidir qual biblioteca carregar.
#include <cstdint>
#include <string>

#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#endif

namespace girassol::hw {

struct CpuFeatures {
    bool neon = false;     // ASIMD
    bool dotprod = false;  // ASIMDDP (SDOT/UDOT)
    bool fp16 = false;     // FPHP + ASIMDHP (aritmética vetorial fp16)
    bool i8mm = false;     // FEAT_I8MM (SMMLA) — ausente no Cortex-A78/A76/A55
    bool sve = false;
    bool sve2 = false;
};

// Bits de AT_HWCAP/AT_HWCAP2 do kernel Linux arm64 (arch/arm64/include/uapi/asm/hwcap.h).
constexpr uint64_t kHwcapAsimd = 1ULL << 1;
constexpr uint64_t kHwcapFphp = 1ULL << 9;
constexpr uint64_t kHwcapAsimdhp = 1ULL << 10;
constexpr uint64_t kHwcapAsimddp = 1ULL << 20;
constexpr uint64_t kHwcapSve = 1ULL << 22;
constexpr uint64_t kHwcap2Sve2 = 1ULL << 1;
constexpr uint64_t kHwcap2I8mm = 1ULL << 13;

inline CpuFeatures decodeAarch64Hwcaps(uint64_t hw, uint64_t hw2) {
    CpuFeatures f;
    f.neon = (hw & kHwcapAsimd) != 0;
    f.dotprod = f.neon && (hw & kHwcapAsimddp) != 0;
    f.fp16 = f.neon && (hw & kHwcapFphp) != 0 && (hw & kHwcapAsimdhp) != 0;
    f.i8mm = f.neon && (hw2 & kHwcap2I8mm) != 0;
    f.sve = (hw & kHwcapSve) != 0;
    f.sve2 = f.sve && (hw2 & kHwcap2Sve2) != 0;
    return f;
}

inline CpuFeatures detectCpuFeatures() {
#if defined(__aarch64__) && defined(__linux__)
    return decodeAarch64Hwcaps(getauxval(AT_HWCAP), getauxval(AT_HWCAP2));
#else
    return CpuFeatures{};
#endif
}

// Variantes empacotadas no APK (ver app/src/main/cpp/CMakeLists.txt):
//  BASE    = armv8-a (qualquer arm64; também x86_64 no emulador)
//  DOTPROD = armv8.2-a+dotprod+fp16 + KleidiAI (Snapdragon 695, Helio G99, Cortex-A55/A76/A78)
//  I8MM    = armv8.2-a+dotprod+fp16+i8mm + KleidiAI (Cortex-A510/A710/X2 em diante)
// i8mm NUNCA é exigido sem que o HWCAP2 o confirme: evita SIGILL no Cortex-A78.
enum class LlmVariant : int { Base = 0, DotProd = 1, I8mm = 2 };

inline LlmVariant selectLlmVariant(const CpuFeatures& f, bool aarch64) {
    if (!aarch64 || !f.neon) return LlmVariant::Base;
    if (f.dotprod && f.fp16 && f.i8mm) return LlmVariant::I8mm;
    if (f.dotprod && f.fp16) return LlmVariant::DotProd;
    return LlmVariant::Base;
}

inline bool isAarch64Build() {
#if defined(__aarch64__)
    return true;
#else
    return false;
#endif
}

inline std::string describeCpuFeatures(const CpuFeatures& f) {
    std::string s;
    auto add = [&](const char* n, bool v) { s += n; s += v ? "=1 " : "=0 "; };
    add("NEON", f.neon); add("DOTPROD", f.dotprod); add("FP16", f.fp16); add("I8MM", f.i8mm);
    add("SVE", f.sve); add("SVE2", f.sve2);
    if (!s.empty()) s.pop_back();
    return s;
}

}  // namespace girassol::hw
