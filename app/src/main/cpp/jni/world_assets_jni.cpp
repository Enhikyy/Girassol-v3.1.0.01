// v4.0 — Ponte JNI dos ativos procedurais da aba Mundo (render/ProceduralWorldAssets.hpp).
// Classe Kotlin: com.enhikyy.girassol.nativebridge.ProceduralAssetsNative.
// Só buffers diretos (sem cópia, sem pinning de arrays Java) e nenhuma referência global:
// cada chamada termina com zero referências locais pendentes (verificado no teste
// instrumentado WorldRenderStabilityTest por repetição sob CheckJNI).
#include <jni.h>

#include <cstdint>

#include "../render/ProceduralWorldAssets.hpp"

using namespace girassol::render;

namespace {

void throwIae(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls) {
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

uint8_t* direct(JNIEnv* env, jobject buf, std::size_t need) {
    if (!buf) return nullptr;
    auto* p = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    const jlong cap = env->GetDirectBufferCapacity(buf);
    if (!p || cap < 0 || static_cast<std::size_t>(cap) < need) return nullptr;
    return p;
}

bool validSize(jint size) { return size >= 4 && size <= 2048 && (size & (size - 1)) == 0; }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_ProceduralAssetsNative_nativeMipChainBytes(JNIEnv*, jclass, jint size) {
    return validSize(size) ? static_cast<jlong>(mipChainBytes(size)) : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_ProceduralAssetsNative_nativeGenerateSurface(
        JNIEnv* env, jclass, jint kind, jint size, jlong seed, jobject albedo, jobject orm, jobject normal, jobject emissive) {
    if (kind < 0 || kind >= static_cast<jint>(Surface::Count) || !validSize(size)) { throwIae(env, "superfície/tamanho inválido"); return JNI_FALSE; }
    const std::size_t level0 = static_cast<std::size_t>(size) * static_cast<std::size_t>(size) * 4;
    const std::size_t chain = mipChainBytes(size);
    uint8_t* a = direct(env, albedo, level0);
    uint8_t* o = direct(env, orm, chain);
    uint8_t* n = direct(env, normal, chain);
    uint8_t* e = direct(env, emissive, level0);
    if (!a || !o || !n || !e) { throwIae(env, "buffers diretos ausentes ou pequenos demais"); return JNI_FALSE; }
    generateSurface(static_cast<Surface>(kind), size, static_cast<uint64_t>(seed), a, o, n, e);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_enhikyy_girassol_nativebridge_ProceduralAssetsNative_nativeCubemapBytes(JNIEnv*, jclass, jint faceSize) {
    return validSize(faceSize) ? static_cast<jlong>(cubemapBytes(faceSize)) : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_ProceduralAssetsNative_nativeGenerateIbl(
        JNIEnv* env, jclass, jint preset, jfloat sx, jfloat sy, jfloat sz, jfloat overcast, jint faceSize, jint samples,
        jobject out, jfloatArray sh) {
    if (preset < 0 || preset > 3 || !validSize(faceSize) || !sh || env->GetArrayLength(sh) < 27) { throwIae(env, "parâmetros de IBL inválidos"); return JNI_FALSE; }
    auto* dst = direct(env, out, cubemapBytes(faceSize));
    if (!dst) { throwIae(env, "buffer do cubemap ausente ou pequeno demais"); return JNI_FALSE; }
    SkyParams p;
    p.preset = static_cast<SkyPreset>(preset);
    p.sunDir[0] = sx; p.sunDir[1] = sy; p.sunDir[2] = sz;
    p.overcast = overcast;
    generateIblCubemap(p, faceSize, samples, reinterpret_cast<uint16_t*>(dst));
    float coeffs[27];
    computeIrradianceSh3(p, coeffs);
    env->SetFloatArrayRegion(sh, 0, 27, coeffs);
    return JNI_TRUE;
}

}  // extern "C"
