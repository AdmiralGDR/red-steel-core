#!/usr/bin/env bash
# vision_test.sh — автономный визуальный QA: запускает клиент, ждёт рендер-поток, делает РЕАЛЬНЫЙ скриншот,
# убивает клиент.
#
# Захват в headless/Wayland-окружении: нативный DISPLAY под XWayland НЕ захватывается (rootless-композитинг →
# чёрный кадр). Поэтому по умолчанию клиент поднимается в собственном рутфул-X-сервере Xvfb с программным GL
# (Mesa llvmpipe/swrast) — туда ffmpeg x11grab реально пишет пиксели. Отключить: USE_XVFB=0.
#
# Утилита захвата: scrot → gnome-screenshot → grim → ffmpeg x11grab (берётся первая доступная).
#
# Использование:  bash scripts/vision_test.sh [out_png] [max_wait_seconds]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/ui_test.png}"
MAX_WAIT="${2:-260}"
USE_XVFB="${USE_XVFB:-1}"
XVFB_DISPLAY="${XVFB_DISPLAY:-:99}"
RES="${VISION_RES:-1280x720}"
LOG="$ROOT/build/vision_client.log"
mkdir -p "$ROOT/build"
: > "$LOG"

XVFB_PID=""
CAP_DISPLAY="${DISPLAY:-:0}"

cleanup() {
  echo "[vision] terminating client…"
  [[ -n "${GRADLE_PID:-}" ]] && { pkill -P "$GRADLE_PID" 2>/dev/null; kill "$GRADLE_PID" 2>/dev/null; }
  pkill -f "net.minecraft.client.main.Main" 2>/dev/null
  pkill -f "runClient" 2>/dev/null
  [[ -n "$XVFB_PID" ]] && kill "$XVFB_PID" 2>/dev/null
  sleep 2
}
trap cleanup EXIT

# ── Поднимаем Xvfb (рутфул-X с программным GL) ──
if [[ "$USE_XVFB" == "1" ]] && command -v Xvfb >/dev/null 2>&1; then
  echo "[vision] starting Xvfb $XVFB_DISPLAY ($RES) with software GL"
  Xvfb "$XVFB_DISPLAY" -screen 0 "${RES}x24" -nolisten tcp >"$ROOT/build/xvfb.log" 2>&1 &
  XVFB_PID=$!
  sleep 2
  export DISPLAY="$XVFB_DISPLAY"
  export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=3.2 __GLX_VENDOR_LIBRARY_NAME=mesa
  CAP_DISPLAY="$XVFB_DISPLAY"
fi

pick_capture() {
  for t in scrot gnome-screenshot grim spectacle import; do
    command -v "$t" >/dev/null 2>&1 && { echo "$t"; return; }
  done
  command -v ffmpeg >/dev/null 2>&1 && { echo "ffmpeg"; return; }
  echo "none"
}
CAP="$(pick_capture)"
echo "[vision] capture tool: $CAP ; DISPLAY=$DISPLAY"

take_shot() {
  case "$CAP" in
    scrot)            DISPLAY="$CAP_DISPLAY" scrot -o "$OUT" ;;
    gnome-screenshot) DISPLAY="$CAP_DISPLAY" gnome-screenshot -f "$OUT" ;;
    grim)             grim "$OUT" ;;
    import)           DISPLAY="$CAP_DISPLAY" import -window root "$OUT" ;;
    ffmpeg)           ffmpeg -y -loglevel error -f x11grab -video_size "$RES" -i "$CAP_DISPLAY" -frames:v 1 "$OUT" ;;
    *)                return 1 ;;
  esac
}

echo "[vision] launching ./gradlew runClient (background)…"
( cd "$ROOT" && ./gradlew runClient >>"$LOG" 2>&1 ) &
GRADLE_PID=$!

ready=0
for ((i=0; i<MAX_WAIT; i++)); do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then echo "[vision] gradle exited early (see $LOG)"; break; fi
  if grep -qE "Render thread/INFO|Backend library|Narrator library|Realms|OpenAL initialized|Sound engine started" "$LOG"; then
    ready=1; echo "[vision] render thread detected after ${i}s"; break
  fi
  sleep 1
done

rc=2
if [[ "$ready" == "1" ]]; then
  sleep 12   # дать GUI/титульнику отрисоваться
  if take_shot && [[ -s "$OUT" ]]; then
    echo "[vision] screenshot saved → $OUT"; rc=0
  else
    echo "[vision] capture failed/empty"; rc=2
  fi
else
  echo "[vision] client never reported render thread within ${MAX_WAIT}s"
fi

exit "$rc"
