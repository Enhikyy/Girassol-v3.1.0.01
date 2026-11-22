// v4.1 — Ativos procedurais da aba Mundo (C++20, sem dependências): texturas PBR até 2048×2048
// (albedo sRGB, ORM linear R=AO G=rugosidade B=metálico, normal em espaço tangente, emissivo
// sRGB), cadeias de mip com renormalização das normais e um ambiente IBL procedural
// (cubemap RGBA16F pré-filtrado por GGX + harmônicos esféricos de 3 bandas já no formato
// pré-escalado que o Filament espera em IndirectLight.Builder.irradiance(3, sh)).
//
// Tudo é determinístico (mesma semente ⇒ mesmos bytes) e testado em host sob ASan/UBSan
// (native-tests/world_assets_tests.cpp). O renderizador Filament (API Java) recebe os bytes
// pela ponte JNI jni/world_assets_jni.cpp e faz o upload com Texture.setImage.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace girassol::render {

/** Superfícies dos dois presets (URBAN_OVERCAST e LUMON_INTERIOR). */
enum class Surface : int {
    WetAsphalt = 0,  // asfalto molhado: poças fBm (rugosidade 0.12) + asfalto seco (0.85), faixas gastas
    Facade = 1,      // concreto #6E767A com juntas de dilatação, painéis teal #1D5961, janelas emissivas
    Sidewalk = 2,    // placas de concreto 0,5 m com juntas e manchas úmidas
    Concrete = 3,    // concreto de forma de tábua (meios-fios chanfrados, coberturas, barreiras)
    Carpet = 4,      // carpete esmeralda #133024–#1B3F30 em placas 4×4, micro-trama e oclusão nas juntas
    Coffer = 5,      // painel de caixotão chanfrado com difusor emissivo embutido
    Partition = 6,   // divisória off-white #D6DBD7, laminado texturizado (rugosidade 0.68)
    Billboard = 7,   // letreiro luminoso de cobertura (emissivo)
    Grass = 8,       // canteiros: solo + grama baixa
    Brick = 9,       // v4.1: tijolo aparente desgastado (aparelho inglês), juntas de argamassa, eflorescência e fuligem
    Count = 10
};

/** Uma camada de mip dentro de um buffer contíguo RGBA8. */
struct MipLevel { int size; std::size_t offset; std::size_t bytes; };

/** Layout da cadeia completa size → 1 (RGBA8). */
std::vector<MipLevel> mipChain(int size);
std::size_t mipChainBytes(int size);
int mipLevelCount(int size);

/**
 * Gera as 4 texturas da superfície. albedo/emissive recebem só o nível 0 (size²·4 bytes; o
 * Filament gera os mips com Texture.generateMipmaps, formato SRGB8_A8 renderizável). orm e
 * normal recebem a cadeia completa (mipChainBytes) com filtragem própria: normais
 * renormalizadas e rugosidade alargada pela variância das normais (Toksvig), que o
 * generateMipmaps genérico não faz.
 */
void generateSurface(Surface s, int size, uint64_t seed,
                     uint8_t* albedo, uint8_t* orm, uint8_t* normal, uint8_t* emissive);

/** Constantes exigidas pela especificação (testadas). */
inline constexpr float kPuddleRoughness = 0.10f;
inline constexpr float kDryAsphaltRoughness = 0.82f;
inline constexpr float kBrickRoughness = 0.86f;
inline constexpr float kMortarRoughness = 0.94f;
inline constexpr float kTealMetallic = 0.65f;
inline constexpr float kTealRoughness = 0.35f;
inline constexpr float kCarpetRoughness = 0.90f;
inline constexpr float kPartitionRoughness = 0.68f;
inline constexpr float kWindowLitFraction = 0.35f;

/** Estatística usada pelos testes: fração de texels (nível 0) com rugosidade em [lo, hi]. */
float roughnessFraction(const uint8_t* orm, int size, float lo, float hi);
/** Fração de texels com emissivo aceso (luminância > limiar) no nível 0. */
float emissiveFraction(const uint8_t* emissive, int size, float threshold = 0.08f);

// ------------------------------------------------------------------ ambiente / IBL

/** Momentos de iluminação: Manhã, Tarde, Noite (cidade nublada) e Interior (salão Lumon). */
enum class SkyPreset : int { Morning = 0, Afternoon = 1, Night = 2, Interior = 3 };

struct SkyParams {
    SkyPreset preset = SkyPreset::Morning;
    float sunDir[3] = {0.f, 1.f, 0.f};  // direção PARA o sol (normalizada)
    float overcast = 0.65f;             // 0 céu limpo … 1 encoberto
};

/** Radiância linear (unidades relativas, zênite diurno ≈ 1) na direção [dir] (normalizada, Y para cima). */
void skyRadiance(const SkyParams& p, const float dir[3], float out[3]);

/** Bytes do cubemap RGBA16F com todos os mips (faceSize → 1), ordem nível-maior, face-menor. */
std::size_t cubemapBytes(int faceSize);
/** Deslocamento em bytes do nível [level], face [face] (0..5 = +X −X +Y −Y +Z −Z). */
std::size_t cubemapOffset(int faceSize, int level, int face);
/** Rugosidade perceptual do nível [level] numa cadeia de [levels] níveis (mapeamento do Filament). */
float lodToPerceptualRoughness(int level, int levels);

/** Direção (convenção de cubemap do OpenGL, usada pelo backend GLES do Filament) do texel (u, v ∈ [0,1]). */
void cubemapDirection(int face, float u, float v, float out[3]);

/**
 * Cubemap pré-filtrado: nível 0 = radiância; nível l = convolução GGX (amostragem por
 * importância, [samples] amostras, N = V = R) com a rugosidade perceptual que o Filament
 * associa ao LOD: lod/(L−1) = r·(2 − r) ⇒ r = 1 − √(1 − lod/(L−1)). Saída RGBA16F.
 */
void generateIblCubemap(const SkyParams& p, int faceSize, int samples, uint16_t* out);

/**
 * Harmônicos esféricos de 3 bandas (27 floats, RGB intercalado) da irradiância, já
 * pré-escalados como o Filament espera: E(n)/π = Σ sh[i]·poly_i(n) com
 * poly = {1, y, z, x, yx, yz, 3z²−1, zx, x²−y²}. Para um ambiente uniforme de radiância 1,
 * sh[0] = 1 (irradiância média / π).
 */
void computeIrradianceSh3(const SkyParams& p, float sh[27]);
/** Avalia o polinômio acima (usado nos testes para comparar com a integral direta). */
void evalSh3(const float sh[27], const float n[3], float out[3]);

uint16_t floatToHalf(float f);
float halfToFloat(uint16_t h);

}  // namespace girassol::render
