# Arquitetura do Girassol

## Princípios

- **Uma única fonte de verdade.** O `WorldCore` vive numa única thread (`girassol-worldcore`). A UI só
  recebe snapshots imutáveis (`WorldRenderSnapshot`, `DashboardSnapshot`) por `StateFlow` e envia comandos.
- **Determinismo.** Mesma semente + mesmas entradas = mesmo estado, bit a bit. Cada fluxo aleatório deriva
  sua semente por SHA-256(semente do mundo ‖ agente ‖ tick ‖ stream), então nascimentos e mortes não
  deslocam a aleatoriedade dos demais. O teto de ticks/s (hardware e temperatura) muda apenas a velocidade
  do relógio, nunca as regras.
- **Eventos canônicos encadeados.** Toda mudança aprovada gera um `CanonicalEvent` com hash SHA-256 ligado
  ao anterior, gravado no SQLite (WAL) por um único thread escritor.
- **Persistência atômica.** Snapshot CBOR+gzip com cabeçalho `GRS3` e SHA-256 a cada 240 ticks, gravado de
  forma atômica (`world_snapshot.bin` + `.bak`). Snapshots corrompidos são rejeitados na restauração.

## Ciclo de 12 estágios por tick

1. Telemetria e governança térmica
2. Ambiente, estigmergia e bioquímica
3. Percepção e competição atencional (Global Workspace)
4. Memória e atualização epistêmica (ACT-R + MemRL)
5. Appraisal emocional e humor
6. Utilidade e energia livre esperada G(π)
7. Planejamento hierárquico com histerese de intenção
8. Deliberação (Cognitive Gate / PIANO) ou reflexo Tsetlin quando a confiança ≥ 0,6
9. Submissão simultânea de propostas
10. Arbitragem determinística e reservas
11. Execução canônica e contabilidade por partidas dobradas
12. Sociedade, aprendizagem, evolução e snapshot (com estágio diário: cliodinâmica, sono, reprodução, expansão urbana)

## Pacotes do `core`

| Pacote | Responsabilidade |
|---|---|
| `world` | `WorldCore`, `WorldModel`, geração de cidade, pathfinding, economia e estigmergia, codec de snapshot, projeções para UI |
| `cognition` | Inferência ativa (PIANO), interface `Deliberator` e `SymbolicLookaheadDeliberator` |
| `memory` | Consolidação no sono e imunidade epistêmica (quarentena de crenças até verificação) |
| `social` | Instituições ADICO, opinião Friedkin–Johnsen, cliodinâmica (PSI, MMP, EMP, SFD, Gini) |
| `biology` | Pedagogia BKT e evolução diploide |
| `reflex` | Máquina de Tsetlin (paridade bit a bit com o C++) |
| `qd` | MAP-Elites + AURORA |
| `rsi` | Auto-aperfeiçoamento delimitado: proposta → Shadow-Replay em cópia profunda → promoção só com ganho líquido, sem queda de diversidade semântica abaixo de 0,35 |

## Módulos nativos e portas (v3.4)

O `core` continua JVM puro. Os módulos nativos entram por interfaces em `core/ports` (`PathfinderPort`,
`LoraPort`, `NeuralDeliberatorPort`), montadas pelo `SimulationHub`. Cada porta tem implementação Kotlin de
referência; a versão C++ só é usada depois que `NativeModules.verifyParity` confirma resultados idênticos
nos vetores de `ParityVectors`. Ligar/desligar um módulo na aba Sistema recria o `WorldCore` a partir do mesmo
estado.

### Motor cognitivo híbrido (llama.cpp + GBNF)

- `llm/llm_engine.cpp` liga o llama.cpp `v1.0.0` (CMake/NDK) ao JNI (`llm/llm_jni.cpp`, classe `LlmNativeBridge`).
  Modelo carregado com `mmap` (sem `mlock`, sem GPU), `n_ctx = 1024`, lote 256, threads = metade dos núcleos (2 a 4).
