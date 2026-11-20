#include "soc_hardware_probe.hpp"

#include <chrono>
#include <dlfcn.h>
#include <sstream>
#include <thread>
#include <vector>

#include "../cognitive/tsetlin_machine.hpp"
#include "../common/splitmix64.hpp"

#if defined(__aarch64__) && defined(__linux__)
#include <sys/auxv.h>
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1UL << 20)
#endif
#ifndef HWCAP_FPHP
#define HWCAP_FPHP (1UL << 9)
#endif
#ifndef HWCAP_SVE
#define HWCAP_SVE (1UL << 22)
#endif
#ifndef HWCAP2_SVE2
#define HWCAP2_SVE2 (1UL << 1)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1UL << 13)
#endif
#endif

namespace girassol::hw {

namespace {
bool canDlopen(const char* name) {
    void* h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (h == nullptr) return false;
    dlclose(h);
    return true;
}
}  // namespace

std::string SocCapabilities::toCompactString() const {
    std::ostringstream os;
    os << "abi=" << abi << ";cpus=" << cpuCount << ";neon=" << neon << ";dotprod=" << dotProd
       << ";i8mm=" << i8mm << ";sve=" << sve << ";sve2=" << sve2 << ";fp16=" << fp16
       << ";avx2=" << avx2 << ";sse42=" << sse42 << ";opencl=" << openClAvailable
       << ";vulkan=" << vulkanAvailable;
    return os.str();
}

SocCapabilities probeSoc() {
    SocCapabilities c;
    c.cpuCount = static_cast<int>(std::max(1U, std::thread::hardware_concurrency()));
#if defined(__aarch64__)
    c.abi = "arm64-v8a";
    c.neon = true;
#if defined(__linux__)
    const unsigned long hw = getauxval(AT_HWCAP);
    const unsigned long hw2 = getauxval(AT_HWCAP2);
    c.dotProd = (hw & HWCAP_ASIMDDP) != 0;
    c.fp16 = (hw & HWCAP_FPHP) != 0;
    c.sve = (hw & HWCAP_SVE) != 0;
    c.sve2 = (hw2 & HWCAP2_SVE2) != 0;
    c.i8mm = (hw2 & HWCAP2_I8MM) != 0;
#endif
#elif defined(__arm__)
    c.abi = "armeabi-v7a";
#if defined(__ARM_NEON)
    c.neon = true;
#endif
#elif defined(__x86_64__)
    c.abi = "x86_64";
    __builtin_cpu_init();
    c.avx2 = __builtin_cpu_supports("avx2");
    c.sse42 = __builtin_cpu_supports("sse4.2");
#else
    c.abi = "desconhecida";
#endif
    c.openClAvailable = canDlopen("libOpenCL.so");
    c.vulkanAvailable = canDlopen("libvulkan.so");
    return c;
}

double benchmarkTsetlin(uint64_t seed, int iterations, uint64_t* checksumOut) {
    girassol::tsetlin::PersistentTsetlinMachine tm(16, 20, 64, 3.5f, 8, seed);
    girassol::SplitMix64 rng(seed ^ 0xA5A5A5A5ULL);
    std::vector<uint8_t> f(16);
    for (int i = 0; i < 64; ++i) {
        for (auto& b : f) b = static_cast<uint8_t>(rng.next() & 1U);
        tm.trainStep(f, (f[0] ^ f[3]) == 0);
    }
    uint64_t checksum = 1469598103934665603ULL;
    const int n = std::max(1, iterations);
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < n; ++i) {
        for (auto& b : f) b = static_cast<uint8_t>(rng.next() & 1U);
        const int32_t v = tm.predictVoteEarlyExit(f);
        checksum = (checksum ^ static_cast<uint64_t>(static_cast<uint32_t>(v))) * 1099511628211ULL;
    }
    const auto t1 = std::chrono::steady_clock::now();
    if (checksumOut != nullptr) *checksumOut = checksum;
    const double secs = std::chrono::duration<double>(t1 - t0).count();
    return secs > 0.0 ? static_cast<double>(n) / secs : 0.0;
}

std::string runSelfTest() {
    // 1. Vetor de referência do SplitMix64 (semente 0) — igual ao Kotlin.
    girassol::SplitMix64 r(0);
    if (r.next() != 0xE220A8397B1DCDAFULL) return "splitmix64: vetor de referência divergente";
    // 2. Aprendizado e serialização GRST.
    girassol::tsetlin::PersistentTsetlinMachine tm(4, 20, 64, 3.0f, 8, 7ULL);
    const std::vector<uint8_t> pos{1, 1, 0, 0};
    const std::vector<uint8_t> neg{0, 0, 1, 1};
    for (int i = 0; i < 400; ++i) {
        tm.trainStep(pos, true);
        tm.trainStep(neg, false);
    }
    if (tm.predictVoteEarlyExit(pos) <= 0) return "tsetlin: não aprendeu padrão positivo";
    if (tm.predictVoteEarlyExit(neg) >= 0) return "tsetlin: não aprendeu padrão negativo";
    const auto blob = tm.serializeBinary();
    girassol::tsetlin::PersistentTsetlinMachine copy(1, 1);
    if (!copy.deserializeBinary(blob)) return "tsetlin: deserialização GRST falhou";
    if (copy.serializeBinary() != blob) return "tsetlin: roundtrip GRST divergente";
    if (copy.predictVoteEarlyExit(pos) != tm.predictVoteEarlyExit(pos)) return "tsetlin: voto divergente após roundtrip";
    return {};
}

}  // namespace girassol::hw
