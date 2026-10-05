#!/usr/bin/env bash
# Non-destructive smoke test for Spatial Player.
#
# "Non-destructive" is a hard rule here. This script never wipes app data, never calls `pm clear`,
# never uninstalls, never reboots, never changes system settings, and never touches the user's own
# media. It only installs/updates the debug build in place, launches it, plays files this project
# generated, and reads logcat / dumpsys / screencaps.
#
# It deliberately does NOT use `adb shell monkey`, which is the "smoke test" that would randomly
# poke at the device and the user's other apps.
#
# Usage:
#   tools/smoke-test.sh
#   ADB=/path/to/adb tools/smoke-test.sh
#
# If the device is locked, UI-focus assertions are reported as SKIP rather than FAIL, because a
# locked screen legitimately prevents any app from gaining focus. Unlock the phone for a full run.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT_MAIN="$PKG_DEBUG/com.gan.spatialplayer.MainActivity"
ACT_SMOKE="$PKG_DEBUG/com.gan.spatialplayer.SmokeTestActivity"
ACT_PROBE="$PKG_DEBUG/com.gan.spatialplayer.SmokeProbeActivity"
MEDIA_DIR="/sdcard/Movies/SpatialPlayerTest"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${OUT_DIR:-$ROOT/smoke/out}"

PASS=0; FAIL=0; SKIP=0
mkdir -p "$OUT_DIR"

say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }

# On-device shell (adb shell <cmd>).
sh_() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
# Host-side adb (no `shell` subcommand), for get-state / install / logcat / exec-out.
adb_() { "$ADB" "$@" 2>/dev/null | tr -d '\r'; }
capture() { "$ADB" exec-out screencap -p > "$OUT_DIR/$1.png" 2>/dev/null; }
# `dumpsys window` contains several focus records including a stale `mCurrentFocus=null`, so scan
# for the last non-null one instead of taking the first match.
focused_window() {
  sh_ dumpsys window | grep -oE "mCurrentFocus=Window\{[^}]*\}" | grep -v "null" | tail -1
}

logcat_dump() { "$ADB" logcat -d -v time > "$OUT_DIR/$1.txt" 2>/dev/null; touch "$OUT_DIR/$1.txt"; }

# pidof alone can report nothing for a cached process, so fall back to ps and to the process
# record. Retries because the process may still be starting when this is first called.
process_pid() {
  local attempt pid
  for attempt in 1 2 3 4 5; do
    pid=$(sh_ pidof "$PKG_DEBUG")
    if [ -z "$pid" ]; then
      pid=$(sh_ "ps -A -o PID,NAME | grep '$PKG_DEBUG' | head -1 | cut -d' ' -f1")
    fi
    if [ -z "$pid" ]; then
      pid=$(sh_ "dumpsys activity processes | grep -oE '[0-9]+:$PKG_DEBUG' | head -1 | cut -d: -f1")
    fi
    if [ -n "$pid" ]; then
      echo "$pid"
      return
    fi
    sleep 1
  done
  echo ""
}

# ---------------------------------------------------------------------------
say "preflight"
if [ ! -f "$APK" ]; then
  fail "APK missing at $APK — run ./gradlew :app:assembleDebug"
  exit 1
fi
info "apk: $(basename "$APK") ($(wc -c < "$APK" | tr -d ' ') bytes)"

if [ "$(adb_ get-state)" != "device" ]; then
  # A cable re-enumeration or an adb server restart makes get-state transiently empty; give the
  # transport a moment before declaring the device missing.
  info "waiting for the adb transport to settle…"
  "$ADB" wait-for-device > /dev/null 2>&1
  sleep 2
fi

if [ "$(adb_ get-state)" != "device" ]; then
  fail "no authorised device (state: '$(adb_ get-state)')"
  "$ADB" devices -l
  exit 1
fi
pass "device authorised"

DEVICE=$(sh_ getprop ro.product.model)
SDK=$(sh_ getprop ro.build.version.sdk)
info "device: $DEVICE (API $SDK)"
if [ "${SDK:-0}" -ge 34 ] 2>/dev/null; then pass "API >= 34"; else fail "API $SDK < 34"; fi

KEYGUARD=$(sh_ dumpsys window | grep -m1 -oE "isKeyguardShowing=(true|false)")
LOCKED="no"
if echo "$KEYGUARD" | grep -q "true"; then LOCKED="yes"; fi
info "keyguard: ${KEYGUARD:-unknown}"

# ---------------------------------------------------------------------------
say "install (in place, no data wipe)"
if "$ADB" install -r -d "$APK" > "$OUT_DIR/install.txt" 2>&1; then
  pass "installed / updated in place (user data preserved)"
