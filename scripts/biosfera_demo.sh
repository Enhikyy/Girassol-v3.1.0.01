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
# o sistema assentar; diálogos ANR do sistema são dispensados antes de cada captura.
sleep 45
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb install -r -g "$APK"; adb install -r -g "$TAPK"
# SwiftShader (GL por software) com o pipeline completo: tela 720×1600 para manter o vídeo fluido
adb shell wm size 720x1600; adb shell wm density 320; sleep 3
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
step() { adb shell am broadcast -a girassol.demo.STEP --es step "$1" >/dev/null; [ "$1" = pick ] || waitstep "OK_$1"; }
# vídeo em segmentos (limite de 180 s do screenrecord); concatenados com ffmpeg no fim
record_loop() { for i in 1 2 3; do adb shell screenrecord --time-limit 175 --bit-rate 8000000 /sdcard/seg$i.mp4 || break; [ -f "$OUT/.stop" ] && break; done; }
waitstep POVOADO; sleep 3; dismiss_anr
tap_text "Biosfera"; sleep 15
shot 01_biosfera_3d
rm -f "$OUT/.stop"; record_loop & RP=$!
sleep 8; shot 02_cidadaos_andando
step pick; waitstep CARTAO
XY=$(grep -o "PASSO CARTAO [-0-9]* [-0-9]*" "$OUT/logcat.txt" | tail -1 | awk '{print $3, $4}')
echo "cidadão projetado em: $XY"
case "$XY" in -1*|"") echo "sem projeção";; *) adb shell input tap $XY;; esac
sleep 5; shot 03_cartao_cidadao
tap_text "fechar"; sleep 2
step orbit
adb shell input swipe $((CX-SW/4)) $((SH/3)) $((CX+SW/4)) $((SH/3)) 1800; sleep 6; shot 04_orbita
step tilt; sleep 4
adb shell input swipe $CX $((SH/3)) $CX $((SH/3+SH/14)) 1200; sleep 6; shot 05_inclinacao
step orbit; step 8x
step ate:0.33; sleep 10; shot 06_8x_manha
step ate:0.68; sleep 10; shot 07_8x_tarde
step ate:0.90; sleep 10; shot 08_8x_noite
step ate:0.40; sleep 6
step cam2d; sleep 8; shot 09_modo_2d
step 3d; step orbit; sleep 6
step pov; sleep 10
adb shell input swipe $((CX+SW/6)) $((SH/2)) $((CX-SW/6)) $((SH/2)) 1500; sleep 6; shot 10_pov_primeira_pessoa
step interior; step tilt; sleep 14; shot 11_interior_brutalista
step pov; sleep 10; shot 12_interior_pov
step orbit; sleep 6
step urban; sleep 10
step 1x
touch "$OUT/.stop"; adb shell pkill -INT screenrecord || true; wait $RP 2>/dev/null; sleep 3
segs=""
for i in 1 2 3; do adb pull /sdcard/seg$i.mp4 "$OUT/seg$i.mp4" >/dev/null 2>&1 && segs="$segs $OUT/seg$i.mp4"; done
if command -v ffmpeg >/dev/null && [ -n "$segs" ]; then
  : > "$OUT/segs.txt"; for f in $segs; do echo "file '$(basename "$f")'" >> "$OUT/segs.txt"; done
  (cd "$OUT" && ffmpeg -y -loglevel error -f concat -safe 0 -i segs.txt -c copy biosfera3d.mp4) || cp $(echo $segs | awk '{print $1}') "$OUT/biosfera3d.mp4"
else
  cp $(echo $segs | awk '{print $1}') "$OUT/biosfera3d.mp4" 2>/dev/null || true
fi
rm -f "$OUT"/seg*.mp4 "$OUT/segs.txt" "$OUT/.stop"
step fim; wait $IP 2>/dev/null
kill $LP 2>/dev/null
{
  echo "== Resumo do logcat (Biosfera 3D v4.0) =="
  echo "-- passos do roteiro"; grep -E "BiosferaDemo" "$OUT/logcat.txt" | sed -E 's/^.*PASSO/PASSO/'
  echo "-- calibração e pipeline"; grep -E "GpuCalibration|GirassolFilament" "$OUT/logcat.txt" | grep -vE "fps=" | head -60
  echo "-- quadros por segundo (amostras de 1 s: ambiente, câmera, fase, cidadãos)"; grep -E "GirassolFilament.*fps=" "$OUT/logcat.txt" | sed -E 's/^.*(fps=)/\1/' | awk 'NR%3==1' | head -120
  echo "-- erros fatais"; grep -E "FATAL EXCEPTION|AndroidRuntime: |SIGSEGV|JNI DETECTED ERROR" "$OUT/logcat.txt" | head -40 || true
} > "$OUT/logcat_resumo.txt"
ls -la "$OUT"; tail -5 "$OUT/instrument.txt"
true
