#pragma once
#include <string>

namespace girassol {
// NewStringUTF exige "Modified UTF-8" válido; tokens podem cortar sequências multibyte.
inline std::string sanitizeUtf8(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        const unsigned char c = static_cast<unsigned char>(in[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : 0;
        if (len == 0 || i + len > in.size()) { ++i; continue; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) if ((static_cast<unsigned char>(in[i + k]) >> 6) != 0x2) ok = false;
        if (ok && c != 0) out.append(in, i, len);
        i += ok ? len : 1;
    }
    return out;
}
}  // namespace girassol
