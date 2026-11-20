#include "llm_engine.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <vector>

#if defined(GIRASSOL_HAS_LLAMA)
#include "llama.h"
#if defined(GIRASSOL_GGML_BACKEND_DL)
#include "ggml-backend.h"
#endif
#endif

namespace girassol::llm {

namespace {
std::mutex gBackendMu;
std::string gCpuBackend;  // backend dinâmico carregado ("" = nenhum / estático)

size_t commonPrefix(const std::vector<int32_t>& a, const std::vector<int32_t>& b) {
    const size_t n = std::min(a.size(), b.size());
    size_t i = 0;
    while (i < n && a[i] == b[i]) ++i;
    return i;
}

std::string sanitizeKey(const std::string& key) {
    std::string out;
    for (char c : key) out.push_back((std::isalnum(static_cast<unsigned char>(c)) || c == '-' || c == '_') ? c : '_');
    return out.substr(0, 64);
}

const char* kvName(KvCacheType t) {
    switch (t) {
        case KvCacheType::Q8_0: return "q8_0";
        case KvCacheType::Q4_0: return "q4_0";
        default: return "f16";
    }
}

#if defined(GIRASSOL_HAS_LLAMA)
std::once_flag gBackendOnce;
void quietLog(enum ggml_log_level level, const char* text, void*) {
    (void)level;
    (void)text;
}
bool abortCb(void* data) { return static_cast<std::atomic<bool>*>(data)->load(); }
ggml_type toGgml(KvCacheType t) {
    switch (t) {
        case KvCacheType::Q8_0: return GGML_TYPE_Q8_0;
        case KvCacheType::Q4_0: return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}
#endif
}  // namespace

bool LlmEngine::available() {
#if defined(GIRASSOL_HAS_LLAMA)
    return true;
#else
    return false;
#endif
}

bool LlmEngine::loadCpuBackend(const std::string& library, std::string* err) {
    std::lock_guard<std::mutex> lock(gBackendMu);
#if defined(GIRASSOL_HAS_LLAMA) && defined(GIRASSOL_GGML_BACKEND_DL)
    if (!gCpuBackend.empty()) return gCpuBackend == library;
    // dlopen pelo soname: o linker do Android procura no diretório de bibliotecas do app
    // (inclusive dentro do APK, com jniLibs não comprimidas). ggml_backend_load recusa o
    // backend se ggml_backend_score() == 0 (recurso SIMD ausente) — nunca há SIGILL.
    ggml_backend_reg_t reg = ggml_backend_load(library.c_str());
    if (!reg) {
        if (err) *err = "backend " + library + " indisponível neste aparelho";
        return false;
    }
    gCpuBackend = library;
    return true;
#else
    (void)library;
    (void)err;
    gCpuBackend = "estático";
    return true;
#endif
}

std::string LlmEngine::loadedCpuBackend() {
    std::lock_guard<std::mutex> lock(gBackendMu);
    return gCpuBackend;
}

LlmEngine::~LlmEngine() { unload(); }

bool LlmEngine::loaded() const {
    std::lock_guard<std::mutex> lock(mu_);
    return model_ != nullptr && ctx_ != nullptr;
}

size_t LlmEngine::slotCount() const {
    std::lock_guard<std::mutex> lock(mu_);
    return slots_.size();
}

std::string LlmEngine::runtimeConfig() const {
    std::lock_guard<std::mutex> lock(mu_);
    if (!ctx_) return "";
    return std::string("flash_attn=") + (flashApplied_ ? "1" : "auto") + " kv=" + kvName(kvApplied_) +
           " ctx=" + std::to_string(cfg_.nCtx) + " slots=" + std::to_string(slots_.size());
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
    cached_.clear();
    slots_.clear();
    lru_.clear();
    pieceToToken_.clear();
}

void LlmEngine::clearSlots() {
    std::lock_guard<std::mutex> lock(mu_);
    for (auto& [k, s] : slots_) {
        (void)k;
        std::error_code ec;
        std::filesystem::remove(s.file, ec);
    }
    slots_.clear();
    lru_.clear();
}

bool LlmEngine::load(const std::string& path, const LlmLoadConfig& cfg, std::string* err) {
    unload();
#if defined(GIRASSOL_HAS_LLAMA)
    std::call_once(gBackendOnce, [] {
        llama_log_set(quietLog, nullptr);
        llama_backend_init();
    });
#if defined(GIRASSOL_GGML_BACKEND_DL)
    if (loadedCpuBackend().empty()) {
        if (err) *err = "nenhum backend de CPU carregado (ggml_backend_load)";
        return false;
    }
#endif
    std::lock_guard<std::mutex> lock(mu_);
    cfg_ = cfg;
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // use_mmap: páginas limpas, recuperáveis pelo kernel
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        if (err) *err = "falha ao carregar GGUF";
        return false;
    }
    auto makeCtx = [&](bool flash, KvCacheType kv) {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(cfg.nCtx);
        cp.n_batch = 256;
        cp.n_ubatch = 256;
        cp.n_seq_max = 1;
        cp.n_threads = cfg.nThreads;
        cp.n_threads_batch = cfg.nThreads;
        cp.no_perf = true;
        cp.flash_attn_type = flash ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k = toGgml(kv);
        cp.type_v = toGgml(kv);  // V quantizado exige flash attention
        cp.abort_callback = abortCb;
        cp.abort_callback_data = &abort_;
        return llama_init_from_model(model_, cp);
    };
    flashApplied_ = cfg.flashAttn;
    kvApplied_ = cfg.flashAttn ? cfg.kvType : KvCacheType::F16;
    ctx_ = makeCtx(flashApplied_, kvApplied_);
    if (!ctx_ && flashApplied_ && kvApplied_ != KvCacheType::F16) {
        // KV quantizado exige head_dim múltiplo do bloco (32): mantém FA com KV F16
        kvApplied_ = KvCacheType::F16;
        ctx_ = makeCtx(true, KvCacheType::F16);
    }
    if (!ctx_ && (flashApplied_ || kvApplied_ != KvCacheType::F16)) {
        // modelo/arquitetura sem suporte a FA ou KV quantizado: volta ao padrão seguro
        flashApplied_ = false;
        kvApplied_ = KvCacheType::F16;
        ctx_ = makeCtx(false, KvCacheType::F16);
    }
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
    // Tabela peça→token para o fast-forward: evita o espaço-prefixo que tokenizadores SPM
    // inserem no início de um texto isolado (que a gramática rejeitaria).
    pieceToToken_.clear();
    {
        const llama_vocab* vocab = llama_model_get_vocab(model_);
        const int32_t nv = llama_vocab_n_tokens(vocab);
        char pb[64];
        for (int32_t t = 0; t < nv; ++t) {
            if (llama_vocab_is_control(vocab, t) || llama_vocab_is_eog(vocab, t)) continue;
            const int32_t n = llama_token_to_piece(vocab, t, pb, sizeof(pb), 0, false);
            if (n <= 0 || static_cast<size_t>(n) > kMaxPiece) continue;
            pieceToToken_.emplace(std::string(pb, static_cast<size_t>(n)), t);
        }
    }
    if (!cfg_.slotDir.empty()) {
        // slots antigos podem ser de outro modelo/config: começa do zero
        std::error_code ec;
        std::filesystem::create_directories(cfg_.slotDir, ec);
        for (const auto& e : std::filesystem::directory_iterator(cfg_.slotDir, ec)) {
            if (e.path().extension() == ".slot") std::filesystem::remove(e.path(), ec);
        }
    }
    return true;
#else
    (void)path; (void)cfg;
    if (err) *err = "compilado sem llama.cpp";
    return false;
#endif
}

std::vector<int32_t> LlmEngine::forcedTokens(const std::string& text) const {
    std::vector<int32_t> out;
    size_t i = 0;
    while (i < text.size()) {
        bool found = false;
        for (size_t len = std::min<size_t>(kMaxPiece, text.size() - i); len > 0; --len) {
            auto it = pieceToToken_.find(text.substr(i, len));
            if (it != pieceToToken_.end()) { out.push_back(it->second); i += len; found = true; break; }
        }
        if (!found) return {};
    }
    return out;
}

std::vector<int32_t> LlmEngine::tokenize(const std::string& text, bool addSpecial) const {
    std::vector<int32_t> tokens;
#if defined(GIRASSOL_HAS_LLAMA)
    const llama_vocab* vocab = llama_model_get_vocab(model_);
    tokens.resize(text.size() + 16);
    int32_t n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), addSpecial, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), addSpecial, true);
    }
    tokens.resize(n > 0 ? static_cast<size_t>(n) : 0);
