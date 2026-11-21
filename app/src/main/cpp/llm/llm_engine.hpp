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

#include "lora_ondevice.hpp"

struct llama_model;
struct llama_context;
struct llama_adapter_lora;

namespace girassol::llm {

enum class KvCacheType : int { F16 = 0, Q8_0 = 1, Q4_0 = 2 };

struct LlmLoadConfig {
    int32_t nThreads = 2;
    int32_t nCtx = 1024;
    bool flashAttn = true;
    KvCacheType kvType = KvCacheType::Q8_0;
    std::string slotDir;      // "" = sem persistência de slots
    int32_t maxSlots = 24;    // LRU de slots por cidadão
    // v3.7: modelos residentes (llama_model mmap) mantidos em RAM ao mesmo tempo. Perfil 4 GB = 1
    // (troca descarrega o modelo); 8 GB = 2; 12 GB = 3. A troca de cidadão/modelo libera e recria
    // apenas o llama_context (KV-cache) — os pesos permanecem mapeados.
    int32_t poolCapacity = 1;
};

struct LlmPoolStatus {
    int32_t capacity = 1;
    int32_t resident = 0;
    std::string active;
    std::vector<std::string> paths;
    std::vector<uint64_t> bytes;
    uint64_t switches = 0;      // trocas servidas pelo pool (sem recarregar pesos)
    uint64_t coldLoads = 0;     // carregamentos do arquivo GGUF
    uint64_t evictions = 0;
}; 

struct LlmRequest {
    std::string system;           // IMMUTABLE_WORLD_PREFIX + identidade do cidadão
    std::string worldPrefix;      // parte do system idêntica para todos (pode ser "")
    std::string user;
    std::string grammar;
    std::string larkGrammar;      // v3.5: mesma linguagem em Lark (llguidance, opcional)
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
    std::string grammarBackend;   // "llguidance" | "gbnf" | ""
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
    static bool hasLlguidance();
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
    // v3.8: leituras sob mu_ — antes liam modelBytes_/desc_ sem trava enquanto outra thread
    // trocava o modelo (load/unpark), uma corrida de dados sobre std::string (use-after-free).
    uint64_t modelBytes() const;
    std::string description() const;
    // "flash_attn=1 kv=q8_0 ctx=1024" efetivamente aplicado.
    std::string runtimeConfig() const;
    size_t slotCount() const;
    LlmPoolStatus poolStatus() const;
    void setPoolCapacity(int32_t capacity);
    // Despejo imediato (onTrimMemory/lowMemory no perfil 8/12 GB): libera todos os modelos
    // estacionados SEM esperar a geração em curso. Devolve quantos foram liberados.
    int32_t evictParkedNow();

    // v3.5 — LoRA on-device (Sharp-Wave Ripple Replay): treina um ciclo sobre os textos
    // episódicos de maior utilidade e, se o NLL real em held-out melhorar, aplica o adaptador.
    LoraTrainReport trainLora(const std::vector<std::string>& texts, const LoraTrainConfig& cfg);
    bool applyLora(const std::string& adapterPath, float scale, std::string* err);
    void clearLora();
    std::string loraStatus() const;
    // head_dim do modelo carregado e se o KV quantizado foi recusado por head_dim % 32 != 0.
    int32_t headDim() const;
    std::string modelFtype() const;

private:
    std::vector<int32_t> tokenize(const std::string& text, bool addSpecial) const;
    std::vector<int32_t> forcedTokens(const std::string& text) const;
    static constexpr size_t kMaxPiece = 24;
    std::string applyTemplate(const std::string& system, const std::string& user, bool withUser) const;
    void evictSlotsLocked();
    bool restoreSlotLocked(const std::string& key, const std::vector<int32_t>& want, size_t* common);
    void saveSlotLocked(const std::string& key);

    // Estado por modelo estacionado no pool (pesos residentes, sem contexto).
    struct Resident {
        llama_model* model = nullptr;
        std::string path;
        uint64_t bytes = 0;
        std::string desc, chatTemplate;
        std::unordered_map<std::string, int32_t> pieceToToken;
        int32_t headDim = 0;
        llama_adapter_lora* adapter = nullptr;
        float adapterScale = 0.0f;
        std::string adapterPath;
        LoraWeights lora;
        LoraTrainReport lastLora;
        uint64_t lastUse = 0;
    };
    void parkActiveLocked();
    void unparkLocked(Resident&& r);
    void freeResidentLocked(Resident& r);
    void evictParkedLocked(size_t keep);
    bool createContextLocked(std::string* err);
    void unloadLocked();

    // v3.8: o pool estacionado tem trava própria (ordem fixa mu_ → poolMu_), para que o despejo
    // sob pressão de memória (evictParkedNow) não espere uma geração longa que segura mu_.
    // Os estacionados nunca são tocados pelo contexto ativo, então liberá-los em paralelo é seguro.
    std::vector<Resident> parked_;
    int32_t poolCapacity_ = 1;
    uint64_t useClock_ = 0, switches_ = 0, coldLoads_ = 0;
    std::atomic<uint64_t> evictions_{0};
    mutable std::mutex poolMu_;

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
    std::string modelPath_;
    llama_adapter_lora* adapter_ = nullptr;
    float adapterScale_ = 0.0f;
    std::string adapterPath_;
    LoraWeights lora_;
    LoraTrainReport lastLora_;
    int32_t headDim_ = 0;
    bool kvHeadDimFallback_ = false;
};

}  // namespace girassol::llm
