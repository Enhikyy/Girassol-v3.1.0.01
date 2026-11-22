// v4.0 — implementação dos ativos procedurais (ver ProceduralWorldAssets.hpp).
#include "render/ProceduralWorldAssets.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <numbers>

namespace girassol::render {
namespace {

constexpr float kPi = std::numbers::pi_v<float>;

// ------------------------------------------------------------------ ruído determinístico

inline uint32_t mix32(uint32_t h) {
    h ^= h >> 16; h *= 0x7feb352du;
    h ^= h >> 15; h *= 0x846ca68bu;
    h ^= h >> 16;
    return h;
}

inline uint32_t hash3(int x, int y, uint32_t seed) {
    const uint32_t ux = static_cast<uint32_t>(x), uy = static_cast<uint32_t>(y);
    return mix32(ux * 0x8da6b343u ^ mix32(uy * 0xd8163841u ^ mix32(seed * 0xcb1ab31fu + 0x9e3779b9u)));
}

inline float rnd(int x, int y, uint32_t seed) { return static_cast<float>(hash3(x, y, seed) & 0xffffffu) / 16777215.0f; }

inline int wrapi(int i, int p) { const int r = i % p; return r < 0 ? r + p : r; }
inline float fade(float t) { return t * t * (3.f - 2.f * t); }
inline float fract(float x) { return x - std::floor(x); }
inline float clamp01(float x) { return std::clamp(x, 0.f, 1.f); }
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float smoothstep(float e0, float e1, float x) { return fade(clamp01((x - e0) / (e1 - e0))); }

/** Ruído de valor periódico (u, v ∈ [0,1) cobrem px × py células) — texturas sem emenda. */
float vnoise(float u, float v, int px, int py, uint32_t seed) {
    const float x = u * static_cast<float>(px), y = v * static_cast<float>(py);
    const float fx = std::floor(x), fy = std::floor(y);
    const int ix = static_cast<int>(fx), iy = static_cast<int>(fy);
    const float tx = fade(x - fx), ty = fade(y - fy);
    const int x0 = wrapi(ix, px), x1 = wrapi(ix + 1, px), y0 = wrapi(iy, py), y1 = wrapi(iy + 1, py);
    const float a = rnd(x0, y0, seed), b = rnd(x1, y0, seed), c = rnd(x0, y1, seed), d = rnd(x1, y1, seed);
    return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
}

float fbm(float u, float v, int period, int octaves, uint32_t seed, int periodY = 0) {
    float sum = 0.f, amp = 0.5f, norm = 0.f;
    int px = period, py = periodY > 0 ? periodY : period;
    for (int o = 0; o < octaves; ++o) {
        sum += amp * vnoise(u, v, px, py, seed + static_cast<uint32_t>(o) * 1013u);
        norm += amp; amp *= 0.5f; px *= 2; py *= 2;
    }
    return sum / norm;
}

// ------------------------------------------------------------------ cor

inline float srgbToLinear(float c) { return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f); }
inline float linearToSrgb(float c) { c = clamp01(c); return c <= 0.0031308f ? c * 12.92f : 1.055f * std::pow(c, 1.f / 2.4f) - 0.055f; }

struct Rgb { float r, g, b; };
inline Rgb hexLin(uint32_t hex) {
    return { srgbToLinear(((hex >> 16) & 0xff) / 255.f), srgbToLinear(((hex >> 8) & 0xff) / 255.f), srgbToLinear((hex & 0xff) / 255.f) };
}
inline Rgb operator*(Rgb a, float s) { return { a.r * s, a.g * s, a.b * s }; }
inline Rgb operator+(Rgb a, Rgb b) { return { a.r + b.r, a.g + b.g, a.b + b.b }; }
inline Rgb mixc(Rgb a, Rgb b, float t) { return { lerp(a.r, b.r, t), lerp(a.g, b.g, t), lerp(a.b, b.b, t) }; }

inline uint8_t toByte(float x) { return static_cast<uint8_t>(std::lround(clamp01(x) * 255.f)); }

/** Saída de um texel antes da codificação. */
struct Texel {
    Rgb albedo{0.18f, 0.18f, 0.18f};  // linear
    float ao = 1.f, rough = 0.8f, metal = 0.f;
    float height = 0.f;               // relevo (unidades arbitrárias → normal)
    Rgb emissive{0.f, 0.f, 0.f};      // linear
};

// ------------------------------------------------------------------ superfícies

struct Ctx { int size; uint32_t seed; };

Texel wetAsphalt(const Ctx& c, float u, float v) {
    Texel t;
    const int n = c.size;
    const float grain = fbm(u, v, 96, 3, c.seed + 1);
    const float pebble = vnoise(u, v, n / 3, n / 3, c.seed + 2);
    const float stones = smoothstep(0.80f, 0.90f, pebble);
    const float repair = fbm(u, v, 3, 3, c.seed + 3);
    float lum = 0.030f + 0.030f * grain + 0.055f * stones;
    lum *= 0.82f + 0.36f * repair;
    t.albedo = Rgb{1.0f, 0.985f, 0.96f} * lum;
    // poças: depressões de baixa frequência; halo úmido em volta
    const float w = fbm(u, v, 4, 4, c.seed + 11);
    const float wet = smoothstep(0.585f, 0.625f, w);
    const float damp = smoothstep(0.50f, 0.585f, w);
    float rough = kDryAsphaltRoughness + (grain - 0.5f) * 0.08f;
    rough = lerp(rough, 0.42f, damp * 0.65f);
    float h = grain * 0.55f + stones * 0.45f;
    // faixas gastas: eixo tracejado (u≈0.5) e bordas contínuas
    const float dash = fract(v * 2.f) < 0.55f ? 1.f : 0.f;
    const float center = (std::fabs(u - 0.5f) < 0.016f ? 1.f : 0.f) * dash;
    const float edge = (std::fabs(u - 0.06f) < 0.010f || std::fabs(u - 0.94f) < 0.010f) ? 1.f : 0.f;
    const float wear = smoothstep(0.30f, 0.46f, fbm(u, v, 24, 4, c.seed + 17));
    const float paint = std::max(center, edge * 0.85f) * wear;
    if (paint > 0.f) {
        const Rgb paintCol = center > 0.f ? Rgb{0.62f, 0.50f, 0.18f} : Rgb{0.58f, 0.57f, 0.54f};
        t.albedo = mixc(t.albedo, paintCol, paint);
        rough = lerp(rough, 0.62f, paint);
        h += 0.18f * paint;
    }
    t.albedo = t.albedo * lerp(1.f, 0.55f, std::max(damp * 0.45f, wet * 0.9f));
    // núcleo da poça: lâmina d'água plana, rugosidade exata da especificação
    rough = lerp(rough, kPuddleRoughness, wet);
    h = lerp(h, 0.30f, wet);
    t.rough = rough;
    t.height = h * 1.6f;
    t.ao = 0.72f + 0.28f * clamp01(h + wet);
    return t;
}

constexpr int kFacadeBays = 6, kFacadeFloors = 6;
constexpr float kBayW = 2.5f, kFloorH = 10.f / 3.f;

/** Janelas acesas: permutação determinística ⇒ exatamente round(35 %) das 36 janelas. */
std::array<uint8_t, kFacadeBays * kFacadeFloors> litWindows(uint32_t seed) {
    constexpr int N = kFacadeBays * kFacadeFloors;
    std::array<int, N> idx{};
    for (int i = 0; i < N; ++i) idx[i] = i;
    for (int i = N - 1; i > 0; --i) { const int j = static_cast<int>(hash3(i, 7, seed) % static_cast<uint32_t>(i + 1)); std::swap(idx[i], idx[j]); }
    std::array<uint8_t, N> lit{};
    const int count = static_cast<int>(std::lround(kWindowLitFraction * N));
    for (int k = 0; k < count; ++k) lit[idx[k]] = static_cast<uint8_t>(1 + (hash3(idx[k], 3, seed) % 5u < 3u ? 0 : 1));  // 1 âmbar, 2 ciano
    return lit;
}

Texel facade(const Ctx& c, float u, float v, const std::array<uint8_t, kFacadeBays * kFacadeFloors>& lit) {
    Texel t;
    const float bx = u * kFacadeBays, fy = v * kFacadeFloors;
    const int bay = std::min(static_cast<int>(bx), kFacadeBays - 1), floor = std::min(static_cast<int>(fy), kFacadeFloors - 1);
    const float x = fract(bx) * kBayW, y = fract(fy) * kFloorH;  // metros dentro do módulo (y para cima)
    const Rgb concrete = hexLin(0x6E767A);
    const Rgb teal = hexLin(0x1D5961);
    const float stain = fbm(u, v, 8, 4, c.seed + 5);
    const float streak = fbm(u, v, 48, 3, c.seed + 6, 4);
    t.albedo = concrete * (0.86f + 0.26f * stain);
    t.rough = 0.80f + (stain - 0.5f) * 0.10f;
    t.height = 0.5f + (fbm(u, v, 64, 3, c.seed + 8) - 0.5f) * 0.25f;
    const bool inWinX = x > 0.30f && x < 2.20f, inWinY = y > 1.00f && y < 3.05f;
    // juntas de dilatação: verticais entre módulos e horizontais na linha de cada laje
    const bool vJoint = x < 0.025f || x > kBayW - 0.025f;
    const bool hJoint = y < 0.03f || (y > 3.27f && y < 3.30f);
    if (inWinX && inWinY) {
        const bool frame = x < 0.35f || x > 2.15f || y < 1.05f || y > 3.00f || std::fabs(x - 1.25f) < 0.025f || std::fabs(y - 2.35f) < 0.02f;
        if (frame) {
            t.albedo = Rgb{0.030f, 0.032f, 0.034f}; t.metal = 0.80f; t.rough = 0.40f; t.height = 0.25f;
        } else {
            t.albedo = Rgb{0.018f, 0.022f, 0.028f}; t.metal = 0.f; t.rough = 0.06f; t.height = 0.10f;
            const uint8_t l = lit[floor * kFacadeBays + bay];
            if (l != 0) {
                const float gy = smoothstep(1.05f, 3.0f, y);
                const Rgb warm{1.00f, 0.56f, 0.22f}, cold{0.42f, 0.86f, 1.00f};
                Rgb e = (l == 1 ? warm : cold) * (0.55f + 0.45f * gy);
                if (hash3(bay, floor, c.seed + 21) % 3u == 0u && fract(y * 14.f) < 0.32f) e = e * 0.35f;  // persianas
                const float sx = rnd(bay, floor, c.seed + 23) * 1.2f + 0.6f;  // silhueta de mobiliário
                if (std::fabs(x - sx) < 0.18f && y < 1.75f) e = e * 0.25f;
                t.emissive = e;
                t.albedo = Rgb{0.05f, 0.045f, 0.04f};
            }
        }
    } else if (y < 1.00f && (bay % 2 == 1)) {
        // peitoril de painel metálico teal com costuras a cada 0,5 m
        const bool seam = fract(x / 0.5f) < 0.03f;
        t.albedo = teal * (0.92f + 0.16f * fbm(u, v, 128, 2, c.seed + 9, 16));
        t.metal = kTealMetallic; t.rough = kTealRoughness + (streak - 0.5f) * 0.04f;
        t.height = seam ? 0.35f : 0.62f;
        if (seam) t.ao = 0.7f;
    } else {
        // concreto: manchas de chuva sob as janelas e furos de tirante a cada 0,6 m
        if (inWinX && y < 1.0f) t.albedo = t.albedo * (0.80f + 0.25f * streak);
        const float tx = fract(x / 0.625f) - 0.5f, ty = fract((y + 0.15f) / 0.5f) - 0.5f;
        if (tx * tx + ty * ty < 0.004f) { t.height = 0.25f; t.ao = 0.55f; t.albedo = t.albedo * 0.6f; }
    }
    if (vJoint || hJoint) { t.height = 0.0f; t.ao = 0.45f; t.albedo = t.albedo * 0.5f; t.metal = 0.f; t.rough = 0.9f; }
    return t;
}

Texel sidewalk(const Ctx& c, float u, float v) {
    Texel t;
    const float sx = u * 8.f, sz = v * 8.f;
    const int ix = static_cast<int>(sx), iz = static_cast<int>(sz);
    const float fx = fract(sx), fz = fract(sz);
    const float tone = 0.90f + 0.18f * rnd(ix, iz, c.seed + 1);
    const float grain = fbm(u, v, 128, 3, c.seed + 2);
    t.albedo = Rgb{0.30f, 0.30f, 0.29f} * (tone * (0.88f + 0.24f * grain));
    const float tiltX = rnd(ix, iz, c.seed + 3) - 0.5f, tiltZ = rnd(ix, iz, c.seed + 4) - 0.5f;
    t.height = 0.6f + 0.08f * (tiltX * fx + tiltZ * fz) + 0.12f * grain;
    const float wet = smoothstep(0.62f, 0.70f, fbm(u, v, 3, 4, c.seed + 5));
    t.rough = lerp(0.78f + (grain - 0.5f) * 0.1f, 0.32f, wet);
    t.albedo = t.albedo * lerp(1.f, 0.7f, wet);
    const float j = std::min(std::min(fx, 1.f - fx), std::min(fz, 1.f - fz));
    if (j < 0.018f) { t.height = 0.0f; t.ao = 0.5f; t.albedo = t.albedo * 0.45f; t.rough = 0.9f; }
    return t;
}

Texel concreteBoard(const Ctx& c, float u, float v) {
    Texel t;
    const float bz = v * 10.f;
    const int board = static_cast<int>(bz);
    const float fb = fract(bz);
    const float wood = fbm(u, v, 6, 4, c.seed + static_cast<uint32_t>(board) * 7u, 160);
    const float tone = 0.9f + 0.18f * rnd(board, 1, c.seed);
    t.albedo = Rgb{0.245f, 0.248f, 0.240f} * (tone * (0.9f + 0.2f * wood));
    t.rough = 0.82f + (wood - 0.5f) * 0.08f;
    t.height = 0.5f + 0.2f * wood;
    if (fb < 0.03f || fb > 0.97f) { t.height = 0.25f; t.ao = 0.6f; t.albedo = t.albedo * 0.7f; }
    const float hx = fract(u * 3.2f) - 0.5f, hy = fract(v * 3.2f) - 0.5f;
    if (hx * hx + hy * hy < 0.0025f) { t.height = 0.1f; t.ao = 0.5f; t.albedo = t.albedo * 0.55f; }
    return t;
}

/**
 * Tijolo aparente desgastado: fiadas de 7,5 cm (tijolo 6,5 cm + junta 1 cm) em aparelho inglês
 * (fiada de frente alternada com fiada de topo), 1 repetição = 1,2 m. Cada tijolo tem tom e
 * desgaste próprios (hash), arestas lascadas, eflorescência salina perto da base das juntas,
 * fuligem/escorrimento vertical de baixa frequência e argamassa recuada (AO + relevo).
 */
Texel brick(const Ctx& c, float u, float v) {
    Texel t;
    constexpr int kCourses = 16;                       // 1,2 m / 7,5 cm
    const float cv = v * kCourses;
    const int course = static_cast<int>(cv);
    const float fv = fract(cv);
    const bool header = (course & 1) == 1;             // fiada de topo (tijolos de 11 cm)
    const int perRow = header ? 10 : 5;                // 1,2 m / 24 cm ou 12 cm
    const float shift = header ? 0.5f / perRow : 0.f;
    const float cu = fract(u + shift) * perRow;
    const int col = static_cast<int>(cu);
    const float fu = fract(cu);
    const float jointV = 0.133f;                       // 1 cm de 7,5 cm
    const float jointU = header ? 0.083f : 0.042f;     // 1 cm de 12 cm / 24 cm
    const float ev = std::min(fv, 1.f - fv), eu = std::min(fu, 1.f - fu);
    const float inMortar = std::max(1.f - smoothstep(0.f, jointV * 0.5f, ev), 1.f - smoothstep(0.f, jointU * 0.5f, eu));
    const uint32_t id = hash3(col + (header ? 4096 : 0), course, c.seed + 5u);
    const float tone = static_cast<float>(id & 0xffu) / 255.f;
    const float burn = static_cast<float>((id >> 8) & 0xffu) / 255.f;
    const float grit = fbm(u, v, 128, 3, c.seed + 21u);
    const float chip = smoothstep(0.62f, 0.78f, vnoise(u, v, 64, 64, c.seed + 23u));
    // paleta: vermelho-terracota, alaranjado e tijolos queimados (escuros)
    Rgb base = mixc(hexLin(0x8A3B26), hexLin(0xA65A3A), tone);
    base = mixc(base, hexLin(0x4E2418), smoothstep(0.78f, 1.0f, burn));
    base = base * (0.86f + 0.24f * grit);
    // fuligem e escorrimento (faixas verticais suaves) + eflorescência acima das juntas
    const float soot = smoothstep(0.45f, 0.85f, fbm(u * 0.5f, v * 0.125f, 3, 4, c.seed + 29u, 1));
    const float effl = smoothstep(0.58f, 0.80f, fbm(u, v, 12, 3, c.seed + 31u)) * (1.f - smoothstep(0.f, 0.35f, fv));
    base = mixc(base, hexLin(0x2A2522), soot * 0.38f);
    base = mixc(base, hexLin(0xC9C2B4), effl * 0.30f);
    const Rgb mortar = hexLin(0x8F8A80) * (0.82f + 0.30f * grit);
    t.albedo = mixc(base, mortar, inMortar);
    t.rough = lerp(kBrickRoughness + (grit - 0.5f) * 0.06f, kMortarRoughness, inMortar);
    t.rough = lerp(t.rough, 0.70f, effl * 0.4f);
    // relevo: face do tijolo levemente abaulada, arestas lascadas, argamassa 6 mm recuada
    const float bulge = 0.08f * (1.f - (2.f * eu) * (2.f * eu) * 0.15f) * (1.f - (2.f * ev) * 0.1f);
    float h = 0.62f + bulge + 0.10f * grit - 0.18f * chip * smoothstep(0.25f, 0.f, std::min(ev, eu));
    h = lerp(h, 0.22f + 0.06f * grit, inMortar);
    t.height = h;
    t.ao = lerp(0.92f + 0.08f * grit, 0.52f, inMortar) * (1.f - 0.15f * chip);
    return t;
}

Texel carpet(const Ctx& c, float u, float v) {
    Texel t;
    const float tx = u * 4.f, tz = v * 4.f;
    const int ix = static_cast<int>(tx), iz = static_cast<int>(tz);
    const float fx = fract(tx), fz = fract(tz);
    // instalação em quarto de volta: a direção da trama alterna entre placas vizinhas
    const bool alongU = ((ix + iz) & 1) == 0;
    const float a = alongU ? fx : fz, b = alongU ? fz : fx;
    const float loops = 0.5f + 0.5f * std::sin(a * 2.f * kPi * 90.f) * std::sin(b * 2.f * kPi * 45.f);
    const float rib = 0.5f + 0.5f * std::sin(b * 2.f * kPi * 45.f);
    const float fiber = vnoise(u, v, c.size / 2, c.size / 2, c.seed + 3);
    const float mottle = fbm(u, v, 16, 3, c.seed + 4);
    const Rgb dark = hexLin(0x133024), light = hexLin(0x1B3F30);
    const float k = clamp01(0.25f + 0.35f * loops + 0.25f * fiber + 0.3f * (mottle - 0.5f) + 0.08f * (rnd(ix, iz, c.seed) - 0.5f));
    t.albedo = mixc(dark, light, k);
    t.rough = kCarpetRoughness + (fiber - 0.5f) * 0.03f;
    t.height = 0.35f * loops + 0.25f * rib + 0.25f * fiber;
    t.ao = 0.80f + 0.2f * loops;
    const float j = std::min(std::min(fx, 1.f - fx), std::min(fz, 1.f - fz));
    if (j < 0.012f) {  // oclusão das juntas entre placas
        const float o = j / 0.012f;
        t.ao *= 0.45f + 0.55f * o; t.height *= o; t.albedo = t.albedo * (0.55f + 0.45f * o);
    }
    return t;
}

Texel coffer(const Ctx& c, float u, float v) {
    Texel t;
    const float e = std::min(std::min(u, 1.f - u), std::min(v, 1.f - v));
    const float grain = fbm(u, v, 64, 3, c.seed + 1);
    const Rgb plaster{0.62f, 0.64f, 0.62f};
    t.albedo = plaster * (0.94f + 0.1f * grain);
    t.rough = 0.72f;
    if (e < 0.06f) {
        t.height = 0.f;
    } else if (e < 0.14f) {  // chanfro: rampa de relevo ⇒ normal inclinada
        t.height = (e - 0.06f) / 0.08f * 1.2f;
        t.ao = 0.75f + 0.25f * (e - 0.06f) / 0.08f;
    } else if (e < 0.16f) {  // fresta escura antes do difusor
        t.height = 1.0f; t.ao = 0.35f; t.albedo = t.albedo * 0.25f;
    } else {
        t.height = 1.2f;
        t.albedo = Rgb{0.80f, 0.82f, 0.82f};
        t.rough = 0.45f;
        const float fall = smoothstep(0.16f, 0.30f, e);
        t.emissive = Rgb{0.92f, 0.97f, 1.0f} * (0.55f + 0.45f * fall);
    }
    return t;
}

Texel partition(const Ctx& c, float u, float v) {
    Texel t;
    const float fine = vnoise(u, v, c.size / 2, c.size / 2, c.seed + 1);
    const float cloud = fbm(u, v, 12, 4, c.seed + 2);
    t.albedo = hexLin(0xD6DBD7) * (0.965f + 0.05f * cloud + 0.02f * fine);
    t.rough = kPartitionRoughness + (fine - 0.5f) * 0.02f;
    t.height = 0.5f + 0.18f * fine + 0.1f * cloud;
    return t;
}

Texel billboard(const Ctx& c, float u, float v) {
    Texel t;
    t.albedo = Rgb{0.02f, 0.02f, 0.025f};
    t.rough = 0.3f;
    const float e = std::min(std::min(u, 1.f - u), std::min(v, 1.f - v));
    float lum = 0.12f + 0.10f * v;  // fundo em degradê
    if (e < 0.035f) lum = 0.f;
    else if (e < 0.05f) lum = 1.0f;  // moldura de neon
    // "texto": linhas de glifos em blocos
    const float row = v * 6.f; const int r = static_cast<int>(row); const float fr = fract(row);
    if (r >= 1 && r <= 3 && fr > 0.2f && fr < 0.8f && u > 0.42f && u < 0.92f) {
        const int g = static_cast<int>(u * 40.f);
        if (hash3(g, r, c.seed) % 4u != 0u && fract(u * 40.f) < 0.78f) lum = 0.95f;
    }
    const float dx = u - 0.22f, dy = v - 0.45f;  // emblema circular
    const float d = std::sqrt(dx * dx + dy * dy);
    if (std::fabs(d - 0.14f) < 0.018f || d < 0.05f) lum = 1.0f;
    t.emissive = Rgb{1.f, 1.f, 1.f} * lum;
    t.height = 0.5f;
    return t;
}

Texel grass(const Ctx& c, float u, float v) {
    Texel t;
    const float blades = vnoise(u, v, c.size / 2, c.size / 2, c.seed + 1);
    const float patchy = fbm(u, v, 6, 4, c.seed + 2);
    const Rgb g0{0.040f, 0.075f, 0.028f}, g1{0.085f, 0.130f, 0.045f}, soil{0.075f, 0.060f, 0.042f};
    Rgb g = mixc(g0, g1, blades);
    t.albedo = mixc(g, soil, smoothstep(0.62f, 0.75f, patchy));
    t.rough = 0.9f;
    t.height = 0.6f * blades + 0.3f * patchy;
    t.ao = 0.7f + 0.3f * blades;
    return t;
}

// ------------------------------------------------------------------ mips

/** Reduz ORM + normal de [src] (tamanho s) para [dst] (s/2): normais renormalizadas, Toksvig na rugosidade. */
void downsampleOrmNormal(const uint8_t* ormSrc, const uint8_t* nSrc, int s, uint8_t* ormDst, uint8_t* nDst) {
    const int d = std::max(1, s / 2);
    for (int y = 0; y < d; ++y) {
        for (int x = 0; x < d; ++x) {
            float ao = 0, ro = 0, me = 0, nx = 0, ny = 0, nz = 0;
            int cnt = 0;
            for (int j = 0; j < 2; ++j) for (int i = 0; i < 2; ++i) {
                const int sx = std::min(s - 1, x * 2 + i), sy = std::min(s - 1, y * 2 + j);
                const std::size_t k = (static_cast<std::size_t>(sy) * s + sx) * 4;
                ao += ormSrc[k] / 255.f; ro += ormSrc[k + 1] / 255.f; me += ormSrc[k + 2] / 255.f;
                float a = nSrc[k] / 127.5f - 1.f, b = nSrc[k + 1] / 127.5f - 1.f, cc = nSrc[k + 2] / 127.5f - 1.f;
                const float l = std::sqrt(a * a + b * b + cc * cc);
                if (l > 1e-6f) { a /= l; b /= l; cc /= l; }
                nx += a; ny += b; nz += cc; ++cnt;
            }
            nx /= cnt; ny /= cnt; nz /= cnt; ao /= cnt; ro /= cnt; me /= cnt;
            const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
            // Toksvig: a dispersão das normais filhas vira rugosidade (evita brilho cintilante longe)
            const float variance = len > 1e-4f ? (1.f - len) / len : 1.f;
            const float alpha = ro * ro;
            const float alpha2 = std::min(1.f, std::sqrt(alpha * alpha + std::min(variance, 1.f)));
            ro = std::sqrt(alpha2);
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; } else { nx = 0; ny = 0; nz = 1; }
            const std::size_t o = (static_cast<std::size_t>(y) * d + x) * 4;
            ormDst[o] = toByte(ao); ormDst[o + 1] = toByte(ro); ormDst[o + 2] = toByte(me); ormDst[o + 3] = 255;
            nDst[o] = toByte(nx * 0.5f + 0.5f); nDst[o + 1] = toByte(ny * 0.5f + 0.5f); nDst[o + 2] = toByte(nz * 0.5f + 0.5f); nDst[o + 3] = 255;
        }
    }
}