#else
    (void)text; (void)addSpecial;
#endif
    return tokens;
}

std::string LlmEngine::applyTemplate(const std::string& system, const std::string& user, bool withUser) const {
    std::string prompt;
#if defined(GIRASSOL_HAS_LLAMA)
    if (!chatTemplate_.empty()) {
        llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
        const size_t nMsg = withUser ? 2 : 1;
        std::vector<char> buf(system.size() * 2 + user.size() * 2 + 512);
        int32_t n = llama_chat_apply_template(chatTemplate_.c_str(), msgs, nMsg, withUser, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(n) + 1);
            n = llama_chat_apply_template(chatTemplate_.c_str(), msgs, nMsg, withUser, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n > 0) prompt.assign(buf.data(), static_cast<size_t>(n));
    }
#endif
    if (prompt.empty()) prompt = withUser ? system + "\n\n" + user + "\nResposta JSON: " : system + "\n\n";
    return prompt;
}

void LlmEngine::evictSlotsLocked() {
    while (static_cast<int32_t>(slots_.size()) > std::max(1, cfg_.maxSlots) && !lru_.empty()) {
        const std::string victim = lru_.back();
        lru_.pop_back();
        auto it = slots_.find(victim);
        if (it != slots_.end()) {
            std::error_code ec;
            std::filesystem::remove(it->second.file, ec);
            slots_.erase(it);
        }
    }
}

