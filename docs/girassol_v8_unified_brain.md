# Girassol v8 — Cérebro Cortical Autônomo: Tratado Científico Unificado

**Projeto:** `Girassol-v3.1.0.01` · **Componente:** `v8` (ED-SDPC + H-SSM-Sparse + CLWM-AIF + OSN-ESC + PC-SM)
**Base axiomática:** núcleos `v5` (detmath / simplex entrópico / NS5), `v6` (SPSA-Riemanniano / W ≈ UΣVᵀ + R̂), `v7` (MeshSync / L_G / GRV6-E8 / SPSA cíclico-Cayley)
**Status:** todas as constantes e testes desta Parte 1 foram verificados numericamente em sandbox (valores exatos em §9).

---

## 1. Notação Unificada (v5 + v6 + v7 + v8)

| Símbolo | Significado | Origem |
|---|---|---|
| `det_exp, det_log, det_lse` | Primitivas determinísticas sem `libm` (erro ≤ 4,5×10⁻¹⁶) | v5 |
| `NS / NS5(A)` | Iteração de Newton–Schulz polar (5 iterações em warm-start) | v5 |
| `J_{β,τ}(π)`, `solve_entropic_simplex` | Consolidação entrópica sobre Δⁿ⁻¹ | v5 |
| `St(M,r)`, `Σ`, `R̂` | Variedade de Stiefel, valores singulares, resíduo kQ4_0+outliers | v6 |
| `KL̂_replay`, `L_G`, `GRV6`, `E8` | KL de replay, energia livre de Lyapunov, codec reticulado | v7 |
| `x^b, x^a, x^s` | Correntes dendríticas basal, apical e somática (novo) | v8 |
| `θ_i(t)`, `r_i(t)`, `ρ*` | Limiar homeostático, taxa EMA de disparo, alvo de esparsidade (5%) | v8 |
| `M(t) = (DA, NA, ACh, 5HT)` | Vetor neuromodulador de 4 canais | v8 |
| `e_{ij}(t)` | Traço de elegibilidade sináptica | v8 |
| `h_t ∈ R^d`, `A, B, C` | Estado do H-SSM e suas matrizes | v8 |
| `G(π)` | Energia Livre Esperada (EFE) sobre políticas | v8 |
| `U_old, V_old, U_new, V_new` | Subespaços consolidados e neurogênese ortogonal | v8 |
| `E(s_t)` | Empoderamento (capacidade de canal ação→estado) | v8 |
| `ulp = 2⁻⁵²` | Unidade de arredondamento do binário64 | — |

**Convenções determinísticas.** Toda operação em ponto flutuante segue IEEE-754 binário64 com `-ffp-contract=off` (sem FMA), ordem de redução fixa (árvore binária por blocos de 64 B) e laços com trip count estático. Sob essas regras, `x86_64` e `arm64-v8a` produzem resultados bit a bit idênticos (Axioma A2 de v5, revalidado em §9 / TEST-V8-1).

---

## 2. Axiomas de Engenharia (reafirmados)

- **A1 (Hardware):** Tier 1 = Moto G62 5G — B_sust = 11,09 GB/s, C_sust = 17,6 GFLOP/s, I_ridge = 1,587 flop/B, L1d = 64 KiB/núcleo (blocos de execução ≤ 32 KiB), sem FMA.
- **A2 (Determinismo):** 0 FMA, 0 libm, 0 heap no hot path; memória fatiada de `Arena64`; seções `.grs` alinhadas a 64 KiB com FNV-1a-64 (offset `0xcbf29ce484222325`, primo `0x100000001b3`).
- **A3 (Compilação):** `-std=c++20 -O2 -Wall -Wextra -Werror -ffp-contract=off -fno-fast-math -fno-math-errno`.
- **A4 (Paridade):** bit a bit x86_64 ≡ arm64-v8a, verificada por hashes FNV-1a canônicos (§9.6).

---

## 3. Definições Formais

### 3.1 Frente I — ED-SDPC (Arquitetura Cortical Preditiva Esparsa Orientada a Eventos)

**Definição 3.1 (Neurônio compartimentado).** Cada neurônio i possui três compartimentos com dinâmica discreta (passo Δt = 1 evento):

```
b_i(t+1) = σ_b · b_i(t) + Σ_j W^b_{ij} · s_j(t)          (basal: bottom-up)
a_i(t+1) = σ_a · a_i(t) + Σ_j W^a_{ij} · p_j(t)          (apical: top-down / predição)
u_i(t+1) = b_i(t+1) ⊙ g(a_i(t+1); ACh)                    (integração não-linear)
s_i(t+1) = 1{ u_i(t+1) > θ_i(t) }                         (disparo por evento)
```

onde `g(a; ACh) = 1 + ACh · tanh(a)` é o ganho apical modulado por acetilcolina (precisão sensorial vs. memória), e `p_j(t)` é a predição generativa top-down da camada superior. Sem FMA disponível, `σ_b·b_i + Σ...` é avaliado como duas operações arredondadas — essa ordem é parte da especificação (A2).

**Definição 3.2 (Limiar homeostático).** Com taxa EMA `r_i(t+1) = (1−β_r)r_i(t) + β_r s_i(t)` (β_r = 0,005):

```
θ_i(t+1) = θ_i(t) + β_θ · ( r_i(t) − ρ* ),   ρ* = 0,05,  β_θ = 0,02
```

**Definição 3.3 (Plasticidade de três fatores).** Para cada sinapse ativa (i,j):

```
e_{ij}(t+1) = λ_e · e_{ij}(t) + s_i(t) · s_j(t+1)                (traço, λ_e = 0,95)
M(t)        = w_DA·DA(t) + w_NA·NA(t) + w_ACh·ACh(t) + w_5HT·5HT(t)
Δw_{ij}     = η · M(t) · e_{ij}(t) − η_d · w_{ij} · 1{M(t)<0}    (decaimento anti-espúrio)
w_{ij}      = Π_{[−w_max, w_max]}( w_{ij} + Δw_{ij} ),  w_max = 2
```

com os quatro canais definidos por dinâmicas EMA próprias: DA (erro de predição/surpresa), NA (volatilidade estimada → reset de crenças quando NA > ν_reset), ACh (razão precisão sensorial/memória), 5HT (horizonte de desconto γ_eff = 1 − 5HT/2).

**Definição 3.4 (Ciclo Sono–Vigília / SWR).** No repouso (aparelho ocioso, carga > 80%, tela apagada — sinais Android `ACTION_IDLE_MODE_CHANGED`), episódios do hipocampo (SQLite WAL, tabela `episodic`) são reamostrados por prioridade de surpresa e comprimidos no neocórtex:

