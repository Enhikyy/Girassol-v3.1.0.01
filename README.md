# Girassol v3.6.0.00

Sociedade sintética que vive continuamente num aparelho Android: cidadãos com corpo, memória,
emoções, crenças e instituições habitam uma cidade isométrica gerada a partir de uma semente,
trabalham, trocam recursos, aprendem uns com os outros, nascem, envelhecem e morrem. Tudo roda
offline, de forma determinística, e sobrevive ao fechamento do app e ao reinício do aparelho.

Implementação feita do zero a partir do documento consolidado "PROJETO GIRASSOL".

## Módulos

| Módulo | Conteúdo |
|---|---|
| `core/` | Motor Kotlin/JVM puro (sem Android): `WorldCore` com ciclo de 12 estágios, economia por partidas dobradas, estigmergia, ADICO + cliodinâmica, inferência ativa, memória ACT-R/MemRL, imunidade epistêmica, sono/consolidação, pedagogia BKT, evolução diploide, Tsetlin, MAP-Elites/AURORA, RSI delimitado com Shadow-Replay e snapshot CBOR+gzip com SHA-256. |
| `app/` | App Android (Compose, minSdk 33, targetSdk 35, só `arm64-v8a` no release): serviço em primeiro plano contínuo, persistência SQLite WAL, autodetecção de hardware, viewport 3D Google Filament (estética Kodak 500T/70mm) com Modo Eco 2D isométrico, balões de fala dos cidadãos, barra de navegação flutuante em cápsula e cinco abas (Chat, Biosfera, Evolução, Sistema, Dados) + gaveta com 15 temas, Biblioteca Offline e MeshSync. |
| `app/src/main/cpp/` | Núcleo nativo C++20 via JNI: `libgirassol_core.so` (A* com clearance, LoRA on-device treinado no sono, áudio binaural em AAudio com ducking, Tsetlin bit a bit idêntico ao Kotlin, MAP-Elites, índice episódico binário bit[256] com popcount, sonda de SoC, detecção SIMD por HWCAP, `/proc/self/status`) e um único motor LLM `libgirassol_llm.so` sobre `libllama.so`/`libggml.so` compartilhadas, com backends de CPU dinâmicos (`GGML_BACKEND_DL`) `libggml-cpu-android_armv8.0_1.so`, `armv8.2_2` (dotprod+fp16+KleidiAI) e `armv8.6_1` (i8mm), escolhidos em runtime. As bibliotecas do Girassol exportam só `JNI_OnLoad` e `Java_*`. |
| `app/src/main/materials/` | Materiais Filament (`city.mat` com PBR KTX2 triplanar, `glow.mat` e o pós analógico `girassol_dehancer_post.mat`) compilados para `.filamat` pelo `matc` na task Gradle `compileFilamentMaterials`. |
| `app/src/main/assets/textures/` | Texturas PBR procedurais KTX2 (concreto brutalista, carpete Backrooms), geradas por `scripts/gen_pbr_ktx2.py` e conferidas por `licenses/TEXTURES.sha256`. |
| `pack_rapido/`, `pack_profundo_a/`, `pack_profundo_b/` | Asset packs `install-time` do AAB (modelo rápido + Sherpa-ONNX; modelo profundo em duas partes < 1,5 GB). |
| `native-tests/` | Testes nativos de host com ASan/UBSan, incluindo a assinatura de paridade Kotlin↔C++. |

## Novidades da v3.6.0.00 (Adão e Eva)

- Mundo começa com 0 cidadãos. Cada `.gguf` importado (F16, BF16, Q8_0, Q5_K, Q4_K_M, Q4_0…) cria exatamente
  um fundador g0 com nome e sexo escolhidos (`WorldCore.spawnFounder`, `SimulationHub.importFounder`). Nenhum
  modelo é embutido no APK; os modelos ficam no armazenamento privado e na tabela `registered_models`
  (SQLite WAL), restaurados ao reabrir. Painel "Cidadãos-Modelo" na aba Sistema: importar, pausar/ativar, remover.
