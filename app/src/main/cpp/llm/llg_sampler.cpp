// Sampler llguidance para o Girassol — adaptado de llama.cpp/common/llguidance.cpp (MIT,
// (c) The ggml authors), sem a dependência da biblioteca `common` (desligada neste build).
// Diferença de segurança: se o llguidance recusar a gramática, devolve nullptr (o motor cai
// na GBNF estrita) em vez de um sampler sem restrição.
#include "llg_sampler.hpp"

// Sem llama.cpp (testes nativos mínimos, -Pgirassol.noLlama) esta unidade fica vazia.
#if defined(GIRASSOL_HAS_LLAMA)

#include "ggml.h"
#include "llama.h"

#include <cstdio>
#include <cstdlib>
#include <exception>
#include <string>

#define LOG_ERR(...) std::fprintf(stderr, __VA_ARGS__)
#define LOG_WRN(...) std::fprintf(stderr, __VA_ARGS__)

#ifdef GIRASSOL_USE_LLGUIDANCE

#    include "llguidance.h"
#    include <cmath>

struct llama_sampler_llg {
    const llama_vocab * vocab;
    std::string         grammar_kind;
    std::string         grammar_data;
    LlgTokenizer *      tokenizer;
    LlgMatcher *        grammar;
    bool                failed = false;  // erro do matcher: bloqueia tudo exceto fim de geração
};

// Em caso de erro nunca devolvemos amostragem sem restrição (o upstream libera o matcher e
// segue livre): só tokens de fim de geração continuam possíveis e o motor reporta a falha.
static void llama_sampler_llg_block(const llama_vocab * vocab, llama_token_data_array * cur_p) {
    for (size_t i = 0; i < cur_p->size; ++i) {
        if (!llama_vocab_is_eog(vocab, cur_p->data[i].id)) cur_p->data[i].logit = -INFINITY;
    }
}

static LlgMatcher * llama_sampler_llg_new(LlgTokenizer * tokenizer, const char * grammar_kind,
                                          const char * grammar_data) {
    LlgConstraintInit cinit;
    llg_constraint_init_set_defaults(&cinit, tokenizer);
    const char * log_level = getenv("LLGUIDANCE_LOG_LEVEL");
    if (log_level && *log_level) {
        cinit.log_stderr_level = atoi(log_level);
    }
    auto c = llg_new_matcher(&cinit, grammar_kind, grammar_data);
    if (llg_matcher_get_error(c)) {
        LOG_ERR("llg error: %s\n", llg_matcher_get_error(c));
        llg_free_matcher(c);
        return nullptr;
    }

    return c;
}

static const char * llama_sampler_llg_name(const llama_sampler * /*smpl*/) {
    return "llguidance";
}

static void llama_sampler_llg_accept_impl(llama_sampler * smpl, llama_token token) {
    auto * ctx = (llama_sampler_llg *) smpl->ctx;
    if (ctx->grammar && !ctx->failed) {
        if (llg_matcher_consume_token(ctx->grammar, token) != 0 || llg_matcher_is_error(ctx->grammar)) {
            LOG_ERR("llg error: %s\n", llg_matcher_get_error(ctx->grammar));
            ctx->failed = true;
        }
    }
}

static void llama_sampler_llg_apply(llama_sampler * smpl, llama_token_data_array * cur_p) {
    auto * ctx = (llama_sampler_llg *) smpl->ctx;
    if (ctx->failed) {
        llama_sampler_llg_block(ctx->vocab, cur_p);
        return;
    }
    if (ctx->grammar) {
        const uint32_t * mask = llg_matcher_get_mask(ctx->grammar);
        if (mask == nullptr) {
            if (llg_matcher_compute_mask(ctx->grammar) == 0) {
                mask = llg_matcher_get_mask(ctx->grammar);
            } else {
                LOG_ERR("llg error: %s\n", llg_matcher_get_error(ctx->grammar));
                ctx->failed = true;
                llama_sampler_llg_block(ctx->vocab, cur_p);
                return;
            }
        }

        for (size_t i = 0; i < cur_p->size; ++i) {
            auto token = cur_p->data[i].id;
            if ((mask[token / 32] & (1 << (token % 32))) == 0) {
                cur_p->data[i].logit = -INFINITY;
            }
        }
    }
}

