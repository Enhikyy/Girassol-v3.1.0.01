#!/usr/bin/env bash
# Verifica o APK arm64 de release: tamanho < limite (MB), uma única libllama.so/libggml.so,
# exatamente os três backends dinâmicos de CPU (armv8.0, armv8.2+dotprod+fp16+KleidiAI,
# armv8.6+i8mm) e nenhuma cópia estática do motor em libgirassol_llm*.so.
# Uso: scripts/check_apk_size.sh <apk> [limite_mb]
set -euo pipefail
apk="$1"; limit="${2:-14}"
[ -f "$apk" ] || { echo "APK não encontrado: $apk"; exit 1; }
size=$(stat -c %s "$apk")
echo "APK: $(basename "$apk") = $((size/1024)) KiB (limite ${limit} MiB)"
list="$(unzip -l "$apk" 'lib/*' | awk '{print $1, $4}' | grep '\.so$')"
echo "$list" | awk '{printf "  %8d KiB  %s\n", $1/1024, $2}'
fail=0
expect=(libllama.so libggml.so libggml-base.so libggml-cpu-android_armv8.0_1.so libggml-cpu-android_armv8.2_2.so libggml-cpu-android_armv8.6_1.so libgirassol_core.so libgirassol_llm.so)
for f in "${expect[@]}"; do
  echo "$list" | grep -q "lib/arm64-v8a/$f$" || { echo "FALHA: ausente lib/arm64-v8a/$f"; fail=1; }
done
extra=$(echo "$list" | grep -E 'libggml-cpu' | grep -v -E 'armv8\.0_1|armv8\.2_2|armv8\.6_1' || true)
[ -z "$extra" ] || { echo "FALHA: backends de CPU não esperados:"; echo "$extra"; fail=1; }
dups=$(echo "$list" | grep -c -E 'libgirassol_llm' || true)
[ "$dups" -eq 1 ] || { echo "FALHA: esperado 1 libgirassol_llm*.so, encontrado $dups"; fail=1; }
[ "$size" -lt $((limit*1024*1024)) ] || { echo "FALHA: APK >= ${limit} MiB"; fail=1; }
# v3.6 "Adão e Eva": nenhum modelo GGUF de fábrica (o usuário povoa o mundo importando .gguf)
ggufs=$(unzip -l "$apk" | awk '{print $4}' | grep -iE '\.gguf(\.part[0-9]+)?$' || true)
[ -z "$ggufs" ] || { echo "FALHA: modelos GGUF embutidos:"; echo "$ggufs"; fail=1; }
[ $fail -eq 0 ] && echo "ok: APK arm64 < ${limit} MiB, motor compartilhado + 3 backends dinâmicos"
exit $fail