float bumpStrength(Surface s) {
    switch (s) {
        case Surface::WetAsphalt: return 5.0f;
        case Surface::Facade: return 9.0f;
        case Surface::Sidewalk: return 6.0f;
        case Surface::Concrete: return 5.0f;
        case Surface::Carpet: return 3.5f;
        case Surface::Coffer: return 7.0f;
        case Surface::Partition: return 1.2f;
        case Surface::Billboard: return 0.5f;
        case Surface::Grass: return 4.0f;
        case Surface::Brick: return 8.0f;
        default: return 1.f;
    }
}

}  // namespace

// ------------------------------------------------------------------ API pública: texturas

int mipLevelCount(int size) {
    int l = 1;
    while (size > 1) { size >>= 1; ++l; }
    return l;
}

std::vector<MipLevel> mipChain(int size) {
    std::vector<MipLevel> out;
    std::size_t off = 0;
    for (int s = size;; s = std::max(1, s / 2)) {
        const std::size_t b = static_cast<std::size_t>(s) * s * 4;
        out.push_back({s, off, b});
        off += b;
        if (s == 1) break;
    }
    return out;
}

std::size_t mipChainBytes(int size) {
    const auto c = mipChain(size);
    return c.back().offset + c.back().bytes;
}

void generateSurface(Surface s, int size, uint64_t seed, uint8_t* albedo, uint8_t* orm, uint8_t* normal, uint8_t* emissive) {
    const Ctx c{size, static_cast<uint32_t>(seed ^ (seed >> 32)) ^ (static_cast<uint32_t>(s) * 0x51ed27u)};
    const auto lit = litWindows(c.seed);
    std::vector<float> height(static_cast<std::size_t>(size) * size);
    const float inv = 1.f / static_cast<float>(size);
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            const float u = (x + 0.5f) * inv, v = (y + 0.5f) * inv;
            Texel t;
            switch (s) {
                case Surface::WetAsphalt: t = wetAsphalt(c, u, v); break;
                case Surface::Facade: t = facade(c, u, v, lit); break;
                case Surface::Sidewalk: t = sidewalk(c, u, v); break;
                case Surface::Concrete: t = concreteBoard(c, u, v); break;
                case Surface::Carpet: t = carpet(c, u, v); break;
                case Surface::Coffer: t = coffer(c, u, v); break;
                case Surface::Partition: t = partition(c, u, v); break;
                case Surface::Billboard: t = billboard(c, u, v); break;
                case Surface::Grass: t = grass(c, u, v); break;
                case Surface::Brick: t = brick(c, u, v); break;
                default: break;
            }
            const std::size_t k = (static_cast<std::size_t>(y) * size + x) * 4;
            albedo[k] = toByte(linearToSrgb(t.albedo.r)); albedo[k + 1] = toByte(linearToSrgb(t.albedo.g));
            albedo[k + 2] = toByte(linearToSrgb(t.albedo.b)); albedo[k + 3] = 255;
            orm[k] = toByte(t.ao); orm[k + 1] = toByte(t.rough); orm[k + 2] = toByte(t.metal); orm[k + 3] = 255;
            emissive[k] = toByte(linearToSrgb(t.emissive.r)); emissive[k + 1] = toByte(linearToSrgb(t.emissive.g));
            emissive[k + 2] = toByte(linearToSrgb(t.emissive.b)); emissive[k + 3] = 255;
            height[static_cast<std::size_t>(y) * size + x] = t.height;
        }
    }
    // normal em espaço tangente (+Y = direção +v da malha), diferenças centrais com repetição
    const float k = bumpStrength(s) * static_cast<float>(size) / 1024.f;
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            auto H = [&](int xx, int yy) { return height[static_cast<std::size_t>(wrapi(yy, size)) * size + wrapi(xx, size)]; };
            const float dx = (H(x + 1, y) - H(x - 1, y)) * 0.5f * k;
            const float dy = (H(x, y + 1) - H(x, y - 1)) * 0.5f * k;
            float nx = -dx, ny = -dy, nz = 1.f;
            const float l = std::sqrt(nx * nx + ny * ny + nz * nz);
            nx /= l; ny /= l; nz /= l;
            const std::size_t o = (static_cast<std::size_t>(y) * size + x) * 4;
            normal[o] = toByte(nx * 0.5f + 0.5f); normal[o + 1] = toByte(ny * 0.5f + 0.5f); normal[o + 2] = toByte(nz * 0.5f + 0.5f); normal[o + 3] = 255;
        }
    }
    const auto chain = mipChain(size);
    for (std::size_t l = 1; l < chain.size(); ++l) {
        downsampleOrmNormal(orm + chain[l - 1].offset, normal + chain[l - 1].offset, chain[l - 1].size,
                            orm + chain[l].offset, normal + chain[l].offset);
    }
}

