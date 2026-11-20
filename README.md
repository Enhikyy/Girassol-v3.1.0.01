# Girassol v3.4.0.00

Sociedade sintética que vive continuamente num aparelho Android: cidadãos com corpo, memória,
emoções, crenças e instituições habitam uma cidade isométrica gerada a partir de uma semente,
trabalham, trocam recursos, aprendem uns com os outros, nascem, envelhecem e morrem. Tudo roda
offline, de forma determinística, e sobrevive ao fechamento do app e ao reinício do aparelho.

Implementação feita do zero a partir do documento consolidado "PROJETO GIRASSOL".

## Módulos

| Módulo | Conteúdo |
|---|---|
| `core/` | Motor Kotlin/JVM puro (sem Android): `WorldCore` com ciclo de 12 estágios, economia por partidas dobradas, estigmergia, ADICO + cliodinâmica, inferência ativa, memória ACT-R/MemRL, imunidade epistêmica, sono/consolidação, pedagogia BKT, evolução diploide, Tsetlin, MAP-Elites/AURORA, RSI delimitado com Shadow-Replay e snapshot CBOR+gzip com SHA-256. |
| `app/` | App Android (Compose, minSdk 33, targetSdk 35, só `arm64-v8a` no release): serviço em primeiro plano contínuo, persistência SQLite WAL, autodetecção de hardware, viewport 3D Google Filament (estética Kodak 500T/70mm) com Modo Eco 2D isométrico, balões de fala dos cidadãos, barra de navegação flutuante OLED e cinco abas (Mundo, Cidadãos, Sociedade, Mente, Sistema). |
| `app/src/main/cpp/` | Núcleo nativo C++20 via JNI: `libgirassol_core.so` (A* com clearance, LoRA on-device treinado no sono, áudio binaural em AAudio com ducking, Tsetlin bit a bit idêntico ao Kotlin, MAP-Elites, índice episódico binário bit[256] com popcount, sonda de SoC, detecção SIMD por HWCAP, `/proc/self/status`) e um único motor LLM `libgirassol_llm.so` sobre `libllama.so`/`libggml.so` compartilhadas, com backends de CPU dinâmicos (`GGML_BACKEND_DL`) `libggml-cpu-android_armv8.0_1.so`, `armv8.2_2` (dotprod+fp16+KleidiAI) e `armv8.6_1` (i8mm), escolhidos em runtime. As bibliotecas do Girassol exportam só `JNI_OnLoad` e `Java_*`. |
| `app/src/main/materials/` | Materiais Filament (`city.mat`, `glow.mat`) compilados para `.filamat` pelo `matc` na task Gradle `compileFilamentMaterials`. |
| `native-tests/` | Testes nativos de host com ASan/UBSan, incluindo a assinatura de paridade Kotlin↔C++. |

## Compilar

Requisitos: JDK 17, Android SDK 35, NDK 27.0.12077973 e CMake 3.22.1.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:compileFilamentMaterials \
          :app:assembleRelease :app:bundleRelease
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pgirassol.abis=arm64-v8a,x86_64
```

Saídas: um APK por ABI (`splits.abi`, sem APK universal) em `app/build/outputs/apk/<tipo>/app-<abi>-<tipo>.apk`
e o bundle `app/build/outputs/bundle/release/app-release.aab`. `scripts/check_apk_size.sh <apk> 14` confere o
limite de 14 MB e a presença de exatamente um motor compartilhado + três backends de CPU. Tamanho medido
(release arm64-v8a, v3.4.0.00): 13.942 KiB (≈13,6 MiB) com libllama/libggml/libggml-base compartilhadas e os três
backends `libggml-cpu-android_armv8.{0_1,2_2,6_1}.so`; o AAB tem ≈14,8 MiB (inclui símbolos de depuração).

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

Modelos recomendados para 4 GB: GGUF de até ~1,5 B parâmetros em **Q4_0** ou Q8_0 (Regra de Ouro KleidiAI;
ex.: Qwen2.5-0.5B/1.5B-Instruct Q4_0). K-quants (Q4_K_M) funcionam, mas não usam os micro-kernels KleidiAI.
O app recusa modelos acima de min(1,6 GB, 40 % da RAM física).

Detalhes de arquitetura e limitações conhecidas: [docs/ARQUITETURA.md](docs/ARQUITETURA.md).
Roteiro de validação em aparelho físico de 4 GB (Snapdragon 695, Helio G99): [docs/VALIDACAO_APARELHO.md](docs/VALIDACAO_APARELHO.md).