- Aceleração ARM64 sem risco de `SIGILL` e sem duplicar o motor (v3.4): llama.cpp é compilado com
  `BUILD_SHARED_LIBS=ON` e `GGML_BACKEND_DL=ON`. O APK leva **uma** `libllama.so`, **uma** `libggml.so` +
  `libggml-base.so`, o wrapper `libgirassol_llm.so` e só três backends de CPU carregáveis (`GGML_CPU_ALL_VARIANTS`,
  variantes não usadas são compiladas fora do diretório empacotado):

  | Backend dinâmico | Recursos | Alvo |
  |---|---|---|
  | `libggml-cpu-android_armv8.0_1.so` | NEON | qualquer arm64 (Cortex-A53) |
  | `libggml-cpu-android_armv8.2_2.so` | dotprod + fp16 + KleidiAI | Snapdragon 695 (A78/A55), Helio G99 (A76/A55) |
  | `libggml-cpu-android_armv8.6_1.so` | dotprod + fp16 + i8mm + KleidiAI | Cortex-A510/A710/X2 em diante |

  `hardware/cpu_features.hpp` lê `getauxval(AT_HWCAP/AT_HWCAP2)` em `libgirassol_core.so` (armv8-a) e
  `LlmVariantSelector` pede ao `ggml_backend_load` o backend mais rápido que o HWCAP confirma, caindo para o
  próximo se a carga falhar. No emulador x86_64 há um único `libggml-cpu.so`. `splits.abi` gera um APK por ABI
  (release só arm64) e `useLegacyPackaging = false` deixa as `.so` alinhadas e sem compressão (mmap direto do APK).
- Regra de Ouro KleidiAI: `LlmQuantPolicy` recomenda pesos Q4_0/Q8_0, liga `flash_attn` e escolhe KV-cache
  `Q4_0` em aparelhos ≤ 4 GB e `Q8_0` acima. Se o modelo não aceitar (head_dim não múltiplo de 32), o motor tenta
  `flash_attn + F16` e depois `F16` sem flash-attn. `use_mmap = true` sempre. O "lazy mode" é implementado no app:
  em ≤ 4 GB o modelo só carrega na primeira deliberação e é descarregado após 90 s ocioso.
- Prefixo imutável e slots por cidadão: todo prompt começa por `NeuralProtocol.IMMUTABLE_WORLD_PREFIX`; o motor
  mantém o KV do prefixo e grava/restaura o estado de cada cidadão com `llama_state_seq_save_file` /
  `llama_state_seq_load_file` (até 24 slots LRU em `filesDir/llm_slots`, apagados pelo disjuntor de memória).
- Gramática: GBNF estrita (o llama.cpp v1.0.0 empacotado não inclui llguidance) com fast-forward — quando a
  gramática só admite uma continuação (chaves JSON fixas), os tokens são forçados sem amostragem, validados num
  clone do sampler de gramática antes de entrar no lote.
- `SemanticEntropyController` (C++20, `llm/semantic_entropy.hpp`): entropia de 3-gramas das últimas 6 falas do
  cidadão; \(T_t = \text{clamp}(T_{base} + \gamma \max(0, \tau_{crit} - H_{ngram}), 0{,}45, 1{,}15)\), com semente
  derivada do `StreamSeeds` do mundo.
- Diagnóstico: aba Sistema mostra as flags (`NEON`, `DOTPROD`, `FP16`, `I8MM`, `SVE`, `SVE2`), a variante e o
  `-march` carregados, o `llama_print_system_info()` e um benchmark de tokens/s (prompt em lote e geração).
- Toda geração usa gramática GBNF (`NeuralProtocol`) e decodificação gulosa; a saída é ainda validada por um
  parser estrito. Saída inválida é descartada e o cidadão usa o deliberador simbólico.
- Determinismo: a inferência roda fora da thread do mundo. O resultado aprovado é drenado no estágio 1 do tick
  seguinte e gravado como evento canônico `LLM_DECISAO` (ação, fala, tick do pedido). O replay a partir do log é
  bit a bit idêntico; a sequência ao vivo depende do tempo de inferência do aparelho.
- Fallback automático para `SymbolicLookaheadDeliberator`: sem `.gguf`, tier térmico ≥ 2, disjuntor de memória
  aberto, RAM livre baixa ou erro de carga. Shadow-Replay do RSI e sandbox LoRA usam sempre o simbólico.
- Falas e pensamentos (LLM ou modelos simbólicos) aparecem como balões na aba Mundo.

### Motores cognitivos e corporais da v3.4