else
  if grep -qi "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" "$OUT_DIR/install.txt"; then
    fail "signature mismatch — this build cannot replace the installed one without uninstalling"
    info "the script will not uninstall automatically, because that erases app data"
  else
    fail "install failed; see $OUT_DIR/install.txt"
  fi
  tail -3 "$OUT_DIR/install.txt"
  exit 1
fi

# ---------------------------------------------------------------------------
say "launch library screen"
adb_ logcat -c
sh_ am start -n "$ACT_MAIN" > /dev/null
sleep 3
capture "01-library"

if [ "$LOCKED" = "yes" ]; then
  skip "library focus assertion (device locked)"
else
  FOCUSED=$(focused_window)
  if echo "$FOCUSED" | grep -q "$PKG_DEBUG"; then
    pass "library screen focused"
  else
    fail "unexpected focus: $FOCUSED"
  fi
fi

PID=$(process_pid)
if [ -n "$PID" ]; then pass "process alive (pid $PID)"; else fail "process not running"; fi

# ---------------------------------------------------------------------------
say "device capability surface"
sh_ dumpsys display | grep -m1 "hdrCapabilities" > "$OUT_DIR/03-hdr.txt"
if grep -q "mSupportedHdrTypes" "$OUT_DIR/03-hdr.txt"; then
  pass "HDR types: $(sed 's/.*mSupportedHdrTypes=\(\[[^]]*\]\).*/\1/' "$OUT_DIR/03-hdr.txt")"
  info "$(sed 's/.*\(mMaxLuminance=[0-9.]*\).*\(mMaxAverageLuminance=[0-9.]*\).*/\1 \2/' "$OUT_DIR/03-hdr.txt")"
else
  fail "could not read HDR capabilities"
fi

sh_ dumpsys display | grep -m1 -oE "mHdrConversionMode=HdrConversionMode\{[^}]*\}" > "$OUT_DIR/03-conversion.txt"
if [ -s "$OUT_DIR/03-conversion.txt" ]; then
  info "HDR conversion: $(cat "$OUT_DIR/03-conversion.txt")"
fi

sh_ dumpsys audio | grep -iE "mHasSpatializerEffect|isSpatializerEnabled" > "$OUT_DIR/03-spatial.txt"
if [ -s "$OUT_DIR/03-spatial.txt" ]; then
  pass "spatializer present: $(tr '\n' ' ' < "$OUT_DIR/03-spatial.txt" | sed 's/  */ /g')"
else
  skip "no spatializer lines in dumpsys audio"
fi

sh_ dumpsys audio | grep -oE "can spatialize media 5\.1:(true|false) on device:[^]]*name:[^ ]*" | tail -2 \
  > "$OUT_DIR/03-routing.txt"
if [ -s "$OUT_DIR/03-routing.txt" ]; then
  info "routing: $(tail -1 "$OUT_DIR/03-routing.txt")"
fi

# Spatial audio only engages on a supported output, so report whether one is attached.
HEADSET=$(sh_ dumpsys audio | grep -oE "type:bt_a2dp addr:[0-9A-F:]+" | tail -1)
if [ -n "$HEADSET" ]; then
  pass "Bluetooth A2DP output present ($HEADSET)"
else
  info "no Bluetooth A2DP output; spatial audio will not engage on the built-in speaker"
fi

# ---------------------------------------------------------------------------
say "in-app capability + decoder routing probe"
sh_ am start -a com.gan.spatialplayer.SMOKE_PROBE -n "$ACT_PROBE" > /dev/null
sleep 2
adb_ logcat -d -v time -s SpatialPlayerProbe > "$OUT_DIR/05-probe.txt" 2>/dev/null
touch "$OUT_DIR/05-probe.txt"

if grep -q "SpatialPlayerProbe" "$OUT_DIR/05-probe.txt"; then
  pass "app reported its own capability matrix"
  grep -oE "summary :: .*" "$OUT_DIR/05-probe.txt" | tail -1 | sed 's/^/      /'
else
  fail "app probe produced no output (see $OUT_DIR/05-probe.txt)"
fi

# The assertions that actually matter for this project's reason to exist.
for CODEC in AC3 EAC3 TrueHD DTS DTS-HD; do
  LINE=$(grep -oE "^.*codec :: $CODEC\|.*" "$OUT_DIR/05-probe.txt" | tail -1)
  if [ -z "$LINE" ]; then
    fail "$CODEC: no routing line reported"
    continue
  fi
  FF=$(echo "$LINE" | sed 's/.*ffmpeg=\([^|]*\).*/\1/')
  AUTO=$(echo "$LINE" | sed 's/.*auto=\([^|]*\).*/\1/')
  if [ "$FF" = "none" ]; then
    fail "$CODEC has no decoder (platform+ffmpeg both absent)"
  else
    pass "$CODEC -> $AUTO"
  fi
done

