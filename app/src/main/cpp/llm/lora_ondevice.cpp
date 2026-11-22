#include "lora_ondevice.hpp"

#if defined(GIRASSOL_HAS_LLAMA)
#include "ggml-backend.h"
#include "ggml.h"
#include "gguf.h"
#include "llama.h"
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <numeric>
#include <unordered_map>

namespace girassol::llm {

namespace {

struct Rng {
    uint64_t s;
    uint64_t next() {
        uint64_t z = (s += 0x9E3779B97F4A7C15ULL);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
        return z ^ (z >> 31);
    }
    double unit() { return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0); }
};

#if defined(GIRASSOL_HAS_LLAMA)

std::string metaStr(const llama_model* m, const std::string& key) {
    char buf[256];
    const int32_t n = llama_model_meta_val_str(m, key.c_str(), buf, sizeof(buf));
    return n > 0 ? std::string(buf, static_cast<size_t>(std::min<int32_t>(n, sizeof(buf) - 1))) : std::string();
}

/** Leitor de linhas de tensores do GGUF base (desquantização via ggml type traits). */
struct GgufRows {
    gguf_context* g = nullptr;
    FILE* f = nullptr;
    size_t dataOff = 0;
    ~GgufRows() {
        if (f) std::fclose(f);
        if (g) gguf_free(g);
    }
    bool open(const std::string& path) {
        gguf_init_params p{true, nullptr};
        g = gguf_init_from_file(path.c_str(), p);
        if (!g) return false;
        f = std::fopen(path.c_str(), "rb");
        dataOff = gguf_get_data_offset(g);
        return f != nullptr;
    }
    int64_t find(const std::string& name) const { return gguf_find_tensor(g, name.c_str()); }
    const int64_t* ne(int64_t id) const { return gguf_get_tensor_ne(g, id); }
    /** Copia a linha `row` (ne[0] elementos) como float. */
    bool row(int64_t id, int64_t r, std::vector<float>& out) const {
        const ggml_type t = gguf_get_tensor_type(g, id);
        const int64_t n0 = ne(id)[0];
        const size_t rs = ggml_row_size(t, n0);
        std::vector<uint8_t> raw(rs);
        const size_t off = dataOff + gguf_get_tensor_offset(g, id) + static_cast<size_t>(r) * rs;
        if (std::fseek(f, static_cast<long>(off), SEEK_SET) != 0) return false;
        if (std::fread(raw.data(), 1, rs, f) != rs) return false;
        out.resize(static_cast<size_t>(n0));
        if (t == GGML_TYPE_F32) {
            std::memcpy(out.data(), raw.data(), rs);
            return true;
        }
        const ggml_type_traits* tr = ggml_get_type_traits(t);
        if (!tr || !tr->to_float) return false;
        tr->to_float(raw.data(), out.data(), n0);
        return true;
    }
    std::string str(const std::string& key) const {
        const int64_t k = gguf_find_key(g, key.c_str());
        return k >= 0 && gguf_get_kv_type(g, k) == GGUF_TYPE_STRING ? gguf_get_val_str(g, k) : std::string();
    }
    double num(const std::string& key, double def) const {
        const int64_t k = gguf_find_key(g, key.c_str());
        if (k < 0) return def;
        switch (gguf_get_kv_type(g, k)) {
            case GGUF_TYPE_FLOAT32: return gguf_get_val_f32(g, k);
            case GGUF_TYPE_FLOAT64: return gguf_get_val_f64(g, k);
            case GGUF_TYPE_UINT32: return gguf_get_val_u32(g, k);
            case GGUF_TYPE_INT32: return gguf_get_val_i32(g, k);
            case GGUF_TYPE_UINT64: return static_cast<double>(gguf_get_val_u64(g, k));
            default: return def;
        }
    }
};

/** Captura das ativações via cb_eval do scheduler. */
struct Capture {
    std::string downWeight;  // nome do peso alvo (src[0] do mul_mat)
    std::string residual;    // "l_out-{L-1}"
    bool enabled = true;
    std::vector<float> a, x;
    int64_t nIn = 0, nOut = 0;
    bool gotA = false, gotX = false;
};

void appendTensor(ggml_tensor* t, std::vector<float>& dst) {
    const int64_t n = ggml_nelements(t);
    std::vector<uint8_t> raw(ggml_nbytes(t));
    ggml_backend_tensor_get(t, raw.data(), 0, raw.size());
    const size_t base = dst.size();
    dst.resize(base + static_cast<size_t>(n));
    if (t->type == GGML_TYPE_F32) {
        std::memcpy(dst.data() + base, raw.data(), static_cast<size_t>(n) * sizeof(float));
    } else {
        const ggml_type_traits* tr = ggml_get_type_traits(t->type);
        if (tr && tr->to_float) tr->to_float(raw.data(), dst.data() + base, n);
    }
}

bool captureCb(ggml_tensor* t, bool ask, void* ud) {
    auto* c = static_cast<Capture*>(ud);
    if (!c->enabled) return !ask;  // ask=false nunca chega quando ask devolve false
    const bool isDown = t->op == GGML_OP_MUL_MAT && t->src[0] && t->src[1] && c->downWeight == t->src[0]->name;
    const bool isRes = c->residual == t->name;
    if (ask) return isDown || isRes;
    if (isDown && ggml_is_contiguous(t->src[1]) && t->src[1]->ne[0] == c->nIn) {
        appendTensor(t->src[1], c->a);
        c->gotA = true;
    } else if (isRes && ggml_is_contiguous(t) && t->ne[0] == c->nOut) {
        appendTensor(t, c->x);
        c->gotX = true;
    }
    return true;
}

struct TokenRec {
    size_t aOff, xOff;
    int32_t target;
    std::vector<int32_t> ids;     // conjunto esparso S (alvo + top-K)
    std::vector<float> baseLogit;
    double lse;                   // log-sum-exp completo do modelo base
};

struct Seq {
    std::vector<int32_t> toks;
    bool held = false;
};

bool decodeAll(llama_context* ctx, const std::vector<int32_t>& toks) {
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_batch b = llama_batch_init(static_cast<int32_t>(toks.size()), 0, 1);
    for (size_t i = 0; i < toks.size(); ++i) {
        b.token[i] = toks[i];
        b.pos[i] = static_cast<llama_pos>(i);
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = 0;
        b.logits[i] = 1;
    }
    b.n_tokens = static_cast<int32_t>(toks.size());
    const bool ok = llama_decode(ctx, b) == 0;
    llama_batch_free(b);
    return ok;
}

/** NLL real (softmax completo) do próximo token, somado sobre a sequência. */
double realNll(llama_context* ctx, const std::vector<int32_t>& toks, int32_t nVocab, int32_t* count) {
    double s = 0;
    for (size_t t = 0; t + 1 < toks.size(); ++t) {
        const float* lg = llama_get_logits_ith(ctx, static_cast<int32_t>(t));
        float mx = -INFINITY;
        for (int32_t v = 0; v < nVocab; ++v) mx = std::max(mx, lg[v]);
        double z = 0;
        for (int32_t v = 0; v < nVocab; ++v) z += std::exp(static_cast<double>(lg[v] - mx));
        s += (mx + std::log(z)) - lg[toks[t + 1]];
        ++*count;
    }
    return s;
}

#endif  // GIRASSOL_HAS_LLAMA

}  // namespace