static void llama_sampler_llg_reset(llama_sampler * smpl) {
    auto * ctx = (llama_sampler_llg *) smpl->ctx;
    if (ctx->grammar) {
        llg_matcher_reset(ctx->grammar);
        ctx->failed = false;
    }
}

static llama_sampler * llama_sampler_llg_clone(const llama_sampler * smpl) {
    const auto * ctx = (const llama_sampler_llg *) smpl->ctx;

    auto * result = girassol_llg_sampler_init(ctx->vocab, nullptr, nullptr);

    // copy the state
    {
        auto * result_ctx = (llama_sampler_llg *) result->ctx;

        if (ctx->grammar) {
            result_ctx->grammar_kind = ctx->grammar_kind;
            result_ctx->grammar_data = ctx->grammar_data;
            result_ctx->grammar      = llg_clone_matcher(ctx->grammar);
            result_ctx->tokenizer    = llg_clone_tokenizer(ctx->tokenizer);
        }
        result_ctx->failed = ctx->failed;
    }

    return result;
}

static void llama_sampler_llg_free(llama_sampler * smpl) {
    const auto * ctx = (llama_sampler_llg *) smpl->ctx;

    if (ctx->grammar) llg_free_matcher(ctx->grammar);
    if (ctx->tokenizer) llg_free_tokenizer(ctx->tokenizer);
    delete ctx;
}

static llama_sampler_i llama_sampler_llg_i = {
    /* .name              = */ llama_sampler_llg_name,
    /* .accept            = */ llama_sampler_llg_accept_impl,
    /* .apply             = */ llama_sampler_llg_apply,
    /* .reset             = */ llama_sampler_llg_reset,
    /* .clone             = */ llama_sampler_llg_clone,
    /* .free              = */ llama_sampler_llg_free,
    /* .backend_init      = */ NULL,
    /* .backend_accept    = */ NULL,
    /* .backend_apply     = */ NULL,
    /* .backend_set_input = */ NULL,
    /* .backend_reset     = */ NULL,
    /* .copy_state        = */ NULL,
};

static size_t llama_sampler_llg_tokenize_fn(const void * user_data, const uint8_t * bytes, size_t bytes_len,
                                            uint32_t * output_tokens, size_t output_tokens_len) {
    const llama_vocab * vocab = (const llama_vocab *) user_data;
    int                 r     = 0;
    try {
        r = llama_tokenize(vocab, (const char *) bytes, bytes_len, (int32_t *) output_tokens, output_tokens_len, false,
                           true);
    } catch (const std::exception & e) {
        GGML_ABORT("llama_tokenize failed: %s\n", e.what());
    }
    if (r < 0) {
        return -r;
    }
    return r;
}

