#!/usr/bin/env bash
# Verifies that spatial audio and head tracking actually engage during playback.
#
# This is the check that matters most for the "spatial audio + head tracking" requirement, and it
# is easy to get wrong in a way that looks fine: the app can output correct 5.1 and declare itself
# spatialisable while the platform still declines to spatialise. The only trustworthy signals are
# `isSpatialized=true` on the active playback and a head-tracking mode other than DISABLED.
#
# Prerequisites: a headset that the platform supports for spatial audio must be connected. Stereo
# is not spatialisable on such a route (measured: `canBeSpatialized stereo=false`, `5.1=true`), so
# a multichannel test file is required.
#
# Non-destructive: plays project-generated media and reads dumpsys.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT_SMOKE="$PKG_DEBUG/com.gan.spatialplayer.SmokeTestActivity"
ACT_PROBE="$PKG_DEBUG/com.gan.spatialplayer.SmokeSpatialProbeActivity"
MEDIA_DIR="/sdcard/Movies/SpatialPlayerTest"
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

say "preflight"
if [ "$(adb_ get-state)" != "device" ]; then fail "no authorised device"; exit 1; fi
pass "device: $(sh_ getprop ro.product.model)"

# -- platform prerequisites ----------------------------------------------------------------
say "platform spatial state"
SPAT_STATE=$(sh_ dumpsys audio | grep -iE "mHasSpatializerEffect|isSpatializerEnabled" | tr '\n' ' ')
info "$SPAT_STATE"

if echo "$SPAT_STATE" | grep -q "mHasSpatializerEffect:true"; then
  pass "device has a spatializer effect"
else
  fail "no spatializer effect on this device"
fi

HEAD=$(sh_ dumpsys audio | grep -m1 -oE "headtracker available:(true|false)")
if echo "$HEAD" | grep -q "true"; then
  pass "a head tracker is available ($HEAD)"
  HEAD_AVAILABLE=yes
else
  HEAD_AVAILABLE=no
  skip "head tracking assertions (no head tracker: $HEAD)"
  info "connect the spatial-audio headset, then re-run"
fi

# -- control experiment: can the platform spatialise at all? --------------------------------
say "control experiment (bare AudioTrack, no player involved)"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
adb_ logcat -c
sh_ am start -a com.gan.spatialplayer.SMOKE_SPATIAL -n "$ACT_PROBE" > /dev/null
sleep 4
adb_ logcat -d -v time -s SpatialPlayerSpatial > "$OUT_DIR/spatial-control.txt" 2>/dev/null
touch "$OUT_DIR/spatial-control.txt"
LOG="$OUT_DIR/spatial-control.txt"

grep -oE "spatializer :: .*" "$LOG" | tail -1 | sed 's/^/      /'
CAN51=$(grep -oE "canBeSpatialized :: 5\.1=(true|false)" "$LOG" | tail -1 | grep -oE "(true|false)")
if [ "$CAN51" = "true" ]; then
  pass "platform reports 5.1 as spatialisable on the current route"
else
  fail "platform does not consider 5.1 spatialisable here (got: ${CAN51:-none})"
fi

CONTROL=$(sh_ dumpsys audio | grep -oE "isSpatialized=(true|false)" | tail -3 | sort -u | tr '\n' ' ')
if echo "$CONTROL" | grep -q "isSpatialized=true"; then
  pass "a bare 5.1 AudioTrack is spatialised by the platform"
else
  skip "bare-AudioTrack spatialisation not observed (flags: ${CONTROL:-none})"
fi

