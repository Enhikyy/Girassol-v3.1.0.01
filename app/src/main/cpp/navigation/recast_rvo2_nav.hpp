#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <queue>
#include <utility>
#include <vector>

namespace girassol::nav {

struct Vec2 {
    float x = 0.0f;
    float z = 0.0f;
};

struct ObstacleBox2D {
    float minX = 0.0f;
    float minZ = 0.0f;
    float maxX = 0.0f;
    float maxZ = 0.0f;
};

struct NavAgentState {
    uint64_t id = 0;
    Vec2 pos{};
    Vec2 vel{};
    Vec2 prefVel{};
    float radius = 0.42f;
    float maxSpeed = 2.4f;
};

class DistrictNavMesh {
public:
    DistrictNavMesh(int gridW, int gridH, float cellSize, float originX, float originZ)
        : w_(std::max(1, gridW)),
          h_(std::max(1, gridH)),
          cellSize_(std::max(0.1f, cellSize)),
          originX_(originX),
          originZ_(originZ),
          walkable_(static_cast<size_t>(std::max(1, gridW)) * static_cast<size_t>(std::max(1, gridH)), 1) {}

    template <typename BoxT>
    void bakeObstacles(const std::vector<BoxT>& obstacles, float agentClearance = 0.45f) {
        std::fill(walkable_.begin(), walkable_.end(), 1);
        for (int gz = 0; gz < h_; ++gz) {
            for (int gx = 0; gx < w_; ++gx) {
                const float wx = originX_ + (static_cast<float>(gx) + 0.5f) * cellSize_;
                const float wz = originZ_ + (static_cast<float>(gz) + 0.5f) * cellSize_;
                for (const auto& box : obstacles) {
                    if (wx >= box.minX - agentClearance && wx <= box.maxX + agentClearance &&
                        wz >= box.minZ - agentClearance && wz <= box.maxZ + agentClearance) {
                        walkable_[static_cast<size_t>(gz) * static_cast<size_t>(w_) + static_cast<size_t>(gx)] = 0;
                        break;
                    }
                }
            }
        }
    }

