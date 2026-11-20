#!/usr/bin/env bash
# Verifica que as bibliotecas do Girassol (libgirassol_*.so) dentro dos APKs exportam apenas
# JNI_OnLoad e Java_* — símbolos internos do llama.cpp/ggml/KleidiAI devem estar ocultos.
# Uso: scripts/check_native_symbols.sh <apk>...   (requer ANDROID_NDK_HOME ou NDK em $ANDROID_HOME/ndk/27.0.12077973)
set -euo pipefail
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/sdk/android}/ndk/27.0.12077973}"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[ -x "$READELF" ] || READELF="$(command -v llvm-readelf || command -v readelf)"
fail=0; checked=0
for apk in "$@"; do
  tmp="$(mktemp -d)"
  unzip -qo "$apk" 'lib/*/libgirassol_*.so' -d "$tmp" || { echo "sem libgirassol_*.so em $apk"; fail=1; continue; }
  for so in "$tmp"/lib/*/libgirassol_*.so; do
    checked=$((checked+1))
    syms="$("$READELF" --dyn-syms -W "$so" | awk '$7 != "UND" && ($5 == "GLOBAL" || $5 == "WEAK") && $8 != "" {print $8}' | sed 's/@.*//' | sort -u)"
    bad="$(echo "$syms" | grep -v -E '^(JNI_OnLoad|Java_.*)$' | grep -v '^$' || true)"
    n="$(echo "$syms" | grep -c . || true)"
    size="$(stat -c %s "$so")"
    abi="$(basename "$(dirname "$so")")"
    if [ -n "$bad" ]; then
      echo "FALHA $(basename "$apk") $abi/$(basename "$so"): símbolos internos exportados:"; echo "$bad" | head -20; fail=1
    else
      echo "ok    $(basename "$apk") $abi/$(basename "$so"): $n símbolos exportados (JNI), $((size/1024)) KiB"
    fi
  done
  rm -rf "$tmp"
done
[ "$checked" -gt 0 ] || { echo "nenhuma biblioteca verificada"; exit 1; }
exit $fail
