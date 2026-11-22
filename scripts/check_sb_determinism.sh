#!/usr/bin/env bash
# Núcleo sináptico (app/src/main/cpp/sb): compila sb_tests isolado de -Ofast/FMA
# (-std=c++20 -ffp-contract=off -fno-fast-math) em x86_64 (host) e arm64-v8a (NDK,
# executado com qemu-aarch64) e exige T1–T18 sem falhas com o hash canônico do T13.
# Uso: scripts/check_sb_determinism.sh [NDK_DIR]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SB="$ROOT/app/src/main/cpp/sb"
OUT="${SB_OUT:-$(mktemp -d)}"
EXPECTED="9ab8752a00f798b6"
FLAGS=(-std=c++20 -O2 -ffp-contract=off -fno-fast-math -Wall -Wextra)

check() {  # $1 = rótulo, $2 = log
  local label="$1" log="$2"
  local summary hash
  summary="$(grep -E '^[0-9]+ verificações, [0-9]+ falhas' "$log" | tail -1)"
  hash="$(grep -oE 'hash=[0-9a-f]{16}' "$log" | head -1 | cut -d= -f2)"
  echo "[$label] $summary · T13 hash=$hash"
  [[ "$summary" =~ ,\ 0\ falhas$ ]] || { echo "ERRO: falhas em $label"; return 1; }
  [[ "$hash" == "$EXPECTED" ]] || { echo "ERRO: hash $hash != $EXPECTED em $label"; return 1; }
}

# x86_64 (g++ do host)
g++ "${FLAGS[@]}" -I"$SB" "$SB/sb_core.cpp" "$SB/sb_tests.cpp" -o "$OUT/sb_tests_x86_64"
"$OUT/sb_tests_x86_64" > "$OUT/x86_64.log"
check "x86_64 g++" "$OUT/x86_64.log"
if command -v objdump >/dev/null; then
  n=$(objdump -d "$OUT/sb_tests_x86_64" | grep -cE 'vfmadd|vfmsub|vfnmadd' || true)
  echo "[x86_64] instruções FMA: $n"; [[ "$n" == 0 ]] || { echo "ERRO: FMA no binário x86_64"; exit 1; }
fi

# arm64-v8a (clang do NDK, estático, executado via qemu-aarch64)
NDK="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then echo "ERRO: NDK não encontrado"; exit 1; fi
CXX="$(ls "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++ 2>/dev/null || true)"
[[ -x "$CXX" ]] || { echo "ERRO: clang++ aarch64 ausente em $NDK"; exit 1; }
for f in sb_core sb_tests sb_tls_pad_static; do
  "$CXX" "${FLAGS[@]}" -I"$SB" -c "$SB/$f.cpp" -o "$OUT/$f.arm64.o"
done
# FMA proibida no NOSSO código (a libc/libm estática do bionic tem as próprias e não entra na conta)
OBJDUMP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
n=$("$OBJDUMP" -d "$OUT/sb_core.arm64.o" "$OUT/sb_tests.arm64.o" | grep -cE '\s(fmadd|fmsub|fnmadd|fnmsub|fmla|fmls)\s' || true)
echo "[arm64] instruções FMA em sb_core/sb_tests: $n"; [[ "$n" == 0 ]] || { echo "ERRO: FMA no código arm64"; exit 1; }
"$CXX" -static -static-libstdc++ "$OUT/sb_core.arm64.o" "$OUT/sb_tests.arm64.o" "$OUT/sb_tls_pad_static.arm64.o" -o "$OUT/sb_tests_arm64"
QEMU="$(command -v qemu-aarch64 || command -v qemu-aarch64-static || true)"
[[ -n "$QEMU" ]] || { echo "ERRO: qemu-aarch64 ausente"; exit 1; }
"$QEMU" "$OUT/sb_tests_arm64" > "$OUT/arm64.log"
check "arm64-v8a NDK clang (qemu)" "$OUT/arm64.log"
echo "check_sb_determinism: OK — hash $EXPECTED idêntico em x86_64 e arm64-v8a"
