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

## Módulos nativos e portas (v3.2)

O `core` continua JVM puro. Os módulos nativos entram por interfaces em `core/ports` (`PathfinderPort`,
`LoraPort`, `NeuralDeliberatorPort`), montadas pelo `SimulationHub`. Cada porta tem implementação Kotlin de
referência; a versão C++ só é usada depois que `NativeModules.verifyParity` confirma resultados idênticos
nos vetores de `ParityVectors`. Ligar/desligar um módulo na aba Sistema recria o `WorldCore` a partir do mesmo
estado.

### Motor cognitivo híbrido (llama.cpp + GBNF)

- `llm/llm_engine.cpp` liga o llama.cpp `v1.0.0` (CMake/NDK, `arm64-v8a`, GGML portátil armv8-a) ao JNI.
  Modelo carregado com `mmap` (sem `mlock`, sem GPU), `n_ctx = 1024`, lote 256, threads = metade dos núcleos (2 a 4).
- Toda geração usa gramática GBNF (`NeuralProtocol`) e decodificação gulosa; a saída é ainda validada por um
  parser estrito. Saída inválida é descartada e o cidadão usa o deliberador simbólico.
- Determinismo: a inferência roda fora da thread do mundo. O resultado aprovado é drenado no estágio 1 do tick
  seguinte e gravado como evento canônico `LLM_DECISAO` (ação, fala, tick do pedido). O replay a partir do log é
  bit a bit idêntico; a sequência ao vivo depende do tempo de inferência do aparelho.
- Fallback automático para `SymbolicLookaheadDeliberator`: sem `.gguf`, tier térmico ≥ 2, `onTrimMemory`, RAM
  livre baixa ou erro de carga. Shadow-Replay do RSI e sandbox LoRA usam sempre o simbólico.
- Falas e pensamentos (LLM ou modelos simbólicos) aparecem como balões na aba Mundo.

### Renderização 3D (Filament 1.75.1) e Modo Eco 2D

- `render3d/FilamentCityRenderer` desenha a cidade brutalista/liminar gerada pela semente (`CityMeshBuilder`):
  lajes em balanço, corredores, janelas emissivas, luzes fluorescentes. Material `unlit` com iluminação e
  oclusão pré-calculadas por vértice (barato em GPUs Mali/Adreno de entrada).
- Pós-processamento "Kodak 500T/70mm": bloom suave, halation vermelho-alaranjado (sprites aditivos + bloom),
  vinheta, neblina, FXAA, gradação Filmic com balanço de branco tungstênio, sombras profundas e granulação
  (overlay Compose em `BlendMode.Overlay`). Limitado a 30 fps com resolução dinâmica.
- `OrbitCamera` (órbita, pinça, rotação, pan) e `Raycast` (raio × AABB dos cidadãos, testado em JVM).
- `RenderModePolicy`: 3D/2D manual; 2D obrigatório em segundo plano, tier térmico ≥ 3 ou falha de GLES.

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

## Testes e CI

- `native-tests`: ASan/UBSan em duas configurações (sem llama; com llama.cpp + GGUF `stories260K` e gramática).
- `:core:test`, `:app:testDebugUnitTest` (câmera/raycast), `:app:lintDebug`, `compileFilamentMaterials`,
  APKs debug e release.
- `androidTest` em emulador x86_64 (API 34): aba Mundo (alternância 3D/2D, toque em cidadão) e aba Sistema
  (diagnóstico C++, benchmark, chaves dos módulos nativos).

## Limitações conhecidas

- Não testado em aparelho físico de 4 GB; o orçamento de RAM e os 30 fps são estimativas de projeto.
  O emulador usa GPU por software (swiftshader); se o GLES3 falhar, o teste valida a queda para o 2D.
- Filament fixado em 1.75.1: versões ≥ 1.76 exigem compileSdk 37, e o AGP 8.7.3 suporta até o 35.
- GGML compilado para armv8-a portátil (sem dotprod/i8mm específicos, KleidiAI e OpenMP desligados):
  compatível com qualquer arm64, mas não é o máximo de tokens/s possível.
- O LLM só atua no mundo ao vivo; RSI e sandbox LoRA usam o deliberador simbólico para manter replay fixo.
- Os símbolos do llama.cpp ficam exportados em `libgirassol_core.so`.
- Iluminação do 3D é pré-calculada (sem sombras dinâmicas) para caber no orçamento térmico 24/7.
