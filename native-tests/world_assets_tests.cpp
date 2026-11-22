// v4.0 — Testes do gerador C++20 de ativos procedurais da aba Mundo sob ASan + UBSan:
// cadeias de mip, determinismo, constantes PBR exigidas (poças 0.12 / asfalto seco 0.85,
// painéis teal metálico 0.65 / rugosidade 0.35, carpete 0.90, divisória 0.68, ~35 % das
// janelas acesas), normais unitárias em todos os mips, half float, cubemap pré-filtrado e
// harmônicos esféricos de 3 bandas comparados com a integral direta da irradiância.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <numbers>
#include <vector>

#include "render/ProceduralWorldAssets.hpp"

using namespace girassol::render;

namespace {
int gWaFailures = 0;
#define WCHECK(cond, msg)                                                          \
    do {                                                                           \
        if (!(cond)) {                                                             \
            std::fprintf(stderr, "FALHA: %s (%s:%d)\n", msg, __FILE__, __LINE__);  \
            ++gWaFailures;                                                         \
        } else {                                                                   \
            std::printf("ok   %s\n", msg);                                         \
        }                                                                          \
    } while (0)

struct Maps {
    int size;
    std::vector<uint8_t> albedo, orm, normal, emissive;
    explicit Maps(int s) : size(s), albedo(static_cast<size_t>(s) * s * 4), orm(mipChainBytes(s)), normal(mipChainBytes(s)), emissive(static_cast<size_t>(s) * s * 4) {}
    void gen(Surface k, uint64_t seed) { generateSurface(k, size, seed, albedo.data(), orm.data(), normal.data(), emissive.data()); }
};

float meanChannel(const std::vector<uint8_t>& m, int size, int ch) {
    double s = 0;
    const size_t n = static_cast<size_t>(size) * size;
    for (size_t i = 0; i < n; ++i) s += m[i * 4 + ch] / 255.0;
    return static_cast<float>(s / n);
}

float metallicFraction(const std::vector<uint8_t>& orm, int size, float lo, float hi) {
    size_t c = 0;
    const size_t n = static_cast<size_t>(size) * size;
    for (size_t i = 0; i < n; ++i) { const float m = orm[i * 4 + 2] / 255.f; if (m >= lo && m <= hi) ++c; }
    return static_cast<float>(c) / n;
}

bool normalsUnit(const Maps& m) {
    for (const auto& lv : mipChain(m.size)) {
        for (int i = 0; i < lv.size * lv.size; ++i) {
            const uint8_t* p = m.normal.data() + lv.offset + static_cast<size_t>(i) * 4;
            const float x = p[0] / 127.5f - 1.f, y = p[1] / 127.5f - 1.f, z = p[2] / 127.5f - 1.f;
            const float l = std::sqrt(x * x + y * y + z * z);
            if (std::fabs(l - 1.f) > 0.03f || z <= 0.f) return false;
        }
    }
    return true;
}
}  // namespace

