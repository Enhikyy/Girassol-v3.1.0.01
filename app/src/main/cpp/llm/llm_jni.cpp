// JNI do motor LLM local. Compilado três vezes (base/dotprod/i8mm) com flags de CPU
// diferentes; o Kotlin carrega exatamente uma variante conforme o HWCAP do aparelho.
#include <jni.h>
#include <string>

#include "../common/utf8.hpp"
#include "llm_engine.hpp"

#ifndef GIRASSOL_LLM_VARIANT
#define GIRASSOL_LLM_VARIANT "base"
#endif
#ifndef GIRASSOL_LLM_MARCH
#define GIRASSOL_LLM_MARCH "default"
#endif

using girassol::sanitizeUtf8;

namespace {
girassol::llm::LlmEngine gLlm;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }


JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmAvailable(JNIEnv*, jobject) {
    return girassol::llm::LlmEngine::available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmLoad(JNIEnv* env, jobject, jstring pathStr, jint threads, jint nCtx) {
    const char* p = env->GetStringUTFChars(pathStr, nullptr);
    std::string path(p ? p : "");
    if (p) env->ReleaseStringUTFChars(pathStr, p);
    std::string err;
    bool ok = gLlm.load(path, threads, nCtx, &err);
    const std::string s = ok ? std::string() : err;
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmUnload(JNIEnv*, jobject) {
    gLlm.requestAbort();
    gLlm.unload();
}

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmAbort(JNIEnv*, jobject) {
    gLlm.requestAbort();
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmDescribe(JNIEnv* env, jobject) {
    const std::string s = gLlm.loaded() ? (gLlm.description() + "|" + std::to_string(gLlm.modelBytes())) : std::string();
    return env->NewStringUTF(s.c_str());
}

// Retorna [texto, erro, promptTokens, genTokens, millis, tokens/s total, tokens/s geração].
JNIEXPORT jobjectArray JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmGenerate(
    JNIEnv* env, jobject, jstring sysStr, jstring userStr, jstring grammarStr, jint maxTokens
) {
    auto read = [&](jstring js) {
        const char* c = js ? env->GetStringUTFChars(js, nullptr) : nullptr;
        std::string s(c ? c : "");
        if (c) env->ReleaseStringUTFChars(js, c);
        return s;
    };
    const auto r = gLlm.generate(read(sysStr), read(userStr), read(grammarStr), maxTokens);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(7, strCls, nullptr);
    const std::string vals[7] = {sanitizeUtf8(r.text), r.error, std::to_string(r.promptTokens), std::to_string(r.genTokens),
                                 std::to_string(r.millis), std::to_string(r.tokensPerSecond), std::to_string(r.genTokensPerSecond)};
    for (int i = 0; i < 7; ++i) {
        jstring js = env->NewStringUTF(vals[i].c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
}

// [promptTps, genTps, nPrompt, nGen]; vazio em caso de erro.
JNIEXPORT jdoubleArray JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmBench(JNIEnv* env, jobject, jint nPrompt, jint nGen) {
    const auto b = gLlm.bench(nPrompt, nGen);
    if (!b.ok) return env->NewDoubleArray(0);
    const jdouble v[4] = {b.promptTps, b.genTps, static_cast<jdouble>(b.nPrompt), static_cast<jdouble>(b.nGen)};
    jdoubleArray arr = env->NewDoubleArray(4);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 4, v);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmSystemInfo(JNIEnv* env, jobject) {
    const std::string s = sanitizeUtf8(girassol::llm::LlmEngine::systemInfo());
    return env->NewStringUTF(s.c_str());
}

// "variante|march" fixado em tempo de compilação.
JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmVariant(JNIEnv* env, jobject) {
    return env->NewStringUTF(GIRASSOL_LLM_VARIANT "|" GIRASSOL_LLM_MARCH);
}

}  // extern "C"