float roughnessFraction(const uint8_t* orm, int size, float lo, float hi) {
    std::size_t n = 0;
    const std::size_t total = static_cast<std::size_t>(size) * size;
    for (std::size_t i = 0; i < total; ++i) {
        const float r = orm[i * 4 + 1] / 255.f;
        if (r >= lo && r <= hi) ++n;
    }
    return static_cast<float>(n) / static_cast<float>(total);
}

float emissiveFraction(const uint8_t* emissive, int size, float threshold) {
    std::size_t n = 0;
    const std::size_t total = static_cast<std::size_t>(size) * size;
    for (std::size_t i = 0; i < total; ++i) {
        const float l = 0.2126f * srgbToLinear(emissive[i * 4] / 255.f) + 0.7152f * srgbToLinear(emissive[i * 4 + 1] / 255.f) +
                        0.0722f * srgbToLinear(emissive[i * 4 + 2] / 255.f);
        if (l > threshold) ++n;
    }
    return static_cast<float>(n) / static_cast<float>(total);
}

// ------------------------------------------------------------------ céu / IBL

void skyRadiance(const SkyParams& p, const float dir[3], float out[3]) {
    const float y = dir[1];
    Rgb zenith, horizon, ground;
    float sunGlow = 0.f;
    Rgb sunCol{1.f, 0.9f, 0.75f};
    switch (p.preset) {
        case SkyPreset::Morning:
            zenith = {0.52f, 0.60f, 0.70f}; horizon = {0.86f, 0.84f, 0.80f}; ground = {0.11f, 0.105f, 0.10f};
            sunGlow = 1.6f; sunCol = {1.0f, 0.92f, 0.80f}; break;
        case SkyPreset::Afternoon:
            zenith = {0.40f, 0.45f, 0.56f}; horizon = {0.98f, 0.72f, 0.48f}; ground = {0.10f, 0.088f, 0.078f};
            sunGlow = 2.4f; sunCol = {1.0f, 0.70f, 0.42f}; break;
        case SkyPreset::Night:
            zenith = {0.008f, 0.011f, 0.022f}; horizon = {0.060f, 0.040f, 0.022f}; ground = {0.010f, 0.009f, 0.008f};
            sunGlow = 0.15f; sunCol = {0.55f, 0.65f, 0.9f}; break;
        case SkyPreset::Interior: {
            // salão Lumon: grade de painéis acima, paredes off-white, carpete esmeralda abaixo
            const Rgb ceil{0.95f, 0.98f, 1.0f}, wall{0.40f, 0.43f, 0.41f}, floor{0.030f, 0.070f, 0.050f};
            Rgb c = y > 0.f ? mixc(wall, ceil, smoothstep(0.25f, 0.85f, y)) : mixc(wall, floor, smoothstep(0.f, 0.35f, -y));
            out[0] = c.r; out[1] = c.g; out[2] = c.b;
            return;
        }
    }
    Rgb c;
    if (y >= 0.f) {
        const float t = std::pow(clamp01(y), 0.45f);
        c = mixc(horizon, zenith, t) * (0.70f + 0.30f * clamp01(y));
    } else {
        c = mixc(horizon * 0.45f, ground, smoothstep(0.f, 0.12f, -y));
    }
    const float sd = dir[0] * p.sunDir[0] + dir[1] * p.sunDir[1] + dir[2] * p.sunDir[2];
    if (sd > 0.f && y > -0.05f) {
        const float clear = 1.f - p.overcast;
        const float g = sunGlow * (0.55f * std::pow(sd, 6.f) + clear * 4.f * std::pow(sd, 96.f));
        c = c + sunCol * g;
    }
    out[0] = c.r; out[1] = c.g; out[2] = c.b;
}