bool LlmEngine::restoreSlotLocked(const std::string& key, const std::vector<int32_t>& want, size_t* common) {
#if defined(GIRASSOL_HAS_LLAMA)
    auto it = slots_.find(key);
    if (it == slots_.end()) return false;
    const size_t gain = commonPrefix(it->second.tokens, want);
    if (gain <= *common) return false;  // o KV atual já serve o mesmo prefixo
    llama_memory_t mem = llama_get_memory(ctx_);
    llama_memory_seq_rm(mem, 0, -1, -1);
    std::vector<llama_token> buf(it->second.tokens.size() + 8);
    size_t n = 0;
    const size_t read = llama_state_seq_load_file(ctx_, it->second.file.c_str(), 0, buf.data(), buf.size(), &n);
    if (read == 0 || n != it->second.tokens.size()) {
        llama_memory_clear(mem, true);
        cached_.clear();
        *common = 0;
        std::error_code ec;
        std::filesystem::remove(it->second.file, ec);
        lru_.remove(key);
        slots_.erase(it);
        return false;
    }
    cached_.assign(buf.begin(), buf.begin() + static_cast<std::ptrdiff_t>(n));
    *common = commonPrefix(cached_, want);
    lru_.remove(key);
    lru_.push_front(key);
    return true;
#else
    (void)key; (void)want; (void)common;
    return false;
#endif
}

