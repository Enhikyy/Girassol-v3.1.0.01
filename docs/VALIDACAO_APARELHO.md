# Validação rápida em aparelho físico de 4 GB (v3.3.0.00)

Roteiro para conferir o Girassol fora do emulador, pensado para Snapdragon 695 (Cortex-A78 + A55, Adreno 619)
e Helio G99 (Cortex-A76 + A55, Mali-G57). Leva cerca de 30 minutos.

## 1. Preparar

```bash
# APK de release (arm64-v8a) do artefato "girassol-apks" do GitHub Actions, ou:
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm grant com.enhikyy.girassol android.permission.POST_NOTIFICATIONS
adb logcat -c && adb logcat -v time -s GirassolLLM GirassolGpu GirassolGpuProbe GirassolMem GirassolFilament GirassolHub
```

Se já existir uma instalação com outra assinatura (debug ↔ release), exporte antes o snapshot
(aba Sistema → "Assinatura do APK" → **Exportar .grs**), desinstale, instale o novo APK e use **Importar .grs**.

## 2. CPU e llama.cpp

Aba Sistema → "CPU ARM64 e motor LLM":

| Item | Snapdragon 695 / Helio G99 | Aparelho ARMv9 (A510/A710/X2+) |
|---|---|---|
| SIMD em runtime | `NEON=1 DOTPROD=1 FP16=1 I8MM=0` | `... I8MM=1` |
| Variante | `dotprod · armv8.2-a+dotprod+fp16+kleidiai` | `i8mm · armv8.2-a+dotprod+fp16+i8mm+kleidiai` |

- Se aparecer `I8MM=1` num A78/A76, anote o modelo: seria um bug de detecção.
- Importe um GGUF pequeno na aba Mente (ex.: Qwen2.5-0.5B-Instruct Q4_0 ou Q4_K_M) e toque em
  **Medir tokens/s**. Anote `pp` (prompt) e `tg` (geração). Q4_0 aproveita mais o repacking/KleidiAI.
- No logcat, `GirassolLLM` mostra a linha `SIMD ... → variante ...` e o `llama_print_system_info`.
- Nenhum `SIGILL` deve aparecer (`adb logcat -b crash`).

## 3. GPU e estética Kodak/Dehancer

Aba Sistema → "GPU e efeitos visuais":

- `GL_RENDERER` esperado: `Adreno (TM) 619` ou `Mali-G57 MC2`.
- Na primeira abertura o app calibra (o 3D fica em Eco 2D por alguns segundos). O nível esperado é
  `completo` ou `reduzido`. O log `GirassolGpu` mostra cada nível testado pela sonda (processo `:gpuprobe`).
- Na aba Mundo, alterne para 3D: o rótulo mostra `FX <nível> · <GL_RENDERER>` e o FPS na faixa de telemetria
  deve ficar perto de 30. Confira bloom, halação vermelho-alaranjada nas luzes, grão e gradação tungstênio.
- Se o nível cair sozinho durante o uso, o `FpsGovernor` mediu menos de 20 fps por 8 s (log `GirassolFilament`).
- **Recalibrar** refaz a sonda (útil depois de atualizar o driver/sistema).

## 4. Memória e disjuntor

Aba Sistema → "Memória real e disjuntor":

- `Limite VmRSS` deve mostrar `1800 MB` num aparelho de 4 GB (o Android reporta ~3,6–3,8 GB de RAM total).
- Com o modelo carregado, anote `VmRSS` e `VmHWM`. Em Q4 0,5 B o RSS fica tipicamente bem abaixo de 1 GB;
  modelos de 1,5 B se aproximam do limite.
- **Testar disjuntor**: o estado vira `ABERTO`, a aba Mente mostra o LLM descarregado e o mundo continua
  andando (deliberador simbólico). **Rearmar** recarrega o modelo.
- Teste de pressão real (opcional): abra vários apps pesados ou rode
  `adb shell am send-trim-memory com.enhikyy.girassol RUNNING_CRITICAL` com o app em primeiro plano;
  o log `GirassolMem` deve mostrar `DISJUNTOR ABERTO`.
- Térmico ADPF: deixe o 3D rodando 15 min e acompanhe `Térmico ADPF · folga 10 s`. Com status ≥ 3 o app
  passa sozinho para Eco 2D.

## 5. Assinatura

- APKs do CI sem os secrets de keystore saem com a **chave de debug** (aviso amarelo). Para publicação,
  configure `GIRASSOL_KEYSTORE_BASE64` e os demais secrets; a aba deve mostrar "keystore de release" e o
  SHA-256 do certificado.

## 6. O que registrar

Modelo do aparelho, versão do Android, flags SIMD, variante, `pp`/`tg` em tokens/s (com o nome do GGUF),
`GL_RENDERER`, nível de efeito calibrado, FPS médio no 3D, VmRSS/VmHWM com e sem modelo e o status térmico
após 15 min. Anexe o logcat filtrado acima ao abrir uma issue.
