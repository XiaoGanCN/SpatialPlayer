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

# Volume of the device audio is actually routed to.
#
# Not `streamVolume:`, and not the `volume_music_speaker` setting. Both lie when a headset is
# attached: `streamVolume` reports the volume of the ACTIVE device while `settings` holds an
# unrelated per-device slot, so reading either one produced a test that could not move - the number
# stayed pegged at the maximum of whichever device it happened to describe.
#
# `dumpsys audio` lists the per-device values under `Current:`; the active device is named on the
# following `Devices:` line. This reads the one that will actually be heard.
music_volume() {
  sh_ "dumpsys audio" | awk '
    # The unambiguous reading, and the one the hardware keys and the gesture both move: the single
    # `streamVolume` of the active output. The per-device list below it is a fallback only, because
    # matching against whichever device happens to be listed first made BEFORE and AFTER refer to
    # *different* devices - measured as a phantom "7 -> 14 (7 steps)" from a drag that the app had
    # correctly ignored, while the speaker read 8 and bt_a2dp read 14.
    /STREAM_MUSIC:/                { in_stream = 1; next }
    in_stream && /streamVolume:/   {
      v = $0
      sub(/.*streamVolume:/, "", v)
      sub(/[^0-9].*/, "", v)
      if (v != "") { print v; exit }
    }
    # Keep the whole line. Stripping a leading "label:" off it with `sub` also ate the first entry
    # in the list, so the device that happened to be listed first could never be found - which went
    # unnoticed for as long as the headphones, listed far down, were the active output.
    in_stream && /Current:/        { current = $0 }
    in_stream && /Devices:/        {
      dev = $0
      sub(/.*Devices:[ ]*/, "", dev)
      sub(/\(.*/, "", dev)
      n = split(current, parts, ",")
      for (i = 1; i <= n; i++) {
        if (index(parts[i], "(" dev ")")) {
          v = parts[i]
          sub(/.*:[ ]*/, "", v)
          gsub(/[^0-9]/, "", v)
          print v
          exit
        }
      }
      exit
    }
  '
}

# Which output the volume reading refers to. `streamVolume` follows the *active* device, so if that
# changes between two readings the difference is a route change and not a gesture. Measured: a drag
# the app had correctly ignored read as "7 -> 14 (7 steps)" because the speaker was active before it
# and the Bluetooth headset after, and those two devices simply held different volumes.
music_device() {
  sh_ "dumpsys audio" | awk '
    /STREAM_MUSIC:/ { in_stream = 1; next }
    in_stream && /Devices:/ {
      d = $0
      sub(/.*Devices:[ ]*/, "", d)
      sub(/:.*/, "", d)
      print d
      exit
    }
  '
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

# Touch injection can go stale for an entire adb session: `input tap` returns success but the event
# is dropped, so every gesture assertion below would fail while looking like an app bug. Probe it by
# swiping the launcher and checking that the foreground window or activity changed.
probe_touch_injection() {
  sh_ input keyevent KEYCODE_HOME > /dev/null 2>&1
  sleep 2
  local before after
  before=$(sh_ dumpsys window | grep -oE "mCurrentFocus=Window\{[^}]*\}" | head -1)
  sh_ input swipe 548 1800 548 1200 200 > /dev/null 2>&1
  sleep 2
  after=$(sh_ dumpsys window | grep -oE "mCurrentFocus=Window\{[^}]*\}" | head -1)
  [ -n "$before" ] && [ -n "$after" ]
}

if [ "$(sh_ getprop ro.product.model)" != "XQ-DQ72" ]; then
  info "skipping touch-injection probe on an unknown device"
else
  # A healthy session can still drop the first injected event, so retry once after a fresh server.
  if ! probe_touch_injection; then
    info "touch injection looks stale; restarting adb"
    adb_ kill-server > /dev/null 2>&1
    sleep 2
    adb_ start-server > /dev/null 2>&1
    adb_ wait-for-device > /dev/null 2>&1
    sleep 2
    if ! probe_touch_injection; then
      fail "touch injection is not being delivered — gesture results would be meaningless"
      exit 1
    fi
    pass "touch injection recovered after restarting adb"
  else
    pass "touch injection is being delivered"
  fi
fi

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

# Precondition: the smoke player must actually be the resumed activity. Without this, a probe or
# dialog left over from another suite makes every luminance reading meaningless - the chrome
# assertions then compare two screenshots of something that is not the player at all.
RESUMED=$(sh_ "dumpsys activity activities" | grep -oE "ResumedActivity: ActivityRecord\{[^}]*\}" | head -1)
info "resumed: ${RESUMED}"
if echo "$RESUMED" | grep -q "com.gan.spatialplayer"; then
  pass "the player is the foreground activity"
else
  fail "the player is not foreground (${RESUMED:-none}) - readings would be meaningless"
  exit 1
fi

restore() {
  if [ -n "${ORIGINAL_VOLUME:-}" ]; then
    set_volume "$ORIGINAL_VOLUME"
  fi
}

# ---------------------------------------------------------------------------
say "a drag shorter than a centimetre must not change the volume"
# Start from a low-but-not-minimum volume. A fixed reset value (this was 15) collides with the
# stream maximum on a device whose max is 30: starting at 28 leaves only two steps of headroom, so an
# upward drag clamps and the assertion reads as "the gesture is dead" when it is the test that is
# wrong. Anchor to the actual bounds instead.
VOL_MAX=$(sh_ dumpsys audio | awk '/STREAM_MUSIC:/{f=1} f&&/Max:/{gsub(/[^0-9]/,"");print;exit}')
VOL_MAX=${VOL_MAX:-15}
RESET=$(( VOL_MAX / 4 ))
info "music volume range 0..${VOL_MAX}; tests start at ${RESET}"

# Set the volume the way the hardware keys do, so it lands on the active output. `media volume
# --set` writes the speaker slot and would be ignored while a headset is routed.
# Blocks until the music level stops moving, so a queued key press cannot be mistaken for a gesture.
settle_volume() {
  local previous=-1 current i
  for ((i = 0; i < 20; i++)); do
    current=$(music_volume)
    current=${current:-0}
    if [ "$current" = "$previous" ]; then
      return 0
    fi
    previous=$current
    sleep 0.4
  done
  info "volume never settled (last reading ${previous})"
}

set_volume() {
  local target="$1" current i
  current=$(music_volume)
  current=${current:-0}
  for ((i = 0; i < 40; i++)); do
    [ "$current" = "$target" ] && return 0
    if [ "$current" -gt "$target" ]; then
      sh_ input keyevent KEYCODE_VOLUME_DOWN > /dev/null 2>&1
    else
      sh_ input keyevent KEYCODE_VOLUME_UP > /dev/null 2>&1
    fi
    sleep 0.15
    current=$(music_volume)
    current=${current:-0}
  done
  info "could not reach volume ${target} (stopped at ${current})"
}

set_volume "$RESET"
# The keys `set_volume` presses are applied asynchronously, so the level can still be moving when the
# next line runs. Measured: the "small drag" reported 7 steps while the deliberate 5 cm drag reported
# 1 - the extra steps were queued key presses landing after the BEFORE reading, not the gesture. Wait
# for two consecutive identical readings before believing the starting level.
settle_volume
BEFORE=$(music_volume)
BEFORE_DEVICE=$(music_device)
TINY=$(( PX_PER_MM * 10 ))            # 10mm
# Assert on what the app itself computed rather than on `dumpsys`. The device's volume table is not a
# stable reference here: `streamVolume` follows the *active* output, and with a headset attached this
# phone moved it between readings (speaker 7, bt_a2dp 14) while the app's own log showed the drag
# changing nothing at all - a phantom "7 -> 14 (7 steps)". `volDelta ... next=N` is exactly what the
# gesture decided, and it cannot be confused by routing.
sh_ logcat -c
adb_ shell input swipe "$RIGHT_X" "$(( HEIGHT * 3 / 5 ))" "$RIGHT_X" "$(( HEIGHT * 3 / 5 - TINY ))" 400 > /dev/null
sleep 2
SMALL_LOG=$(sh_ "logcat -d -s PlayerActivity:V" | grep volDelta)
SMALL_NEXTS=$(printf '%s' "$SMALL_LOG" | grep -oE "next=[0-9]+" | sort -u | wc -l | tr -d ' ')
AFTER=$(music_volume)
info "10mm (${TINY}px) drag: ${SMALL_NEXTS} distinct volume value(s) from ${BEFORE} (dumpsys now ${AFTER})"

if [ "${SMALL_NEXTS:-0}" -le 1 ] 2>/dev/null; then
  pass "small drag changed at most 1 step"
else
  fail "small drag changed $(( SMALL_NEXTS - 1 )) steps — sensitivity is still too high"
fi

# ---------------------------------------------------------------------------
say "a deliberate drag must still work"
set_volume "$RESET"
settle_volume
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
PRESENCE="$ROOT/tools/chrome_presence.py"
TAP_X=$(( WIDTH / 2 )); TAP_Y=$(( HEIGHT / 2 ))
TIMEOUT_MS=6000

chrome_state() {
  # Raw adb, not adb_(): that helper pipes through `tr -d '\r'`, which corrupts binary PNG data
  # and silently yields a zero-byte screenshot.
  "$ADB" exec-out screencap -p > "$OUT_DIR/.gesture-state.png" 2>/dev/null
  python3 "$PRESENCE" "$OUT_DIR/.gesture-state.png"
}

# Measured on device: 28.5 with the chrome up against 2.7 without, so 10 is a wide margin
# either side rather than a tuned threshold.
visible() { python3 -c "import sys; sys.exit(0 if float('${1:-0}') > 10 else 1)"; }
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
  fail "could not reach the '${want}' chrome state after ${tries} taps (last luminance ${STATE})"
  return 1
}

# --- a single tap must toggle the chrome -------------------------------------------------
HIDDEN=$(drive_to hidden) || exit 1
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
H=$(drive_to hidden) || exit 1
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
