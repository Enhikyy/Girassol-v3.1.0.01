#include "llm_engine.hpp"

#include <algorithm>
#include <chrono>
#include <vector>

#if defined(GIRASSOL_HAS_LLAMA)
#include "llama.h"
#endif

namespace girassol::llm {

#if defined(GIRASSOL_HAS_LLAMA)
namespace {
std::once_flag gBackendOnce;
void quietLog(enum ggml_log_level level, const char* text, void*) {
    (void)level;
    (void)text;
}
bool abortCb(void* data) { return static_cast<std::atomic<bool>*>(data)->load(); }
}  // namespace
#endif

bool LlmEngine::available() {
#if defined(GIRASSOL_HAS_LLAMA)
    return true;
#else
    return false;
#endif
}

LlmEngine::~LlmEngine() { unload(); }

bool LlmEngine::loaded() const {
    std::lock_guard<std::mutex> lock(mu_);
    return model_ != nullptr && ctx_ != nullptr;
}

void LlmEngine::unload() {
    std::lock_guard<std::mutex> lock(mu_);
#if defined(GIRASSOL_HAS_LLAMA)
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
#endif
    ctx_ = nullptr;
    model_ = nullptr;
    modelBytes_ = 0;
    desc_.clear();
    chatTemplate_.clear();
}

bool LlmEngine::load(const std::string& path, int32_t nThreads, int32_t nCtx, std::string* err) {
    unload();
#if defined(GIRASSOL_HAS_LLAMA)
    std::call_once(gBackendOnce, [] {
        llama_log_set(quietLog, nullptr);
        llama_backend_init();
    });
    std::lock_guard<std::mutex> lock(mu_);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // pesos mapeados: páginas limpas, recuperáveis pelo kernel
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        if (err) *err = "falha ao carregar GGUF";
        return false;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_seq_max = 1;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    cp.abort_callback = abortCb;
    cp.abort_callback_data = &abort_;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        llama_model_free(model_);
        model_ = nullptr;
        if (err) *err = "falha ao criar contexto";
        return false;
    }
    modelBytes_ = llama_model_size(model_);
    char buf[256] = {0};
    llama_model_desc(model_, buf, sizeof(buf));
    desc_ = buf;
    const char* tmpl = llama_model_chat_template(model_, nullptr);
    chatTemplate_ = tmpl ? tmpl : "";
    return true;
#else
    (void)path; (void)nThreads; (void)nCtx;
    if (err) *err = "compilado sem llama.cpp";
    return false;
#endif
}

LlmResult LlmEngine::generate(const std::string& system, const std::string& user, const std::string& grammar, int32_t maxTokens) {
    LlmResult res;
#if defined(GIRASSOL_HAS_LLAMA)
    std::lock_guard<std::mutex> lock(mu_);
    if (!model_ || !ctx_) { res.error = "modelo não carregado"; return res; }
    abort_.store(false);
    const auto t0 = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(model_);

    // prompt via template de chat do modelo (fallback textual simples)
    std::string prompt;
    if (!chatTemplate_.empty()) {
        llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
        std::vector<char> buf(system.size() * 2 + user.size() * 2 + 512);
        int32_t n = llama_chat_apply_template(chatTemplate_.c_str(), msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(n) + 1);
            n = llama_chat_apply_template(chatTemplate_.c_str(), msgs, 2, true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n > 0) prompt.assign(buf.data(), static_cast<size_t>(n));
    }
    if (prompt.empty()) prompt = system + "\n\n" + user + "\nResposta JSON: ";

    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(ctx_));
    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t nTok = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                                  static_cast<int32_t>(tokens.size()), true, true);
    if (nTok < 0) {
        tokens.resize(static_cast<size_t>(-nTok));
        nTok = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                              static_cast<int32_t>(tokens.size()), true, true);
    }
    if (nTok <= 0) { res.error = "tokenização falhou"; return res; }
    // contexto enxuto: mantém o fim do prompt se exceder (reserva espaço para a resposta)
    const int32_t budget = nCtx - maxTokens - 8;
    if (nTok > budget) {
        if (budget <= 8) { res.error = "contexto insuficiente"; return res; }
        tokens.erase(tokens.begin(), tokens.begin() + (nTok - budget));
        nTok = budget;
    }
    tokens.resize(static_cast<size_t>(nTok));
    res.promptTokens = nTok;

    llama_memory_clear(llama_get_memory(ctx_), true);

    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler* gram = grammar.empty() ? nullptr : llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
    if (!grammar.empty() && gram == nullptr) {
        llama_sampler_free(chain);
        res.error = "gramática GBNF inválida";
        return res;
    }
    if (gram) llama_sampler_chain_add(chain, gram);
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    // prefill em blocos de n_batch
    const int32_t nBatch = static_cast<int32_t>(llama_n_batch(ctx_));
    for (int32_t i = 0; i < nTok; i += nBatch) {
        const int32_t n = std::min(nBatch, nTok - i);
        if (llama_decode(ctx_, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            llama_sampler_free(chain);
            res.error = abort_.load() ? "abortado" : "decode do prompt falhou";
            return res;
        }
    }
    const auto tGen0 = std::chrono::steady_clock::now();
    std::string out;
    char piece[256];
    for (int32_t g = 0; g < maxTokens; ++g) {
        llama_token tok = llama_sampler_sample(chain, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        const int32_t n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, static_cast<size_t>(n));
        res.genTokens++;
        // gramática concluída: objeto JSON fechado
        if (!out.empty() && out.back() == '}' && gram) break;
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) {
            res.error = abort_.load() ? "abortado" : "decode falhou";
            break;
        }
    }
    llama_sampler_free(chain);
    const auto t1 = std::chrono::steady_clock::now();
    res.millis = std::chrono::duration<double, std::milli>(t1 - t0).count();
    res.tokensPerSecond = res.millis > 0 ? (res.genTokens + res.promptTokens) * 1000.0 / res.millis : 0.0;
    const double genSecs = std::chrono::duration<double>(t1 - tGen0).count();
    res.genTokensPerSecond = genSecs > 0 ? res.genTokens / genSecs : 0.0;
    res.text = out;
    res.ok = res.error.empty() && !out.empty();
    return res;
