#!/usr/bin/env bash
# Verifies gesture behaviour: sensitivity and the tap-to-controls contract.
#
# Both of these were reported as broken, and both had causes that were invisible from the outside:
#
#  * The gesture controller scales drags by the viewport, but nobody ever told it the viewport, so
#    viewWidth/viewHeight stayed at 1 and *every* vertical delta saturated its per-event cap. A drag
#    of a few millimetres therefore moved the volume by many steps at once.
#  * The controls overlay is a full-size sibling drawn above the gesture layer and swallowed its
#    touches, so gestures only worked when the chrome happened to be hidden.
#
# Non-destructive: changes the music stream volume during the run and restores it at the end.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT_SMOKE="$PKG_DEBUG/com.gan.spatialplayer.SmokeTestActivity"
CLIP="/sdcard/Movies/SpatialPlayerTest/hdr10_hevc_720p.mkv"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/smoke/out}"
mkdir -p "$OUT_DIR"

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
adb_() { "$ADB" "$@" 2>/dev/null | tr -d '\r'; }

# Music stream volume index, the value a volume gesture actually moves.
music_volume() {
  sh_ "dumpsys audio" | awk '/STREAM_MUSIC:/{f=1} f&&/streamVolume:/{gsub(/[^0-9]/,"");print;exit}'
}

# Screen density informs how many pixels a millimetre is.
density() {
  local d
  d=$(sh_ "wm density" | grep -oE "Physical density: [0-9]+" | grep -oE "[0-9]+")
  echo "${d:-420}"
}

say "preflight"
if [ "$(adb_ get-state)" != "device" ]; then fail "no authorised device"; exit 1; fi
pass "device: $(sh_ getprop ro.product.model)"

if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no clip at $CLIP"
  exit 0
fi

# Vertical gestures are split by the screen midline: left half is brightness, right half volume.
WIDTH=$(sh_ "wm size" | grep -oE "Physical size: [0-9]+" | grep -oE "[0-9]+")
WIDTH=${WIDTH:-1096}
RIGHT_X=$(( WIDTH * 3 / 4 ))
HEIGHT=$(sh_ "wm size" | grep -oE "x[0-9]+" | head -1 | grep -oE "[0-9]+")
HEIGHT=${HEIGHT:-2560}
DENSITY=$(density)
PX_PER_MM=$(( DENSITY * 10 / 254 ))   # density is px/inch; 1mm = density/25.4 px

say "start playback"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
    --es smoke_path "$CLIP" --es smoke_mime "video/x-matroska" > /dev/null
sleep 6
if [ -n "$(sh_ pidof "$PKG_DEBUG")" ]; then pass "player running"; else fail "player not running"; exit 1; fi

ORIGINAL_VOLUME=$(music_volume)
info "original music volume: ${ORIGINAL_VOLUME:-unknown}"
info "density ${DENSITY}dpi -> 1mm = ${PX_PER_MM}px"

restore() {
  if [ -n "${ORIGINAL_VOLUME:-}" ]; then
    sh_ "media volume --stream 3 --set $ORIGINAL_VOLUME" > /dev/null 2>&1
  fi
}

# ---------------------------------------------------------------------------
say "a drag shorter than a centimetre must not change the volume"
RESET=15
sh_ "media volume --stream 3 --set $RESET" > /dev/null 2>&1
sleep 1
BEFORE=$(music_volume)
TINY=$(( PX_PER_MM * 10 ))            # 10mm
adb_ shell input swipe "$RIGHT_X" "$(( HEIGHT * 3 / 5 ))" "$RIGHT_X" "$(( HEIGHT * 3 / 5 - TINY ))" 400 > /dev/null
sleep 2
AFTER=$(music_volume)
STEP=$(( AFTER - BEFORE ))
info "10mm (${TINY}px) drag: $BEFORE -> $AFTER  (${STEP} steps)"

if [ "${STEP#-}" -le 1 ] 2>/dev/null; then
  pass "small drag changed at most 1 step"
else
  fail "small drag changed $STEP steps — sensitivity is still too high"
fi

# ---------------------------------------------------------------------------
say "a deliberate drag must still work"
sh_ "media volume --stream 3 --set $RESET" > /dev/null 2>&1
sleep 1
BEFORE=$(music_volume)
BIG=$(( PX_PER_MM * 50 ))             # 5cm
adb_ shell input swipe "$RIGHT_X" "$(( HEIGHT * 3 / 5 ))" "$RIGHT_X" "$(( HEIGHT * 3 / 5 - BIG ))" 500 > /dev/null
sleep 2
AFTER=$(music_volume)
STEP=$(( AFTER - BEFORE ))
info "50mm (${BIG}px) drag: $BEFORE -> $AFTER  (${STEP} steps)"

