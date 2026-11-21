# Girassol — cruzamento técnico de especificações e estado executável

**Auditoria em:** 21/11/2026  
**Repositório auditado:** `conta14dinheiro-droid/Girassol-v3.1.0.01`  
**Branch de trabalho:** `feat/dados-dashboard-ux`  
**Regra de evidência:** um módulo só é considerado integrado quando há implementação no caminho de execução, teste pertinente e documentação compatível. Nomes de classes ou propostas em documentos não provam integração.

Este documento cruza a especificação v2.5, o material de pesquisa de autoaperfeiçoamento, o registro de progresso legado, o consolidado histórico e o prompt GEA com o código que existe neste repositório. Ele substitui qualquer leitura automática de percentuais herdados de outro repositório.

## 1. Decisões que governam esta continuação

1. O repositório atual continua sendo a autoridade para arquitetura, APIs, formatos persistidos e comportamento. A documentação v2.0/v2.5 é referência de direção, não uma autorização para trocar o projeto por outro.
2. Preservar Kotlin/JVM puro no motor determinístico, C++20/JNI nos módulos nativos, Google Filament, Compose, SQLite WAL, llama.cpp e Sherpa-ONNX.
3. Não duplicar motores que já existem. Melhorar integração, persistência, limites de recursos, observabilidade e testes antes de adicionar dependências.
4. Não ativar OpenCL/Vulkan para LLM apenas por flag global: o código atual desliga explicitamente esses backends e usa CPU dinâmica + KleidiAI quando disponível. Uma ativação futura exige compilação reproduzível, sondagem de dispositivo, fallback e testes multi-SoC.
5. Toda alteração de código/pesos gerada por pesquisa/autoaperfeiçoamento deve ficar em sandbox isolado, passar por testes cegos e avaliação held-out e exigir aprovação humana. O app não deve reescrever seu próprio APK, fonte ou pesos base em produção.
6. Não usar estimativas de papers/preprints como benchmark local. Medir CPU, RAM, temperatura, áudio e consumo nos aparelhos reais.

## 2. Cruzamento de implementação