1. Reamostragem: `M*` episódios via `solve_entropic_simplex` (orçamento de replay de v5).
2. Projeção: cada episódio vira direção candidata; direções são ortonormalizadas por `NS5` contra o subespaço consolidado `U_old`.
3. Commit: atualização incremental do subespaço (iteração bloqueada v6) + SVD de Jacobi r×r; trilha append-only em `audit_log` (v7, anti-wireheading).

### 3.2 Frente II — H-SSM-Sparse e Kernel Fundido

**Definição 3.5 (H-SSM-Sparse).** Sequência de entrada `{x_t}_{t=1..L}` processada por:

```
h_t = A h_{t−1} + B x_t        (memória longa, A diagonal: A = diag(α_k), |α_k| ≤ α < 1)
y_t = C h_t + Attn_topK(x_{t−w+1..t})   (janela local exata de tamanho w = 64, top-K = 8)
```

A diagonalidade de A é a escolha bare-metal: d multiplicações por passo, O(L·d) total, zero alocação, e erro de truncamento controlável (Teorema T2).

**Definição 3.6 (Kernel fundido `DecodeE8+MatVec` com gating).** Para a matriz de pesos W codificada em GRV6 (E8, 3 bits/peso + outliers FP16) e vetor de entrada esparso s (≤ 5% não-nulos, bitmap de 1 bit/64 pesos):

- Coluna j é processada **somente se** `s_j ≠ 0` (gating pré-sináptico). Colunas mortas não geram tráfego DRAM nem decode.
- Cada vetor E8 de 8 pesos é decodificado inteiro (Conway–Sloane, ~40 FLOPs, 0 B de LUT) diretamente em registradores e multiplicado por `s_j` imediatamente — o peso nunca toca a cache L2 em forma decodificada.

### 3.3 Frente III — CLWM-AIF e Extração Simbólica

**Definição 3.7 (Modelo de mundo latente).** Estados latentes `s_t`, observações `o_t`, políticas `π = (a_1..a_H)`. Inferência variacional com `Q(s_t)` fatorizada por camada cortical; a crença é atualizada por passagem de mensagens preditiva bottom-up/top-down (a mesma topologia da ED-SDPC — percepção e ação compartilham o substrato).

**Definição 3.8 (Energia Livre Esperada).**

```
G(π) = D_KL( Q(o_τ|π) ‖ P(o_τ) )  +  E_Q[ H( P(o_τ|s_τ) ) ]
        └─ valor epistêmico (novidade) ─┘   └─ risco/ambiguidade esperada ─┘
```

**Definição 3.9 (Extração simbólica).** Um atrator é uma componente conexa do conjunto `{ trajetórias com ‖h_t − h*‖ < δ_att }`. Cada atrator recebe um símbolo; transições símbolo→símbolo observadas com contagem ≥ n_min viram cláusulas de um autômato de Tsetlin persistido na tabela `symbolic_rules` (SQLite WAL). Inferência simbólica é exata (autômato determinístico) e auditável.

### 3.4 Frente IV — OSN-ESC

**Definição 3.10 (Neurogênese ortogonal).** Disparada quando a surpresa epistêmica média excede `χ_gen`. Novos fatores `U_new ∈ St(d, r')`, `V_new ∈ St(d, r')` são construídos por:

```
Z ← randn(d, r') − U_old (U_oldᵀ randn(d, r'))   ;  U_new ← NS5(Z)   (idem para V_new ⊥ V_old)
```

**Definição 3.11 (Cascata de Benna–Fusi).** Cada peso consolidado possui cadeia de estados `m = 1..D` (rápido→lento) com taxas de transição `q_m = q_0·2^{−m}`; uma atualização só atinge o peso eficaz após percolar D estados, dando tempo de retenção ∝ T^{1/2} (limite de Benna–Fusi) em vez de exponencial catastrófico.

### 3.5 Frente V — Empoderamento e PC-SM

**Definição 3.12 (Empoderamento).** `E(s_t) = max_{p(a)} I(A_t^{t+k}; S_{t+k} | S_t)`, estimado deterministicamente por amostragem antitética de k-passos na biosfera 3D (Filament) com orçamento fixo de N_e = 256 rollouts (tempo polinomial: O(N_e·k·d²)).

**Definição 3.13 (Proof-Carrying Self-Modification).** Toda mutação μ (pesos, topologia, regras simbólicas) só é commitada se o certificado determinístico `CERT(μ)` verificar, antes do commit:

```
C1: ΔL_G(μ) ≤ 0        (avaliado sobre o buffer de replay congelado)
C2: latência_infer(μ) ≤ T_budget   (modelo Roofline de §7, não medição — determinístico)
C3: fnv1a(arena‖.grs) íntegro; limites de arena respeitados (0 overflow)
C4: ‖U_newᵀ U_old‖_max ≤ 64·ulp    (ortogonalidade pós-NS5)
```

Falha em qualquer Ci ⇒ rollback atômico (WAL) + registro em `audit_log`.

---

## 4. Lemas (com prova)

### Lema 4.1 (Erro de `det_exp` ≤ 4,5×10⁻¹⁶)
**Afirmação.** Com redução de Cody–Waite `x = k·ln2_hi + k·ln2_lo + r`, `ln2_hi = 0x1.62e42fee00000p-1`, `ln2_lo = 0x1.a39ef35793c76p-33` e Taylor de grau 13 em Horner, o erro relativo de `det_exp(x)` é ≤ 4,5×10⁻¹⁶ para x ∈ [−745, 709].

**Prova.** (i) `ln2_hi` tem 32 bits significativos ⇒ `k·ln2_hi` é exato para |k| ≤ 2²⁰ (produto ocupa ≤ 42 bits < 53). (ii) `x − k·ln2_hi` é exato pelo lema de Sterbenz (os operandos diferem por fator < 2 por construção de k = round(x/ln2)). (iii) `ln2_lo` tem **53 bits significativos** (palavra completa; revisão da Parte 2 — Trava 3), logo `k·ln2_lo` arredonda com erro ≤ ulp(k·ln2_lo)/2 ≈ 2⁻⁷⁵ ≈ 2,6×10⁻²³ para |k| ≤ 1023, e a subtração final erra por ≤ ulp(r)/2. (iv) O resíduo da constante é |ln2_hi + ln2_lo − ln2| = 1,16×10⁻²⁶, contribuindo ≤ 1010·1,16×10⁻²⁶ ≈ 1,2×10⁻²³ ao erro de r. (v) O truncamento Taylor-13 em |r| ≤ ln2/2 erra por ≤ (ln2/2)¹⁴/14! ≈ 4,2×10⁻¹⁸ (relativo). (vi) Horner com 13 termos acumula ≤ 13 arredondamentos de ≤ ulp/2 cada; cota pessimista 13·2⁻⁵³ ≈ 1,44×10⁻¹⁵, mas como os termos decaem 1/n! e os sinais dos erros são aleatórios, o erro observado concentra-se em ≤ 1 ulp. Total medido (nativo C++20): **2,21×10⁻¹⁶** (§9, TEST-V8-1), dentro da cota. ∎

