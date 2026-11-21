#!/usr/bin/env bash
# Compatibilidade CodeAssist (edição no próprio Android): os scripts de build não podem
# conter caminhos absolutos de desktop. Caminhos devem ser relativos ao projeto ou vir de
# propriedades/variáveis (sdk.dir no local.properties, ANDROID_HOME etc.).
# Uso: scripts/check_relative_paths.sh
set -euo pipefail
cd "$(dirname "$0")/.."
files=$(ls settings.gradle.kts build.gradle.kts app/build.gradle.kts core/build.gradle.kts \
        pack_*/build.gradle.kts app/src/main/cpp/CMakeLists.txt native-tests/CMakeLists.txt gradle.properties 2>/dev/null)
pattern='(^|[^A-Za-z0-9_$.{])(/home/|/Users/|/root/|/opt/|/tmp/|/mnt/|/sdcard/|/storage/emulated|[A-Za-z]:[\\/](Users|Program|Android|dev|src))'
fail=0
for f in $files; do
  hits=$(grep -nE "$pattern" "$f" | grep -vE '^\s*[0-9]+:\s*(//|#)' || true)
  if [ -n "$hits" ]; then echo "FALHA: caminho absoluto em $f:"; echo "$hits"; fail=1; fi
done
for f in $files; do
  case "$f" in *.kts)
    grep -qE 'file\("/|File\("/' "$f" && { echo "FALHA: file(\"/...\") absoluto em $f"; fail=1; } ;;
  esac
done
[ $fail -eq 0 ] && echo "ok: $(echo "$files" | wc -w) scripts de build só com caminhos relativos"
exit $fail