int32_t OnDeviceLoraTrainer::headDim(const llama_model* model) {
#if defined(GIRASSOL_HAS_LLAMA)
    if (!model) return 0;
    const std::string arch = metaStr(model, "general.architecture");
    const std::string kl = metaStr(model, arch + ".attention.key_length");
    if (!kl.empty()) {
        const int v = std::atoi(kl.c_str());
        if (v > 0) return v;
    }
    const int32_t nh = llama_model_n_head(model);
    return nh > 0 ? llama_model_n_embd(model) / nh : 0;
#else
    (void)model;
    return 0;
#endif
}

bool OnDeviceLoraTrainer::writeAdapterGguf(const std::string& path, const std::string& arch, const LoraWeights& w, std::string* err) {
#if defined(GIRASSOL_HAS_LLAMA)
    if (!w.valid()) { if (err) *err = "pesos LoRA inválidos"; return false; }
    ggml_init_params ip{};
    ip.mem_size = (w.a.size() + w.b.size()) * sizeof(float) + 4 * ggml_tensor_overhead() + 4096;
    ip.mem_buffer = nullptr;
    ip.no_alloc = false;
    ggml_context* gc = ggml_init(ip);
    if (!gc) { if (err) *err = "ggml_init falhou"; return false; }
    ggml_tensor* ta = ggml_new_tensor_2d(gc, GGML_TYPE_F32, w.nIn, w.rank);
    ggml_tensor* tb = ggml_new_tensor_2d(gc, GGML_TYPE_F32, w.rank, w.nOut);
    ggml_set_name(ta, (w.target + ".lora_a").c_str());
    ggml_set_name(tb, (w.target + ".lora_b").c_str());
    std::memcpy(ta->data, w.a.data(), w.a.size() * sizeof(float));
    std::memcpy(tb->data, w.b.data(), w.b.size() * sizeof(float));
    gguf_context* g = gguf_init_empty();
    gguf_set_val_str(g, "general.type", "adapter");
    gguf_set_val_str(g, "general.architecture", arch.c_str());
    gguf_set_val_str(g, "general.name", "girassol-swr-lora");
    gguf_set_val_str(g, "adapter.type", "lora");
    gguf_set_val_f32(g, "adapter.lora.alpha", w.alpha);
    gguf_set_val_u32(g, "girassol.lora.cycles", static_cast<uint32_t>(w.cycles));
    gguf_set_val_u64(g, "girassol.lora.step", static_cast<uint64_t>(w.step));
    gguf_add_tensor(g, ta);
    gguf_add_tensor(g, tb);
    const bool ok = gguf_write_to_file(g, path.c_str(), false);
    gguf_free(g);
    ggml_free(gc);
    if (!ok && err) *err = "falha ao gravar " + path;
    return ok;
#else
    (void)path; (void)arch; (void)w;
    if (err) *err = "compilado sem llama.cpp";
    return false;
#endif
}

