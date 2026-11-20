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

## O que está implementado e o que não está

Implementado e testado: motor completo, persistência e restauração, serviço contínuo com reinício no boot,
núcleo nativo com paridade verificada, autodetecção de hardware, painéis e teste de estresse epistêmico.

Diferenças em relação ao documento original, de forma explícita:

- **Sem LLM local (llama.cpp/GGUF/Llamatik).** A deliberação usa `SymbolicLookaheadDeliberator`, que
  implementa a interface `Deliberator`; um backend de LLM pode ser plugado nessa interface depois. Não há
  modelos de linguagem embarcados no APK.
- **Sem Filament/3D.** A cidade é desenhada em Canvas isométrico 2D.
- **LoRA, mixer binaural e navegação nativos** estão compilados e expostos via JNI, mas o motor do mundo usa
  as versões Kotlin para manter o determinismo idêntico entre aparelhos.
- **RSI** usa replay de comprimento fixo (240 ticks) para que a decisão de promoção seja a mesma em qualquer aparelho.
- O app não foi testado em aparelho físico neste ambiente; os testes rodam na JVM, no host (C++) e no lint/build do Android.
