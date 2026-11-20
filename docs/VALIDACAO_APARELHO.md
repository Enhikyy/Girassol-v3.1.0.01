# Validação em aparelho físico de 4 GB — checklist de 15 minutos (v3.4.0.00)

Roteiro para conferir o Girassol fora do emulador, pensado para Snapdragon 695 (Cortex-A78 + A55, Adreno 619)
e Helio G99 (Cortex-A76 + A55, Mali-G57). Cada bloco tem tempo-alvo; o total fica em torno de 15 minutos.
Marque `[x]` no que passou e anote os números pedidos em **Registrar**.

## 0. Preparar (2 min)

```bash
# APK arm64 do artefato "girassol-apks" (ou o .aab do artefato "girassol-aab"), ou localmente:
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb shell pm grant com.enhikyy.girassol android.permission.POST_NOTIFICATIONS
adb logcat -c && adb logcat -v time -s GirassolLLM GirassolGpu GirassolGpuProbe GirassolMem GirassolFilament GirassolHub GirassolADPF GirassolTTS
```

- [ ] APK arm64 < 14 MB (`ls -l` no arquivo; o CI também verifica com `scripts/check_apk_size.sh`).
- [ ] Se já existia instalação com outra assinatura: **Exportar .grs** antes, desinstalar, instalar e **Importar .grs**.
- Para o `.aab`: `bundletool build-apks --bundle app-release.aab --output g.apks --connected-device && bundletool install-apks --apks g.apks`.

## 1. CPU, backend dinâmico e llama.cpp (3 min)

Aba Sistema → "CPU ARM64 e motor LLM":

| Item | Snapdragon 695 / Helio G99 | ARMv9 (A510/A710/X2+) |
|---|---|---|
| SIMD em runtime | `NEON=1 DOTPROD=1 FP16=1 I8MM=0` | `... I8MM=1` |
| Backend carregado | `libggml-cpu-android_armv8.2_2.so` (dotprod+fp16+KleidiAI) | `libggml-cpu-android_armv8.6_1.so` (i8mm) |

- [ ] Nenhum `SIGILL` em `adb logcat -b crash`.
- [ ] Importar um GGUF **Q4_0** (Regra de Ouro KleidiAI; ex.: Qwen2.5-0.5B-Instruct Q4_0). A linha
      "Quantização" deve dizer `compatível KleidiAI`; um Q4_K_M mostra `prefira Q4_0/Q8_0`.
- [ ] "KV-cache · flash-attn": em 4 GB deve aparecer `q4_0` (8/12 GB: `q8_0`). Modelos cujo head_dim não é
      múltiplo de 32 caem para `f16` — o app registra o fallback na descrição.
- [ ] "Modo lazy (≤ 4 GB)": `ativo`. O modelo só carrega na primeira deliberação e é liberado após 90 s ocioso.
- [ ] **Medir tokens/s** → anote `pp` e `tg`.
- [ ] Depois de alguns minutos, "Prefixo/slots reaproveitados" > 0 (prefixo imutável do mundo + slot por
      cidadão) e "Fast-forward GBNF" > 0 (tokens forçados pela gramática sem amostragem).

## 2. Mundo 3D, ORCA e colisões (3 min)

Aba Mundo → 3D:

- [ ] `GL_RENDERER` esperado: `Adreno (TM) 619` ou `Mali-G57 MC2`; FPS perto de 30.
- [ ] Cidadãos andam em posições contínuas (sem saltos de tile) e **não atravessam edifícios**.
- [ ] Aba Mente → "Corpo, movimento e genética": `ORCA: passos ajustados` e `Colisões resolvidas` crescem;
      cortisol/ocitocina/dopamina mudam com o dia/noite e com a fome.

## 3. Memória episódica e busca FTS (2 min)

Aba Mente → "Memória episódica em 2 estágios":

- [ ] `Consultas` cresce; `Estágio 1` (candidatos por Hamming) é bem menor que `Corpus`.
- [ ] Buscar `horta` → resultados com `FTS5 bm25()` (ou `FTS4 + BM25 Kotlin` se o SQLite do sistema não
      tiver FTS5). A busca só enxerga memórias já gravadas no snapshot (a cada 600 ticks).
- [ ] Aba Sistema → "Módulos nativos C++": `Memória binária bit[256]` com `popcount C++ = Kotlin`.

## 4. Memória real, disjuntor e energia (3 min)

Aba Sistema:

- [ ] "Memória real e disjuntor": `Limite VmRSS` = `1800 MB` em 4 GB. **Testar disjuntor** → `ABERTO`, LLM
      descarregado (slots apagados), mundo segue no deliberador simbólico. **Rearmar** volta ao normal.
- [ ] Opcional: `adb shell am send-trim-memory com.enhikyy.girassol RUNNING_CRITICAL` → log `DISJUNTOR ABERTO`.
- [ ] "Energia e ADPF": `PARTIAL_WAKE_LOCK ativo`, `WifiLock FULL_LOW_LATENCY ativo`, temperatura em °C e
      `ADPF: sessão ativa (2 threads)` com `girassol-sim` e `girassol-llm` (o LLM entra após a 1ª geração).
      Se o aparelho não suportar ADPF, a linha diz `ADPF indisponível` — isso não é falha.

## 5. Áudio e voz (1 min)

- [ ] Ligar "Áudio binaural (AAudio)": zumbido estéreo; blips de fala espacializados.
- [ ] Ligar "Vozes TTS offline (pt-BR)": as falas são lidas com pan estéreo pela posição do cidadão e a trilha
      Trautonium **abaixa** enquanto a voz toca (ducking). Sem voz pt-BR instalada, a linha avisa
      `sem dados de voz pt-BR instalados` (instale em Configurações → Sistema → Idiomas → Saída de texto para fala).

## 6. Estabilidade (1 min, depois deixar rodando)

- [ ] Deixar 15 min em 3D com o LLM ligado. Com status térmico ≥ 3 o app passa sozinho para Eco 2D e o
      LLM pausa; a EFE passa a pesar `C_neural(T_SoC)` com a temperatura medida.
- [ ] Bloquear a tela 5 min: ao voltar, o tick avançou (serviço em primeiro plano + wake lock).

## Registrar

Modelo do aparelho, Android, flags SIMD, backend carregado, GGUF usado, `pp`/`tg`, tipo de KV-cache,
tokens reaproveitados/forçados, `GL_RENDERER`, FPS médio, VmRSS/VmHWM com e sem modelo, status térmico e
temperatura após 15 min, estado ADPF e se a voz TTS offline funcionou. Anexe o logcat filtrado ao abrir uma issue.

## Assinatura

APKs/AAB do CI sem os secrets saem com a **chave de debug** (aviso amarelo na aba Sistema). Para publicação,
configure `GIRASSOL_KEYSTORE_BASE64`, `GIRASSOL_KEYSTORE_PASSWORD`, `GIRASSOL_KEY_ALIAS` e
`GIRASSOL_KEY_PASSWORD`; o APK e o `.aab` passam a ser assinados com a keystore de release.