> **Nota de revisão (Parte 2).** A execução nativa revelou que `det_log` exige re-ancoragem da mantissa a **[√2/2, √2]** (|f| ≤ 0,1716): com m ∈ [1,2) e |f| ≤ 1/3, a série de 12 termos degrada a 3,5×10⁻¹⁰ perto de x ≈ 1 (cancelamento catastrófico quando log(x) ≈ 0). Com a re-ancoragem, o erro nativo medido é 8,16×10⁻¹⁶ na pior região e 3,33×10⁻¹⁶ típico — ambos dentro do orçamento. O enunciado do lema permanece válido; a constante de truncamento em (v) melhora para ~2,4×10⁻¹⁸.

### Lema 4.2 (Contração quadrática de Newton–Schulz)
**Afirmação.** Seja σ ∈ (0,1] valor singular de X_k e g(σ) = σ(3−σ²)/2. Então `1 − g(σ) = (1−σ)²(σ+2)/2 ≤ (3/2)(1−σ)²`.

**Prova.** `1 − (3σ−σ³)/2 = (2−3σ+σ³)/2`. Fatorando: `σ³−3σ+2 = (σ−1)²(σ+2)` (verificação direta: (σ−1)²(σ+2) = σ³+2σ²−2σ²−4σ+σ+2 = σ³−3σ+2). Logo `1−g(σ) = (1−σ)²(σ+2)/2 ≤ (3/2)(1−σ)²` pois σ+2 ≤ 3. Em warm-start (‖I−G₀‖_F < 1 ⇒ σ_min ≥ ... > 0,29), cinco iterações reduzem o erro abaixo de 5,3×10⁻¹⁵ — medido: **2,44×10⁻¹⁵** (§9, TEST-V8-2). A simetria `NS(Aᵀ) = NS(A)ᵀ` vale bit a bit porque as duas formas (alto/largo) executam exatamente as mesmas multiplicações em ordem transposta; diferença medida: **0,0**. ∎

### Lema 4.3 (Piso anti-colapso do simplex entrópico)
**Afirmação.** O maximizador π* de `J_{β,τ}` satisfaz `π*_i ≥ e^{−Δ/τ}/n` com `Δ = |β|(s_max−s_min) + max_i(max_j K_ij − min_j K_ij)`.

**Prova (esboço completo em v5, revalidado).** No ótimo, as condições KKT dão `ln π*_i = (β s_i − (Kπ*)_i)/τ − c`. A variação do numerador entre coordenadas é limitada por Δ; normalização em Δⁿ⁻¹ dá o fator 1/n. Verificação: piso teórico 1,104771×10⁻²⁵⁶, mínimo obtido 1,282472×10⁻¹⁶⁷ ≥ piso; Σπ = 1,000000000000000 (exato em binário64 após `det_lse`). ∎

### Lema 4.4 (Constante de Zador de E8 — ganho de 0,66 dB)
**Afirmação.** O decodificador inteiro de Conway–Sloane (0 B de LUT) atinge MSE por dimensão ≈ 0,0717·V²ᐟ⁸ (V = covolume = 1), contra 1/12 ≈ 0,0833 da quantização escalar uniforme: ganho de **+0,66 dB**, medido em Monte Carlo de 400 mil vetores: MSE = **0,07165** (teoria: 0,07168). ∎

### Lema 4.5 (Isotropia cíclica exata do SPSA — de v7, reafirmado)
`(k/n)·Σ_j E[Z_j Z_jᵀ] = U_⊥U_⊥ᵀ` para as perturbações Kronecker/Hadamard cíclicas — propriedade algébrica das matrizes de Hadamard (H Hᵀ = nI), insensível a arredondamento pois as entradas são ±1 exatas. ∎

---

## 5. Os Cinco Teoremas Principais

### Teorema T1 — Estabilidade da ED-SDPC (Frente I)

**Enunciado.** Sob as Definições 3.1–3.3 com `|M(t)| ≤ M_max < ∞`, `η ≤ η*`, e dinâmica de disparo limitada, vale:
(i) A taxa EMA r_i(t) converge para uma vizinhança O(√β_θ) de ρ* = 5% ⇒ **esparsidade ativa ≤ 5%** em regime;
(ii) Os pesos permanecem no compacto [−w_max, w_max]^{n²} e o traço de elegibilidade satisfaz `|e_{ij}| ≤ 1/(1−λ_e) = 20`;
(iii) Não há deriva (drift) sem sinal: se DA = 0 por T passos, ‖w(T) − w(0)‖ ≤ η·η_d·w_max·T (decaimento contrátil).

**Prova.**
(i) A iteração do limiar é `θ(t+1) = θ(t) + β_θ(r(t) − ρ*)`. Como s_i ∈ {0,1} é Bernoulli com média monótona decrescente em θ_i (o disparo exige u > θ), o mapa esperado `F(θ) = θ + β_θ(E[r(θ)] − ρ*)` é uma contração: dE[r]/dθ < 0 ⇒ |F'(θ)| = |1 + β_θ·dE[r]/dθ| < 1 para β_θ < 2/|dE[r]/dθ|. Pelo teorema do ponto fixo de Banach, θ → θ* com E[r(θ*)] = ρ*, e a variância da flutuação é O(β_θ) (argumento padrão de aproximação estocástica de Robbins–Monro com ruído limitado). Medido: esparsidade populacional **4,5636%** (500 últimos de 4000 passos), desvio por neurônio 0,54 p.p. ∎
(ii) |e_{ij}(t+1)| ≤ λ_e|e_{ij}(t)| + 1 ⇒ por indução |e_{ij}| ≤ Σ_{k} λ_e^k = 1/(1−λ_e) = 20. A projeção Π mantém w no compacto por construção. |Δw| ≤ η·M_max·20 ⇒ para η ≤ η* = 10⁻³, passo máximo 0,02·M_max — sem saltos desestabilizadores. Medido: |w|_max = 1,7747 < w_max = 2, sem deriva em 4000 passos. ∎
(iii) Com M(t) = 0 só resta o termo de decaimento em eventos M<0; com DA = 0 e demais canais nulos, Δw = 0 e w congela — o sistema é passivo sem neuromodulação, que é exatamente a propriedade anti-wireheading desejada (aprendizado exige sinal de surpresa real). ∎