| Frente dos anexos | Evidência no repositório | Estado verificável | Trabalho restante |
|---|---|---|---|
| Quatro camadas de memória | `core/memory/SleepConsolidationAndImmuneEngine.kt`, `core/memory/EpisodicIndex.kt`, `WorldCore.kt`, `WorldModel.kt` | **Integrada, grafo relacional inicial** | Recuperação episódica Hamming/RRF/BM25/ACT-R/MemRL/Hawkes está no loop; a consolidação do sono cria nós `SEMANTIC` e `PROCEDURAL` com arestas em `linkedMemoryIds`, entidades em `entityIds` e proveniência rastreável até os episódios verificados. O buffer de trabalho é uma lista ordenada de IDs no próprio `Citizen`, limitada a 5–9 slots, isolada por agente e serializada no snapshot. Ainda faltam tipos de aresta semântica mais ricos, consultas de grafo dedicadas e validação de conhecimento causal; não é ainda um knowledge graph completo. |
| Tsetlin persistente e paridade | `core/reflex/TsetlinMachine.kt`, `app/src/main/cpp/cognitive/tsetlin_machine.hpp`, `native-tests/native_tests.cpp`, `core/.../TsetlinParityTest.kt` | **Integrada** | Há feedback I/II, estados serializáveis GRST V2, early-exit, seed, especificidade e estado do PRNG preservados na restauração; o leitor mantém compatibilidade V1. A expansão do vetor de atributos do agente ainda exige nova versão do formato e migração do estado persistido. |
| BKT / FSRS / IRT / Voyager | `core/biology/PedagogyAndDiploidEvolutionEngine.kt` | **Integrada no motor** | Coberta em `GeaDeterministicEnginesTest.kt`. Evoluir telemetria explicável de domínio por habilidade e revisão, sem duplicar as fórmulas existentes. |
| Active Inference / PIANO | `core/cognition/ActiveInferencePianoEngine.kt`, `WorldCore.kt` | **Integrada, orquestração parcial** | VFE, energia livre esperada, risco pragmático, ganho epistêmico e custo neural são usados na escolha de políticas. O workspace prioriza sinais, mas não há um scheduler concorrente de módulos PIANO independente; a simulação continua serial e determinística. |
| Deffuant/FJ, confiança, Dunbar, ADICO e PSI | `core/social/SocietyAdicoCliodynamicsEngine.kt`, `WorldCore.kt` | **Integrada** | Laços, confiança por domínio, opinião, regras institucionais, sanções, assembleia e cliodinâmica são chamados no ciclo diário. Melhorias futuras devem cobrir invariantes e cenários de polarização/colapso, não criar uma segunda sociedade. |
| Economia emergente | `core/world/DoubleEntryEconomyAndStigmergy.kt`, `WorldCore.kt`, `core/.../SubjectiveMarketTest.kt` | **Parcial, integrada** | Ledger em micro-unidades, soma zero, comuns e jobs existem. O escambo de alimento agora calcula preço marginal a partir de urgência, inventário, excedente, escassez e histórico de partidas dobradas; a troca alimento/crédito é liquidada atomicamente em duas pernas e tem testes. Contratos/dívidas, impostos configuráveis e negociação para outros recursos ainda não existem. |
| MAP-Elites / AURORA | `core/qd/MapElitesAurora.kt`, `WorldCore.kt`, `WorldModel.kt` | **Integrada** | O encoder projeta descritores, o arquivo recebe candidatos no ciclo diário e métricas são gravadas. Falta uma auditoria de persistência/migração do arquivo e teste de estabilidade em execuções longas. |
| Navegação Recast/Detour e ORCA | `core/world/CrowdNavigation.kt`, `core/world/Pathfinding.kt`, `app/src/main/cpp/navigation/grid_astar.hpp`, `WorldCore.kt` | **Parcial, integrada** | O app usa grade/A* com clearance e ORCA/RVO2 para colisões locais. Recast/Detour não está integrado. A troca por NavMesh só deve ocorrer se o modelo espacial 2.5D atual não atender a uma necessidade confirmada. |
| Áudio binaural/HRTF | `app/src/main/cpp/audio/binaural_hrtf_spatializer.hpp`, `ambient_binaural_synth.hpp`, `aaudio_engine.cpp`, `AmbientAudioController.kt` | **Parcial, executável** | AAudio mistura som ambiente, blips espaciais, ITD/ILD aproximado, atenuação e ducking. Não é uma implementação de convolução com HRIR medido por pessoa; precisa de validação auditiva e de performance em aparelhos/fones reais. |
| LLM local, KV e perfis | `app/src/main/cpp/llm/llm_engine.cpp`, `app/src/main/java/.../llm/LocalLlmManager.kt`, `app/src/main/cpp/CMakeLists.txt` | **CPU integrada; GPU LLM não ativa** | Perfis 4/8/12 GB escolhem contexto e KV q4_0/q8_0; Flash Attention e KV quantizado têm fallback para F16. CPU dinâmica, HWCAP e KleidiAI opcional estão configurados. OpenCL e Vulkan estão explicitamente OFF no build atual. |
| llguidance | `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/llm/llg_sampler.cpp`, `app/build.gradle.kts` | **Opcional com fallback** | O CI compila o crate Rust; o build `auto` pode seguir com GBNF se não conseguir compilar. A disponibilidade efetiva deve ser verificada em runtime, não inferida pelo nome da tarefa. |
| Treino LoRA | `app/src/main/cpp/llm/lora_ondevice.cpp`, `core/forge/LoraAdapter.kt`, `WorldCore.kt` | **Treino real, escopo parcial** | Há forward/backward esparso sobre o caminho da RMSNorm final, Adam, arquivo GGUF, avaliação held-out e restauração do estado em rejeição. Não é OPLoRA ortogonal geral, EWC/Fisher completo ou atualização de todas as matrizes Transformer. |
| SenseVoice / Pocket-TTS | `app/build.gradle.kts`, `AudioTranscriber.kt`, `PocketTtsVoice.kt`, `CitizenVoice.kt` | **Dependências e integração parcial** | Downloads são verificados por SHA-256 e os modelos são empacotados conforme distribuição. Precisam de teste real de fala/ASR, perfil de RAM e qualidade pt-BR em hardware. |
| MeshSync | `app/.../mesh/MeshSyncManager.kt`, `core/mesh/MeshSyncProtocol.kt` | **LAN autenticada, CRDT ainda não** | NSD/mDNS + beacon UDP, TCP, HMAC, anti-replay, snapshot diferencial e migração existem. O transporte atual usa rede Wi-Fi local; não é Wi-Fi Direct com CRDT/HLC de eventos concorrentes. |
| RSI e autoaperfeiçoamento | `core/rsi/BoundedRsiEngine.kt`, `core/rsi/RsiCoordinator.kt`, `WorldCore.kt` | **Promoção de política delimitada, não autoedição de software** | O gate avalia invariantes, diversidade, Brier e ganho em Shadow-Replay; agora rejeita métricas não finitas/fora do domínio, trata janelas sem previsões como evidência ausente (`NaN`) e impede o CMA-ES de ranquear medições inválidas. `GeaDeterministicEnginesTest` cobre rejeição sem calibração e entradas inválidas. O `safe_loop.py` da pesquisa é um esqueleto testado com componentes simulados, não uma implementação de produção. Falta ligar uma esteira externa isolada com juiz cego, held-out, relatório e aprovação humana antes de qualquer mudança persistente. |

