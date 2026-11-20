// JNI do motor LLM local (libgirassol_llm.so). Liga dinamicamente a libllama.so; o backend
// de CPU (libggml-cpu-android_*.so) é escolhido pelo Kotlin a partir do HWCAP e carregado
// por nativeLlmLoadCpuBackend() antes do primeiro modelo.
#include <jni.h>
#include <string>
#include <vector>

#include "../common/utf8.hpp"
#include "llm_engine.hpp"
#include "semantic_entropy.hpp"

using girassol::sanitizeUtf8;

namespace {
girassol::llm::LlmEngine gLlm;

std::string readStr(JNIEnv* env, jstring js) {
    const char* c = js ? env->GetStringUTFChars(js, nullptr) : nullptr;
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

jobjectArray toArray(JNIEnv* env, const std::vector<std::string>& vals) {
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(vals.size()), strCls, nullptr);
    for (size_t i = 0; i < vals.size(); ++i) {
        jstring js = env->NewStringUTF(vals[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return out;
}
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }

JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmAvailable(JNIEnv*, jobject) {
    return girassol::llm::LlmEngine::available() ? JNI_TRUE : JNI_FALSE;
}

// "" = sucesso; caso contrário a mensagem de erro.
JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmLoadCpuBackend(JNIEnv* env, jobject, jstring lib) {
    std::string err;
    const bool ok = girassol::llm::LlmEngine::loadCpuBackend(readStr(env, lib), &err);
    return env->NewStringUTF(ok ? "" : err.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmCpuBackend(JNIEnv* env, jobject) {
    return env->NewStringUTF(girassol::llm::LlmEngine::loadedCpuBackend().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmLoad(
    JNIEnv* env, jobject, jstring pathStr, jint threads, jint nCtx, jboolean flashAttn, jint kvType, jstring slotDir, jint maxSlots
) {
    girassol::llm::LlmLoadConfig cfg;
    cfg.nThreads = threads;
    cfg.nCtx = nCtx;
    cfg.flashAttn = flashAttn == JNI_TRUE;
    cfg.kvType = static_cast<girassol::llm::KvCacheType>(kvType < 0 || kvType > 2 ? 0 : kvType);
    cfg.slotDir = readStr(env, slotDir);
    cfg.maxSlots = maxSlots;
    std::string err;
    const bool ok = gLlm.load(readStr(env, pathStr), cfg, &err);
    return env->NewStringUTF(ok ? "" : err.c_str());
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

JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmClearSlots(JNIEnv*, jobject) {
    gLlm.clearSlots();
}

JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmDescribe(JNIEnv* env, jobject) {
    const std::string s = gLlm.loaded()
        ? (gLlm.description() + "|" + std::to_string(gLlm.modelBytes()) + "|" + gLlm.runtimeConfig())
        : std::string();
    return env->NewStringUTF(sanitizeUtf8(s).c_str());
}

// Temperatura do SemanticEntropyController para as últimas falas (separadas por '\n').
JNIEXPORT jfloat JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeSemanticTemperature(JNIEnv* env, jobject, jstring recent, jfloat tBase) {
    return girassol::llm::SemanticEntropyController::temperatureFor(readStr(env, recent), tBase);
}

// [texto, erro, promptTokens, genTokens, millis, tok/s total, tok/s geração,
//  tokens reaproveitados, tokens fast-forward, slot restaurado(0/1)]
JNIEXPORT jobjectArray JNICALL
Java_com_enhikyy_girassol_nativebridge_LlmNativeBridge_nativeLlmGenerate(
    JNIEnv* env, jobject, jstring sysStr, jstring prefixStr, jstring userStr, jstring grammarStr, jstring keysCsv,
    jstring slotKey, jfloat temperature, jint seed, jint maxTokens
) {
    girassol::llm::LlmRequest req;
    req.system = readStr(env, sysStr);
    req.worldPrefix = readStr(env, prefixStr);
    req.user = readStr(env, userStr);
    req.grammar = readStr(env, grammarStr);
    const std::string keys = readStr(env, keysCsv);
    size_t start = 0;
    while (start < keys.size()) {
        const size_t c = keys.find(',', start);
        const std::string k = keys.substr(start, c == std::string::npos ? std::string::npos : c - start);
        if (!k.empty()) req.jsonKeys.push_back(k);
        if (c == std::string::npos) break;
        start = c + 1;
    }
    req.slotKey = readStr(env, slotKey);
    req.temperature = temperature;
    req.seed = static_cast<uint32_t>(seed);
    req.maxTokens = maxTokens;
    const auto r = gLlm.generate(req);
    return toArray(env, {sanitizeUtf8(r.text), r.error, std::to_string(r.promptTokens), std::to_string(r.genTokens),
                         std::to_string(r.millis), std::to_string(r.tokensPerSecond), std::to_string(r.genTokensPerSecond),
                         std::to_string(r.reusedTokens), std::to_string(r.forcedTokens), r.slotRestored ? "1" : "0"});
}

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

}  // extern "C"
