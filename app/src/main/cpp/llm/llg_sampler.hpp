#pragma once
// Sampler llguidance opcional (crate Rust `llguidance`, compilado com GIRASSOL_LLGUIDANCE=ON).
struct llama_sampler;
struct llama_vocab;

/** nullptr se o build não inclui o llguidance ou se a gramática foi recusada. */
llama_sampler* girassol_llg_sampler_init(const llama_vocab* vocab, const char* grammar_kind, const char* grammar_data);
// true quando o matcher llguidance entrou em erro (a geração foi bloqueada e deve ser descartada)
bool girassol_llg_sampler_failed(const llama_sampler* smpl);