bool OnDeviceLoraTrainer::readAdapterGguf(const std::string& path, LoraWeights& w, std::string* err) {
#if defined(GIRASSOL_HAS_LLAMA)
    ggml_context* gc = nullptr;
    gguf_init_params p{false, &gc};
    gguf_context* g = gguf_init_from_file(path.c_str(), p);
    if (!g) { if (err) *err = "adaptador ilegível"; return false; }
    ggml_tensor *ta = nullptr, *tb = nullptr;
    for (ggml_tensor* t = ggml_get_first_tensor(gc); t; t = ggml_get_next_tensor(gc, t)) {
        const std::string n = t->name;
        if (n.size() > 7 && n.compare(n.size() - 7, 7, ".lora_a") == 0) ta = t;
        if (n.size() > 7 && n.compare(n.size() - 7, 7, ".lora_b") == 0) tb = t;
    }
    bool ok = ta && tb && ta->type == GGML_TYPE_F32 && tb->type == GGML_TYPE_F32 && ta->ne[1] == tb->ne[0];
    if (ok) {
        LoraWeights r;
        r.target = std::string(ta->name).substr(0, std::strlen(ta->name) - 7);
        r.nIn = static_cast<int32_t>(ta->ne[0]);
        r.rank = static_cast<int32_t>(ta->ne[1]);
        r.nOut = static_cast<int32_t>(tb->ne[1]);
        const int64_t ka = gguf_find_key(g, "adapter.lora.alpha");
        r.alpha = ka >= 0 ? gguf_get_val_f32(g, ka) : static_cast<float>(r.rank);
        const int64_t kc = gguf_find_key(g, "girassol.lora.cycles");
        r.cycles = kc >= 0 ? static_cast<int32_t>(gguf_get_val_u32(g, kc)) : 0;
        const int64_t ks = gguf_find_key(g, "girassol.lora.step");
        r.step = ks >= 0 ? static_cast<int64_t>(gguf_get_val_u64(g, ks)) : 0;
        r.a.assign(static_cast<float*>(ta->data), static_cast<float*>(ta->data) + ggml_nelements(ta));
        r.b.assign(static_cast<float*>(tb->data), static_cast<float*>(tb->data) + ggml_nelements(tb));
        r.ma.assign(r.a.size(), 0.0f); r.va.assign(r.a.size(), 0.0f);
        r.mb.assign(r.b.size(), 0.0f); r.vb.assign(r.b.size(), 0.0f);
        w = std::move(r);
    } else if (err) {
        *err = "adaptador sem par lora_a/lora_b F32";
    }
    gguf_free(g);
    ggml_free(gc);
    return ok;
#else
    (void)path; (void)w;
    if (err) *err = "compilado sem llama.cpp";
    return false;
#endif
}

