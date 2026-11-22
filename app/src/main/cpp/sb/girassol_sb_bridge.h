// Ponte C entre libgirassol_sb.so (dono do estado do Orçamento Sináptico) e
// libgirassol_llm.so (treino LoRA on-device). Nenhum símbolo extra é exportado: o Kotlin obtém
// o endereço desta tabela estática (SynapticBudgetNative.nativeBridgeHandle) e o entrega ao
// motor LLM (LlmNativeBridge.nativeLlmSetGradientProjector). Versão/magia validadas no uso.
#pragma once
#include <cstdint>

extern "C" {

#define GIRASSOL_SB_BRIDGE_MAGIC 0x47534242u  // 'GSBB'
#define GIRASSOL_SB_BRIDGE_VERSION 1u

struct GirassolSbBridge {
  uint32_t magic;
  uint32_t version;
  // G (rows x cols, row-major, float32) <- G (I - P) em blocos contíguos de D colunas
  // (aproximação bloco-diagonal da covariância de entrada com base compartilhada).
  // Retorna o número de blocos projetados (0 se não houver estado ativo ou posto 0).
  // Colunas residuais (cols % D) não são tocadas.
  int64_t (*project_f32)(float* G, int64_t rows, int64_t cols);
  // Copia até kStageRows blocos de D ativações (linhas de X: rows x cols) para a área de
  // preparação; o motor Kotlin consolida (observe + commit) na sua thread de pilha grande.
  int64_t (*stage_activations_f32)(const float* X, int64_t rows, int64_t cols);
  // Dimensão D do estado ativo (0 = inativo).
  int32_t (*active_dim)();
};

}  // extern "C"
