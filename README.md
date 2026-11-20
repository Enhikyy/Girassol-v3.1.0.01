# Girassol v3.2.0.00

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
| `app/src/main/cpp/` | Núcleo nativo C++20 via JNI: llama.cpp (GGUF, mmap, gramáticas GBNF), A* em grade usado pelo movimento, LoRA on-device treinado no sono, áudio binaural em AAudio, Tsetlin bit a bit idêntico ao Kotlin, MAP-Elites, sonda de SoC e benchmark. |
| `app/src/main/materials/` | Materiais Filament (`city.mat`, `glow.mat`) compilados para `.filamat` pelo `matc` na task Gradle `compileFilamentMaterials`. |
| `native-tests/` | Testes nativos de host com ASan/UBSan, incluindo a assinatura de paridade Kotlin↔C++. |

## Compilar

Requisitos: JDK 17, Android SDK 35, NDK 27.0.12077973 e CMake 3.22.1.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:compileFilamentMaterials \
          :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Na primeira execução o Gradle baixa e verifica por SHA-256 o código do llama.cpp (tag `v1.0.0`) e as
ferramentas do Filament 1.75.1 (`matc`) em `build/third_party/`. Para usar cópias locais:
`-Pgirassol.llamaDir=/caminho/llama.cpp -Pgirassol.matc=/caminho/matc`. `-Pgirassol.abis=x86_64` troca as ABIs
(útil para o emulador).

Testes de instrumentação (aparelho ou emulador conectado): `./gradlew :app:connectedDebugAndroidTest`.

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
   diagnóstico C++ (autoteste, paridade, benchmark de CPU), verificação de snapshot e reinício do mundo.
5. A faixa de telemetria acima da barra flutuante mostra FPS do Filament, estado do llama.cpp, ciclos LoRA e RSS.

Modelos recomendados para 4 GB: GGUF de até ~1,5 B parâmetros em Q4 (ex.: Qwen2.5-0.5B/1.5B-Instruct Q4_K_M).
O app recusa modelos acima de min(1,6 GB, 40 % da RAM física).

Detalhes de arquitetura e limitações conhecidas: [docs/ARQUITETURA.md](docs/ARQUITETURA.md).