std::size_t cubemapBytes(int faceSize) {
    std::size_t b = 0;
    for (int s = faceSize;; s = std::max(1, s / 2)) { b += static_cast<std::size_t>(s) * s * 8 * 6; if (s == 1) break; }
    return b;
}

std::size_t cubemapOffset(int faceSize, int level, int face) {
    std::size_t b = 0;
    int s = faceSize;
    for (int l = 0; l < level; ++l) { b += static_cast<std::size_t>(s) * s * 8 * 6; s = std::max(1, s / 2); }
    return b + static_cast<std::size_t>(s) * s * 8 * static_cast<std::size_t>(face);
}

void cubemapDirection(int face, float u, float v, float out[3]) {
    const float sc = 2.f * u - 1.f, tc = 2.f * v - 1.f;
    float x = 0, y = 0, z = 0;
    switch (face) {  // convenção GL: ver tabela 8.19 da especificação OpenGL ES 3.0
        case 0: x = 1.f;  y = -tc; z = -sc; break;
        case 1: x = -1.f; y = -tc; z = sc;  break;
        case 2: x = sc;   y = 1.f; z = tc;  break;
        case 3: x = sc;   y = -1.f; z = -tc; break;
        case 4: x = sc;   y = -tc; z = 1.f; break;
        default: x = -sc; y = -tc; z = -1.f; break;
    }
    const float l = std::sqrt(x * x + y * y + z * z);
    out[0] = x / l; out[1] = y / l; out[2] = z / l;
}

