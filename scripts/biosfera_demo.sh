#!/usr/bin/env bash
# Demonstração da Biosfera 3D no emulador: instala o APK debug + APK de teste, roda o roteiro
# BiosferaDemoTest (-e demo true) e, em paralelo, toca na aba, faz gestos de câmera e grava
# capturas e vídeo em demo/.
set -u
PKG=com.enhikyy.girassol.debug
OUT=demo; mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
TAPK=$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -1)
echo "APK: $APK  /  teste: $TAPK"
# o emulador recém-ligado sobrecarrega o Pixel Launcher (diálogo "isn't responding"): espera
# o sistema assentar e desliga o launcher durante a demo.
sleep 45
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb install -r -g "$APK"; adb install -r -g "$TAPK"
adb shell wm size; adb shell wm density
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 & LP=$!
TPKG=$(adb shell pm list instrumentation | grep -o 'instrumentation:[^ ]*' | grep girassol | head -1 | cut -d: -f2)
echo "instrumentação: $TPKG"
adb shell am instrument -w -r -e demo true -e class com.enhikyy.girassol.BiosferaDemoTest "$TPKG" > "$OUT/instrument.txt" 2>&1 & IP=$!
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
find_xy() { python3 - "$1" "$OUT/ui.xml" <<'P'
import re,sys
t,f=sys.argv[1],sys.argv[2]
try: x=open(f,encoding='utf-8').read()
except Exception: sys.exit()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    tx=re.search(r'text="([^"]*)"',n).group(1); cd=re.search(r'content-desc="([^"]*)"',n).group(1)
    if tx==t or cd==t:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()); print((a+c)//2,(b+d)//2); break
P
}
dismiss_anr() { dump; local w; w=$(find_xy "Wait"); [ -n "$w" ] && adb shell input tap $w && echo "diálogo ANR do sistema dispensado"; }
tap_text() { dump; local b; b=$(find_xy "$1"); [ -n "$b" ] && adb shell input tap $b && echo "toque em '$1' ($b)" || echo "nó '$1' não encontrado"; }
waitstep() { for i in $(seq 1 300); do grep -q "PASSO $1" "$OUT/logcat.txt" && return 0; sleep 1; done; echo "passo $1 não chegou"; }
shot() { dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; echo "captura $1"; }
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
CX=$((SW/2)); CY=$((SH*45/100))
waitstep POVOADO; sleep 3; dismiss_anr
tap_text "Biosfera"; sleep 15
shot 01_biosfera_3d
adb shell screenrecord --time-limit 150 --bit-rate 8000000 /sdcard/biosfera3d.mp4 & RP=$!
sleep 8; shot 02_cidadaos_andando
adb shell input swipe $((CX-SW/4)) $CY $((CX+SW/4)) $CY 1800; sleep 6; shot 03_orbita
adb shell input swipe $CX $((CY-SH/10)) $CX $((CY+SH/12)) 1500; sleep 6; shot 04_inclinacao
waitstep VELOCIDADE_8X; sleep 12; shot 05_8x_manha
sleep 12; shot 06_8x_dia
sleep 12; shot 07_8x_tarde
waitstep CARTAO
XY=$(grep -o "PASSO CARTAO [-0-9]* [-0-9]*" "$OUT/logcat.txt" | tail -1 | awk '{print $3, $4}')
echo "cidadão projetado em: $XY"
case "$XY" in -1*|"") echo "sem projeção";; *) adb shell input tap $(echo $XY | awk '{print $1, $2+8}');; esac
sleep 4; shot 08_cartao_cidadao
waitstep FIM
wait $RP 2>/dev/null; sleep 3
adb pull /sdcard/biosfera3d.mp4 "$OUT/biosfera3d.mp4" || true
wait $IP 2>/dev/null
kill $LP 2>/dev/null
grep -E "BiosferaDemo|Filament|Choreographer" "$OUT/logcat.txt" | head -200 > "$OUT/logcat_resumo.txt" || true
ls -la "$OUT"; tail -5 "$OUT/instrument.txt"
true