- Reprodução por mérito (`core/world/ReproductionMerit.kt`): os dois adultos precisam provar evolução desde t0 —
  erro do aceitador Anokhin ≤ 90 % da linha de base, Brier melhor, ≥ 1 promoção aprovada no `BoundedRsiPromotionGate`
  (CMA-ES ou ciclo LoRA aceito no sono), bem-estar ≥ 0,70 e excedente de comida nos comuns. ETA dinâmico
  (ticks, dias, tempo real) no HUD da Biosfera e na aba Evolução; evento `NASCIMENTO_MERITO` na cadeia SHA-256.
- Chat (aba principal): pílulas para conversar com cada cidadão ou com o canal aberto; cada cidadão responde com o
  seu `.gguf` (gramática restrita) ou, sem modelo, pelo deliberador simbólico baseado no estado real. Mensagens
  persistidas em `chat_messages` (SQLite WAL), no `.grs` (`WorldState.chat`) e na cadeia de eventos; voz TTS pt-BR.
- Evolução: gráficos Canvas (Anokhin/Brier/CMA-ES com marcadores de promoção/LoRA, árvore g0→g1→g2 com nó do
  próximo nascimento, comuns/PSI/Gini/população), mérito individual e seções recolhíveis.
- RAM: pesos em mmap não contam como heap; admissão por RAM ativa estimada (LFM2.5-230M-F16, 350M F16 e Q5_K
  aceitos em 3631 MB). Perfis 4/8/12 GB mudam `n_ctx` e o tipo de KV; um contexto residente por vez.
- Anti-clarão Mali: ver `docs/RENDER_CALIBRATION.md`. Balões: no máximo 2, sem sobreposição, com fade-out.

## Novidades da v3.5.0.00

Os 11 pontos do adendo de fechamento, com onde cada um vive no código:

1. Biblioteca de Conhecimento (processo 19): ingestão offline de `TXT`, `EPUB`, `PDF`, `Parquet` e áudio
   (`core/knowledge/DocumentParsers.kt`, `app/knowledge/LibraryIngest.kt`, `AudioTranscriber.kt` com SenseVoice-Small
   via Sherpa-ONNX), alimentando a memória semântica e o léxico comprimido emergente (`EmergentLexicon`).
   MeshSync LAN (`core/mesh/MeshSyncProtocol.kt`, `app/mesh/MeshSyncManager.kt`): descoberta NSD `_girassol._tcp` +
   beacon UDP 47653, quadros autenticados por HMAC-SHA256, WifiLock/MulticastLock com tempo limite de 10 min,
   snapshots diferenciais `.grs` e migração de cidadãos com reversão em caso de falha.
2. LoRA real no aparelho: `OnDeviceLoraTrainer` (`cpp/lora_ondevice.cpp`) treina um adaptador leve no ciclo de sono a
   partir das memórias episódicas de maior utilidade, aceita só se a NLL de validação cair, e aplica via
   `llama_adapter_lora_init` / `llama_set_adapters_lora`.
3. Cadeia SHA-256 no SQLite WAL: cada evento canônico grava `prev_hash` e `event_hash` (`WorldStateHashChain`),
   com verificação na aba Sistema ("Verificar cadeia SHA-256").
4. Sistemas Funcionais de Anokhin: síntese aferente + Aceitador de Resultados da Ação
   (`AnokhinFunctionalSystem.kt` / `anokhin_functional_system.hpp`) antes de atualizar plasticidade e surpresa.
5. CMA-ES compacto (`CmaEs.kt` / `cma_es.hpp`) para hiperparâmetros contínuos de decisão e locomoção, promovido só
   pelo `BoundedRsiPromotionGate`.
6. Qualquer `.gguf` válido (F16/FP16 ~350M, Q4_0, Q8_0 …), sem filtro por nome; KV-cache volta para F16 sempre que
   `head_dim % 32 != 0`.
