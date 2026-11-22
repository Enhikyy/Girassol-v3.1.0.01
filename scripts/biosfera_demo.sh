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
waitstep() { [ "${DEAD:-0}" = 1 ] && return 1; for i in $(seq 1 300); do grep -q "PASSO $1" "$OUT/logcat.txt" && return 0; [ $((i % 30)) = 0 ] && ! timeout 20 adb shell true >/dev/null 2>&1 && { DEAD=1; echo "emulador sem resposta"; return 1; }; sleep 1; done; echo "passo $1 não chegou"; }
shot() { dismiss_anr; adb exec-out screencap -p > "$OUT/$1.png"; echo "captura $1"; }
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
CX=$((SW/2)); CY=$((SH*45/100))
step() { adb shell am broadcast -a girassol.demo.STEP --es step "$1" >/dev/null; [ "$1" = pick ] || waitstep "OK_$1"; }
waitlong() { for i in $(seq 1 "$2"); do grep -q "PASSO $1" "$OUT/logcat.txt" && return 0; sleep 2; done; echo "passo $1 não chegou"; }

# ---- renderizações offline quadro a quadro (CinematicCapture), com detecção de travamento
alive() { timeout 20 adb shell true >/dev/null 2>&1; }
waitcap() { # $1 = tag; desiste se o aparelho parar de responder ou se nada novo aparecer no log por 8 min
  local last=0 since=$(date +%s)
  while true; do
    grep -q "PASSO CAPTURA_OK_$1" "$OUT/logcat.txt" && return 0
    local sz; sz=$(stat -c %s "$OUT/logcat.txt")
    if [ "$sz" != "$last" ]; then last=$sz; since=$(date +%s); fi
    # puxa os quadros já gravados a cada segmento concluído (se o emulador cair, não se perde tudo)
    local segs; segs=$(grep -c "PASSO CAPTURA_SEGMENTO $1 " "$OUT/logcat.txt")
    if [ "$segs" != "${PULLED[$1]:-0}" ]; then PULLED[$1]=$segs; mkdir -p "$OUT/render_$1"
      timeout 120 adb pull "/sdcard/Android/data/com.enhikyy.girassol/files/captura_$1/." "$OUT/render_$1" >/dev/null 2>&1 || true; fi
    if [ $(( $(date +%s) - since )) -gt 480 ] || ! alive; then echo "captura $1: emulador travou/sem progresso"; DEAD=1; return 1; fi
    sleep 10
  done
}
BEST=""; DEAD=0; declare -A PULLED
encode() {
  local tag=$1
  if command -v ffmpeg >/dev/null && [ -f "$OUT/render_$tag/quadros/f_0000.png" ]; then
    ffmpeg -y -loglevel error -framerate 15 -i "$OUT/render_$tag/quadros/f_%04d.png" -vf "scale=720:1280:flags=lanczos" -c:v libx264 -pix_fmt yuv420p -crf 18 "$OUT/render_$tag/biosfera3d_$tag.mp4" || true
  fi
  rm -rf "$OUT/render_$tag/quadros"
}
getcap() {
  local tag=$1
  if ! waitcap $tag; then encode $tag; [ -f "$OUT/render_$tag/biosfera3d_$tag.mp4" ] && BEST=$tag; return 1; fi
  CAP=$(grep -o "PASSO CAPTURA_OK_$tag [0-9]* [^ ]*" "$OUT/logcat.txt" | tail -1 | awk '{print $4}')
  N=$(grep -o "PASSO CAPTURA_OK_$tag [0-9]*" "$OUT/logcat.txt" | tail -1 | awk '{print $3}')
  echo "captura $tag: $N quadros em $CAP"
  [ "${N:-0}" -gt 0 ] || return 0
  mkdir -p "$OUT/render_$tag"
  adb pull "$CAP/." "$OUT/render_$tag" >/dev/null 2>&1 || true
  encode $tag
  BEST=$tag
}
getcap minimal && getcap reduced
[ "$DEAD" = 1 ] && echo "emulador travado; pulando a interface ao vivo"
# ---- 2) app ao vivo: interface (barras, cartão do cidadão, HUD do POV, 2D)
waitstep POVOADO; sleep 3; dismiss_anr
tap_text "Biosfera"; sleep 15
adb shell screenrecord --time-limit 150 --bit-rate 6000000 /sdcard/aovivo.mp4 & RP=$!
shot app_01_barras_ambiente_camera
step pick; waitstep CARTAO
XY=$(grep -o "PASSO CARTAO [-0-9]* [-0-9]*" "$OUT/logcat.txt" | tail -1 | awk '{print $3, $4}')
echo "cidadão projetado em: $XY"
case "$XY" in -1*|"") echo "sem projeção";; *) adb shell input tap $XY;; esac
sleep 6; shot 03_cartao_cidadao
tap_text "POV 1ª Pessoa"; sleep 10; shot 06_hud_pov
step orbit; sleep 4
step interior; sleep 12; shot app_03_interior
step urban; sleep 8
step cam2d; sleep 8; shot 09_modo_2d
step 3d; step orbit
wait $RP 2>/dev/null; sleep 3
adb pull /sdcard/aovivo.mp4 "$OUT/biosfera3d_app_ao_vivo.mp4" >/dev/null 2>&1 || true
step fim
[ "$DEAD" = 1 ] || getcap full
if [ -n "$BEST" ]; then
  echo "melhor nível renderizado: $BEST"
  cp "$OUT/render_$BEST"/*.png "$OUT/" 2>/dev/null || true
  cp "$OUT/render_$BEST/biosfera3d_$BEST.mp4" "$OUT/biosfera3d.mp4" 2>/dev/null || true
  echo "$BEST" > "$OUT/nivel_renderizado.txt"
fi
alive && { timeout 60 adb shell am force-stop com.enhikyy.girassol.debug || true; }
kill $IP 2>/dev/null
kill $LP 2>/dev/null
{
  echo "== Resumo do logcat (Biosfera 3D v4.1) =="
  echo "-- passos do roteiro"; grep -E "BiosferaDemo" "$OUT/logcat.txt" | sed -E 's/^.*PASSO/PASSO/'
  echo "-- calibração e pipeline"; grep -E "GpuCalibration|GirassolFilament" "$OUT/logcat.txt" | grep -vE "fps=" | head -60
  echo "-- captura offline (ms por quadro no SwiftShader)"; grep -E "BiosferaCaptura" "$OUT/logcat.txt" | sed -E "s/^.*BiosferaCaptura: //" | head -80
  echo "-- quadros por segundo ao vivo (amostras de 1 s: ambiente, câmera, fase, cidadãos)"; grep -E "GirassolFilament.*fps=" "$OUT/logcat.txt" | sed -E 's/^.*(fps=)/\1/' | awk 'NR%3==1' | head -120
  echo "-- erros fatais"; grep -E "FATAL EXCEPTION|AndroidRuntime: |SIGSEGV|JNI DETECTED ERROR" "$OUT/logcat.txt" | head -40 || true
} > "$OUT/logcat_resumo.txt"
ls -la "$OUT"; tail -5 "$OUT/instrument.txt"
true