- Memória episódica em 2 estágios (`core/memory/EpisodicIndex.kt` + `cpp/memory/binary_episodic_index.hpp`):
  embedding binário SimHash bit[256] (32 bytes) por memória; Estágio 1 seleciona 24 candidatos por distância de
  Hamming (XOR + popcount; `__builtin_popcountll` no C++, paridade dourada verificada no aparelho); Estágio 2
  funde por RRF (k = 60) cosseno float32 de um vetor denso 256 e BM25, ponderado pela intensidade de Hawkes
  \(\lambda_m(t) = \mu_0 + \sum \alpha v_i e^{-\beta(t - t_i)}\) mantida de forma recursiva. A memória de trabalho
  segue 7 ± 2 (5..9 conforme a carga); no sono, o replay de ondas agudas (SWR) reforça Q_m das memórias
  recentes. As memórias projetadas também vão para uma tabela SQLite FTS5 (fallback FTS4 + BM25 Kotlin) para
  busca na aba Mente.
- Imunidade epistêmica: relatos da mesma fonte/proveniência são fundidos por média (não contam duas vezes um
  boato repetido); fontes independentes usam a fusão cumulativa ⊕ após o desconto ⊗ pela confiança.
- Inferência ativa: \(G(\pi) = D_{KL}[Q(o|\pi) \| P_{pref}(o)] - \mathbb{E}[H(P(o|s))] + C_{neural}(T_{SoC})\) com
  KL/entropia de Bernoulli por necessidade e custo neural sigmoide em torno de 40 °C (temperatura da bateria
  quantizada a 1 °C pela telemetria).
- CfC (`core/biology/CfcNeuromodulator.kt`): rede Closed-form Continuous-time de 48 neurônios, invariante a Δt,
  que produz cortisol, ocitocina, dopamina e um fator de locomoção (0,75–1,25) a cada tick; os hormônios entram
  na EFE, na taxa de aprendizado MemRL e na velocidade preferida.
- Multidão (`core/world/CrowdNavigation.kt`): `DistrictNavMesh` com clearance (custo maior perto de paredes no
  A*), `stepRvo2OrcaVelocities` (porta do RVO2/ORCA, raio 0,2, horizonte 2) e `resolveCircleAABB` contra os tiles
  sólidos — posições contínuas sem atravessar edifícios, usadas nas vistas 2D e 3D.
- Genética: no nascimento os adaptadores LoRA pessoais dos pais são fundidos por TIES-Merging (trim, eleição de
  sinal, média disjunta); aos 2 dias de vida o filho que supera em ≥ 15 % a aptidão média dos pais ganha o
  evento `SUPERACAO` e cooldown reprodutivo menor.

### Energia, ADPF e voz (v3.4)

- `GirassolContinuousService` mantém `PARTIAL_WAKE_LOCK` com timeout renovado e um `WifiLock`
  `WIFI_MODE_FULL_LOW_LATENCY`; lê a temperatura da bateria (`EXTRA_TEMPERATURE`) para a telemetria.
- `perf/AdpfHints.kt`: sessão `PerformanceHintManager` com os tids das threads `girassol-sim` e `girassol-llm`;
  a duração-alvo acompanha os ticks por segundo e cada tick/geração chama `reportActualWorkDuration`.
- Áudio: o sintetizador AAudio aplica ducking na trilha Trautonium (`computeTrautoniumDuckingGain`) durante
  blips de fala e durante a voz TTS. `audio/CitizenVoice.kt` usa o `TextToSpeech` do Android com voz pt-BR offline
  e pan estéreo pela posição do cidadão. O pipeline Sherpa-ONNX (Pocket-TTS/Kokoro + SenseVoice) não é
  empacotado: os modelos somam dezenas de MB e estourariam o limite de 14 MB do APK.

### Renderização 3D (Filament 1.75.1) e Modo Eco 2D

- `render3d/FilamentCityRenderer` desenha a cidade brutalista/liminar gerada pela semente (`CityMeshBuilder`):
  lajes em balanço, corredores, janelas emissivas, luzes fluorescentes. Material `unlit` com iluminação e
  oclusão pré-calculadas por vértice (barato em GPUs Mali/Adreno de entrada).