#else
    (void)system; (void)user; (void)grammar; (void)maxTokens;
    res.error = "compilado sem llama.cpp";
    return res;
#endif
}

LlmBench LlmEngine::bench(int32_t nPrompt, int32_t nGen) {
    LlmBench b;
#if defined(GIRASSOL_HAS_LLAMA)
    std::lock_guard<std::mutex> lock(mu_);
    if (!model_ || !ctx_) { b.error = "modelo não carregado"; return b; }
    abort_.store(false);
    const llama_vocab* vocab = llama_model_get_vocab(model_);
    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(ctx_));
    const int32_t nBatch = static_cast<int32_t>(llama_n_batch(ctx_));
    nPrompt = std::clamp(nPrompt, 1, std::min(nBatch, nCtx / 2));
    nGen = std::clamp(nGen, 1, nCtx - nPrompt - 1);
    const int32_t nVocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token> toks(static_cast<size_t>(nPrompt));
    for (int32_t i = 0; i < nPrompt; ++i) toks[static_cast<size_t>(i)] = static_cast<llama_token>((i * 7919 + 13) % std::max(1, nVocab));
    llama_memory_clear(llama_get_memory(ctx_), true);
    const auto t0 = std::chrono::steady_clock::now();
    if (llama_decode(ctx_, llama_batch_get_one(toks.data(), nPrompt)) != 0) { b.error = "decode pp falhou"; return b; }
    const auto t1 = std::chrono::steady_clock::now();
    llama_token tok = toks.back();
    for (int32_t g = 0; g < nGen; ++g) {
        tok = static_cast<llama_token>((tok * 31 + 17) % std::max(1, nVocab));
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) { b.error = "decode tg falhou"; break; }
        b.nGen++;
    }
    const auto t2 = std::chrono::steady_clock::now();
    llama_memory_clear(llama_get_memory(ctx_), true);
    b.nPrompt = nPrompt;
    const double pp = std::chrono::duration<double>(t1 - t0).count();
    const double tg = std::chrono::duration<double>(t2 - t1).count();
    b.promptTps = pp > 0 ? nPrompt / pp : 0.0;
    b.genTps = tg > 0 ? b.nGen / tg : 0.0;
    b.ok = b.error.empty();
#else
    (void)nPrompt; (void)nGen;
    b.error = "compilado sem llama.cpp";
#endif
    return b;
}

std::string LlmEngine::systemInfo() {
#if defined(GIRASSOL_HAS_LLAMA)
    const char* s = llama_print_system_info();
    return s ? std::string(s) : std::string();
#else
    return "sem llama.cpp";
#endif
}

}  // namespace girassol::llm
