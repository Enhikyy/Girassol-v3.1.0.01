#pragma once
// Motor cognitivo local: llama.cpp + GGUF (mmap) + gramática GBNF obrigatória.
// Decodificação gulosa (greedy) sob gramática: dada a mesma entrada e o mesmo modelo a
// saída é reprodutível no mesmo aparelho. Contexto enxuto (n_ctx ≤ 1024) e KV-cache
// limpo a cada pedido — a RAM nativa fica ≈ tamanho do modelo mapeado + ~40–90 MB.
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

struct llama_model;
struct llama_context;

namespace girassol::llm {

struct LlmResult {
    bool ok = false;
    std::string text;
    std::string error;
    int32_t promptTokens = 0;
    int32_t genTokens = 0;
    double millis = 0.0;
    double tokensPerSecond = 0.0;
};

class LlmEngine {
public:
    LlmEngine() = default;
    ~LlmEngine();
    static bool available();  // compilado com llama.cpp?
    bool load(const std::string& path, int32_t nThreads, int32_t nCtx, std::string* err);
    void unload();
    bool loaded() const;
    LlmResult generate(const std::string& system, const std::string& user, const std::string& grammar, int32_t maxTokens);
    void requestAbort() { abort_.store(true); }
    uint64_t modelBytes() const { return modelBytes_; }
    std::string description() const { return desc_; }

private:
    mutable std::mutex mu_;
    llama_model* model_ = nullptr;
    llama_context* ctx_ = nullptr;
    uint64_t modelBytes_ = 0;
    std::string desc_;
    std::string chatTemplate_;
    std::atomic<bool> abort_{false};
};

}  // namespace girassol::llm