- Pós-processamento "Kodak 500T/70mm": bloom suave, halation vermelho-alaranjado (sprites aditivos + bloom),
  vinheta, neblina, FXAA, gradação Filmic com balanço de branco tungstênio, sombras profundas e granulação
  (overlay Compose em `BlendMode.Overlay`). Limitado a 30 fps com resolução dinâmica.
- `OrbitCamera` (órbita, pinça, rotação, pan) e `Raycast` (raio × AABB dos cidadãos, testado em JVM).
- `RenderModePolicy`: 3D/2D manual; 2D obrigatório em segundo plano, tier térmico ≥ 3, falha de GLES, durante a
  calibração de GPU ou quando nenhum nível de efeito é seguro.

### Resiliência de shaders em GPUs reais (v3.3)

Falha de compilação de shader no Filament não vira exceção Java: o driver aborta o processo. Por isso:

- `GlInfo` lê `GL_RENDERER`/`GL_VENDOR`/`GL_VERSION` num contexto EGL pbuffer. GL por software (SwiftShader,
  llvmpipe) recebe direto o nível `NONE` (comportamento da v3.2 no CI).
- Em GPU de hardware, `GpuCalibration` testa os níveis `FULL → REDUCED → MINIMAL → NONE` no serviço
  `GpuProbeService`, que roda no processo isolado `:gpuprobe` e renderiza fora da tela a mesma cena, materiais
  `.filamat` e passes (bloom, halation, gradação, FXAA). Se o processo morrer ou expirar (25 s), o nível é
  descartado e o próximo é testado; o processo principal nunca cai. O resultado é guardado por
  aparelho + versão do app + versão do Filament + driver.
- Sentinela: enquanto o 3D roda, o nível ativo fica gravado; se o app morrer no meio, o próximo início rebaixa um
  nível.
- `FpsGovernor`: após 5 s de aquecimento, se a média de 8 s ficar abaixo de 20 fps, o nível cai um degrau sem
  recriar a superfície (`applyEffectLevel`).
- Níveis: `FULL` (bloom 6 níveis, halação, grão, gradação 500T, vinheta, névoa, FXAA), `REDUCED` (bloom 3, sem
  FXAA/névoa), `MINIMAL` (só gradação, halação e grão), `NONE` (cena sem pós). A aba Sistema e o rótulo da aba
  Mundo mostram o nível ativo e o `GL_RENDERER`.

### Memória real e disjuntor (v3.3)

- `hardware/proc_memory.hpp` lê `/proc/self/status` em C++ (`VmRSS`, `VmHWM`, `VmSize`, `RssAnon`, `RssFile`,
  `VmSwap`); `MemoryGuard` amostra a cada 2 s e combina com `ActivityManager.MemoryInfo`, heap nativo e o estado
  térmico ADPF (`PowerManager.currentThermalStatus` e `getThermalHeadroom`).
- `MemoryBreaker` (puro, testado em JVM): limite de VmRSS por RAM física (≤ 3,2 GB → 1,2 GB; ≤ 4,6 GB →
  1,8 GB; ≤ 6,6 GB → 2,6 GB; acima → 3,2 GB). Dispara ao passar do limite ou em `TRIM_MEMORY_RUNNING_CRITICAL`/
  `onLowMemory`; descarrega o contexto e o modelo do llama.cpp, chama `SQLiteDatabase.releaseMemory()`, limpa o
  `cacheDir` e pede GC. O loop determinístico continua com o deliberador simbólico. Rearma depois de 60 s abaixo
  de 80 % do limite e sem novo sinal crítico (ou pelo botão "Rearmar").

### Navegação, LoRA e áudio

- Navegação: A* em grade em C++ (`navigation/grid_astar.hpp`), mesma ordem de expansão e desempate do Kotlin,
  então trajetórias permanecem reprodutíveis pela semente.
- LoRA: no sono, as memórias consolidadas viram amostras; o adaptador (rank 4) é treinado em C++ e testado num
  replay sandbox de 120 ticks numa cópia profunda do mundo. Só é aplicado se não piorar a pontuação nem gerar
  violações; cada ciclo entra no histórico (evento `LORA_SONO`). O LoRA ajusta a utilidade (peso 0,08), não os
  pesos do GGUF.
- Áudio: sintetizador binaural C++ em AAudio (a API nativa sobre a qual o Oboe é construído; sem biblioteca Oboe empacotada), batimento e timbre
  modulados pela estabilidade social e pelo humor coletivo; ativar/silenciar na aba Sistema.

