# Calibração de renderização v3.6 (anti-clarão Mali-G57 / Adreno)

Relato de campo (SM-A226BR, Mali-G57 MC2, 3631 MB): tela branca estourada em 3D. Causa: exposição
alta + bloom forte com limiar baixo somados ao brilho emissivo noturno e aos sprites aditivos.

| Parâmetro | v3.5 | v3.6 | Onde |
|---|---|---|---|
| Exposição | `setExposure(1.0)` | f/16 · 1/125 s · ISO 100 | `FilamentCityRenderer.CAMERA_*` |
| Força do bloom | 0,22 | 0,18 | `BLOOM_STRENGTH` |
| Limiar do bright-pass (linear) | — | 1,15 | `BLOOM_THRESHOLD_LINEAR` |
| Joelho do bright-pass pós-Filmic | — | 0,82 | `BRIGHT_KNEE_LDR` (material `girassol_dehancer_post`) |
| Clamp de altas luzes | 1000 (sem efeito) | 10 (mínimo da API) | `BLOOM_HIGHLIGHT_CLAMP` |
| Emissivo noturno · intensidade do brilho | 6,0 · 2,4 | 1,4 · 0,55 | `NIGHT_EMISSIVE` |
| Sprites de brilho | aditivo sem teto | blend "screen" com teto 0,45; máx. 240 | `glow.mat`, `glowSprites` |

Limitações: o emulador da CI roda com `EffectLevel.NONE` (GL por software), então a validação visual do
bloom só é possível em aparelho real; a cena é não iluminada (unlit) com gradação de cor no pós-processamento.