## 3. Recursos e segurança de execução

### Perfis de memória

A política implementada em `LocalLlmManager.kt` usa aproximadamente:
- perfil até 5,5 GiB de RAM física: contexto 1.024, KV q4_0 e lazy unload;
- até 10 GiB: contexto 2.048, KV q8_0;
- acima disso: contexto 4.096, KV q8_0.

Esses são parâmetros de software, não provas de que modelos específicos caberão em todos os aparelhos. A admissão do modelo usa estimativa de RAM ativa e pode rejeitar arquivos. O teste físico ainda precisa medir pico real, LMK e aquecimento.

### Backends

O CMake atual define `GGML_VULKAN OFF` e `GGML_OPENCL OFF`. A sonda `dlopen` detecta a existência de bibliotecas, mas detectar um driver não prova que um backend tensorial esteja funcionando. Não mudar essas flags para ON sem:
- matriz de CI por ABI e toolchain;
- fallback por backend e dispositivo;
- teste de inferência e paridade;
- medição de memória/temperatura;
- confirmação de que o APK não ganhou dependência rígida de `libOpenCL.so`/Vulkan em aparelhos incompatíveis.

### Pesquisa e autoaperfeiçoamento

Os números de ganhos no arquivo de pesquisa são declarações atribuídas aos autores; a própria pesquisa registra preprints, resultados não replicados e partes não testadas. O pipeline proposto para o Girassol deve separar geração, avaliação cega, calibragem externa, held-out, monitor de risco, registro e aprovação humana. Nunca substituir o gate de segurança por uma pontuação autoatribuída pelo agente.

## 4. Prioridade de implementação

