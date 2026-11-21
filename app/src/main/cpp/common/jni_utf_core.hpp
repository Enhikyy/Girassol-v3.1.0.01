#pragma once
// Conversão UTF-16 ↔ UTF-8 padrão (sem JNI) usada pela ponte jni_utf.hpp — v3.8.
// Separada para ser testada no host sob ASan/UBSan (native-tests/cognition_tests.cpp).
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace girassol::jniutf {

inline void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

/** UTF-16 → UTF-8 padrão. Substituto isolado vira '?' (igual a String.toByteArray(Charsets.UTF_8) na JVM). */
inline std::string utf16ToUtf8(const char16_t* s, size_t n) {
    std::string out;
    out.reserve(n + n / 2);
    for (size_t i = 0; i < n; ++i) {
        uint32_t c = s[i];
        if (c >= 0xD800 && c <= 0xDBFF) {
            if (i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (static_cast<uint32_t>(s[i + 1]) - 0xDC00);
                ++i;
            } else {
                c = '?';
            }
        } else if (c >= 0xDC00 && c <= 0xDFFF) {
            c = '?';
        }
        appendUtf8(out, c);
    }
    return out;
}

/** UTF-8 padrão → UTF-16; sequências inválidas, sobrelongas ou truncadas são descartadas. */
inline std::u16string utf8ToUtf16(std::string_view in) {
    std::u16string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        const auto c = static_cast<unsigned char>(in[i]);
        size_t len = 0;
        uint32_t cp = 0, min = 0;
        if (c < 0x80) { len = 1; cp = c; }
        else if ((c >> 5) == 0x6) { len = 2; cp = c & 0x1F; min = 0x80; }
        else if ((c >> 4) == 0xE) { len = 3; cp = c & 0x0F; min = 0x800; }
        else if ((c >> 3) == 0x1E) { len = 4; cp = c & 0x07; min = 0x10000; }
        if (len == 0 || i + len > in.size()) { ++i; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            const auto cc = static_cast<unsigned char>(in[i + k]);
            if ((cc >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { ++i; continue; }
        i += len;
        if (len > 1 && cp < min) continue;              // sobrelonga
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) continue;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return out;
}

}  // namespace girassol::jniutf
