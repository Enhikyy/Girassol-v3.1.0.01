#pragma once
// Conversão de strings JNI sem "Modified UTF-8" (v3.8).
//
// GetStringUTFChars/GetStringUTFRegion/NewStringUTF usam o UTF-8 modificado da JVM: U+0000
// vira C0 80 e caracteres fora do BMP (emoji, ideogramas raros) viram pares substitutos
// codificados separadamente (6 bytes). Isso quebrava (a) a paridade das sementes SHA-256 do
// WorldTick com o Kotlin (que usa UTF-8 padrão) para ids com emoji e (b) a tokenização do
// llama.cpp, e o NewStringUTF descartava os emoji gerados pelo modelo. Aqui a leitura é feita
// em UTF-16 (GetStringRegion) e convertida para UTF-8 padrão; a escrita decodifica UTF-8
// padrão para UTF-16 e usa NewString. Bytes inválidos/truncados são descartados (um token do
// LLM pode cortar uma sequência multibyte ao meio).
#include <jni.h>

#include <vector>

#include "jni_utf_core.hpp"

namespace girassol::jniutf {

inline std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize n = env->GetStringLength(s);
    if (n <= 0) return {};
    std::vector<jchar> buf(static_cast<size_t>(n));
    env->GetStringRegion(s, 0, n, buf.data());
    return utf16ToUtf8(reinterpret_cast<const char16_t*>(buf.data()), buf.size());
}

inline jstring toJava(JNIEnv* env, std::string_view utf8) {
    const std::u16string u = utf8ToUtf16(utf8);
    return env->NewString(reinterpret_cast<const jchar*>(u.data()), static_cast<jsize>(u.size()));
}

}  // namespace girassol::jniutf