float lodToPerceptualRoughness(int level, int levels) {
    if (levels <= 1) return 0.f;
    const float x = clamp01(static_cast<float>(level) / static_cast<float>(levels - 1));
    return 1.f - std::sqrt(1.f - x);
}

uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffffu;
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        const uint32_t shift = static_cast<uint32_t>(14 - exp);
        uint32_t h = mant >> shift;
        if ((mant >> (shift - 1)) & 1u) ++h;
        return static_cast<uint16_t>(sign | h);
    }
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7c00u);  // satura em infinito
    uint32_t h = sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) ++h;  // arredondamento
    return static_cast<uint16_t>(h);
}

float halfToFloat(uint16_t h) {
    const uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu, mant = h & 0x3ffu;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) x = sign;
        else { exp = 1; while (!(mant & 0x400u)) { mant <<= 1; --exp; } mant &= 0x3ffu; x = sign | ((exp + 112u) << 23) | (mant << 13); }
    } else if (exp == 31) x = sign | 0x7f800000u | (mant << 13);
    else x = sign | ((exp + 112u) << 23) | (mant << 13);
    float f;
    std::memcpy(&f, &x, 4);
    return f;
}

namespace {
inline void hammersley(uint32_t i, uint32_t n, float& u, float& v) {
    uint32_t b = i;
    b = (b << 16u) | (b >> 16u);
    b = ((b & 0x55555555u) << 1u) | ((b & 0xAAAAAAAAu) >> 1u);
    b = ((b & 0x33333333u) << 2u) | ((b & 0xCCCCCCCCu) >> 2u);
    b = ((b & 0x0F0F0F0Fu) << 4u) | ((b & 0xF0F0F0F0u) >> 4u);
    b = ((b & 0x00FF00FFu) << 8u) | ((b & 0xFF00FF00u) >> 8u);
    u = static_cast<float>(i) / static_cast<float>(n);
    v = static_cast<float>(b) * 2.3283064365386963e-10f;
}
}  // namespace

