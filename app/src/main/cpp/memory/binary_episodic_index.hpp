#pragma once
// Estágio 1 da memória episódica: embeddings binários bit[256] (4 × uint64 = 32 bytes) por
// SimHash e distância de Hamming via XOR + __builtin_popcountll. Espelho exato de
// core/.../memory/EpisodicIndex.kt (simHash, hamming, paritySignature) — a assinatura
// dourada é verificada nos dois lados.
#include <algorithm>
#include <array>
#include <cstdint>
#include <string>
#include <vector>

#include "../common/splitmix64.hpp"

namespace girassol::memory {

using Bits256 = std::array<uint64_t, 4>;

inline uint64_t fnv1a64(const std::string& s) {
    uint64_t h = 0xcbf29ce484222325ULL;
    for (unsigned char c : s) {
        h ^= static_cast<uint64_t>(c);
        h *= 0x100000001b3ULL;
    }
    return h;
}

inline Bits256 simHash(const std::vector<std::string>& features) {
    int32_t acc[256] = {0};
    for (const auto& f : features) {
        SplitMix64 rng(fnv1a64(f));
        for (int w = 0; w < 4; ++w) {
            const uint64_t r = rng.next();
            for (int b = 0; b < 64; ++b) acc[w * 64 + b] += ((r >> b) & 1ULL) ? 1 : -1;
        }
    }
    Bits256 out{0, 0, 0, 0};
    for (int i = 0; i < 256; ++i)
        if (acc[i] > 0) out[i / 64] |= (1ULL << (i % 64));
    return out;
}

inline int hamming(const Bits256& a, const Bits256& b) {
    int d = 0;
    for (int i = 0; i < 4; ++i) d += __builtin_popcountll(a[i] ^ b[i]);
    return d;
}

/** Top-k por Hamming (empate: índice maior primeiro, como o Kotlin ordena por id desc). */
inline std::vector<int> topKHamming(const Bits256& q, const std::vector<Bits256>& docs, size_t k) {
    std::vector<int> idx(docs.size());
    for (size_t i = 0; i < docs.size(); ++i) idx[i] = static_cast<int>(i);
    std::vector<int> dist(docs.size());
    for (size_t i = 0; i < docs.size(); ++i) dist[i] = hamming(q, docs[i]);
    std::sort(idx.begin(), idx.end(), [&](int a, int b) { return dist[a] != dist[b] ? dist[a] < dist[b] : a > b; });
    if (idx.size() > k) idx.resize(k);
    return idx;
}

inline uint64_t paritySignature() {
    SplitMix64 rng(31337ULL);
    static const char* words[] = {"horta", "praca", "comer", "sucesso", "falha", "usina", "boato", "colheita", "agua", "escola"};
    std::vector<std::vector<std::string>> docs(32);
    for (auto& d : docs)
        for (int k = 0; k < 4; ++k) d.emplace_back(words[(rng.next() >> 1) % 10ULL]);
    std::vector<Bits256> hashes;
    hashes.reserve(docs.size());
    for (const auto& d : docs) hashes.push_back(simHash(d));
    uint64_t h = 0xcbf29ce484222325ULL;
    for (size_t i = 0; i < hashes.size(); ++i) {
        for (uint64_t w : hashes[i]) h = (h ^ w) * 0x100000001b3ULL;
        const int d = hamming(hashes[i], hashes[(i + 1) % hashes.size()]);
        h = (h ^ static_cast<uint64_t>(static_cast<int64_t>(d))) * 0x100000001b3ULL;
    }
    return h;
}

}  // namespace girassol::memory