7. Build blindado: `-Pgirassol.llguidance=auto|on|off` compila o crate `llguidance` (Rust, `aarch64-linux-android`)
   e cai para GBNF estrito se falhar; `-Pgirassol.embedModels=true -Pgirassol.distribution=direto` gera o APK
   arm64-v8a com o modelo rápido Q4_0 + Sherpa-ONNX + texturas KTX2, checado abaixo de 1,90 GB
   (`scripts/check_apk_size.sh <apk> 1945`); `-Pgirassol.distribution=play` gera o AAB com o modelo profundo
   (~2,2 GB) em asset packs `install-time` (`scripts/check_aab_size.sh`: AAB < 5 GB, cada pack < 1,5 GB,
   base + packs < 4 GB). O modelo profundo também pode ser importado pelo SAF.
8. Pós-processamento `girassol_dehancer_post.filamat` (bloom, halação vermelho-alaranjada, sombras densas e grão
   Kodak) numa segunda View sobre um RenderTarget, e corredores liminares Backrooms ligando os laboratórios
   Severance/Lumon às praças e hortas da ala Modernismo Tropical (`core/render/BackroomsCorridors.kt`).
9. Crescimento infantil → adulto (`CitizenGrowth`) e esqueleto procedural de 7 ossos com skinning rígido na CPU enviado ao Filament
   (`core/render/CitizenRig.kt`): passada sincronizada com a velocidade ORCA/RVO2, braços no teclado nos
   terminais CRT e boca/gesto pela amplitude vocal do Pocket-TTS (`VoiceAmplitude`).
10. 15 temas nativos com contraste WCAG AA ≥ 4,5:1 (pior caso sobre superfícies translúcidas) entre Instrument Sans /
    JetBrains Mono e o fundo, validado em `ThemeContrastTest` (`core/ui/ThemePalettes.kt`). Fontes OFL em
    `app/src/main/res/font/`, conferidas por `licenses/FONTS.sha256`.
11. Caminhos relativos em `settings.gradle.kts`, `build.gradle.kts` e `CMakeLists.txt` (CodeAssist), verificados no
    CI por `scripts/check_relative_paths.sh`.

## Compilar

Requisitos: JDK 17, Android SDK 35, NDK 27.0.12077973 e CMake 3.22.1.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:compileFilamentMaterials \
          :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pgirassol.abis=arm64-v8a,x86_64
