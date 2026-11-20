#pragma once
// A* 4-conexo com custo estigmérgico — espelho exato de
// core/src/main/kotlin/.../world/Pathfinding.kt (findPathGrid).
// Mesma aritmética float (compilar com -ffp-contract=off), mesma chave de prioridade
// (bits do float << 32 | índice, min-heap) e mesmo limite de expansão: os caminhos
// são idênticos aos da JVM, preservando a reprodutibilidade por semente.
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <queue>
#include <vector>

namespace girassol::nav {

// Ordinais de TileType (Kotlin): GRAMA, RUA, CALCADA, EDIFICIO, CAMPO, PRACA, AGUA
inline bool tileWalkable(int32_t t) { return !(t == 3 || t == 6); }
inline float tileCost(int32_t t) {
    if (t == 1 || t == 2) return 0.8f;
    if (t == 4) return 1.2f;
    return 1.0f;
}

inline uint32_t floatBits(float f) {
    uint32_t u;
    std::memcpy(&u, &f, sizeof(u));
    return u;
}

class GridAStar {
public:
    std::vector<int32_t> findPath(int32_t w, const int32_t* tiles, int32_t sx, int32_t sz, int32_t gx, int32_t gz,
                                  const float* trail, const float* danger) {
        std::vector<int32_t> path;
        if (w <= 0 || gx < 0 || gx >= w || gz < 0 || gz >= w) return path;
        const int32_t start = sz * w + sx;
        const int32_t goal = gz * w + gx;
        if (start == goal) return path;
        const size_t n = static_cast<size_t>(w) * static_cast<size_t>(w);
        if (start < 0 || static_cast<size_t>(start) >= n) return path;
        g_.assign(n, std::numeric_limits<float>::max());
        parent_.assign(n, -1);
        closed_.assign(n, 0);
        std::priority_queue<int64_t, std::vector<int64_t>, std::greater<int64_t>> open;
        auto key = [](float f, int32_t idx) -> int64_t {
            const uint64_t k = (static_cast<uint64_t>(floatBits(f)) << 32) | static_cast<uint64_t>(static_cast<uint32_t>(idx));
            return static_cast<int64_t>(k);
        };
        g_[start] = 0.0f;
        open.push(key(h(sx, sz, gx, gz), start));
        static const int32_t dx[4] = {1, -1, 0, 0};
        static const int32_t dz[4] = {0, 0, 1, -1};
        int64_t expanded = 0;
        while (!open.empty()) {
            const int32_t cur = static_cast<int32_t>(open.top() & 0xFFFFFFFFLL);
            open.pop();
            if (closed_[cur]) continue;
            closed_[cur] = 1;
            if (cur == goal) break;
            if (++expanded > static_cast<int64_t>(n)) break;
            const int32_t cx = cur % w;
            const int32_t cz = cur / w;
            for (int d = 0; d < 4; ++d) {
                const int32_t nx = cx + dx[d];
                const int32_t nz = cz + dz[d];
                if (nx < 0 || nx >= w || nz < 0 || nz >= w) continue;
                const int32_t ni = nz * w + nx;
                if (closed_[ni]) continue;
                const int32_t tile = tiles[ni];
                if (!tileWalkable(tile) && ni != goal) continue;
                float cost = tileCost(tile);
                if (trail) { float t = trail[ni] / 10.0f; if (t > 1.0f) t = 1.0f; cost *= (1.0f - 0.35f * t); }
                if (danger) { float t = danger[ni] / 5.0f; if (t > 2.0f) t = 2.0f; cost *= (1.0f + t); }
                const float ng = g_[cur] + cost;
                if (ng < g_[ni]) {
                    g_[ni] = ng;
                    parent_[ni] = cur;
                    const float hh = h(nx, nz, gx, gz) * 0.5f;
                    open.push(key(ng + hh, ni));
                }
            }
        }
        if (parent_[goal] == -1) return path;
        int32_t t = goal;
        while (t != start && t != -1) { path.push_back(t); t = parent_[t]; }
        std::reverse(path.begin(), path.end());
        return path;
    }

private:
    static float h(int32_t x, int32_t z, int32_t gx, int32_t gz) {
        const int32_t a = x > gx ? x - gx : gx - x;
        const int32_t b = z > gz ? z - gz : gz - z;
        return static_cast<float>(a + b);
    }
    std::vector<float> g_;
    std::vector<int32_t> parent_;
    std::vector<uint8_t> closed_;
};

}  // namespace girassol::nav