int runWorldAssetsTests() {
    // cadeia de mip completa 1024 → 1
    const auto chain = mipChain(1024);
    WCHECK(chain.size() == 11 && mipLevelCount(1024) == 11 && chain.back().size == 1, "cadeia de mip 1024×1024 tem 11 níveis até 1×1");
    size_t expect = 0; for (int s = 1024; s >= 1; s /= 2) expect += static_cast<size_t>(s) * s * 4;
    WCHECK(mipChainBytes(1024) == expect, "bytes da cadeia de mip RGBA8");
    // v4.1: texturas 2048² nos aparelhos de 8/12 GB
    WCHECK(mipLevelCount(2048) == 12 && mipChain(2048).back().size == 1, "cadeia de mip 2048×2048 tem 12 níveis até 1×1");

    // asfalto molhado em resolução real (1024²)
    Maps asphalt(1024);
    asphalt.gen(Surface::WetAsphalt, 42);
    const float puddles = roughnessFraction(asphalt.orm.data(), 1024, 0.08f, 0.12f);
    const float dry = roughnessFraction(asphalt.orm.data(), 1024, 0.75f, 0.90f);
    std::printf("asfalto: poças(r≈0.10)=%.3f seco(r≈0.82)=%.3f\n", puddles, dry);
    WCHECK(kPuddleRoughness == 0.10f && kDryAsphaltRoughness == 0.82f, "constantes v4.1: poça 0.10 / asfalto seco 0.82");
    WCHECK(puddles > 0.06f && puddles < 0.45f, "poças fBm com rugosidade 0.10 cobrem 6–45 % do asfalto");
    WCHECK(dry > 0.25f, "asfalto seco texturizado com rugosidade ≈0.82");
    WCHECK(normalsUnit(asphalt), "normais do asfalto unitárias em todos os mips");
    Maps asphalt2(1024);
    asphalt2.gen(Surface::WetAsphalt, 42);
    WCHECK(asphalt.albedo == asphalt2.albedo && asphalt.orm == asphalt2.orm && asphalt.normal == asphalt2.normal, "gerador determinístico (mesma semente ⇒ mesmos bytes)");
    asphalt2.gen(Surface::WetAsphalt, 43);
    WCHECK(asphalt.orm != asphalt2.orm, "semente diferente ⇒ poças diferentes");
    // Toksvig: rugosidade média não cai ao descer a cadeia
    {
        const MipLevel l3 = mipChain(1024)[3];
        double s0 = meanChannel(asphalt.orm, 1024, 1), s3 = 0;
        for (int i = 0; i < l3.size * l3.size; ++i) s3 += asphalt.orm[l3.offset + static_cast<size_t>(i) * 4 + 1] / 255.0;
        s3 /= l3.size * l3.size;
        std::printf("rugosidade média mip0=%.3f mip3=%.3f\n", s0, s3);
        WCHECK(s3 >= s0 - 0.01, "mips com Toksvig preservam/alargam a rugosidade");
    }

    // fachada: concreto #6E767A, painéis teal e ~35 % das janelas acesas
    Maps facade(1024);
    facade.gen(Surface::Facade, 7);
    const float teal = metallicFraction(facade.orm, 1024, 0.62f, 0.68f);
    const float lit = emissiveFraction(facade.emissive.data(), 1024);
    const float glass = roughnessFraction(facade.orm.data(), 1024, 0.04f, 0.08f);
    std::printf("fachada: teal=%.3f acesas=%.3f vidro=%.3f\n", teal, lit, glass);
    WCHECK(teal > 0.04f, "painéis metálicos teal (metálico 0.65) presentes");
    {
        // rugosidade dos painéis teal ≈ 0.35
        double s = 0; size_t c = 0;
        for (size_t i = 0; i < 1024u * 1024u; ++i) {
            const float m = facade.orm[i * 4 + 2] / 255.f;
            if (m > 0.62f && m < 0.68f) { s += facade.orm[i * 4 + 1] / 255.0; ++c; }
        }
        const double r = c ? s / c : 0;
        std::printf("rugosidade média teal=%.3f\n", r);
        WCHECK(std::fabs(r - kTealRoughness) < 0.03, "painéis teal com rugosidade 0.35");
    }
    const float litShare = lit / glass;
    std::printf("fração de janelas acesas (por área de vidro)=%.3f\n", litShare);
    WCHECK(litShare > 0.25f && litShare < 0.45f, "≈35 % das janelas emissivas acesas");
    WCHECK(normalsUnit(facade), "normais da fachada unitárias em todos os mips");

    // carpete esmeralda 4×4 e divisória off-white
    Maps carpet(512);
    carpet.gen(Surface::Carpet, 3);
    const float cr = meanChannel(carpet.orm, 512, 1);
    const float rr = meanChannel(carpet.albedo, 512, 0), gg = meanChannel(carpet.albedo, 512, 1), bb = meanChannel(carpet.albedo, 512, 2);
    std::printf("carpete: rugosidade=%.3f albedo sRGB=(%.3f %.3f %.3f)\n", cr, rr, gg, bb);
    WCHECK(std::fabs(cr - kCarpetRoughness) < 0.03f, "carpete com rugosidade 0.90");
    WCHECK(gg > rr && gg > bb && gg > 0.07f && gg < 0.30f, "carpete na faixa esmeralda #133024–#1B3F30");
    Maps part(256);
    part.gen(Surface::Partition, 3);
    WCHECK(std::fabs(meanChannel(part.orm, 256, 1) - kPartitionRoughness) < 0.02f, "divisória com rugosidade 0.68");
    Maps coffer(256);
    coffer.gen(Surface::Coffer, 3);
    WCHECK(emissiveFraction(coffer.emissive.data(), 256) > 0.4f, "caixotão com painel emissivo embutido");
    // v4.1: tijolo aparente (juntas de argamassa recuadas, tijolos com rugosidade ≈0.86)
    {
        Maps br(512);
        br.gen(Surface::Brick, 9);
        const float rB = meanChannel(br.albedo, 512, 0), gB = meanChannel(br.albedo, 512, 1), bB = meanChannel(br.albedo, 512, 2);
        const float mortar = roughnessFraction(br.orm.data(), 512, 0.91f, 0.99f);
        const float bricks = roughnessFraction(br.orm.data(), 512, 0.78f, 0.91f);
        std::printf("tijolo: albedo sRGB=(%.3f %.3f %.3f) argamassa=%.3f tijolos=%.3f\n", rB, gB, bB, mortar, bricks);
        WCHECK(rB > gB && gB > bB * 0.9f && rB > 0.25f, "tijolo com albedo terracota (R > G > B)");
        WCHECK(mortar > 0.05f && mortar < 0.40f, "núcleo das juntas de argamassa (rugosidade ≈0.94) ocupa 5–40 %");
        WCHECK(bricks > 0.45f, "faces dos tijolos com rugosidade ≈0.86");
        WCHECK(normalsUnit(br), "normais do tijolo unitárias em todos os mips");
        Maps br2(512);
        br2.gen(Surface::Brick, 9);
        WCHECK(br.albedo == br2.albedo && br.normal == br2.normal, "tijolo determinístico");
    }
    for (int k = 0; k < static_cast<int>(Surface::Count); ++k) {
        Maps m(64);
        m.gen(static_cast<Surface>(k), 1);
        if (!normalsUnit(m)) { WCHECK(false, "normais unitárias em todas as superfícies"); break; }
        if (k == static_cast<int>(Surface::Count) - 1) WCHECK(true, "normais unitárias em todas as superfícies");
    }

    // half float
    bool halfOk = true;
    for (float f : {0.f, 1.f, -2.5f, 0.0001f, 65000.f, 0.333f, 12.75f}) {
        const float r = halfToFloat(floatToHalf(f));
        if (std::fabs(r - f) > std::fabs(f) * 1e-3f + 1e-6f) halfOk = false;
    }
    WCHECK(halfOk, "conversão float ↔ half");

    // cubemap pré-filtrado
    SkyParams sky;
    sky.preset = SkyPreset::Morning;
    const float sd[3] = {0.5f, 0.4f, -0.3f};
    const float sl = std::sqrt(sd[0] * sd[0] + sd[1] * sd[1] + sd[2] * sd[2]);
    sky.sunDir[0] = sd[0] / sl; sky.sunDir[1] = sd[1] / sl; sky.sunDir[2] = sd[2] / sl;
    const int face = 32;
    std::vector<uint16_t> cube(cubemapBytes(face) / 2);
    generateIblCubemap(sky, face, 32, cube.data());
    float up[3];
    cubemapDirection(2, 0.5f, 0.5f, up);
    WCHECK(up[1] > 0.999f, "face +Y aponta para cima");
    float zen[3];
    skyRadiance(sky, up, zen);
    const uint16_t* c0 = cube.data() + cubemapOffset(face, 0, 2) / 2;
    const size_t mid = (static_cast<size_t>(face / 2) * face + face / 2) * 4;
    WCHECK(std::fabs(halfToFloat(c0[mid + 1]) - zen[1]) < 0.02f, "nível 0 do cubemap = radiância do céu");
    bool finite = true;
    for (uint16_t h : cube) if (!std::isfinite(halfToFloat(h))) { finite = false; break; }
    WCHECK(finite, "cubemap sem NaN/inf");
    WCHECK(std::fabs(lodToPerceptualRoughness(5, 6) - 1.f) < 1e-6f && lodToPerceptualRoughness(0, 6) == 0.f, "LOD → rugosidade perceptual (convenção do Filament)");

    // SH3: comparar com a integral direta E(n)/π em algumas direções
    float sh[27];
    computeIrradianceSh3(sky, sh);
    double worst = 0;
    const float dirs[4][3] = {{0, 1, 0}, {1, 0, 0}, {0, 0, -1}, {0, -1, 0}};
    for (const auto& d : dirs) {
        double E[3] = {0, 0, 0};
        constexpr int NT = 96, NP = 192;
        for (int i = 0; i < NT; ++i) {
            const double th = (i + 0.5) * std::numbers::pi / NT, st = std::sin(th);
            const double dw = st * (std::numbers::pi / NT) * (2 * std::numbers::pi / NP);
            for (int j = 0; j < NP; ++j) {
                const double ph = (j + 0.5) * 2 * std::numbers::pi / NP;
                const float w[3] = {static_cast<float>(st * std::cos(ph)), static_cast<float>(std::cos(th)), static_cast<float>(st * std::sin(ph))};
                const double c = w[0] * d[0] + w[1] * d[1] + w[2] * d[2];
                if (c <= 0) continue;
                float L[3];
                skyRadiance(sky, w, L);
                for (int k = 0; k < 3; ++k) E[k] += L[k] * c * dw;
            }
        }
        float s[3];
        evalSh3(sh, d, s);
        for (int k = 0; k < 3; ++k) {
            const double ref = E[k] / std::numbers::pi;
            worst = std::max(worst, std::fabs(s[k] - ref) / std::max(ref, 0.02));
        }
    }
    std::printf("SH3: erro relativo máximo vs integral direta=%.3f\n", worst);
    WCHECK(worst < 0.15, "SH de 3 bandas pré-escalado reproduz a irradiância/π (erro < 15 %)");
    SkyParams inter;
    inter.preset = SkyPreset::Interior;
    float shI[27];
    computeIrradianceSh3(inter, shI);
    const float upN[3] = {0, 1, 0}, dnN[3] = {0, -1, 0};
    float eu[3], ed[3];
    evalSh3(shI, upN, eu); evalSh3(shI, dnN, ed);
    WCHECK(eu[1] > ed[1] * 3.f, "IBL interior: teto luminoso domina o carpete");
    return gWaFailures;
}