static LlgTokenizer * llama_sampler_llg_new_tokenizer(const llama_vocab * vocab) {
    // TODO store the tokenizer in the vocab somehow
    static const llama_vocab * vocab_cache;
    static LlgTokenizer *      tokenizer_cache;

    if (vocab_cache == vocab) {
        return llg_clone_tokenizer(tokenizer_cache);
    }

    auto tok_eos = llama_vocab_eot(vocab);
    if (tok_eos == LLAMA_TOKEN_NULL) {
        tok_eos = llama_vocab_eos(vocab);
    }

    size_t vocab_size = llama_vocab_n_tokens(vocab);

    auto token_lens       = new uint32_t[vocab_size];
    // we typically have ~7 bytes per token; let's go on the safe side here
    auto token_bytes_size = vocab_size * 16 + 1024 * 1024;
    auto token_bytes      = new uint8_t[token_bytes_size];

    size_t offset = 0;
    for (size_t i = 0; i < vocab_size; i++) {
        size_t max_token = 1024;
        if (token_bytes_size - offset < max_token) {
            GGML_ABORT("token_bytes buffer too small\n");
        }

        llama_token token = i;
        auto        dp    = (char *) token_bytes + offset;
        // token_to_piece (e não llama_detokenize): este último remove o espaço inicial de
        // vocabulários SPM e deixaria tokens como "▁" com 0 bytes, que o llguidance aceitaria
        // na máscara e rejeitaria no consumo.
        auto        size  = llama_token_to_piece(vocab, token, dp, max_token, 0, false);
        if (size < 0) {
            GGML_ABORT("llama_token_to_piece failed\n");
        }
        if (size == 0) {
            size = llama_token_to_piece(vocab, token, dp + 1, max_token - 1, 0, true);
            if (size < 0) {
                GGML_ABORT("llama_token_to_piece failed\n");
            }
            if (size != 0) {
                *dp = '\xff';  // special token prefix marker
                size += 1;
            }
        }

        token_lens[i] = size;
        offset += size;
    }

    // Vocabulários SPM (llama/Phi-3): llama_tokenize("{") devolve "▁{" por causa do prefixo de
    // espaço, e o llguidance passaria a exigir o token "▁" antes de cada literal forçado. Nesses
    // casos usamos o tokenizador guloso do próprio llguidance sobre a trie de tokens.
    const bool spm = llama_vocab_type(vocab) == LLAMA_VOCAB_TYPE_SPM;
    LlgTokenizerInit tinit = {
        /* .vocab_size                         = */ (uint32_t) vocab_size,
        /* .tok_eos                            = */ (uint32_t) tok_eos,
        /* .token_lens                         = */ token_lens,
        /* .token_bytes                        = */ token_bytes,
        /* .tokenizer_json                     = */ nullptr,
        /* .tokenize_assumes_string            = */ true,
        /* .tokenize_fn                        = */ spm ? nullptr : llama_sampler_llg_tokenize_fn,
        /* .use_approximate_greedy_tokenize_fn = */ spm,
        /* .tokenize_user_data                 = */ vocab,
        /* .slices                             = */ nullptr,
    };

    char           error_buffer[1024];
    LlgTokenizer * tokenizer = llg_new_tokenizer(&tinit, error_buffer, sizeof(error_buffer));

    delete[] token_bytes;
    delete[] token_lens;

    if (tokenizer == nullptr) {
        LOG_ERR("llg tokenizer error: %s\n", error_buffer);
        return tokenizer;
    }

    if (tokenizer_cache) {
        llg_free_tokenizer(tokenizer_cache);
    }
    vocab_cache     = vocab;
    tokenizer_cache = tokenizer;

    return llg_clone_tokenizer(tokenizer_cache);
}

bool girassol_llg_sampler_failed(const llama_sampler * smpl) {
    if (smpl == nullptr || smpl->iface != &llama_sampler_llg_i) return false;
    return ((const llama_sampler_llg *) smpl->ctx)->failed;
}

llama_sampler * girassol_llg_sampler_init(const llama_vocab * vocab, const char * grammar_kind,
                                       const char * grammar_data) {
    auto * ctx = new llama_sampler_llg;

    if (grammar_kind != nullptr && grammar_kind[0] != '\0') {
        auto tokenizer = llama_sampler_llg_new_tokenizer(vocab);
        *ctx           = {
            /* .vocab        = */ vocab,
            /* .grammar_kind = */ grammar_kind,
            /* .grammar_data = */ grammar_data,
            /* .tokenizer    = */ tokenizer,
            /* .grammar      = */ llama_sampler_llg_new(tokenizer, grammar_kind, grammar_data),
            /* .failed       = */ false,
        };
        if (ctx->grammar == nullptr ||
            ((size_t) llama_vocab_n_tokens(vocab) + 31) / 32 * 4 != llg_matcher_get_mask_byte_size(ctx->grammar)) {
            if (ctx->grammar) llg_free_matcher(ctx->grammar);
            if (ctx->tokenizer) llg_free_tokenizer(ctx->tokenizer);
            delete ctx;
            return nullptr;  // fallback automático para GBNF
        }
    } else {
        *ctx = {
            /* .vocab        = */ vocab,
            /* .grammar_kind = */ {},
            /* .grammar_data = */ {},
            /* .tokenizer    = */ nullptr,
            /* .grammar      = */ nullptr,
            /* .failed       = */ false,
        };
    }

    return llama_sampler_init(
        /* .iface = */ &llama_sampler_llg_i,
        /* .ctx   = */ ctx);
}

#else

bool girassol_llg_sampler_failed(const llama_sampler *) { return false; }

llama_sampler * girassol_llg_sampler_init(const llama_vocab *, const char *, const char *) {
    LOG_WRN("llguidance (cmake -DLLAMA_LLGUIDANCE=ON) is not enabled");
    return nullptr;
}

#endif  // GIRASSOL_USE_LLGUIDANCE

#endif  // GIRASSOL_HAS_LLAMA