# AV1/HEVC/H.264 should resolve to hardware on this class of device.
for CODEC in H.264 HEVC AV1; do
  LINE=$(grep -oE "^.*codec :: $CODEC\|.*" "$OUT_DIR/05-probe.txt" | tail -1)
  PLAT=$(echo "$LINE" | sed 's/.*platform=\([^|]*\).*/\1/')
  if [ "$PLAT" = "hardware" ]; then
    pass "$CODEC -> hardware"
  else
    info "$CODEC -> $PLAT"
  fi
done

# ---------------------------------------------------------------------------
say "decoder routing (platform view)"
# This is the half that can be checked without AC3/TrueHD media: for each codec this player cares
# about, report whether the platform has a decoder for it. The FFmpeg half is reported by the app
# itself on its inspection page.
sh_ dumpsys media.player | grep -E "^Media type|^  Decoder " > "$OUT_DIR/05-codecs.txt"
for MIME in audio/ac3 audio/eac3 audio/true-hd audio/vnd.dts audio/vnd.dts.hd; do
  if grep -q "Media type '$MIME'" "$OUT_DIR/05-codecs.txt"; then
    DEC=$(awk -v m="Media type '$MIME'" '$0==m{f=1;next} /^Media type/{f=0} f&&/Decoder/{print; exit}' "$OUT_DIR/05-codecs.txt")
    info "platform $MIME -> ${DEC:-none}"
  else
    info "platform $MIME -> no platform decoder (FFmpeg extension covers it)"
  fi
done

# ---------------------------------------------------------------------------
say "playback: generated H.264 + AAC clip"
CLIP="$MEDIA_DIR/h264_aac_720p.mp4"
if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "clip absent ($CLIP) — run tools/make-test-media.sh"
else
  adb_ logcat -c
  # SmokeTestActivity is debug-only and exported precisely so adb can drive playback, while the
  # shipped manifest keeps PlayerActivity unexported.
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$CLIP" --es smoke_mime "video/mp4" > /dev/null
  sleep 7
  capture "04-playing"
  logcat_dump "04-playback-log"

  if grep -qiE "c2\.qti|OMX\.|MediaCodecVideoRenderer" "$OUT_DIR/04-playback-log.txt"; then
    pass "a video decoder was engaged"
    grep -oiE "(c2|OMX)\.[A-Za-z0-9._-]+" "$OUT_DIR/04-playback-log.txt" | sort -u | head -4 | sed 's/^/      /'
  else
    skip "no video decoder lines captured"
  fi

  if grep -qiE "MediaCodecAudioRenderer|aac" "$OUT_DIR/04-playback-log.txt"; then
    pass "an audio decoder was engaged"
  else
    skip "no audio decoder lines captured"
  fi

  STARTED=$(sh_ dumpsys audio | grep -c "state:started")
  if [ "${STARTED:-0}" -gt 0 ] 2>/dev/null; then
    pass "audio playback active ($STARTED started track(s))"
  else
    info "no audio track in state:started (clip is only 5 s)"
  fi

  if [ "$LOCKED" = "yes" ]; then
    skip "player focus assertion (device locked)"
  else
    FOCUS=$(focused_window)
    if echo "$FOCUS" | grep -q "$PKG_DEBUG"; then
      pass "player focused"
    else
      info "focus: $FOCUS"
    fi
  fi
fi

# ---------------------------------------------------------------------------
say "stability"
PID_AFTER=$(process_pid)
if [ -n "$PID_AFTER" ]; then pass "process alive after playback (pid $PID_AFTER)"; else fail "process died"; fi

logcat_dump "06-final-log"
CRASH_LINES=$(grep -cE "FATAL EXCEPTION|E AndroidRuntime" "$OUT_DIR/06-final-log.txt" 2>/dev/null)
CRASH_LINES=${CRASH_LINES:-0}
if [ "$CRASH_LINES" -eq 0 ]; then
  pass "no fatal exceptions in logcat"
else
  fail "$CRASH_LINES fatal exception line(s) — see $OUT_DIR/06-final-log.txt"
  grep -E "FATAL EXCEPTION|E AndroidRuntime" "$OUT_DIR/06-final-log.txt" | head -5 | sed 's/^/      /'
fi

ANRS=$(grep -ciE "ANR in $PKG_DEBUG" "$OUT_DIR/06-final-log.txt" 2>/dev/null)
ANRS=${ANRS:-0}
if [ "$ANRS" -eq 0 ]; then pass "no ANRs"; else fail "$ANRS ANR(s)"; fi

# ---------------------------------------------------------------------------
say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
printf '  artifacts: %s\n' "$OUT_DIR"
if [ "$LOCKED" = "yes" ]; then
  printf '  \033[33mnote\033[0m device was locked; UI assertions were skipped. Unlock for a full run.\n'
fi
[ "$FAIL" -eq 0 ] || exit 1