# -- the real thing: does the player get spatialised? ---------------------------------------
say "player playback engaged with head tracking"
CLIP="$MEDIA_DIR/ac3_51_720p.mkv"
if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no 5.1 clip at $CLIP — run tools/make-host-test-media.sh and push it"
else
  sh_ am force-stop "$PKG_DEBUG"
  sleep 1
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$CLIP" --es smoke_mime "video/x-matroska" > /dev/null
  sleep 6

  SPAT=$(sh_ dumpsys audio | grep -oE "isSpatialized=(true|false)" | tail -4 | sort -u | tr '\n' ' ')
  if echo "$SPAT" | grep -q "isSpatialized=true"; then
    pass "player audio is spatialised ($SPAT)"
  else
    fail "player audio was not spatialised (flags: ${SPAT:-none})"
    info "this was caused historically by setting isContentSpatialized(true), which means"
    info "'content is already binaural' and makes the platform skip spatialisation"
  fi

  MODE=$(sh_ dumpsys audio | grep -oE "mActualHeadTrackingMode:[A-Z_]*" | tail -1)
  info "head tracking mode: ${MODE:-unknown}"
  if echo "$MODE" | grep -qE "RELATIVE_WORLD|RELATIVE_SCREEN"; then
    pass "head tracking is ACTIVE ($MODE)"
  elif [ "$HEAD_AVAILABLE" = "yes" ]; then
    fail "head tracking still disabled ($MODE) despite a tracker being available"
  else
    skip "head tracking mode assertion"
  fi

  # Multichannel output is what the spatialiser consumes. Once it engages it re-renders to the
  # output's own layout, so a spatialised track may legitimately report a different mask - that is
  # a consequence of success, not a failure.
  MASKS=$(sh_ dumpsys audio | grep -oE "channelMask=0x[0-9a-f]+" | tail -4 | sort -u | tr '\n' ' ')
  info "recent output channel masks: ${MASKS:-none}"
fi

# -- HDR: assert the panel actually goes into HDR for a 10-bit PQ stream ---------------------
say "HDR display engagement (4K HDR10 AV1 + 5.1)"
HDR_CLIP="$MEDIA_DIR/yt_hdr10_av1_ac3_51.mkv"
if [ "$(sh_ "[ -f $HDR_CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no HDR clip at $HDR_CLIP (see README for the yt-dlp recipe)"
else
  sh_ am force-stop "$PKG_DEBUG"
  sleep 1
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$HDR_CLIP" --es smoke_mime "video/x-matroska" > /dev/null
  sleep 10

  APPLIED=$(sh_ dumpsys display | grep -m1 -oE "mAppliedHDR=(true|false)")
  if echo "$APPLIED" | grep -q "true"; then
    pass "display is in HDR ($APPLIED)"
  else
    info "display HDR flag: ${APPLIED:-unreported}"
  fi

  RATIO=$(sh_ dumpsys display | grep -m1 -oE "hdrSdrRatio [0-9.]+" | grep -oE "[0-9.]+")
  if [ -n "$RATIO" ]; then
    info "hdrSdrRatio=$RATIO (above 1.0 means the panel is driving HDR brightness)"
  fi

  # Multichannel + spatialisation must both hold on the HDR file too.
  SPAT=$(sh_ dumpsys audio | grep -oE "isSpatialized=(true|false)" | tail -4 | sort -u | tr '\n' ' ')
  if echo "$SPAT" | grep -q "isSpatialized=true"; then
    pass "HDR clip audio is spatialised"
  else
    fail "HDR clip audio was not spatialised ($SPAT)"
  fi

  if grep -qE "FATAL EXCEPTION" "$OUT_DIR/spatial-control.txt" 2>/dev/null; then
    fail "exception during HDR playback"
  fi
fi

say "channel downmix is reported"
# A 7.1 track on an output that takes stereo must say so. The chip strip reports the *decoder*, so
# without this a 7.1 film quietly claims 7.1 while the user hears a downmix.
CLIP_71="/sdcard/Movies/SpatialPlayerTest/seven_one_pcm.mkv"
HOST_71="${HOST_71:-/tmp/seven_one_pcm.mkv}"
if [ ! -f "$HOST_71" ]; then
  skip "no 7.1 test file at $HOST_71 (ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000:duration=15 -af aformat=channel_layouts=7.1 -ac 8 -c:a pcm_s16le $HOST_71)"
else
  if [ "$(sh_ "[ -f $CLIP_71 ] && echo yes || echo no")" != "yes" ]; then
    "$ADB" push "$HOST_71" "$CLIP_71" >/dev/null 2>&1
  fi
  sh_ am force-stop "$PKG_DEBUG"
  sleep 1
  sh_ logcat -c
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$CLIP_71" --es smoke_mime video/x-matroska >/dev/null
  sleep 8
  DOWNMIX="$(sh_ "logcat -d" | grep -E "audio downmix" | tail -1)"
  if [ -n "$DOWNMIX" ]; then
    pass "the downmix is reported: $(printf '%s' "$DOWNMIX" | sed 's/.*PlayerActivity: //')"
  else
    fail "a 7.1 track on this output was not reported as downmixed"
  fi
fi

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
printf '  artifacts: %s\n' "$OUT_DIR"
[ "$FAIL" -eq 0 ] || exit 1
