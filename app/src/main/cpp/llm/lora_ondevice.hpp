#pragma once
// Adaptação LoRA real on-device (v3.5) — treino incremental de um adaptador leve durante o
// ciclo de sono (Sharp-Wave Ripple Replay) sobre as memórias episódicas de maior utilidade.
//
// Alvo: `blk.{L-1}.ffn_down.weight` (última camada; funciona também com embeddings amarrados).
// Forward base pelo llama.cpp com `cb_eval` capturando, por token:
//   a = entrada do ffn_down (n_ff), x = resíduo `l_out` da última camada (n_embd).
// Logits base vêm do próprio llama.cpp. O gradiente é *esparso top-K*: só o token-alvo e os
// K mais prováveis entram (linhas de W_out desquantizadas direto do GGUF via ggml type traits),
// com a RMSNorm final derivada exatamente. Adam + clipping; validação real em held-out
// recarregando o adaptador escrito (GGUF `general.type=adapter`) com llama_adapter_lora_init.
#include <cstdint>
#include <string>
#include <vector>

#include "../sb/girassol_sb_bridge.h"

struct llama_model;
struct llama_context;
struct llama_adapter_lora;

namespace girassol::llm {

struct LoraTrainConfig {
    std::string adapterPath;     // destino do .gguf (atualizado só se aceito)
    int32_t rank = 8;
    float alpha = 16.0f;
    int32_t epochs = 4;
    float lr = 2e-3f;
    int32_t topK = 8;
    int32_t maxTokens = 768;     // orçamento de tokens de treino por ciclo
    float heldOutFraction = 0.25f;
    int32_t nThreads = 2;
    uint64_t seed = 1935;
    // Orçamento Sináptico: se presente, as linhas do gradiente esparso de A (rank x n_ff) são
    // projetadas em (I - P) ANTES do passo do Adam (project_rows em blocos de D), e uma amostra
    // das ativações do ciclo aceito é preparada para consolidação (observe + commit).
    const GirassolSbBridge* sbBridge = nullptr;
};

struct LoraTrainReport {
    bool ok = false;
    bool accepted = false;
    std::string error;
    std::string target;
    int32_t trainTokens = 0;
    int32_t heldTokens = 0;
    double trainNllBefore = 0, trainNllAfter = 0;
    double heldNllBase = 0, heldNllAdapted = 0;   // NLL real (softmax completo) via llama.cpp
    double millis = 0;
    int32_t cycle = 0;
    int32_t rank = 0;
    int64_t sbProjectedBlocks = 0;   // blocos de D colunas projetados pelo Orçamento Sináptico
    int64_t sbStagedRows = 0;        // ativações preparadas para consolidação
};

/** Estado persistente do adaptador (A: r×n_in, B: n_out×r, momentos do Adam). */
struct LoraWeights {
    std::string target;
    int32_t nIn = 0, nOut = 0, rank = 0;
    float alpha = 16.0f;
    std::vector<float> a, b, ma, va, mb, vb;
    int64_t step = 0;
    int32_t cycles = 0;
    bool valid() const { return rank > 0 && (int64_t)a.size() == (int64_t)rank * nIn && (int64_t)b.size() == (int64_t)nOut * rank; }
};

class OnDeviceLoraTrainer {
public:
    /** Treina um ciclo. `model` precisa estar carregado de `modelPath` (mmap). */
    static LoraTrainReport train(llama_model* model, const std::string& modelPath, const std::vector<std::string>& texts,
                                 const LoraTrainConfig& cfg, LoraWeights& weights);
    /** Escreve o adaptador no formato GGUF esperado pelo llama.cpp. */
    static bool writeAdapterGguf(const std::string& path, const std::string& arch, const LoraWeights& w, std::string* err);
    /** Lê A/B de um adaptador gravado anteriormente (retoma o treino incremental). */
    static bool readAdapterGguf(const std::string& path, LoraWeights& w, std::string* err);
    /** head_dim (key_length ou n_embd/n_head) — KV quantizado exige múltiplo de 32. */
    static int32_t headDim(const llama_model* model);
};

}  // namespace girassol::llm
