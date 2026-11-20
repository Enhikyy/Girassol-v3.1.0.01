#pragma once
// SemanticEntropyController — temperatura adaptativa anti-repetição.
// H_ngram = entropia de Shannon (normalizada em [0,1]) da distribuição de 3-gramas de
// palavras nas últimas 6 falas do cidadão. Quando as falas ficam repetitivas (H baixa),
// a temperatura sobe:  T_t = clamp(T_base + γ · max(0, τ_crit − H_ngram), 0.45, 1.15).
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace girassol::llm {

class SemanticEntropyController {
public:
    static constexpr size_t kWindow = 6;
    static constexpr float kMinT = 0.45f;
    static constexpr float kMaxT = 1.15f;

    explicit SemanticEntropyController(float tBase = 0.70f, float gamma = 0.90f, float tauCrit = 0.80f)
        : tBase_(tBase), gamma_(gamma), tauCrit_(tauCrit) {}

    void clear() { recent_.clear(); }
    void push(std::string_view utterance) {
        recent_.emplace_back(utterance);
        while (recent_.size() > kWindow) recent_.pop_front();
    }
    size_t size() const { return recent_.size(); }

    // Palavras em minúsculas (ASCII) separadas por qualquer byte não alfanumérico;
    // bytes UTF-8 ≥ 0x80 são mantidos (acentos fazem parte da palavra).
    static std::vector<std::string> words(std::string_view s) {
        std::vector<std::string> out;
        std::string cur;
        for (unsigned char c : s) {
            const bool keep = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0x80;
            if (keep) {
                cur.push_back(static_cast<char>((c >= 'A' && c <= 'Z') ? c + 32 : c));
            } else if (!cur.empty()) {
                out.push_back(std::move(cur));
                cur.clear();
            }
        }
        if (!cur.empty()) out.push_back(std::move(cur));
        return out;
    }

    // Entropia normalizada H/log2(N) dos 3-gramas (N = total de ocorrências). Sem 3-gramas
    // suficientes devolve 1 (nada a corrigir).
    float ngramEntropy() const {
        std::unordered_map<std::string, uint32_t> counts;
        uint32_t total = 0;
        for (const auto& u : recent_) {
            const auto w = words(u);
            for (size_t i = 0; i + 2 < w.size(); ++i) {
                counts[w[i] + '\x1f' + w[i + 1] + '\x1f' + w[i + 2]]++;
                ++total;
            }
        }
        if (total < 2) return 1.0f;
        double h = 0.0;
        for (const auto& [k, c] : counts) {
            (void)k;
            const double p = static_cast<double>(c) / total;
            h -= p * std::log2(p);
        }
        return static_cast<float>(std::clamp(h / std::log2(static_cast<double>(total)), 0.0, 1.0));
    }

    float temperature() const {
        const float h = ngramEntropy();
        return std::clamp(tBase_ + gamma_ * std::max(0.0f, tauCrit_ - h), kMinT, kMaxT);
    }

    // Separa falas por '\n' (formato enviado pelo Kotlin) e calcula T.
    static float temperatureFor(std::string_view joined, float tBase = 0.70f) {
        SemanticEntropyController c(tBase);
        size_t start = 0;
        while (start <= joined.size()) {
            const size_t nl = joined.find('\n', start);
            const auto part = joined.substr(start, nl == std::string_view::npos ? std::string_view::npos : nl - start);
            if (!part.empty()) c.push(part);
            if (nl == std::string_view::npos) break;
            start = nl + 1;
        }
        return c.temperature();
    }

private:
    float tBase_, gamma_, tauCrit_;
    std::deque<std::string> recent_;
};

}  // namespace girassol::llm