| Prioridade | Entrega | Critério de aceite |
|---|---|---|
| P0 | CI e build reproduzível; corrigir ativos externos e checksums oficiais | `:core:test`, `:app:testDebugUnitTest`, lint, compilação nativa, APK e AAB no CI; checksums ainda obrigatórios |
| P1 | Painel Dados e observabilidade coerente | métricas derivadas do `DashboardSnapshot`; testes Compose; sem dados sintéticos em produção |
| P1 | Crosswalk dos módulos, migrações e contratos persistidos | versões de schema/eventos documentadas e testes de roundtrip/migração |
| P2 | Fechar lacunas de memória semântica/procedural e economia | ownership, proveniência, persistência, invariantes e testes de replay |
| P2 | Harness externo de RSI com lineage archive, dev/held-out, monitor, juiz A/B cego e aprovação humana | testes stdlib no CI; callbacks reais devem executar em runner isolado e decisões são registradas em JSONL |
| P3 | Avaliar P2P CRDT/HLC e GPU LLM por device tier | protótipos reversíveis, builds isolados e medições por SoC |
| P3 | Validar HRTF, áudio, estabilidade 24/7 e performance | instrumentação/hardware real; CI sozinha não prova qualidade sensorial ou térmica |

## 5. O que não será tratado como conclusão

- Abrir uma classe, tabela, arquivo ou flag não prova que o recurso é usado pelo mundo vivo.
- Passar em teste unitário não comprova estabilidade 24/7, comportamento térmico nem experiência acústica em aparelhos reais.
- Uma execução bem-sucedida do pipeline native-tests não comprova que o APK foi compilado.
- O registro de progresso que menciona `conta13dinheiro-gif/Girassol-v2.0` e commits antigos é histórico de outro repositório. Ele não é a fonte de verdade para esta auditoria.
- Não será emitido percentual global de conclusão sem uma definição de denominador e evidência completa por requisito. O estado acima usa classificação por frente e evidência específica.

## 6. Verificações da branch

As validações atuais são rastreadas no Pull Request #1 e em GitHub Actions. A última alteração de checksum do Pocket-TTS atualiza o digest oficial publicado pelo projeto sherpa-onnx em 21/11/2026, mantendo a checagem criptográfica. A conclusão do novo build Android ainda depende do CI desta branch.


## 6. Correções de integração desta branch

- **Tsetlin GRST V2:** o snapshot agora registra os hiperparâmetros e o estado do SplitMix64; a restauração retoma o mesmo fluxo determinístico de treino. O leitor Kotlin/C++ continua aceitando o formato V1 legado. Foram adicionados testes de round-trip, continuidade de treino, rejeição de estado corrompido e leitura V1.
- **Memória de trabalho por cidadão:** os IDs dos episódios ativos deixaram de viver num buffer transitório compartilhado pelo motor. Cada cidadão agora possui seu próprio buffer ordenado, com capacidade configurável entre 5 e 9, e o snapshot CBOR persiste esse estado. A recuperação remove referências obsoletas, deduplica e mantém LRU por cidadão.
- **Grafo semântico/procedural inicial:** a consolidação do sono agora retorna metadados de relação para resumos `SCHEMA` e macros `SKILL_MACRO`; o mundo grava arestas para os episódios verificados, preserva entidades e atualiza nós antigos na próxima consolidação. Isso estabelece proveniência relacional sem afirmar que já existe um grafo semântico causal completo.
- **Preço subjetivo e liquidação bilateral:** o escambo entre cidadãos deixou de usar preço fixo. A cotação utiliza necessidade/estoque do comprador, excedente do vendedor, reservas dos comuns e média móvel de transações anteriores recuperadas do ledger. O novo `exchange` pré-valida as duas pernas antes de alterar saldos; contratos, dívidas e impostos seguem pendentes.
- **Harness externo de autoaperfeiçoamento:** `tools/guarded_rsi/safe_loop.py` mantém arquivo de linhagens, seleção Thompson, métricas dev/held-out, monitor, comparação A/B com ordem aleatória, aprovação humana e auditoria JSONL. Os testes usam callbacks simulados. O harness declara explicitamente que não é sandbox nem executa código; integração com gerador/avaliadores reais depende de runner isolado sem segredos/rede e de aprovação específica.
- **Limite da evidência:** as alterações precisam passar pelo CI desta branch. A validação em emulador/aparelhos físicos e a carga 24/7 continuam sendo etapas separadas.
