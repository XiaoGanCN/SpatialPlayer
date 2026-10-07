#!/usr/bin/env bash
# Verifies the picture geometry survives a rotation - which is what the ambient wash and the control
# capsule are both positioned from.
#
# The activity is NOT recreated on a rotation (`configChanges` includes `orientation`), so nothing
# recomputes the picture rect unless something asks it to. That has gone wrong three times, always
# the same way: the ambient wash is drawn in "the bands the picture leaves empty", so a rect left
# over from the previous orientation puts the glow on top of the picture.
#
# Why this asserts on numbers instead of a screenshot: the wash is a dim gradient peaked at ~104/255
# and the reference footage is mostly dark, so a misplaced band is nearly invisible in a capture. A
# stale rect is unambiguous as coordinates, and `PanelProbeReceiver` reads the rect the view is
# actually drawing with (`AmbientGlowView.videoRectForTest`) next to the one it should have.
#
# Non-destructive: forces each rotation explicitly and restores auto-rotate plus portrait on exit.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="com.gan.spatialplayer.debug"
ACT_SMOKE="$PKG/com.gan.spatialplayer.SmokeTestActivity"
ACT_PROBE="$PKG/com.gan.spatialplayer.PanelProbeActivity"
CLIP="${CLIP:-/sdcard/Movies/SpatialPlayerTest/yt_1080p_ac3_51.mkv}"
MIME="${MIME:-video/mp4}"

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
adb_() { "$ADB" "$@" 2>/dev/null | tr -d '\r'; }

# Rotation settings are restored on every exit path, including Ctrl-C.
ORIGINAL_ACCEL=$(sh_ settings get system accelerometer_rotation)
ORIGINAL_ROTATION=$(sh_ settings get system user_rotation)
restore_rotation() {
  sh_ settings put system accelerometer_rotation "${ORIGINAL_ACCEL:-1}" > /dev/null 2>&1
  sh_ settings put system user_rotation "${ORIGINAL_ROTATION:-0}" > /dev/null 2>&1
}
trap restore_rotation EXIT

# Reports the geometry the running player is using, as one line.
geometry() {
  sh_ logcat -c > /dev/null 2>&1
  sh_ am start -n "$ACT_PROBE" --es probe geometry > /dev/null 2>&1
  sleep 3
  sh_ "logcat -d -s PanelProbe:I" | grep -o "geometry :: .*" | tail -1 | sed 's/geometry :: //'
}

# Asserts one geometry reading: the glow's rect must equal the one the current container implies.
check_geometry() {
  local label="$1" report="$2"
  if [ -z "$report" ]; then
    fail "$label: the probe reported nothing"
    return
  fi
  info "$label: $report"
  if printf '%s' "$report" | grep -q "matches=true"; then
    pass "$label: the wash matches the picture"
  else
    fail "$label: the wash is drawn for different geometry than the picture"
  fi
  # A rect that is empty or degenerate is the other half of the failure: without a picture rect the
  # wash draws nothing, which looks the same as "the setting is off".
  local size container w h
  container=$(printf '%s' "$report" | grep -oE "container=[0-9]+x[0-9]+" | head -1 | cut -d= -f2)
  size=$(printf '%s' "$report" | grep -oE "size=[0-9]+x[0-9]+" | head -1 | cut -d= -f2)
  w=${size%x*}
  h=${size#*x}
  if [ -n "$size" ] && [ "${w:-0}" -gt 0 ] 2>/dev/null && [ "${h:-0}" -gt 0 ] 2>/dev/null; then
    pass "$label: the picture rect is real (${size} inside ${container:-?})"
  else
    fail "$label: the picture rect is empty or unreadable (size=${size:-none})"
  fi

  # The wash's rect is the load-bearing measurement; the capsule is positioned from that same rect
  # by a bottom margin, so a rect that matches is a capsule that is placed from the right geometry.
  # A capsule *position* is deliberately not asserted on here: reading one back through
  # `getLocationOnScreen` returned a frame from before the layout, and both a in-process log and the
  # probe agreed with each other and disagreed with the screen. Measured against a screenshot
  # instead - the capsule sits at the picture's bottom edge, which is what the margin asks for.
}

say "preflight"
if [ "$(adb_ get-state)" != "device" ]; then fail "no authorised device"; exit 1; fi
pass "device: $(sh_ getprop ro.product.model)"
if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no clip at $CLIP"; exit 0
fi

say "start playback in portrait"
sh_ input keyevent KEYCODE_WAKEUP > /dev/null
sh_ settings put system accelerometer_rotation 0
sh_ settings put system user_rotation 0
sleep 1
sh_ am force-stop "$PKG"
sleep 1
sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
    --es smoke_path "$CLIP" --es smoke_mime "$MIME" > /dev/null
sleep 9
RESUMED=$(sh_ "dumpsys activity activities" | grep -oE "ResumedActivity: ActivityRecord\{[^}]*\}" | head -1)
if echo "$RESUMED" | grep -q "com.gan.spatialplayer"; then
  pass "the player is the foreground activity"
else
  fail "the player is not foreground (${RESUMED:-none})"; exit 1
fi
check_geometry "portrait" "$(geometry)"

say "rotate to landscape"
sh_ settings put system user_rotation 1
sleep 6
check_geometry "landscape" "$(geometry)"

say "rotate back to portrait"
sh_ settings put system user_rotation 0
sleep 6
check_geometry "portrait again" "$(geometry)"

say "rotate the other way and back, which is where a one-pass recompute loses it"
sh_ settings put system user_rotation 3
sleep 5
check_geometry "landscape (other way)" "$(geometry)"
sh_ settings put system user_rotation 0
sleep 5
check_geometry "portrait (third pass)" "$(geometry)"

say "cleanup"
restore_rotation
sh_ am force-stop "$PKG"
info "rotation settings restored (accelerometer=${ORIGINAL_ACCEL:-1} user=${ORIGINAL_ROTATION:-0})"

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
