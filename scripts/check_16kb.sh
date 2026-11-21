#!/usr/bin/env bash
# v3.8 — páginas de 16 KB (Android 15+): cada .so arm64-v8a/x86_64 precisa de segmentos LOAD
# alinhados a 0x4000 e estar alinhada a 16 KB e sem compressão dentro do APK.
set -euo pipefail
BT="${ANDROID_HOME:-$HOME/android}/build-tools/35.0.0"
ZIPALIGN="$BT/zipalign"
READELF="$(command -v llvm-readelf || command -v readelf)"
fail=0
for apk in "$@"; do
  echo "== $apk"
  "$ZIPALIGN" -c -P 16 -v 4 "$apk" > /tmp/zipalign.log 2>&1 || { echo "ERRO: zipalign -P 16 falhou"; grep -v "OK" /tmp/zipalign.log | tail -20; fail=1; }
  tmp="$(mktemp -d)"
  unzip -q -o "$apk" 'lib/*' -d "$tmp" || true
  while IFS= read -r so; do
    bad="$("$READELF" -lW "$so" | awk '$1=="LOAD"{print $NF}' | while read -r a; do [ $((a)) -lt 16384 ] && echo "$a"; done || true)"
    if [ -n "$bad" ]; then echo "ERRO: ${so#$tmp/} LOAD alinhado a $bad (< 0x4000)"; fail=1; fi
  done < <(find "$tmp/lib" -name '*.so' \( -path '*arm64-v8a*' -o -path '*x86_64*' \) 2>/dev/null)
  n="$(find "$tmp/lib" -name '*.so' 2>/dev/null | wc -l)"
  echo "   $n bibliotecas nativas verificadas"
  rm -rf "$tmp"
done
[ "$fail" -eq 0 ] && echo "OK: alinhamento de 16 KB" || exit 1