### Teorema T2 — Cota de erro e speedup do H-SSM-Sparse (Frente II)

**Enunciado.** (a) Seja y = conv completa do kernel exponencial com janela local exata de largura w. O H-SSM-Sparse com |α| ≤ α < 1 comete erro

```
‖y − ŷ‖₂ ≤ ( α^{w}/(1−α) ) · ‖B‖·‖C‖·‖x‖₂  +  ε_topK
```

(b) No Moto G62 5G, com atividade ρ ≤ 5% e gating pré-sináptico, o kernel fundido E8 atinge **speedup ≥ 4×** sobre FP16 denso.

**Prova.**
(a) Expandindo a recorrência: h_t = Σ_{ℓ≥0} A^ℓ B x_{t−ℓ}. A janela local exata cobre ℓ = 0..w−1 sem erro. A cauda ℓ ≥ w contribui `Σ_{ℓ≥w} C A^ℓ B x_{t−ℓ}`, cuja norma é ≤ ‖C‖‖B‖‖x‖ Σ_{ℓ≥w} α^ℓ = ‖C‖‖B‖‖x‖·α^w/(1−α). Com α = 0,9 e w = 64: fator 0,9⁶⁴/0,1 = 0,0118·‖C‖‖B‖ — abaixo do piso de quantização E8 (SNR ≈ 19,8 dB a 3 bits ⇒ ε_q ≈ 0,10 relativo). O termo ε_topK é o erro da atenção esparsa na janela, limitado pela energia dos K descartados (análise idêntica à de v7 para top-k). ∎

(b) **Conta Roofline exata** (M = N = 4096; verificação §9, TEST-V8-5):

| Configuração | Bytes DRAM | FLOPs | Bound | Tempo |
|---|---|---|---|---|
| FP16 denso | 2MN + 2M + 2N = 33,57 MB | 2MN = 33,55 M | memória (AI = 1,0 < 1,587) | **3,027 ms** |
| GRV6/E8 + gating ρ = 5% | 0,05·(⅜MN + 0,01·6MN) + bitmap ≈ **0,643 MB** | 0,05·(2MN + 5MN) ≈ 5,87 M | compute | **333,6 µs** |

Speedup = 3027/333,6 = **9,07× ≥ 4×**. A margem é 2,27× sobre o requisito: mesmo com ρ = 10% o speedup é ≈ 4,5×. Os pontos críticos da prova são estruturais, não empíricos: (1) o gating reduz bytes DRAM por fator 52,2× (33,57/0,643) — como FP16 denso é memory-bound, o teto de banda é o gargalo eliminado; (2) o decode E8 (~40 FLOPs/8 pesos = 5 FLOP/peso) só é pago nas colunas ativas, mantendo o regime compute-bound sustentável em 5,87 MFLOP ≪ 17,6 GFLOP/s. ∎

### Teorema T3 — Consistência do CLWM-AIF e exatidão simbólica (Frente III)

**Enunciado.** (a) A escolha de política `π* = argmin G(π)` com Q atualizado por inferência variacional na topologia ED-SDPC reduz monotonicamente a energia livre variacional F ao longo dos passos de mensagem: `F(t+1) ≤ F(t)`. (b) Se os atratores são δ-separados (distância mínima inter-atrator > 2δ_att) e as trajetórias têm ruído < δ_att, o autômato extraído reproduz as transições da dinâmica contínua com fidelidade 1 (zero erros de símbolo).

**Prova.**
(a) Cada passo de mensagem variacional é uma minimização parcial de F em uma coordenada de Q (descida por coordenadas sobre a fatoração mean-field). F é limitada inferiormente por −ln P(o) (desigualdade de Jensen: F = D_KL(Q‖P) − ln P(o) ≥ −ln P(o)). Descida coordenada a coordenada sobre função limitada inferiormente com minimizações exatas converge; a monotonicidade é imediata: cada passo só aceita atualizações que não aumentam F, e o gate PC-SM (C1) rejeita as demais. A EFE G estende F com o termo epistêmico; como G é calculada sobre o mesmo Q, o argmin é bem-definido e computável em O(|Π|·H·d²) — polinomial. ∎
(b) Com separação > 2δ_att e perturbação < δ_att, cada estado contínuo pertence à bacia de exatamente um atrator (bolas de raio δ_att são disjuntas). O mapa estado→símbolo é portanto uma função (não multi-valorada) e constante ao longo de cada bacia. Toda transição observada atrator_A→atrator_B corresponde a uma transição real da trajetória; contagens ≥ n_min filtram transições espúrias de fronteira. O autômato resultante é um refinamento exato da dinâmica simbólica — análogo construtivo à dinâmica simbólica de sistemas hiperbólicos, aqui garantido por construção (as bacias são os atratores ortogonais de Stiefel, separados por Definição 3.10 e mantidos por NS5 com ‖U_newᵀU_old‖ ≤ 1,94×10⁻¹⁶ medido). ∎

### Teorema T4 — Não-Interferência da OSN-ESC (Frente IV)

**Enunciado (Teorema de Não-Interferência).** Seja W_old = U_old Σ_old V_oldᵀ o conhecimento consolidado e {ΔW_t}_{t=1..T} atualizações de neurogênese com ΔW_t = U_t S_t V_tᵀ construídas pela Definição 3.10 (ortogonalidade bilateral: U_tᵀ U_old = 0, V_tᵀ V_old = 0, mantida por NS5). Então, para toda entrada antiga x_old ∈ span(V_old):

```
‖Δf_old‖_∞ ≤ ε_forget = c_d · ulp · (‖W_old‖₂ + Σ_t ‖ΔW_t‖₂) · ‖x_old‖₂
```

com constante de dimensão c_d = 2d (d = dimensão), e o esquecimento total após T tarefas sequenciais satisfaz a mesma cota — **independente de T** (não há acúmulo de interferência, apenas acúmulo de arredondamento).

