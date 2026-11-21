#!/usr/bin/env bash
# Demonstração da Biosfera 3D no emulador: instala o APK debug + APK de teste, roda o roteiro
# BiosferaDemoTest (-e demo true) e, em paralelo, grava capturas, vídeo e gestos de câmera em demo/.
set -u
PKG=com.enhikyy.girassol.debug
OUT=demo; mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
TAPK=$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -1)
echo "APK: $APK  /  teste: $TAPK"
adb install -r -g "$APK"; adb install -r -g "$TAPK"
adb shell settings put global hide_error_dialogs 1 || true
adb shell wm size; adb shell wm density
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 & LP=$!
TPKG=$(adb shell pm list instrumentation | grep -o 'instrumentation:[^ ]*' | grep girassol | head -1 | cut -d: -f2)
echo "instrumentação: $TPKG"
adb shell am instrument -w -r -e demo true -e class com.enhikyy.girassol.BiosferaDemoTest "$TPKG" > "$OUT/instrument.txt" 2>&1 & IP=$!
waitstep() { for i in $(seq 1 240); do grep -q "PASSO $1" "$OUT/logcat.txt" && return 0; sleep 1; done; echo "passo $1 não chegou"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "captura $1"; }
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
CX=$((SW/2)); CY=$((SH*45/100))
waitstep 3D_PRONTO; sleep 10
shot 01_biosfera_3d
adb shell screenrecord --time-limit 100 --bit-rate 8000000 /sdcard/biosfera3d.mp4 & RP=$!
sleep 6; shot 02_cidadaos
adb shell input swipe $((CX-SW/4)) $CY $((CX+SW/4)) $CY 1800; sleep 5; shot 03_orbita
adb shell input swipe $CX $((CY-SH/10)) $CX $((CY+SH/12)) 1500; sleep 5; shot 04_inclinacao
waitstep VELOCIDADE_8X; sleep 12; shot 05_8x_rotina
sleep 10; shot 06_baloes
waitstep CARTAO; sleep 4; shot 07_cartao_cidadao
waitstep FIM
wait $RP 2>/dev/null; sleep 3
adb pull /sdcard/biosfera3d.mp4 "$OUT/biosfera3d.mp4" || true
wait $IP 2>/dev/null
kill $LP 2>/dev/null
grep -E "BiosferaDemo|Filament|girassol.*(E|W) " "$OUT/logcat.txt" | head -200 > "$OUT/logcat_resumo.txt" || true
ls -la "$OUT"; tail -5 "$OUT/instrument.txt"
true