if [ "${STEP:-0}" -ge 1 ] 2>/dev/null; then
  pass "deliberate drag moved the volume ($STEP steps)"
else
  fail "deliberate drag did nothing — the gesture is now too dead"
fi

if [ "${STEP:-0}" -le 8 ] 2>/dev/null; then
  pass "deliberate drag stayed proportional ($STEP steps for ~5cm)"
else
  fail "deliberate drag jumped $STEP steps for 5cm"
fi

# ---------------------------------------------------------------------------
say "the chrome responds to a single tap, and does not time out early"
LUM="$ROOT/tools/chrome_luminance.py"
TAP_X=$(( WIDTH / 2 )); TAP_Y=$(( HEIGHT / 2 ))
TIMEOUT_MS=6000

chrome_state() {
  # Raw adb, not adb_(): that helper pipes through `tr -d '\r'`, which corrupts binary PNG data
  # and silently yields a zero-byte screenshot.
  "$ADB" exec-out screencap -p > "$OUT_DIR/.gesture-state.png" 2>/dev/null
  python3 "$LUM" "$OUT_DIR/.gesture-state.png"
}

visible() { python3 -c "import sys; sys.exit(0 if float('${1:-0}') > 20 else 1)"; }
tap() { adb_ shell input tap "$TAP_X" "$TAP_Y" > /dev/null 2>&1; }

# A tap toggles, so rather than assuming a starting state, tap until the wanted state is reached.
# Screenshots cost ~1s here, so elapsed time is measured rather than inferred from sleep counts.
drive_to() {
  local want="$1" tries=0
  while [ "$tries" -lt 4 ]; do
    STATE=$(chrome_state)
    if [ "$want" = "shown" ] && visible "$STATE"; then printf '%s' "$STATE"; return 0; fi
    if [ "$want" = "hidden" ] && ! visible "$STATE"; then printf '%s' "$STATE"; return 0; fi
    tap
    sleep 1
    tries=$((tries + 1))
  done
  printf '%s' "$STATE"
  return 1
}

# --- a single tap must toggle the chrome -------------------------------------------------
HIDDEN=$(drive_to hidden)
info "chrome hidden: ${HIDDEN}"
tap
sleep 1
SHOWN=$(chrome_state)
if visible "$SHOWN"; then
  pass "a single tap revealed the hidden chrome (${HIDDEN} -> ${SHOWN})"
else
  fail "a single tap did not reveal the chrome (${HIDDEN} -> ${SHOWN})"
fi

# --- it must survive its timeout window, then clear itself -------------------------------
# Re-show from a known-hidden state so the timer starts at a known moment.
T0=$(date +%s)
H=$(drive_to hidden)
info "drive_to hidden returned '${H}' at t=$(( $(date +%s) - T0 ))s"
tap
SHOWN_AT=$(date +%s)
AFTER=$(chrome_state)
# A capture taken in the first moments after a tap can land before the frame is composited, which
# reads as "still hidden". Poll briefly; SHOWN_AT stays the moment of the tap so the timeout is
# still measured from the right instant.
SETTLE=0
while [ "$SETTLE" -lt 3 ] && ! visible "$AFTER"; do
  sleep 1
  AFTER=$(chrome_state)
  SETTLE=$((SETTLE + 1))
done
NOW=$(( $(date +%s) - SHOWN_AT ))
info "chrome state settled after ${SETTLE}s of polling"
if visible "$AFTER"; then
  pass "chrome visible after the showing tap (t=${NOW}s)"
else
  fail "chrome did not appear (${AFTER})"
fi

OK=1
while :; do
  NOW=$(( $(date +%s) - SHOWN_AT ))
  # Leave room for the ~1s capture cost at the end of the window.
  [ "$NOW" -ge $(( TIMEOUT_MS / 1000 - 3 )) ] && break
  S=$(chrome_state)
  NOW=$(( $(date +%s) - SHOWN_AT ))
  info "t=${NOW}s luminance=${S}"
  if ! visible "$S"; then
    fail "chrome vanished at t=${NOW}s, inside the ${TIMEOUT_MS}ms timeout"
    OK=0
    break
  fi
done
[ "$OK" = "1" ] && pass "chrome survived the whole timeout window (no ~0.5s timeout)"

DEADLINE=$(( $(date +%s) + 14 ))
HID=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  S=$(chrome_state)
  if ! visible "$S"; then HID=1; break; fi
done
NOW=$(( $(date +%s) - SHOWN_AT ))
if [ "$HID" = "1" ]; then
  pass "chrome auto-hid once the idle timeout elapsed (t=${NOW}s)"
else
  fail "chrome never auto-hid"
fi

restore
say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
printf '  music volume restored to %s\n' "${ORIGINAL_VOLUME:-unknown}"
[ "$FAIL" -eq 0 ] || exit 1