```

Saídas: um APK por ABI (`splits.abi`, sem APK universal) em `app/build/outputs/apk/<tipo>/app-<abi>-<tipo>.apk`
e o bundle `app/build/outputs/bundle/release/app-release.aab`. Sem `-Pgirassol.embedModels=true` o APK fica leve
(sem modelos; útil para desenvolvimento). Com modelos embutidos, `scripts/check_apk_size.sh <apk> 1945` confere o
teto de 1,90 GB do APK direto (o Android recusa APKs únicos acima de 2 GB com `INSTALL_FAILED_INVALID_APK`) e a
presença de exatamente um motor compartilhado + três backends de CPU; `scripts/check_aab_size.sh <aab>` confere
os limites do Google Play para os asset packs.

Na primeira execução o Gradle baixa e verifica por SHA-256 o código do llama.cpp (tag `v1.0.0`) e as
ferramentas do Filament 1.75.1 (`matc`) em `build/third_party/`. Para usar cópias locais:
`-Pgirassol.llamaDir=/caminho/llama.cpp -Pgirassol.kleidiaiDir=/caminho/kleidiai-v1.32.0 -Pgirassol.matc=/caminho/matc`
(o KleidiAI v1.32.0 também é baixado e verificado por SHA-256). `-Pgirassol.abis=x86_64` troca as ABIs
(útil para o emulador; o padrão é só `arm64-v8a`).

Testes de instrumentação (aparelho ou emulador conectado): `./gradlew :app:connectedDebugAndroidTest`.

Verificar que nenhum símbolo interno do llama.cpp/ggml vazou: `scripts/check_native_symbols.sh app/build/outputs/apk/*/*.apk`.

Testes nativos (ASan/UBSan), com e sem llama.cpp:

```bash
cd native-tests && cmake -S . -B build -G Ninja && cmake --build build && ./build/girassol_native_tests
cmake -S . -B build-llama -G Ninja -DGIRASSOL_LLAMA_DIR=/caminho/llama.cpp && cmake --build build-llama
GIRASSOL_TEST_GGUF=/caminho/stories260K.gguf ./build-llama/girassol_native_tests
```

O release é assinado com a keystore indicada por `GIRASSOL_KEYSTORE_PATH`, `GIRASSOL_KEYSTORE_PASSWORD`,
`GIRASSOL_KEY_ALIAS` e `GIRASSOL_KEY_PASSWORD`. Sem essas variáveis, usa a chave de debug (útil para
testes, não para publicação). No GitHub Actions, configure o secret `GIRASSOL_KEYSTORE_BASE64`
(keystore em base64) e os demais secrets com os mesmos nomes.

## Uso

1. Instale o APK e conceda a permissão de notificação (exigida pelo serviço em primeiro plano).
2. Aba Mundo: alterne entre 3D (Filament) e 2D (Modo Eco). No 3D: pinça para zoom, dois dedos para girar e
   deslocar, um dedo para orbitar; toque num cidadão (raycast 3D) para ver o detalhe. Balões mostram falas e
   pensamentos. Em segundo plano, sob alerta térmico ou se o GLES falhar, o app cai para o 2D sozinho.
3. Aba Mente: importe um modelo `.gguf` (Storage Access Framework); veja RAM nativa, tokens/s, decisões
   aplicadas e o histórico de ciclos LoRA. Sem modelo, a deliberação usa o `SymbolicLookaheadDeliberator`.
   Também é possível injetar boatos/contaminações e forçar um ciclo RSI.
4. Aba Sistema: velocidade, serviço contínuo, chaves dos módulos nativos (navegação, LoRA, áudio binaural, LLM),
   diagnóstico C++ (autoteste, paridade, benchmark de CPU), verificação de snapshot e reinício do mundo. Desde a
   v3.3 também mostra: flags SIMD detectadas em runtime (NEON/DOTPROD/FP16/I8MM), variante do llama.cpp
   carregada e benchmark de tokens/s; VmRSS/VmHWM reais, RAM livre do sistema, estado térmico ADPF e o disjuntor
   de memória; `GL_RENDERER`/`GL_VENDOR` e o nível de efeito visual ativo; assinatura do APK (debug ou release)
   com exportação/importação do snapshot `.grs`.
5. Desde a v3.4: aba Mente mostra a memória episódica em 2 estágios (consultas, candidatos por Hamming, busca
   SQLite FTS5/FTS4 nas memórias), hormônios CfC, passos ORCA, colisões resolvidas, TIES-Merging e superações;
   aba Sistema mostra KV-cache quantizado, modo lazy, tokens reaproveitados por prefixo/slot e fast-forward GBNF,
   o painel "Energia e ADPF" (wake lock, WifiLock, temperatura, sessão ADPF) e a chave de vozes TTS offline.
6. A faixa de telemetria acima da barra flutuante mostra FPS do Filament, estado do llama.cpp, ciclos LoRA e RSS.

Modelos: o APK direto já traz o modelo rápido Qwen2.5-0.5B-Instruct Q4_0; o AAB traz também o profundo
Phi-3.5-mini-instruct Q4_0 (~2,2 GB). Qualquer `.gguf` válido pode ser importado pelo SAF, incluindo F16/FP16 de
~350M parâmetros (~700 MB, acelerados em ARMv8.2 fp16). K-quants (Q4_K_M) funcionam, mas não usam os
micro-kernels KleidiAI. O orçamento de RAM do modelo é ajustado pela RAM física do aparelho.

Detalhes de arquitetura e limitações conhecidas: [docs/ARQUITETURA.md](docs/ARQUITETURA.md).
Roteiro de validação em aparelho físico de 4 GB (Snapdragon 695, Helio G99): [docs/VALIDACAO_APARELHO.md](docs/VALIDACAO_APARELHO.md).