### Proteção de release

`proguard-rules.pro` mantém classes/métodos `native`, o pacote `com.google.android.filament`, o bridge JNI e os
campos `Long` que guardam ponteiros nativos do llama.cpp.

Símbolos nativos (v3.3): llama.cpp/ggml compilados com `-fvisibility=hidden`, e as bibliotecas do Girassol
ligadas com `exports.map` (version script: só `Java_*` e `JNI_OnLoad`), `--exclude-libs,ALL`, `--gc-sections`
e páginas de 16 KB. O CI confere a tabela dinâmica de cada `.so` nos APKs (`scripts/check_native_symbols.sh`).

Assinatura: `SigningInfo` lê o certificado do APK instalado (`GET_SIGNING_CERTIFICATES`) e o classifica como chave
de debug (`CN=Android Debug`) ou keystore de release, com o SHA-256. Como o Android não permite atualizar um app
com outra assinatura, a aba Sistema avisa para exportar o snapshot `.grs` (SAF) antes de desinstalar e oferece
a importação depois.

## Testes e CI

- `native-tests`: ASan/UBSan em duas configurações (sem llama; com llama.cpp + GGUF `stories260K` e gramática),
  incluindo decodificação de HWCAP (Cortex-A78 → dotprod, nunca i8mm), parser de `/proc/self/status` e o
  benchmark de tokens/s.
- `:core:test`, `:app:testDebugUnitTest` (câmera/raycast, disjuntor, seletor de variante LLM, níveis de efeito e
  `FpsGovernor`, classificação de assinatura), `:app:lintDebug`, `compileFilamentMaterials`, APKs debug e release
  e a checagem de símbolos exportados.
- `androidTest` em emulador x86_64 (API 34): aba Mundo (alternância 3D/2D, toque em cidadão) e aba Sistema
  (diagnóstico C++, benchmark, chaves dos módulos nativos, painéis de SIMD, memória/disjuntor, GPU e assinatura,
  sonda de GPU em processo separado).

## Limitações conhecidas

- Não testado em aparelho físico de 4 GB; o orçamento de RAM e os 30 fps são estimativas de projeto. Use
  [VALIDACAO_APARELHO.md](VALIDACAO_APARELHO.md) para conferir.
  O emulador usa GPU por software (SwiftShader), que não compila os shaders de pós-processamento do Filament
  (o FXAA aborta o processo); em GL por software o app desativa o pós-processamento, então o CI valida a cena
  3D, a câmera e o raycast, mas não a estética Kodak nem a sonda de GPU em hardware (no emulador ela só roda o
  nível `NONE`).
- A sonda de GPU cobre crashes de compilação e de primeiro frame; bugs de driver que só aparecem depois de
  minutos de uso dependem da sentinela (rebaixamento no próximo início) e do `FpsGovernor`.
- Os limites do disjuntor são heurísticos; o Android pode matar o processo (LMK) antes dele em aparelhos com
  muitos apps em segundo plano.
- Filament fixado em 1.75.1: versões ≥ 1.76 exigem compileSdk 37, e o AGP 8.7.3 suporta até o 35.
- OpenMP desligado (threadpool do ggml). As variantes dotprod/i8mm não foram medidas em aparelho real; o ganho
  de tokens/s depende do modelo e da quantização (Q4_0 aproveita mais o repacking/KleidiAI que Q4_K_M).
- Embeddings episódicos são SimHash/hashing de termos, não um modelo neural de embeddings.
- llguidance não está no llama.cpp v1.0.0 empacotado: a gramática é GBNF estrita com fast-forward.
- KV-cache quantizado exige head_dim múltiplo de 32 (com flash-attn); outros modelos caem para F16.
- Sem Sherpa-ONNX (orçamento de 14 MB); a voz depende do motor TTS instalado no aparelho.
- ADPF e WifiLock dependem do suporte do fabricante; não foram medidos em aparelho físico.
- O LLM só atua no mundo ao vivo; RSI e sandbox LoRA usam o deliberador simbólico para manter replay fixo.
- Iluminação do 3D é pré-calculada (sem sombras dinâmicas) para caber no orçamento térmico 24/7.