LoraTrainReport OnDeviceLoraTrainer::train(llama_model* model, const std::string& modelPath, const std::vector<std::string>& texts,
                                           const LoraTrainConfig& cfg, LoraWeights& weights) {
    LoraTrainReport rep;
#if defined(GIRASSOL_HAS_LLAMA)
    const auto t0 = std::chrono::steady_clock::now();
    if (!model) { rep.error = "modelo não carregado"; return rep; }
    GgufRows gg;
    if (!gg.open(modelPath)) { rep.error = "GGUF base ilegível"; return rep; }
    const std::string arch = gg.str("general.architecture");
    const int nLayer = static_cast<int>(gg.num(arch + ".block_count", 0));
    if (arch.empty() || nLayer <= 0) { rep.error = "metadados de arquitetura ausentes"; return rep; }
    if (gg.find(arch.empty() ? "" : "output_norm.weight") < 0) { rep.error = "sem output_norm"; return rep; }
    const double eps = gg.num(arch + ".attention.layer_norm_rms_epsilon", -1.0);
    if (eps <= 0) { rep.error = "arquitetura sem RMSNorm final (LoRA on-device indisponível)"; return rep; }
    const std::string target = "blk." + std::to_string(nLayer - 1) + ".ffn_down.weight";
    const int64_t tid = gg.find(target);
    if (tid < 0) { rep.error = "tensor alvo ausente: " + target; return rep; }
    const int64_t nIn = gg.ne(tid)[0], nOut = gg.ne(tid)[1];
    int64_t outId = gg.find("output.weight");
    if (outId < 0) outId = gg.find("token_embd.weight");  // embeddings amarrados
    if (outId < 0 || gg.ne(outId)[0] != nOut) { rep.error = "projeção de saída incompatível"; return rep; }
    const int64_t gId = gg.find("output_norm.weight");
    std::vector<float> gain;
    if (!gg.row(gId, 0, gain) || static_cast<int64_t>(gain.size()) != nOut) { rep.error = "output_norm ilegível"; return rep; }
    rep.target = target;

    // --- estado do adaptador (retoma o ciclo anterior se compatível) -----------------
    const int32_t r = std::clamp(cfg.rank, 1, 64);
    if (!weights.valid() || weights.target != target || weights.nIn != nIn || weights.nOut != nOut) {
        LoraWeights w;
        w.target = target; w.nIn = static_cast<int32_t>(nIn); w.nOut = static_cast<int32_t>(nOut);
        w.rank = r; w.alpha = cfg.alpha;
        Rng rng{cfg.seed ^ 0x4C6F5241ULL};
        const double bound = 1.0 / std::sqrt(static_cast<double>(nIn));
        w.a.resize(static_cast<size_t>(r * nIn));
        for (float& v : w.a) v = static_cast<float>((rng.unit() * 2.0 - 1.0) * bound);
        w.b.assign(static_cast<size_t>(nOut * r), 0.0f);  // B=0 → adaptador nasce neutro
        w.ma.assign(w.a.size(), 0.0f); w.va.assign(w.a.size(), 0.0f);
        w.mb.assign(w.b.size(), 0.0f); w.vb.assign(w.b.size(), 0.0f);
        weights = std::move(w);
    }
    rep.rank = weights.rank;
    const LoraWeights backup = weights;
    const int32_t R = weights.rank;
    const float s = weights.alpha / static_cast<float>(R);

    // --- corpus: tokens, divisão treino/held-out determinística ----------------------
    const llama_vocab* vocab = llama_model_get_vocab(model);
    const int32_t nVocab = llama_vocab_n_tokens(vocab);
    std::vector<Seq> seqs;
    int32_t budget = std::max(32, cfg.maxTokens);
    const int stride = std::max(2, static_cast<int>(std::lround(1.0 / std::clamp(cfg.heldOutFraction, 0.05f, 0.5f))));
    size_t maxLen = 8;
    for (size_t i = 0; i < texts.size() && budget > 0; ++i) {
        std::vector<int32_t> tk(texts[i].size() + 8);
        int32_t n = llama_tokenize(vocab, texts[i].c_str(), static_cast<int32_t>(texts[i].size()), tk.data(), static_cast<int32_t>(tk.size()), true, false);
        if (n < 0) { tk.resize(static_cast<size_t>(-n)); n = llama_tokenize(vocab, texts[i].c_str(), static_cast<int32_t>(texts[i].size()), tk.data(), static_cast<int32_t>(tk.size()), true, false); }
        if (n < 3) continue;
        tk.resize(static_cast<size_t>(std::min<int32_t>(n, std::min<int32_t>(128, budget))));
        budget -= static_cast<int32_t>(tk.size());
        Seq q; q.toks = std::move(tk);
        q.held = (seqs.size() % static_cast<size_t>(stride)) == static_cast<size_t>(stride - 1);
        maxLen = std::max(maxLen, q.toks.size());
        seqs.push_back(std::move(q));
    }
    if (seqs.size() >= 2 && std::none_of(seqs.begin(), seqs.end(), [](const Seq& q) { return q.held; })) seqs.back().held = true;
    if (seqs.size() < 2) { rep.error = "memórias insuficientes para treino (mín. 2 textos)"; return rep; }

    // --- contexto de treino dedicado com cb_eval ---------------------------------------
    Capture cap;
    cap.downWeight = target;
    cap.residual = "l_out-" + std::to_string(nLayer - 1);
    cap.nIn = nIn; cap.nOut = nOut;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(maxLen + 16);
    cp.n_batch = cp.n_ctx;
    cp.n_ubatch = cp.n_ctx;
    cp.n_seq_max = 1;
    cp.n_threads = std::max(1, cfg.nThreads);
    cp.n_threads_batch = cp.n_threads;
    cp.cb_eval = captureCb;
    cp.cb_eval_user_data = &cap;
    cp.type_k = GGML_TYPE_F16;
    cp.type_v = GGML_TYPE_F16;
    cp.no_perf = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { rep.error = "contexto de treino falhou"; return rep; }
    llama_set_adapters_lora(ctx, nullptr, 0, nullptr);  // passe base sempre sem adaptador

    std::vector<TokenRec> train, held;
    std::vector<float> A, X;
    double heldBase = 0; int32_t heldCount = 0;
    const int K = std::clamp(cfg.topK, 1, 64);
    std::vector<int32_t> order(static_cast<size_t>(nVocab));
    for (const Seq& q : seqs) {
        cap.a.clear(); cap.x.clear(); cap.gotA = cap.gotX = false; cap.enabled = true;
        if (!decodeAll(ctx, q.toks)) { rep.error = "decode de treino falhou"; break; }
        const size_t n = q.toks.size();
        if (!cap.gotA || !cap.gotX || cap.a.size() != n * static_cast<size_t>(nIn) || cap.x.size() != n * static_cast<size_t>(nOut)) {
            rep.error = "captura de ativações incompleta (arquitetura sem ffn_down denso?)";
            break;
        }
        if (q.held) heldBase += realNll(ctx, q.toks, nVocab, &heldCount);
        const size_t aBase = A.size(), xBase = X.size();
        A.insert(A.end(), cap.a.begin(), cap.a.end());
        X.insert(X.end(), cap.x.begin(), cap.x.end());
        for (size_t t = 0; t + 1 < n; ++t) {
            const float* lg = llama_get_logits_ith(ctx, static_cast<int32_t>(t));
            float mx = -INFINITY;
            for (int32_t v = 0; v < nVocab; ++v) mx = std::max(mx, lg[v]);
            double z = 0;
            for (int32_t v = 0; v < nVocab; ++v) z += std::exp(static_cast<double>(lg[v] - mx));
            TokenRec rec;
            rec.aOff = aBase + t * static_cast<size_t>(nIn);
            rec.xOff = xBase + t * static_cast<size_t>(nOut);
            rec.target = q.toks[t + 1];
            rec.lse = mx + std::log(z);
            std::iota(order.begin(), order.end(), 0);
            std::partial_sort(order.begin(), order.begin() + K, order.end(), [&](int32_t p, int32_t q2) { return lg[p] > lg[q2]; });
            rec.ids.push_back(rec.target);
            for (int k = 0; k < K; ++k) if (order[static_cast<size_t>(k)] != rec.target) rec.ids.push_back(order[static_cast<size_t>(k)]);
            for (int32_t id : rec.ids) rec.baseLogit.push_back(lg[id]);
            (q.held ? held : train).push_back(std::move(rec));
        }
    }
    if (!rep.error.empty() || train.empty() || held.empty()) {
        if (rep.error.empty()) rep.error = "sem tokens de treino/held-out";
        llama_free(ctx);
        return rep;
    }
    rep.trainTokens = static_cast<int32_t>(train.size());
    rep.heldTokens = heldCount;
    rep.heldNllBase = heldBase / std::max(1, heldCount);

    // --- linhas de W_out necessárias (desquantizadas uma vez) -------------------------
    std::unordered_map<int32_t, std::vector<float>> rows;
    auto rowOf = [&](int32_t id) -> const std::vector<float>& {
        auto it = rows.find(id);
        if (it == rows.end()) {
            std::vector<float> v;
            if (!gg.row(outId, id, v)) v.assign(static_cast<size_t>(nOut), 0.0f);
            it = rows.emplace(id, std::move(v)).first;
        }
        return it->second;
    };
    for (const auto* set : {&train, &held}) for (const TokenRec& rc : *set) for (int32_t id : rc.ids) rowOf(id);

    // --- forward/backward esparso exato pela RMSNorm final ----------------------------
    std::vector<double> u(static_cast<size_t>(R)), du(static_cast<size_t>(R));
    std::vector<double> xp(static_cast<size_t>(nOut)), dyv(static_cast<size_t>(nOut)), gdy(static_cast<size_t>(nOut)), dx(static_cast<size_t>(nOut));
    std::vector<float> gA(weights.a.size()), gB(weights.b.size());
    auto step = [&](const TokenRec& rc, bool grad) -> double {
        const float* a = A.data() + rc.aOff;
        const float* x = X.data() + rc.xOff;
        for (int j = 0; j < R; ++j) {
            double acc = 0;
            const float* row = weights.a.data() + static_cast<size_t>(j) * nIn;
            for (int64_t i = 0; i < nIn; ++i) acc += static_cast<double>(row[i]) * a[i];
            u[static_cast<size_t>(j)] = acc;
        }
        double ss0 = 0, ss1 = 0;
        for (int64_t o = 0; o < nOut; ++o) {
            double d = 0;
            const float* brow = weights.b.data() + static_cast<size_t>(o) * R;
            for (int j = 0; j < R; ++j) d += static_cast<double>(brow[j]) * u[static_cast<size_t>(j)];
            xp[static_cast<size_t>(o)] = x[o] + s * d;
            ss0 += static_cast<double>(x[o]) * x[o];
            ss1 += xp[static_cast<size_t>(o)] * xp[static_cast<size_t>(o)];
        }
        const double rms0 = std::sqrt(ss0 / nOut + eps), rms1 = std::sqrt(ss1 / nOut + eps);
        for (int64_t o = 0; o < nOut; ++o) dyv[static_cast<size_t>(o)] = gain[static_cast<size_t>(o)] * (xp[static_cast<size_t>(o)] / rms1 - x[o] / rms0);
        const size_t S = rc.ids.size();
        std::vector<double> z(S);
        double rest = 1.0, acc = 0;
        for (size_t k = 0; k < S; ++k) {
            const std::vector<float>& w = rowOf(rc.ids[k]);
            double d = 0;
            for (int64_t o = 0; o < nOut; ++o) d += w[static_cast<size_t>(o)] * dyv[static_cast<size_t>(o)];
            z[k] = rc.baseLogit[k] + d;
            rest -= std::exp(rc.baseLogit[k] - rc.lse);
            acc += std::exp(z[k] - rc.lse);
        }
        const double lse1 = rc.lse + std::log(std::max(0.0, rest) + acc);
        const double loss = lse1 - z[0];  // ids[0] é o alvo
        if (!grad) return loss;
        std::fill(gdy.begin(), gdy.end(), 0.0);
        for (size_t k = 0; k < S; ++k) {
            const double dz = std::exp(z[k] - lse1) - (k == 0 ? 1.0 : 0.0);
            const std::vector<float>& w = rowOf(rc.ids[k]);
            for (int64_t o = 0; o < nOut; ++o) gdy[static_cast<size_t>(o)] += dz * w[static_cast<size_t>(o)];
        }
        double dot = 0;
        for (int64_t o = 0; o < nOut; ++o) {
            gdy[static_cast<size_t>(o)] *= gain[static_cast<size_t>(o)];
            dot += (xp[static_cast<size_t>(o)] / rms1) * gdy[static_cast<size_t>(o)];
        }
        std::fill(du.begin(), du.end(), 0.0);
        for (int64_t o = 0; o < nOut; ++o) {
            const double xh = xp[static_cast<size_t>(o)] / rms1;
            dx[static_cast<size_t>(o)] = (gdy[static_cast<size_t>(o)] - xh * dot / nOut) / rms1;
            float* gb = gB.data() + static_cast<size_t>(o) * R;
            const float* brow = weights.b.data() + static_cast<size_t>(o) * R;
            for (int j = 0; j < R; ++j) {
                gb[j] += static_cast<float>(s * dx[static_cast<size_t>(o)] * u[static_cast<size_t>(j)]);
                du[static_cast<size_t>(j)] += s * brow[j] * dx[static_cast<size_t>(o)];
            }
        }
        for (int j = 0; j < R; ++j) {
            float* ga = gA.data() + static_cast<size_t>(j) * nIn;
            const double dj = du[static_cast<size_t>(j)];
            for (int64_t i = 0; i < nIn; ++i) ga[i] += static_cast<float>(dj * a[i]);
        }
        return loss;
    };
    auto meanLoss = [&](const std::vector<TokenRec>& set) {
        double t = 0;
        for (const TokenRec& rc : set) t += step(rc, false);
        return t / std::max<size_t>(1, set.size());
    };
    rep.trainNllBefore = meanLoss(train);

    auto adam = [&](std::vector<float>& p, std::vector<float>& g, std::vector<float>& m, std::vector<float>& v, double scale) {
        const double b1 = 0.9, b2 = 0.999;
        const double c1 = 1.0 - std::pow(b1, static_cast<double>(weights.step)), c2 = 1.0 - std::pow(b2, static_cast<double>(weights.step));
        for (size_t i = 0; i < p.size(); ++i) {
            const double gi = g[i] * scale;
            m[i] = static_cast<float>(b1 * m[i] + (1 - b1) * gi);
            v[i] = static_cast<float>(b2 * v[i] + (1 - b2) * gi * gi);
            p[i] -= static_cast<float>(cfg.lr * (m[i] / c1) / (std::sqrt(v[i] / c2) + 1e-8));
            g[i] = 0.0f;
        }
    };
    Rng rng{cfg.seed + static_cast<uint64_t>(weights.cycles) * 7919ULL};
    std::vector<size_t> idx(train.size());
    std::iota(idx.begin(), idx.end(), 0);
    const size_t mb = 8;
    for (int ep = 0; ep < std::max(1, cfg.epochs); ++ep) {
        for (size_t i = idx.size(); i > 1; --i) std::swap(idx[i - 1], idx[static_cast<size_t>(rng.next() % i)]);
        for (size_t st = 0; st < idx.size(); st += mb) {
            const size_t en = std::min(idx.size(), st + mb);
            for (size_t k = st; k < en; ++k) step(train[idx[k]], true);
            double norm = 0;
            for (float g : gA) norm += static_cast<double>(g) * g;
            for (float g : gB) norm += static_cast<double>(g) * g;
            norm = std::sqrt(norm) / static_cast<double>(en - st);
            const double scale = (1.0 / static_cast<double>(en - st)) * (norm > 1.0 ? 1.0 / norm : 1.0);
            weights.step++;
            // Orçamento Sináptico: G_A <- G_A (I - P) antes do passo (protege o subespaço de
            // ativações consolidado de ciclos anteriores; gB é livre — B é a saída do adaptador).
            if (cfg.sbBridge != nullptr && cfg.sbBridge->project_f32 != nullptr)
                rep.sbProjectedBlocks += cfg.sbBridge->project_f32(gA.data(), R, nIn);
            adam(weights.a, gA, weights.ma, weights.va, scale);
            adam(weights.b, gB, weights.mb, weights.vb, scale);
        }
    }
    rep.trainNllAfter = meanLoss(train);

    // --- validação real no held-out: o llama.cpp aplica o adaptador recém-escrito --------
    const std::string tmp = cfg.adapterPath + ".tmp";
    weights.cycles = backup.cycles + 1;
    std::string err;
    double adapted = 0, prev = rep.heldNllBase;
    int32_t cnt = 0;
    cap.enabled = false;
    auto evalAdapter = [&](const std::string& path, double* out) -> bool {
        llama_adapter_lora* ad = llama_adapter_lora_init(model, path.c_str());
        if (!ad) return false;
        float sc = 1.0f;
        llama_set_adapters_lora(ctx, &ad, 1, &sc);
        double tot = 0; int32_t c = 0;
        bool ok = true;
        for (const Seq& q : seqs) {
            if (!q.held) continue;
            if (!decodeAll(ctx, q.toks)) { ok = false; break; }
            tot += realNll(ctx, q.toks, nVocab, &c);
        }
        llama_set_adapters_lora(ctx, nullptr, 0, nullptr);
        llama_adapter_lora_free(ad);
        *out = c > 0 ? tot / c : 0;
        cnt = c;
        return ok && c > 0;
    };
    bool okEval = writeAdapterGguf(tmp, arch, weights, &err) && evalAdapter(tmp, &adapted);
    if (okEval && backup.valid() && backup.cycles > 0 && std::filesystem::exists(cfg.adapterPath)) {
        double p = 0;
        if (evalAdapter(cfg.adapterPath, &p)) prev = std::min(prev, p);
    }
    llama_free(ctx);
    rep.heldNllAdapted = adapted;
    rep.ok = okEval;
    if (!okEval) rep.error = err.empty() ? "validação do adaptador falhou" : err;
    rep.accepted = okEval && cnt > 0 && adapted < prev - 1e-4 && rep.trainNllAfter < rep.trainNllBefore;
    std::error_code ec;
    if (rep.accepted) {
        std::filesystem::rename(tmp, cfg.adapterPath, ec);
        if (ec) { rep.accepted = false; rep.error = "rename do adaptador falhou"; }
    }
    if (rep.accepted && cfg.sbBridge != nullptr && cfg.sbBridge->stage_activations_f32 != nullptr && !A.empty()) {
        // ativações de entrada do ffn_down deste ciclo (n_tokens x n_ff) -> área de preparação
        rep.sbStagedRows = cfg.sbBridge->stage_activations_f32(A.data(), static_cast<int64_t>(A.size() / static_cast<size_t>(nIn)), nIn);
    }
    if (!rep.accepted) {
        std::filesystem::remove(tmp, ec);
        weights = backup;  // ciclo rejeitado não contamina o próximo
    }
    rep.cycle = weights.cycles;
    rep.millis = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
#else
    (void)model; (void)modelPath; (void)texts; (void)cfg; (void)weights;
    rep.error = "compilado sem llama.cpp";
#endif
    return rep;
}

}  // namespace girassol::llm
