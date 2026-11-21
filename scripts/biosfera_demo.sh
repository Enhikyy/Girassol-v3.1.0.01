#!/usr/bin/env bash
# Demonstração da Biosfera 3D no emulador: instala o APK debug, abre o app, força o modo 3D,
# gira a câmera e grava capturas + vídeo em demo/.
set -u
PKG=com.enhikyy.girassol.debug
OUT=demo; mkdir -p "$OUT"
APK=$(ls app/build/outputs/apk/debug/*x86_64*.apk app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
echo "APK: $APK"
adb install -r -g "$APK"
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 & LP=$!
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 25
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "captura $1"; }
tap_text() { # toca no nó do uiautomator cujo text/content-desc contém $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  local b; b=$(python3 - "$1" "$OUT/ui.xml" <<'P'
import re,sys
t,f=sys.argv[1],sys.argv[2]
x=open(f,encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    tx=re.search(r'text="([^"]*)"',n).group(1); cd=re.search(r'content-desc="([^"]*)"',n).group(1)
    if tx==t or cd==t:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()); print((a+c)//2,(b+d)//2); break
P
)
  [ -n "$b" ] && adb shell input tap $b && echo "toque em '$1' ($b)" || echo "nó '$1' não encontrado"
}
shot 01_abertura
tap_text "3D"; sleep 12
shot 02_biosfera_3d
adb shell screenrecord --time-limit 40 --bit-rate 6000000 /sdcard/biosfera3d.mp4 & RP=$!
sleep 3
W=$(adb shell wm size | rg -o '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
CX=$((SW/2)); CY=$((SH/2))
# órbita horizontal, depois vertical
adb shell input swipe $((CX-300)) $CY $((CX+300)) $CY 1800; sleep 4; shot 03_orbita
adb shell input swipe $CX $((CY-200)) $CX $((CY+150)) 1500; sleep 4; shot 04_inclinacao
sleep 8; shot 05_cidadaos_andando
sleep 10; shot 06_baloes
wait $RP 2>/dev/null; sleep 2
adb pull /sdcard/biosfera3d.mp4 "$OUT/biosfera3d.mp4" || true
tap_text "modo:MODE_3D" >/dev/null 2>&1 || true
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui_final.xml" >/dev/null 2>&1
kill $LP 2>/dev/null; true
ls -la "$OUT"