    std::vector<Vec2> findStraightPath(Vec2 start, Vec2 goal) const {
        auto toGrid = [&](Vec2 p) -> std::pair<int, int> {
            const int gx = std::clamp(static_cast<int>((p.x - originX_) / cellSize_), 0, w_ - 1);
            const int gz = std::clamp(static_cast<int>((p.z - originZ_) / cellSize_), 0, h_ - 1);
            return {gx, gz};
        };
        const auto [sx, sz] = toGrid(start);
        const auto [gx, gz] = toGrid(goal);
        const int total = w_ * h_;
        std::vector<float> gScore(static_cast<size_t>(total), 1e9f);
        std::vector<int> parent(static_cast<size_t>(total), -1);

        auto idx = [&](int x, int z) -> int { return z * w_ + x; };
        using Node = std::pair<float, int>;
        std::priority_queue<Node, std::vector<Node>, std::greater<Node>> open;

        const int startIdx = idx(sx, sz);
        const int goalIdx = idx(gx, gz);
        gScore[static_cast<size_t>(startIdx)] = 0.0f;
        open.emplace(0.0f, startIdx);

        const int dx[8] = {1, -1, 0, 0, 1, 1, -1, -1};
        const int dz[8] = {0, 0, 1, -1, 1, -1, 1, -1};
        const float cost[8] = {1.0f, 1.0f, 1.0f, 1.0f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f};

        while (!open.empty()) {
            const auto [f, curr] = open.top();
            open.pop();
            if (f > gScore[static_cast<size_t>(curr)] + 1e4f) continue;
            if (curr == goalIdx) break;
            const int cx = curr % w_;
            const int cz = curr / w_;
            for (int d = 0; d < 8; ++d) {
                const int nx = cx + dx[d];
                const int nz = cz + dz[d];
                if (nx < 0 || nx >= w_ || nz < 0 || nz >= h_) continue;
                const int nIdx = idx(nx, nz);
                if (walkable_[static_cast<size_t>(nIdx)] == 0 && nIdx != goalIdx) continue;
                const float tentative = gScore[static_cast<size_t>(curr)] + cost[d];
                if (tentative < gScore[static_cast<size_t>(nIdx)]) {
                    gScore[static_cast<size_t>(nIdx)] = tentative;
                    parent[static_cast<size_t>(nIdx)] = curr;
                    const float h = std::hypot(static_cast<float>(gx - nx), static_cast<float>(gz - nz));
                    open.emplace(tentative + h, nIdx);
                }
            }
        }

        std::vector<Vec2> path;
        if (parent[static_cast<size_t>(goalIdx)] == -1 && startIdx != goalIdx) {
            path.push_back(goal);
            return path;
        }
        int trace = goalIdx;
        while (trace != -1 && trace != startIdx) {
            const int tx = trace % w_;
            const int tz = trace / w_;
            path.push_back({
                originX_ + (static_cast<float>(tx) + 0.5f) * cellSize_,
                originZ_ + (static_cast<float>(tz) + 0.5f) * cellSize_
            });
            trace = parent[static_cast<size_t>(trace)];
        }
        std::reverse(path.begin(), path.end());
        if (!path.empty()) path.back() = goal;
        return path;
    }

private:
    int w_, h_;
    float cellSize_, originX_, originZ_;
    std::vector<uint8_t> walkable_;
};

inline void stepRvo2OrcaVelocities(std::vector<NavAgentState>& agents, float timeHorizon = 2.0f, float dt = 0.05f) {
    const size_t n = agents.size();
    const float safeHorizon = std::max(0.1f, timeHorizon);
    std::vector<Vec2> nextVels(n);

    for (size_t i = 0; i < n; ++i) {
        Vec2 vNew = agents[i].prefVel;
        for (size_t j = 0; j < n; ++j) {
            if (i == j) continue;
            const float rx = agents[j].pos.x - agents[i].pos.x;
            const float rz = agents[j].pos.z - agents[i].pos.z;
            const float vx = agents[i].vel.x - agents[j].vel.x;
            const float vz = agents[i].vel.z - agents[j].vel.z;
            const float distSq = rx * rx + rz * rz;
            const float combinedR = agents[i].radius + agents[j].radius;
            const float combinedRSq = combinedR * combinedR;

            if (distSq > combinedRSq) {
                const float wx = vx - rx / safeHorizon;
                const float wz = vz - rz / safeHorizon;
                const float wLenSq = wx * wx + wz * wz;
                const float dot = wx * rx + wz * rz;
                if (dot < 0.0f && dot * dot > combinedRSq * wLenSq) {
                    const float wLen = std::sqrt(std::max(wLenSq, 1e-8f));
                    const float mag = (combinedR / safeHorizon) - wLen;
                    vNew.x += 0.5f * (wx / wLen) * mag;
                    vNew.z += 0.5f * (wz / wLen) * mag;
                }
            } else {
                const float dist = std::sqrt(std::max(distSq, 1e-8f));
                const float push = (combinedR - dist) / std::max(dt, 1e-3f);
                vNew.x -= 0.5f * (rx / dist) * push;
                vNew.z -= 0.5f * (rz / dist) * push;
            }
        }
        const float speed = std::hypot(vNew.x, vNew.z);
        if (speed > agents[i].maxSpeed && speed > 1e-6f) {
            vNew.x = (vNew.x / speed) * agents[i].maxSpeed;
            vNew.z = (vNew.z / speed) * agents[i].maxSpeed;
        }
        nextVels[i] = vNew;
    }

    for (size_t i = 0; i < n; ++i) {
        agents[i].vel = nextVels[i];
        agents[i].pos.x += agents[i].vel.x * dt;
        agents[i].pos.z += agents[i].vel.z * dt;
    }
}

} // namespace girassol::nav