void LlmEngine::saveSlotLocked(const std::string& key) {
#if defined(GIRASSOL_HAS_LLAMA)
    if (cfg_.slotDir.empty() || key.empty() || cached_.empty()) return;
    auto it = slots_.find(key);
    if (it != slots_.end() && it->second.tokens == cached_) {
        lru_.remove(key);
        lru_.push_front(key);
        return;
    }
    const std::string file = cfg_.slotDir + "/" + sanitizeKey(key) + ".slot";
    const size_t wrote = llama_state_seq_save_file(ctx_, file.c_str(), 0, cached_.data(), cached_.size());
    if (wrote == 0) return;
    slots_[key] = Slot{cached_, file};
    lru_.remove(key);
    lru_.push_front(key);
    evictSlotsLocked();
#else
    (void)key;
#endif
}

LlmResult LlmEngine::generate(const LlmRequest& req) {
    LlmResult res;
#if defined(GIRASSOL_HAS_LLAMA)
    std::lock_guard<std::mutex> lock(mu_);
    if (!model_ || !ctx_) { res.error = "modelo não carregado"; return res; }
    abort_.store(false);
    const auto t0 = std::chrono::steady_clock::now();
    const llama_vocab* vocab = llama_model_get_vocab(model_);
    const int32_t maxTokens = std::max(1, req.maxTokens);

    // --- prompt e prefixo estável (system) -------------------------------------------
    std::string prompt = applyTemplate(req.system, req.user, true);
    const bool ff = !req.grammar.empty() && !req.jsonKeys.empty();
    std::string forced;
    if (ff) forced = "{\"" + req.jsonKeys[0] + "\":\"";
    std::vector<int32_t> tokens = tokenize(prompt, true);
    if (tokens.empty()) { res.error = "tokenização falhou"; return res; }
    const std::vector<int32_t> sysTokens = tokenize(applyTemplate(req.system, "", false), true);
    size_t stable = commonPrefix(sysTokens, tokens);

    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(ctx_));
    const int32_t budget = nCtx - maxTokens - 48;
    if (static_cast<int32_t>(tokens.size()) > budget) {
        if (budget <= 8) { res.error = "contexto insuficiente"; return res; }
        // corta o meio do sufixo do usuário, preservando o prefixo estável e o fim
        const size_t drop = tokens.size() - static_cast<size_t>(budget);
        const size_t from = std::min(stable, tokens.size() - drop);
        tokens.erase(tokens.begin() + static_cast<std::ptrdiff_t>(from), tokens.begin() + static_cast<std::ptrdiff_t>(from + drop));
        stable = std::min(stable, from);
    }
    res.promptTokens = static_cast<int32_t>(tokens.size());

    // --- reaproveitamento do KV-cache: prefixo comum + slot do cidadão ----------------
    llama_memory_t mem = llama_get_memory(ctx_);
    size_t common = commonPrefix(cached_, tokens);
    if (!req.slotKey.empty()) res.slotRestored = restoreSlotLocked(req.slotKey, tokens, &common);
    if (common >= tokens.size()) common = tokens.size() - 1;  // precisa de logits do último token
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    cached_.resize(common);
    res.reusedTokens = static_cast<int32_t>(common);

    // --- amostrador: GBNF → temperatura semântica → semente ---------------------------
    llama_sampler* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler* gram = req.grammar.empty() ? nullptr : llama_sampler_init_grammar(vocab, req.grammar.c_str(), "root");
    if (!req.grammar.empty() && gram == nullptr) {
        llama_sampler_free(chain);
        res.error = "gramática GBNF inválida";
        return res;
    }
    if (gram) llama_sampler_chain_add(chain, gram);
    if (req.temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(req.temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(req.seed));
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }

    auto decodeRun = [&](const int32_t* data, int32_t n) -> bool {
        const int32_t nBatch = static_cast<int32_t>(llama_n_batch(ctx_));
        for (int32_t i = 0; i < n; i += nBatch) {
            const int32_t m = std::min(nBatch, n - i);
            if (llama_decode(ctx_, llama_batch_get_one(const_cast<llama_token*>(data + i), m)) != 0) return false;
            cached_.insert(cached_.end(), data + i, data + i + m);
        }
        return true;
    };
    // Fast-forward: tokens fixos entram em lote no KV e passam pelo accept da gramática.
    auto injectForced = [&](const std::string& text) -> bool {
        const std::vector<int32_t> ft = forcedTokens(text);
        if (ft.empty() || gram == nullptr) return false;
        // valida a sequência inteira num clone da gramática antes de tocar o estado real
        // (aceitar um token proibido dispara GGML_ASSERT dentro do llama.cpp)
        llama_sampler* probe = llama_sampler_clone(gram);
        bool okSeq = probe != nullptr;
        for (size_t k = 0; okSeq && k < ft.size(); ++k) {
            llama_token_data td{ft[k], 0.0f, 0.0f};
            llama_token_data_array arr{&td, 1, -1, false};
            llama_sampler_apply(probe, &arr);
            if (!std::isfinite(td.logit)) { okSeq = false; break; }
            llama_sampler_accept(probe, ft[k]);
        }
        if (probe) llama_sampler_free(probe);
        if (!okSeq) return false;
        for (int32_t t : ft) llama_sampler_accept(chain, t);
        if (!decodeRun(ft.data(), static_cast<int32_t>(ft.size()))) return false;
        res.forcedTokens += static_cast<int32_t>(ft.size());
        return true;
    };

    std::string out;
    if (!decodeRun(tokens.data() + common, static_cast<int32_t>(tokens.size() - common))) {
        llama_sampler_free(chain);
        llama_memory_clear(mem, true);
        cached_.clear();
        res.error = abort_.load() ? "abortado" : "decode do prompt falhou";
        return res;
    }
    bool ffActive = ff && injectForced(forced);
    if (ffActive) out = forced;
    size_t nextKey = 1;
    int quotes = 0;
    for (char c : out) quotes += c == '"';

    const auto tGen0 = std::chrono::steady_clock::now();
    char piece[256];
    bool done = false;
    for (int32_t g = 0; g < maxTokens && !done; ++g) {
        llama_token tok = llama_sampler_sample(chain, ctx_, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        const int32_t n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            out.append(piece, static_cast<size_t>(n));
            for (int32_t k = 0; k < n; ++k) quotes += piece[k] == '"';
        }
        res.genTokens++;
        if (!out.empty() && out.back() == '}' && gram) break;  // objeto JSON fechado
        if (llama_decode(ctx_, llama_batch_get_one(&tok, 1)) != 0) {
            res.error = abort_.load() ? "abortado" : "decode falhou";
            break;
        }
        cached_.push_back(tok);
        if (ffActive && quotes % 2 == 0) {
            if (out.back() != '"') { ffActive = false; continue; }  // o modelo já seguiu sozinho
            if (nextKey < req.jsonKeys.size()) {
                const std::string seg = ",\"" + req.jsonKeys[nextKey] + "\":\"";
                if (injectForced(seg)) { out += seg; quotes += 3; ++nextKey; }
                else ffActive = false;
            } else {
                out.push_back('}');  // fim do objeto: não precisa ser decodificado
                res.forcedTokens++;
                done = true;
            }
        }
    }
    llama_sampler_free(chain);

    // KV mantém só o prefixo estável (mundo + identidade) → próximo pedido reaproveita.
    if (stable > 0 && stable <= cached_.size() && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(stable), -1)) {
        cached_.resize(stable);
        if (!req.slotKey.empty()) saveSlotLocked(req.slotKey);
    } else {
        llama_memory_clear(mem, true);
        cached_.clear();
    }

    const auto t1 = std::chrono::steady_clock::now();
    res.millis = std::chrono::duration<double, std::milli>(t1 - t0).count();
    res.tokensPerSecond = res.millis > 0 ? (res.genTokens + res.promptTokens) * 1000.0 / res.millis : 0.0;
    const double genSecs = std::chrono::duration<double>(t1 - tGen0).count();
    res.genTokensPerSecond = genSecs > 0 ? res.genTokens / genSecs : 0.0;
    res.text = out;
    res.ok = res.error.empty() && !out.empty();
    return res;
#else
    (void)req;
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
    cached_.clear();
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
