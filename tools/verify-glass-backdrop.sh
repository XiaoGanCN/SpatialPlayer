#!/usr/bin/env bash
# Does the glass sample the picture that is genuinely behind it?
#
# The ported glass reads its backdrop out of the video's SurfaceView with PixelCopy, and three
# different coordinate spaces are involved: window pixels, the picture's rect on screen, and the
# surface's own buffer (the decoded frame size, which has nothing to do with either). Feeding the
# wrong one is not a subtle error - in portrait the requested rectangle collapsed to a single row of
# pixels, which the rim smeared over the whole pane as vertical stripes.
#
# Method: freeze the clip, photograph the same frame with the chrome hidden (raw) and shown (glass),
# then correlate the two at identical screen coordinates. See tools/glass_tracking.py for the maths.
#
# Non-destructive: uses the debug build's exported shim, and restores the display's auto-rotate
# settings on exit. The screen has to be landscape, because in portrait the capsule floats in the
# letterbox where there is deliberately no picture behind it.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="org.spatialplayer.debug"
ACT_SMOKE="$PKG_DEBUG/org.spatialplayer.SmokeTestActivity"
CLIP="/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4"
HOST_CLIP="${HOST_CLIP:-/tmp/grid_720p.mp4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/smoke/out/glass}"
mkdir -p "$OUT_DIR"

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
shot() { "$ADB" exec-out screencap -p > "$OUT_DIR/$1"; }

# Auto-rotate settings are restored on every exit path, including failure.
ORIG_ACCEL="$(sh_ settings get system accelerometer_rotation)"
ORIG_ROT="$(sh_ settings get system user_rotation)"
restore_rotation() {
  sh_ settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null
  sh_ settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null
}
trap restore_rotation EXIT

sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sh_ wm dismiss-keyguard >/dev/null

say "test pattern"
if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  if [ -f "$HOST_CLIP" ]; then
    "$ADB" push "$HOST_CLIP" "$CLIP" >/dev/null 2>&1
    info "pushed $(basename "$HOST_CLIP")"
  else
    # Pattern matters: it needs structure on BOTH axes. SMPTE bars alone are vertically uniform, so
    # a vertical error in the mapping is invisible; the grid supplies the missing horizontal edges.
    skip "no test pattern at $CLIP - generate one with:
        ffmpeg -f lavfi -i smptebars=size=1280x720:rate=30 -vf 'drawgrid=w=64:h=48:t=2:c=white@0.75' -t 30 -c:v libx264 -pix_fmt yuv420p -profile:v high -level 4.0 $HOST_CLIP"
    exit 0
  fi
fi
pass "static test pattern present"

say "landscape"
sh_ settings put system accelerometer_rotation 0 >/dev/null
sh_ settings put system user_rotation 1 >/dev/null

# `real W x H` in dumpsys display is the panel's native size and never changes with rotation, so it
# cannot be used to detect one. Poll the rotation itself instead; it takes a moment to apply.
ROTATION=""
for _ in $(seq 1 20); do
  ROTATION="$(sh_ dumpsys display | grep -m1 -oE 'mCurrentOrientation=[0-9]+' | grep -oE '[0-9]+')"
  if [ "$ROTATION" = "1" ] || [ "$ROTATION" = "3" ]; then break; fi
  sleep 0.5
done

PHYS_W="$(sh_ dumpsys display | grep -m1 -oE 'real [0-9]+ x [0-9]+' | grep -oE '[0-9]+' | head -1)"
PHYS_H="$(sh_ dumpsys display | grep -m1 -oE 'real [0-9]+ x [0-9]+' | grep -oE '[0-9]+' | tail -1)"
# A quarter turn puts the panel's height along the screen's x axis.
WIDTH="${PHYS_H:-2560}"
HEIGHT="${PHYS_W:-1096}"
info "rotation ${ROTATION:-unknown}, screen ${WIDTH}x${HEIGHT}"
if [ "$ROTATION" = "1" ] || [ "$ROTATION" = "3" ]; then
  pass "landscape"
else
  fail "the display did not rotate - the pane would sit over the letterbox"
  exit 1
fi

say "play, then pause"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
sh_ am start -a org.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
    --es smoke_path "$CLIP" --es smoke_mime video/mp4 >/dev/null
sleep 8

# Use the running player's controls directly. Coordinate taps raced the idle timer and
# could photograph hidden chrome; a static pattern alone cannot detect that false positive.
glass_state() {
  "$ADB" logcat -c
  sh_ am broadcast -p "$PKG_DEBUG" -a org.spatialplayer.debug.GLASS_CAPTURE --ez visible "$1" >/dev/null
  sleep 0.8
  local report
  report=$("$ADB" logcat -d -s PanelProbe)
  if ! echo "$report" | grep -q "glass-capture shown=$1"; then
    fail "chrome did not reach visible=$1"
    exit 1
  fi
}
glass_state false

say "freeze check"
shot frozen_a.png
sleep 1
shot frozen_b.png
if python3 "$ROOT/tools/glass_tracking.py" freeze "$OUT_DIR/frozen_a.png" "$OUT_DIR/frozen_b.png"; then
  pass "the picture is frozen"
else
  fail "the picture is still moving - the pause tap missed"
  exit 1
fi

say "raw and glass frames of the same picture"
glass_state false
shot raw.png
sleep 1
shot raw2.png
glass_state true
shot glass.png
if cmp -s "$OUT_DIR/raw.png" "$OUT_DIR/glass.png"; then
  fail "raw and glass captures are identical"
  exit 1
fi

say "tracking"
python3 "$ROOT/tools/glass_tracking.py" "$OUT_DIR/raw.png" "$OUT_DIR/raw2.png" "$OUT_DIR/glass.png"
if [ $? -eq 0 ]; then
  pass "the frosted glass tracks the picture behind it"
else
  fail "the glass does not track the picture behind it"
fi

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
printf '  artifacts: %s\n' "$OUT_DIR"
[ "$FAIL" -eq 0 ]