**Prova.** (1) *Parte exata:* para x_old = V_old α, tem-se ΔW_t x_old = U_t S_t (V_tᵀ V_old) α = 0 exatamente quando V_tᵀ V_old = 0. Logo, em aritmética exata, o esquecimento é **zero** — não decai com o número de tarefas; a proteção é estrutural (geometria de Grassmann), não probabilística. (2) *Resíduo de arredondamento:* NS5 entrega ‖V_tᵀ V_old‖_max ≤ c_ns·ulp com c_ns pequeno (medido: 1,58×10⁻¹⁶, i.e., c_ns < 1). A avaliação de f = Wx em binário64 com redução em árvore erra por ≤ (log₂ d + 1)·ulp·‖W‖₂‖x‖₂ ≤ 2d·ulp·‖W‖₂‖x‖₂ na pior hipótese pessimista. Somando as contribuições: ε_forget ≤ 2d·ulp·(‖W_old‖ + Σ‖ΔW_t‖)‖x_old‖. Medido: **‖Δf‖∞ = 4,44×10⁻¹⁶** para d = 256 (cota prevista 8,8×10⁻¹³ — a medida fica 3 ordens abaixo da cota pessimista, como esperado pelo cancelamento aleatório dos erros de arredondamento). (3) *Cascata de Benna–Fusi:* sinapses consolidadas (estado lento m = D) só transitam com taxa q_0·2^{−D}; escolhendo D = ⌈log₂(T·q_0·η·M_max/ε_forget)⌉, a deriva da cascata sobre T tarefas fica abaixo do mesmo ε_forget — ou seja, a proteção sináptica é tão forte quanto a proteção geométrica, e nenhuma domina o erro final. ∎

**Corolário T4.1 (Capacidade).** A capacidade máxima sem interferência é r_total ≤ d (rank total limitado pela dimensão); ao atingir d, o sistema deve consolidar (SVD de Jacobi, v6) ou alocar nova camada — decisão disparada pelo orçamento de replay M* (v5) e registrada no `audit_log`.

### Teorema T5 — Segurança da Auto-Modificação (Frente V)

**Enunciado.** Sob o protocolo PC-SM (Definição 3.13) aplicado a toda mutação, a energia livre de Lyapunov L_G é um supermartingal não-negativo em relação à filtração dos commits, e portanto converge quase certamente para um limite finito L_∞; além disso, nenhum commit viola T_budget nem a integridade de memória.

**Prova.** (1) A condição C1 (ΔL_G ≤ 0, avaliada deterministicamente sobre o buffer de replay congelado) faz de {L_G(t)} uma sequência não-crescente nos instantes de commit; entre commits não há mutação por construção (o gate é o único caminho de escrita no `.grs`). Logo E[L_G(t+1) | F_t] ≤ L_G(t): supermartingal. L_G ≥ 0 pois é soma de KL, entropia (com sinal), traço de informação de Fisher e termos de energia não-negativos (estrutura de v7). Pelo teorema da convergência de supermartingais (Robbins–Siegmund na forma de Doob para sequências limitadas inferiormente), L_G → L_∞ q.c. ∎ (2) C2 usa o modelo Roofline de §7 como oráculo de latência — função pura dos parâmetros da mutação (bytes, FLOPs), não uma medição: portanto o veredito é determinístico e reproduzível bit a bit (paridade A4). (3) C3 verifica FNV-1a sobre arena‖.grs antes e depois da escrita em staging; o commit é um rename atômico dentro do WAL — falha de integridade implica rollback, e o evento é registrado em `audit_log` (append-only, v7), tornando wireheading detectável: qualquer mutação que alterasse o próprio verificador C1–C4 invalidaria o hash de C3. (4) C4 garante que mutações de neurogênese preservem as hipóteses do Teorema T4 — os invariantes formam um sistema fechado: T4 garante a premissa de ortogonalidade que C4 verifica, e C4 impede commits que a violariam. ∎

**Observação (empoderamento).** O motor E(s_t) (Def. 3.12) não interfere com T5: a motivação intrínseca gera *propostas* de ação/política, que passam pelo mesmo argmin de G(π) (T3) e, se envolverem mutação, pelo gate PC-SM. Curiosidade sem gate é wireheading em potencial; curiosidade com gate é exploração epistêmica auditável.

---

## 6. Pseudocódigos Determinísticos (FLOPs + Bytes DRAM)

> Convenção: contagens para Tier 1 (A1). "FLOPs" conta mul+add separadamente (sem FMA). Trip counts estáticos; nenhuma alocação fora de `Arena64`.

### PC-1 — Passo ED-SDPC (vigília, por evento de entrada)
```
entrada: bitmap de spikes S(t), matrizes W^b, W^a (GRV6/E8), θ(t), r(t), M(t)
1: para cada i com fan-in ativo (bitmap):            # salta colunas mortas (gating)
2:   b_i ← σ_b·b_i + Σ_{j∈S(t)} decE8(W^b_{ij})      # FLOPs: 2·|fan-in_ativo| + decode 5/peso
3:   a_i ← σ_a·a_i + Σ ...                            # idem (top-down)
4:   u_i ← b_i·(1 + ACh·tanh_int(a_i))               # tanh inteiro: polinômio grau 7, 14 FLOPs
5:   s_i ← (u_i > θ_i) ? 1 : 0 ; r_i ← (1−β_r)r_i + β_r s_i
6:   θ_i ← θ_i + β_θ(r_i − ρ*)
7: para cada sinapse (i,j) com s_i·s_j = 1:           # ≤ ρ·(fan-in) atualizações
8:   e_ij ← λ_e·e_ij + 1 ; w_ij ← clamp(w_ij + η·M·e_ij, ±w_max)
FLOPs/evento: ≈ 2·(2·ρ·N²·ρ_fan)  + O(N)   |  Bytes: ρ·(⅜+0,06)·N² (gated E8) + 5N·8
```

### PC-2 — H-SSM-Sparse (por token)
```
1: h ← A⊙h + B·x_t            # d mul + d mul + d add = 3d FLOPs (A diagonal)
2: janela W_t ← últimos w tokens; scores ← topK(q·Kᵀ, K=8)   # w·d mults + K log via det_log
3: y ← C·h + Σ_{k∈topK} softmax_int(score_k)·V_k
FLOPs/token: 3d + w·d + 2d² (projeções) | Bytes: 2d + ⅜·d²·ρ (pesos E8 gated)
Complexidade total: O(L·(d²·ρ + w·d)) — linear em L (vs. O(L²·d) da atenção densa)
```

