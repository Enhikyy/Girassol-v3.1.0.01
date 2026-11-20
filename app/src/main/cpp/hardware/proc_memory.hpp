#pragma once
// Memória física real do processo lida de /proc/self/status (VmRSS, pico VmHWM, ...).
// Leitura barata (um arquivo de ~1,5 KB), segura para chamar a cada poucos segundos.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace girassol::hw {

struct ProcMemory {
    int64_t vmRssKb = -1;    // residente agora
    int64_t vmHwmKb = -1;    // pico residente ("high water mark")
    int64_t vmSizeKb = -1;   // espaço virtual (inclui o GGUF mapeado)
    int64_t rssAnonKb = -1;  // anônima (heap, KV-cache)
    int64_t rssFileKb = -1;  // páginas de arquivo (GGUF via mmap, .so)
    int64_t vmSwapKb = -1;
};

inline int64_t parseKbField(const std::string& text, const char* key) {
    size_t pos = 0;
    const size_t klen = std::strlen(key);
    while ((pos = text.find(key, pos)) != std::string::npos) {
        if ((pos == 0 || text[pos - 1] == '\n') && pos + klen < text.size() && text[pos + klen] == ':') {
            const char* p = text.c_str() + pos + klen + 1;
            while (*p == ' ' || *p == '\t') ++p;
            char* end = nullptr;
            const long long v = std::strtoll(p, &end, 10);
            return end != p ? static_cast<int64_t>(v) : -1;
        }
        pos += klen;
    }
    return -1;
}

inline ProcMemory parseProcStatus(const std::string& text) {
    ProcMemory m;
    m.vmRssKb = parseKbField(text, "VmRSS");
    m.vmHwmKb = parseKbField(text, "VmHWM");
    m.vmSizeKb = parseKbField(text, "VmSize");
    m.rssAnonKb = parseKbField(text, "RssAnon");
    m.rssFileKb = parseKbField(text, "RssFile");
    m.vmSwapKb = parseKbField(text, "VmSwap");
    return m;
}

inline ProcMemory readSelfProcMemory() {
    FILE* f = std::fopen("/proc/self/status", "re");
    if (!f) return {};
    std::string text;
    char buf[1024];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) text.append(buf, n);
    std::fclose(f);
    return parseProcStatus(text);
}

}  // namespace girassol::hw
