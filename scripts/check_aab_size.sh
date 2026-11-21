#!/usr/bin/env bash
# Verifica o .aab da distribuição play: tamanho total < 5 GiB, cada asset pack install-time
# < 1,5 GB (limite do Google Play) e base + packs install-time < 4 GB.
# Uso: scripts/check_aab_size.sh <aab>
set -euo pipefail
aab="$1"
[ -f "$aab" ] || { echo "AAB não encontrado: $aab"; exit 1; }
size=$(stat -c %s "$aab")
echo "AAB: $(basename "$aab") = $((size/1048576)) MiB (limite 5120 MiB)"
fail=0
[ "$size" -lt $((5*1024*1024*1024)) ] || { echo "FALHA: AAB >= 5 GiB"; fail=1; }
# soma dos tamanhos descomprimidos por módulo (primeiro componente do caminho)
mods="$(unzip -l "$aab" | awk 'NF>=4 && $1 ~ /^[0-9]+$/ {split($4,p,"/"); if (p[2]!="") s[p[1]]+=$1} END {for (m in s) print m, s[m]}' | sort)"
echo "$mods" | awk '{printf "  %-20s %8d MiB\n", $1, $2/1048576}'
total=0
while read -r m b; do
  [ -z "$m" ] && continue
  case "$m" in BUNDLE-METADATA|META-INF) continue;; esac
  total=$((total + b))
  if [[ "$m" == pack_* ]] && [ "$b" -ge 1500000000 ]; then echo "FALHA: asset pack $m >= 1,5 GB"; fail=1; fi
done <<< "$mods"
echo "base + asset packs: $((total/1048576)) MiB (limite Play 4 GB)"
[ "$total" -lt 4000000000 ] || { echo "FALHA: base + install-time >= 4 GB"; fail=1; }
# v3.6 "Adão e Eva": nenhum modelo GGUF de fábrica (o usuário povoa o mundo importando .gguf)
ggufs=$(unzip -l "$aab" | awk '{print $4}' | grep -iE '\.gguf(\.part[0-9]+)?$' || true)
[ -z "$ggufs" ] || { echo "FALHA: modelos GGUF embutidos:"; echo "$ggufs"; fail=1; }
[ $fail -eq 0 ] && echo "ok: AAB < 5 GiB, packs < 1,5 GB, base + install-time < 4 GB"
exit $fail