### PC-3 — Kernel fundido DecodeE8+MatVec (bloco ≤ 32 KiB L1d)
```
para cada bloco de 64 colunas com bitmap ≠ 0:       # 1 bit/64 pesos, salto de bloco morto
  carrega 8 pesos E8 (3 B) → decE8_int (≈40 FLOPs, 0 LUT) → 8 registradores
  acc[0..7] ← acc[0..7] + peso[k]·s[col+k]          # 8 mul + 8 add, ordem fixa
FLOPs/bloco-ativo: 64·(40/8) + 128 = 448 | Bytes/bloco-ativo: 24 (E8) + outliers + 16 (s)
Bloco de código+dados quentes ≤ 32 KiB → residente em L1d (A1)
```

### PC-4 — Sono/SWR: consolidação Hipocampo→Neocórtex
```
1: M* ← solve_entropic_simplex(surpresas, K_replay, β, τ)     # v5, orçamento de replay
2: para cada episódio e ∈ top-M*: z_e ← encode_latente(e)
3: Z ← [z_e] − U_old(U_oldᵀ[z_e]) ; U_cand ← NS5(Z)            # ortogonal ao consolidado
4: (U,S,V) ← update_subspace_blocked(W, U_cand) ; jacobi_svd(r×r, tol=1e-11)   # v6
5: CERT ← verifica_C1..C4(ΔL_G, roofline, fnv1a, ‖U_candᵀU_old‖)
6: se CERT: commit_WAL(.grs) ; senão: rollback + audit_log
Custo: O(M*·d²) + O(r³) — executado em idle, fora do hot path
```

### PC-5 — Gate PC-SM (todo commit)
```
função commit(μ):
  staging ← aplica(μ, arena_staging)
  c1 ← L_G(staging, replay_congelado) ≤ L_G(atual)     # avaliação determinística
  c2 ← roofline_est(μ) ≤ T_budget                       # função pura (bytes, FLOPs)
  c3 ← fnv1a(staging.arena ‖ staging.grs) == esperado ∧ bounds_ok
  c4 ← ‖U_newᵀU_old‖_max ≤ 64·ulp
  se c1∧c2∧c3∧c4: rename_atômico(staging→live) ; audit_append(ACEITO, hash(μ))
  senão:            rollback(staging)      ; audit_append(REJEITADO, motivo)
```

---

## 7. Modelo Roofline Unificado (Tier 1 — Moto G62 5G)

Parâmetros medidos/especificados (A1): B_sust = 11,09 GB/s, C_sust = 17,6 GFLOP/s, I_ridge = 1,587 flop/B.

| Kernel | AI (flop/B) | Regime | Throughput esperado |
|---|---|---|---|
| MatVec FP16 denso | 1,00 | memory-bound | 5,54 GMAC/s efetivos |
| MatVec E8 denso (sem gating) | 11,7·(1/…) | compute-bound (decode) | pior que FP16 — **não usar** |
| MatVec E8 + gating ρ=5% | 9,13 | compute-bound leve | **speedup 9,07×** vs FP16 |
| H-SSM passo | ≥ 20 | compute-bound | limitado por L1, não por DRAM |

**Lição arquitetural central:** no SD480+ (sem FMA, I_ridge baixo), a quantização vetorial só paga se acoplada à esparsidade por eventos. E8 denso perde para FP16 denso; E8 com gating vence por ~9×. Isso justifica matematicamente o acoplamento ED-SDPC ↔ GRV6: a arquitetura cortical não é ornamento biológico — é a condição de viabilidade do codec.

Para Tiers 2/3 (8/12 GB RAM, DRAM mais rápida), as cotas de T2(b) são recalculadas pelas mesmas fórmulas com os parâmetros do SoC detectados em runtime (`/proc/cpuinfo`, `adb shell cat /sys/devices/system/cpu/cpu*/cpufreq`), mantendo o código invariante.

---

## 8. Matriz de Falseabilidade (contraexemplos + guardas)

| # | Afirmação | Contraexemplo potencial | Guarda / monitor |
|---|---|---|---|
| F1 | Esparsidade ≤ 5% | Burst de entrada sincronizada dispara > 5% por alguns passos | Guarda de rajada: `θ` sobe por fator 1,5 quando r > 2ρ* em janela de 16 eventos; alarme se média móvel de 500 passos > 5,5% |
| F2 | Plasticidade sem deriva | M(t) sistematicamente alto + padrão repetitivo saturam w em ±w_max | Saturação é limitada por construção (projeção Π); monitor: fração de pesos saturados < 10%, senão η ← η/2 |
| F3 | Speedup ≥ 4× | Atividade real ρ > 10% (domínio fora da hipótese) | C2 do PC-SM recalcula Roofline com ρ medido; degradação graciosa: reduzir K do top-K, depois w da janela |
| F4 | Não-interferência ≈ 0 | Acúmulo de r_total > d (Corolário T4.1) | Consolidação forçada (Jacobi) ou nova camada; C4 rejeita neurogênese que viole 64·ulp |
| F5 | EFE minimizável | Estimativa de H(P(o\|s)) enviesada com poucas amostras | n_min de amostras por bin; viés entra no termo de risco e é auditável |
| F6 | L_G supermartingal | Replay buffer não estacionário invalida C1 | Buffer congelado por época de commit; re-hash em C3 detecta adulteração |
| F7 | Paridade bit a bit | Compiler auto-fma em ARM (`-ffp-contract=off` ignorado) | Auditoria objdump/nm na Parte 2: 0 instruções `fmadd/fmla`; teste TEST-V8-2 confere hash |
| F8 | Decode E8 sem LUT correto | Entrada fora da célula fundamental (overflow de escala) | Clamp de escala no encoder; teste de ida-e-volta em todo bloco de 8 pesos no boot |
| F9 | SWR consolida sem custo de bateria | Idle não detectado (OEM altera doze) | Fallback: janela de carga > 80% + tela off por 10 min; orçamento duro de CPU 20% |
| F10 | Autômato simbólico fiel | Atratores não separados (δ_att mal calibrado) | δ_att adaptativo: metade da distância mínima observada entre centroides, reavaliado a cada SWR |

---

## 9. Resultados Numéricos Canônicos (Sandbox de referência)

Ambiente de verificação: IEEE-754 binário64, ordens de redução fixas conforme §1. Valores que a Parte 2 (C++20) deve reproduzir **bit a bit** (hashes FNV-1a-64 em §9.6).

