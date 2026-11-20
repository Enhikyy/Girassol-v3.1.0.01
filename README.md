# Girassol v3.1.0.01

Sociedade sintética que vive continuamente num aparelho Android: cidadãos com corpo, memória,
emoções, crenças e instituições habitam uma cidade isométrica gerada a partir de uma semente,
trabalham, trocam recursos, aprendem uns com os outros, nascem, envelhecem e morrem. Tudo roda
offline, de forma determinística, e sobrevive ao fechamento do app e ao reinício do aparelho.

Implementação feita do zero a partir do documento consolidado "PROJETO GIRASSOL".

## Módulos

| Módulo | Conteúdo |
|---|---|
| `core/` | Motor Kotlin/JVM puro (sem Android): `WorldCore` com ciclo de 12 estágios, economia por partidas dobradas, estigmergia, ADICO + cliodinâmica, inferência ativa, memória ACT-R/MemRL, imunidade epistêmica, sono/consolidação, pedagogia BKT, evolução diploide, Tsetlin, MAP-Elites/AURORA, RSI delimitado com Shadow-Replay e snapshot CBOR+gzip com SHA-256. |
| `app/` | App Android (Compose, minSdk 33, targetSdk 35): serviço em primeiro plano contínuo, persistência SQLite WAL, autodetecção de hardware, renderização isométrica em Canvas e cinco painéis (Mundo, Cidadãos, Sociedade, Mente, Sistema). |
| `app/src/main/cpp/` | Núcleo nativo C++20 via JNI: Tsetlin bit a bit idêntico ao Kotlin, MAP-Elites, navegação, mixer binaural, adaptador LoRA, sonda de SoC (HWCAP, OpenCL/Vulkan) e benchmark. |
| `native-tests/` | Testes nativos de host com ASan/UBSan, incluindo a assinatura de paridade Kotlin↔C++. |

## Compilar

Requisitos: JDK 17, Android SDK 35, NDK 27.0.12077973 e CMake 3.22.1.

```bash
./gradlew :core:test testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Testes nativos:

```bash
cd native-tests && cmake -S . -B build -G Ninja && cmake --build build && ./build/girassol_native_tests
```

O release é assinado com a keystore indicada por `GIRASSOL_KEYSTORE_PATH`, `GIRASSOL_KEYSTORE_PASSWORD`,
`GIRASSOL_KEY_ALIAS` e `GIRASSOL_KEY_PASSWORD`. Sem essas variáveis, usa a chave de debug (útil para
testes, não para publicação). No GitHub Actions, configure o secret `GIRASSOL_KEYSTORE_BASE64`
(keystore em base64) e os demais secrets com os mesmos nomes.

## Uso

1. Instale o APK e conceda a permissão de notificação (exigida pelo serviço em primeiro plano).
2. Aba Mundo: cidade isométrica com zoom/arrasto; toque num cidadão para ver o detalhe.
3. Aba Mente: injete um boato ou uma contaminação real para testar a imunidade epistêmica e force um ciclo RSI.
4. Aba Sistema: velocidade (limitada pelo nível do hardware e pela temperatura), serviço contínuo,
   diagnóstico nativo (autoteste, paridade Kotlin↔C++, benchmark), verificação de snapshot e reinício do mundo.

Detalhes de arquitetura e limitações conhecidas: [docs/ARQUITETURA.md](docs/ARQUITETURA.md).