void generateIblCubemap(const SkyParams& p, int faceSize, int samples, uint16_t* out) {
    const int levels = mipLevelCount(faceSize);
    samples = std::max(8, samples);
    for (int l = 0; l < levels; ++l) {
        const int s = std::max(1, faceSize >> l);
        const float pr = lodToPerceptualRoughness(l, levels);
        const float a = pr * pr;
        for (int f = 0; f < 6; ++f) {
            uint16_t* dst = out + cubemapOffset(faceSize, l, f) / 2;
            for (int y = 0; y < s; ++y) {
                for (int x = 0; x < s; ++x) {
                    float n[3];
                    cubemapDirection(f, (x + 0.5f) / s, (y + 0.5f) / s, n);
                    float acc[3] = {0, 0, 0};
                    if (l == 0 || a < 1e-4f) {
                        skyRadiance(p, n, acc);
                    } else {
                        // base ortonormal em torno de N
                        const float up[3] = {std::fabs(n[2]) < 0.999f ? 0.f : 1.f, 0.f, std::fabs(n[2]) < 0.999f ? 1.f : 0.f};
                        float tx[3] = {up[1] * n[2] - up[2] * n[1], up[2] * n[0] - up[0] * n[2], up[0] * n[1] - up[1] * n[0]};
                        const float tl = std::sqrt(tx[0] * tx[0] + tx[1] * tx[1] + tx[2] * tx[2]);
                        tx[0] /= tl; tx[1] /= tl; tx[2] /= tl;
                        const float ty[3] = {n[1] * tx[2] - n[2] * tx[1], n[2] * tx[0] - n[0] * tx[2], n[0] * tx[1] - n[1] * tx[0]};
                        float wsum = 0.f;
                        for (int i = 0; i < samples; ++i) {
                            float e1, e2;
                            hammersley(static_cast<uint32_t>(i), static_cast<uint32_t>(samples), e1, e2);
                            const float phi = 2.f * kPi * e1;
                            const float cosT = std::sqrt((1.f - e2) / (1.f + (a * a - 1.f) * e2));
                            const float sinT = std::sqrt(std::max(0.f, 1.f - cosT * cosT));
                            const float hx = sinT * std::cos(phi), hy = sinT * std::sin(phi), hz = cosT;
                            const float h[3] = {tx[0] * hx + ty[0] * hy + n[0] * hz, tx[1] * hx + ty[1] * hy + n[1] * hz, tx[2] * hx + ty[2] * hy + n[2] * hz};
                            const float ndh = h[0] * n[0] + h[1] * n[1] + h[2] * n[2];
                            const float L[3] = {2.f * ndh * h[0] - n[0], 2.f * ndh * h[1] - n[1], 2.f * ndh * h[2] - n[2]};
                            const float ndl = L[0] * n[0] + L[1] * n[1] + L[2] * n[2];
                            if (ndl <= 0.f) continue;
                            float c[3];
                            skyRadiance(p, L, c);
                            acc[0] += c[0] * ndl; acc[1] += c[1] * ndl; acc[2] += c[2] * ndl;
                            wsum += ndl;
                        }
                        if (wsum > 0.f) { acc[0] /= wsum; acc[1] /= wsum; acc[2] /= wsum; }
                    }
                    const std::size_t o = (static_cast<std::size_t>(y) * s + x) * 4;
                    dst[o] = floatToHalf(acc[0]); dst[o + 1] = floatToHalf(acc[1]); dst[o + 2] = floatToHalf(acc[2]); dst[o + 3] = floatToHalf(1.f);
                }
            }
        }
    }
}