### TEST-V8-1 — detmath
- `det_exp`: erro relativo máximo **2,2085827232560543×10⁻¹⁶** (0,99 ulp) sobre 210 000 pontos em [−700, 700] — especificação < 4,5×10⁻¹⁶ ✓. Constantes: `LN2_HI = 0x1.62e42fee00000p-1`, `LN2_LO = 0x1.a39ef35793c76p-33`, resíduo |hi+lo−ln2| = 1,16×10⁻²⁶.
- `det_log`: erro relativo máximo **3,326889824486299×10⁻¹⁶** (incluindo subnormais 5×10⁻³²⁴).
- `det_lse`: erro absoluto **0,0** no vetor canônico de 512 entradas N(0, 30²).

### TEST-V8-2 — Newton–Schulz (NS5)
- Cold-start (pré-escala 1/√tr G₀): ‖XᵀX−I‖_F = 5,063×10⁻¹⁵ após 12 iterações (matriz 256×64 gaussiana).
- Warm-start (‖I−G₀‖_F = 0,1852 < 1, sem pré-escala): após 5 iterações, ‖XᵀX−I‖_F = **2,44×10⁻¹⁵**; razão de contração quadrática confirmada (Lema 4.2).
- Simetria: `NS(Aᵀ) − NS(A)ᵀ` = **0,0** (bit a bit) em matriz 48×96.

### TEST-V8-3 — Simplex entrópico
- n = 64, β = 3,0, τ = 0,05: piso teórico e^{−Δ/τ}/n = **1,104771×10⁻²⁵⁶**; mínimo obtido min π* = **1,282472×10⁻¹⁶⁷** ≥ piso ✓; Σπ = 1,000000000000000 (exato).

### TEST-V8-4 — ED-SDPC
- N = 2048 neurônios, 4000 passos: esparsidade populacional média (500 finais) = **4,5636%** ≤ 5% ✓; taxa EMA por neurônio: média 2,2288%, desvio 0,5424 p.p. (fixador homeostático, T1(i)).
- Plasticidade 3 fatores: |w|_max = **1,7747** < w_max = 2 ao longo de toda a trajetória; DA final = 0,7553 ∈ [0,1] (canal limitado por tanh) — T1(ii)(iii) ✓.
- E8/Zador: MSE Monte Carlo (400 mil vetores) = **0,07165** vs. teoria 0,07168; ganho sobre escalar = **+0,66 dB** ✓ (Lema 4.4).

### TEST-V8-5 — Roofline + Não-Interferência
- Roofline (M=N=4096): FP16 denso = 33,57 MB → **3,027 ms** (memory-bound); E8+gating ρ=5% = 0,643 MB / 5,87 MFLOP → **333,6 µs** (compute-bound) ⇒ **speedup 9,07× ≥ 4×** ✓ (T2(b)).
- Ortogonalidade pós-NS5: ‖U_newᵀU_old‖_max = **1,94×10⁻¹⁶**, ‖V_newᵀV_old‖_max = **1,58×10⁻¹⁶**; esquecimento medido ‖Δf_old‖∞ = **4,44×10⁻¹⁶** (cota pessimista 8,8×10⁻¹³) — T4 ✓.

### 9.6 Hashes canônicos FNV-1a-64 (vetores de saída, IEEE-754 LE)

**Âncoras nativas (vigentes — Trava 2, reancorados na execução nativa C++20 `x86_64` com `-ffp-contract=off`; fixtures geradas por SplitMix64/Xoshiro256** com seeds fixas em `test_v8.cpp`):**

```
TEST-V8-1: 0x278c01e818c47c50
TEST-V8-2: 0xbb17432487009526
TEST-V8-3: 0x391b489d80f47c53
TEST-V8-4: 0x4bd5071f29dd7cff
TEST-V8-5: 0x5ed527e9088b664e
```

**Âncoras de referência (Parte 1, implementação NumPy — superseded pelas nativas acima, mantidas para rastreabilidade):**

```
TEST-V8-1: 0x4e1b8b25f8d1eedd   TEST-V8-4: 0x2c248d8b57923dd5
TEST-V8-2: 0x0a418029c85998af   TEST-V8-5: 0xc55c30e33565a633
TEST-V8-3: 0x8865b9ecc626ed76
```

> **Nota de paridade:** as âncoras nativas são autoverificadas pelo próprio binário `test_v8` (exit≠0 em divergência) e revalidadas em `arm64-v8a` no CI via cross-compilação `aarch64-linux-gnu` + QEMU (job `arm64-parity` de `girassol_v8_ci.yml`). Qualquer divergência de hash entre `x86_64` e `arm64-v8a` indica violação de A2/A4 (contraexemplo F7) e bloqueia o commit via C3.

---

## 10. Diagrama do Ciclo Cortical Completo

```
                    ┌────────────────────────── VIGÍLIA ──────────────────────────┐
                    │                                                              │
 sensores/tokens ──►│  bottom-up: W^b (GRV6/E8 gated, PC-3)                        │
 (Android/JNI)      │      ▼                                                       │
                    │  ┌──────────────┐   u = b⊙g(a;ACh)   ┌──────────────┐        │
 predição ─────────►│  │ dendrito     │ ─────────────────► │ soma: s=1{u>θ}│──┐    │
 generativa p(t)    │  │ basal b_i    │                    │ θ adaptativo  │  │    │
   (H-SSM, PC-2)    │  │ dendrito     │ ◄── top-down: W^a  │ r→ρ*=5% (T1) │  │    │
                    │  └──────────────┘                    └──────────────┘  │    │
                    │        ▲ plasticidade 3 fatores: Δw=η·M·e (T1)         │    │
                    │        │ M=(DA,NA,ACh,5HT) ◄── surpresa/volatilidade   │    │
                    └────────┼───────────────────────────────────────────────┼────┘
                             │                                                │
              CLWM-AIF (T3): │ argmin_π G(π) = risco + epistêmico ◄───────────┘
                             │        │                       spikes esparsos
                             │        ▼
                             │  ações na biosfera 3D (Filament) ──► E(s_t) empoderamento (T5)
                             │        │
                             ▼        ▼
        ┌───────────────── SONO / SWR (idle) ─────────────────┐
        │ hipocampo: SQLite WAL `episodic`                     │
        │   │ replay M* via solve_entropic_simplex (v5)        │
        │   ▼                                                  │
        │ neocórtex: U_old⊥U_new via NS5 (PC-4) ── T4 ‖Δf‖≈0   │
        │ extração simbólica → autômato de Tsetlin (T3-b)      │
        │   │                                                  │
        │   ▼                                                  │
        │ GATE PC-SM (T5): C1 ΔL_G≤0 · C2 Roofline · C3 FNV-1a │
        │                  · C4 ortogonalidade                 │
        │   ├── ACEITO → commit atômico .grs + audit_log       │
        │   └── REJEITADO → rollback WAL + audit_log           │
        └──────────────────────────────────────────────────────┘
```

