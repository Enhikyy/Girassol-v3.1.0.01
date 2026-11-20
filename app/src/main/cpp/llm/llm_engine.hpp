#pragma once
// Motor cognitivo local: llama.cpp + GGUF (mmap) + gramática GBNF obrigatória.
//
// v3.4:
//  * backend de CPU dinâmico (GGML_BACKEND_DL) escolhido pelo HWCAP — loadCpuBackend();
//  * flash attention + KV-cache quantizado (Q4_0 em aparelhos de 4 GB, Q8_0 em 8/12 GB);
//  * cache de prefixo: o IMMUTABLE_WORLD_PREFIX (regras do mundo, igual para todos) fica no
//    KV-cache da sequência 0 e só o sufixo do cidadão é reprocessado; slots por cidadão são
//    salvos/restaurados com llama_state_seq_save_file / llama_state_seq_load_file (LRU);
//  * fast-forward da gramática: trechos fixos do JSON ({"acao":" … ","pensamento":" …) são
//    injetados em lote e aceitos pelo amostrador GBNF, sem gastar um passo de decodificação
//    por token;
//  * amostragem com temperatura do SemanticEntropyController + semente determinística
//    (mesma semente + mesmo estado → mesma saída); temperatura ≤ 0 → gulosa.
#include <atomic>
#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct llama_model;
struct llama_context;

namespace girassol::llm {

enum class KvCacheType : int { F16 = 0, Q8_0 = 1, Q4_0 = 2 };

struct LlmLoadConfig {
    int32_t nThreads = 2;
    int32_t nCtx = 1024;
    bool flashAttn = true;
    KvCacheType kvType = KvCacheType::Q8_0;
    std::string slotDir;      // "" = sem persistência de slots
    int32_t maxSlots = 24;    // LRU de slots por cidadão
};

struct LlmRequest {
    std::string system;           // IMMUTABLE_WORLD_PREFIX + identidade do cidadão
    std::string worldPrefix;      // parte do system idêntica para todos (pode ser "")
    std::string user;
    std::string grammar;
    std::vector<std::string> jsonKeys;  // ordem dos campos para o fast-forward ("" = sem)
    std::string slotKey;          // id do cidadão para o slot persistente ("" = sem)
    float temperature = 0.0f;     // ≤ 0 → greedy
    uint32_t seed = 0;
    int32_t maxTokens = 96;
};

struct LlmResult {
    bool ok = false;
    std::string text;
    std::string error;
    int32_t promptTokens = 0;
    int32_t genTokens = 0;
    int32_t reusedTokens = 0;     // tokens do prompt servidos pelo KV-cache/slot
    int32_t forcedTokens = 0;     // tokens injetados pelo fast-forward
    bool slotRestored = false;
    double millis = 0.0;
    double tokensPerSecond = 0.0;     // (prompt + geração) / tempo total
    double genTokensPerSecond = 0.0;  // somente decodificação token a token
};

struct LlmBench {
    bool ok = false;
    std::string error;
    double promptTps = 0.0;
    double genTps = 0.0;
    int32_t nPrompt = 0;
    int32_t nGen = 0;
};

class LlmEngine {
public:
    LlmEngine() = default;
    ~LlmEngine();
    static bool available();
    // Carrega um backend de CPU dinâmico pelo nome (ex.: libggml-cpu-android_armv8.2_2.so).
    // Em builds estáticos (testes de host) devolve true sem fazer nada.
    static bool loadCpuBackend(const std::string& library, std::string* err);
    static std::string loadedCpuBackend();
    bool load(const std::string& path, const LlmLoadConfig& cfg, std::string* err);
    bool load(const std::string& path, int32_t nThreads, int32_t nCtx, std::string* err) {
        LlmLoadConfig c; c.nThreads = nThreads; c.nCtx = nCtx; return load(path, c, err);
    }
    void unload();
    bool loaded() const;
    LlmResult generate(const LlmRequest& req);
    LlmResult generate(const std::string& system, const std::string& user, const std::string& grammar, int32_t maxTokens) {
        LlmRequest r; r.system = system; r.user = user; r.grammar = grammar; r.maxTokens = maxTokens; return generate(r);
    }
    LlmBench bench(int32_t nPrompt, int32_t nGen);
    static std::string systemInfo();
    void requestAbort() { abort_.store(true); }
    // Apaga slots (RAM e disco) — usado pelo disjuntor de memória.
    void clearSlots();
    uint64_t modelBytes() const { return modelBytes_; }
    std::string description() const { return desc_; }
    // "flash_attn=1 kv=q8_0 ctx=1024" efetivamente aplicado.
    std::string runtimeConfig() const;
    size_t slotCount() const;

private:
    std::vector<int32_t> tokenize(const std::string& text, bool addSpecial) const;
    std::vector<int32_t> forcedTokens(const std::string& text) const;
    static constexpr size_t kMaxPiece = 24;
    std::string applyTemplate(const std::string& system, const std::string& user, bool withUser) const;
    void evictSlotsLocked();
    bool restoreSlotLocked(const std::string& key, const std::vector<int32_t>& want, size_t* common);
    void saveSlotLocked(const std::string& key);

    mutable std::mutex mu_;
    llama_model* model_ = nullptr;
    llama_context* ctx_ = nullptr;
    uint64_t modelBytes_ = 0;
    std::string desc_;
    std::string chatTemplate_;
    std::atomic<bool> abort_{false};
    LlmLoadConfig cfg_;
    bool flashApplied_ = false;
    KvCacheType kvApplied_ = KvCacheType::F16;
    std::vector<int32_t> cached_;  // tokens presentes na sequência 0 do KV-cache
    struct Slot { std::vector<int32_t> tokens; std::string file; };
    std::unordered_map<std::string, Slot> slots_;
    std::list<std::string> lru_;   // frente = mais recente
    std::unordered_map<std::string, int32_t> pieceToToken_;
};

}  // namespace girassol::llm