namespace {
constexpr float kShC[9] = {0.282095f, 0.488603f, 0.488603f, 0.488603f, 1.092548f, 1.092548f, 0.315392f, 1.092548f, 0.546274f};
constexpr float kShA[9] = {1.f, 2.f / 3.f, 2.f / 3.f, 2.f / 3.f, 0.25f, 0.25f, 0.25f, 0.25f, 0.25f};  // Â_l / π
inline void shPoly(const float n[3], float y[9]) {
    const float x = n[0], yy = n[1], z = n[2];
    y[0] = 1.f; y[1] = yy; y[2] = z; y[3] = x;
    y[4] = yy * x; y[5] = yy * z; y[6] = 3.f * z * z - 1.f; y[7] = z * x; y[8] = x * x - yy * yy;
}
}  // namespace

void computeIrradianceSh3(const SkyParams& p, float sh[27]) {
    double acc[27] = {};
    constexpr int NT = 128, NP = 256;
    for (int i = 0; i < NT; ++i) {
        const double th = (i + 0.5) * std::numbers::pi / NT;
        const double st = std::sin(th);
        const double dw = st * (std::numbers::pi / NT) * (2.0 * std::numbers::pi / NP);
        for (int j = 0; j < NP; ++j) {
            const double ph = (j + 0.5) * 2.0 * std::numbers::pi / NP;
            const float d[3] = {static_cast<float>(st * std::cos(ph)), static_cast<float>(std::cos(th)), static_cast<float>(st * std::sin(ph))};
            float L[3];
            skyRadiance(p, d, L);
            float y[9];
            shPoly(d, y);
            for (int k = 0; k < 9; ++k) {
                const double w = static_cast<double>(kShC[k]) * y[k] * dw;
                acc[k * 3] += L[0] * w; acc[k * 3 + 1] += L[1] * w; acc[k * 3 + 2] += L[2] * w;
            }
        }
    }
    for (int k = 0; k < 9; ++k)
        for (int c = 0; c < 3; ++c) sh[k * 3 + c] = static_cast<float>(acc[k * 3 + c] * kShC[k] * kShA[k]);
}

void evalSh3(const float sh[27], const float n[3], float out[3]) {
    float y[9];
    shPoly(n, y);
    out[0] = out[1] = out[2] = 0.f;
    for (int k = 0; k < 9; ++k) { out[0] += sh[k * 3] * y[k]; out[1] += sh[k * 3 + 1] * y[k]; out[2] += sh[k * 3 + 2] * y[k]; }
}

}  // namespace girassol::render