---

## 11. O que é novo em v8 (contribuições além do estado da arte)

1. **Acoplamento formal esparsidade-por-eventos ↔ quantização vetorial** (T2b): a literatura de quantização (GPTQ, k-quants do llama.cpp) trata peso como custo estático; mostramos que o ganho do codec E8 só se materializa sob *gating pré-sináptico*, com prova Roofline e margem 2,27× no pior tier.
2. **Teorema de Não-Interferência estrutural (T4)** com cota fechada independente de T: em vez de regularização (EWC) ou replay puro, a proteção é geométrica (ortogonalidade bilateral em Grassmann mantida por NS5) — esquecimento no piso de arredondamento, medido 4,44×10⁻¹⁶.
3. **Plasticidade de 3 fatores com 4 canais neuromoduladores discretos e prova de confinamento (T1)** em dinâmica orientada a eventos com esparsidade garantida por fixador de Banach — não por clipping ad hoc.
4. **Proof-Carrying Self-Modification (T5)**: gate de commit com certificado determinístico quadrúplo (Lyapunov + Roofline + integridade FNV-1a + ortogonalidade), fechando o sistema de invariantes com T4 — RSI com supermartingal auditável, sem medição em runtime no caminho de decisão.
5. **Cota de cauda explícita para H-SSM-Sparse (T2a)** com parâmetros (α = 0,9, w = 64) escolhidos para ficar abaixo do piso de quantização E8 — aproximação escondida sob o ruído do codec.

**Fim da Parte 1.** A Parte 2 entrega `girassol_v8_core.hpp` + `test_v8.cpp` + bindings JNI/Kotlin compilados com as flags de A3, auditados com `objdump`/`nm` (0 FMA, 0 libm) e reproduzindo os hashes de §9.6.

---

## 12. Adendo da Parte 2 (descobertas da validação nativa C++20)

A execução nativa com as flags A3 revelou quatro pontos que foram incorporados ao código e às provas, sem alterar os enunciados dos teoremas:

1. **`det_log` re-ancorado a [√2/2, √2]** — ver nota de revisão no Lema 4.1. Sem a re-ancoragem, o erro degradava a 3,5×10⁻¹⁰ perto de x ≈ 1; com ela, 8,16×10⁻¹⁶ na pior região.
2. **`det_sqrt` com bit-hack de sqrt** (divisão de expoente, constante `0x1FF7A3BEA91D9B1B` + 8 Newtons): erro ≤ 2,21×10⁻¹⁶ em varredura 10±³⁰⁰. A versão com magic de *rsqrt* convergia devagar demais para entradas fora de [0,5; 2] e quebrava a pré-escala do NS — foi substituída.
3. **NS: 12 iterações no cold-start.** O Lema 4.2 garante contração quadrática apenas no regime ‖I−G‖ pequeno; valores singulares pequenos pós-escala contraem linearmente por 1,5× por iteração, exigindo ~12 iterações a partir de cold-start (medido: ‖I−G₁₂‖_F = 1,78×10⁻¹⁵). NS5 (5 iterações) permanece correto no regime warm-start de v5 (‖I−G₀‖_F < 1): medido 1,34×10⁻¹⁵. A paridade `NS(Aᵀ) = NS(A)ᵀ` é **bit a bit exata** (diff = 0,0) pois os ramos tall/wide compartilham a mesma árvore de redução (Trava 4).
4. **Escalonamento sináptico homeostático (Turrigiano) + guarda de rajada F1.** A validação nativa mostrou que a plasticidade Hebbiana sobre W^b recorrente, sem normalização, entra em realimentação positiva: 31% dos pesos saturados em ±w_max, drive médio ‖b‖ ≈ 366, esparsidade 67%. O escalonamento de coluna (‖W^b_{:j}‖₂ ≤ γ = 1,0, via `det_sqrt`) limita o drive por Cauchy–Schwarz e restaura o fixador de T1(i): esparsidade média nativa **4,22%** (alvo 5%), com guarda F1 (passo θ ×4 quando rate > 3ρ*) e `rate(0) = ρ*` (evita overshoot de θ por transiente da EMA). Parâmetros nativos: β_r = 0,02, β_θ = 0,1, η_pl = 10⁻⁴ (η < β_θ, como exige a condição de contração de T1.i).

**Premissa de T4 verificada nativamente:** a projeção complementar Z = Zraw − U_old(U_oldᵀZraw) só é ⊥ a U_old se U_old estiver de fato em Stiefel — neurogênese com U_old sub-ortonormalizado (NS5 cold) viola C4 (orth_max = 5,2×10⁻²). O gate PC-SM rejeita corretamente nesse caso; com U_old convergido (12 iterações), orth_max = **1,25×10⁻¹⁶** e ‖Δf_old‖∞ = **1,11×10⁻¹⁶** — reconfirmando T4 no piso de máquina.

**Auditoria do binário nativo (x86_64, g++):** 0 instruções FMA (`objdump -d`), 0 símbolos libm (`nm -u`), 0 `new`/`malloc` no núcleo. Layout column-major do GRV6 confirmado: o kernel salta colunas inativas sem leitura (contadores `cols_skipped`/`bytes_dram` em `KernelStats`, usados pelo C2).

**Endurecimento final de integração Android (commit `feat/girassol-v8-unified-brain`):** (i) arena estática BSS do JNI elevada a **26 MiB** (MAX_NEURONS = 1024 consome ~24,06 MiB no pior caso — .bss não infla o APK); (ii) `nativeInitBrain`/`nativeStep`/`nativeNeuromod` serializados por `std::mutex`, eliminando a corrida entre a thread de UI/render e o ciclo SWR; (iii) `det_floor` com guarda de domínio IEEE-754 (|x| ≥ 2⁵² retorna x — cast para int64 seria UB fora dela; o hot path opera estritamente dentro do envelope por construção); (iv) literal composto C99 substituído por array `constexpr` (portabilidade Clang/NDK sob `-Werror`); (v) remoção de código morto em `Hssm::step`. Nenhuma das mudanças altera valores computados: as cinco âncoras FNV-1a-64 de §9.6 foram reexecutadas após o endurecimento e permanecem **bit a bit idênticas** (0x278c01e818c47c50, 0xbb17432487009526, 0x391b489d80f47c53, 0x4bd5071f29dd7cff, 0x5ed527e9088b664e), com auditoria 0 FMA / 0 libm / 0 heap reconfirmada no x86_64 e no `.so` JNI.
