#!/usr/bin/env bash
# Verifies the playback policy that is easy to get backwards: music survives the app going away,
# film does not.
#
# This is asserted from the platform's own view rather than from screenshots, because "is it still
# playing" is exactly what `dumpsys media_session` knows and a picture cannot show. The session being
# listed at all is also the media-centre half of the requirement.
#
# Non-destructive. Leaves the screen on, restores the volume it found, and touches no user data.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT_SMOKE="$PKG_DEBUG/com.gan.spatialplayer.SmokeTestActivity"
DIR="/sdcard/Movies/SpatialPlayerTest"
AUDIO_CLIP="$DIR/stems_51.mkv"       # audio only: six channels, no video track
VIDEO_CLIP="$DIR/grid_720p.mp4"      # has a video track

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

# The player's own published state. `position` is what proves progress: a paused session keeps its
# position, so a state string alone would pass even if playback had stopped.
session_state() {
  sh_ dumpsys media_session | grep -oE "state=PlaybackState \{state=[A-Z]+" | head -1 | sed 's/.*state=//'
}
session_position() {
  sh_ dumpsys media_session | grep -oE "state=PlaybackState \{state=[A-Z]+\([0-9]\), position=[0-9]+" \
    | head -1 | sed 's/.*position=//'
}
music_volume() {
  sh_ dumpsys audio | awk '
    /STREAM_MUSIC:/ { f = 1; next }
    f && /streamVolume:/ { v = $0; sub(/.*streamVolume:/, "", v); sub(/[^0-9].*/, "", v); print v; exit }
  '
}
play() {
  sh_ am force-stop "$PKG_DEBUG"
  sleep 1
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$1" --es smoke_mime "$2" > /dev/null
  sleep 7
}

say "preflight"
if [ "$(sh_ get-state 2>/dev/null || "$ADB" get-state 2>/dev/null)" != "device" ]; then
  fail "no authorised device"; printf '\n  passed %d   failed %d\n' "$PASS" "$FAIL"; exit 1
fi
pass "device authorised"
sh_ input keyevent KEYCODE_WAKEUP > /dev/null
ORIGINAL_VOLUME=$(music_volume)
info "music volume starts at ${ORIGINAL_VOLUME:-unknown}"

say "music keeps playing in the background"
if [ "$(sh_ "[ -f $AUDIO_CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no audio-only clip at $AUDIO_CLIP"
else
  play "$AUDIO_CLIP" video/x-matroska
  BEFORE=$(session_position)
  sh_ input keyevent KEYCODE_HOME
  sleep 8
  STATE=$(session_state)
  AFTER=$(session_position)
  info "state $STATE, position ${BEFORE:-?} -> ${AFTER:-?}"
  if [ "$STATE" = "PLAYING(3)" ]; then
    pass "audio is still playing after the app was backgrounded"
  else
    fail "audio stopped when backgrounded (state ${STATE:-none})"
  fi
  if [ -n "${BEFORE:-}" ] && [ -n "${AFTER:-}" ] && [ "$AFTER" -gt "$BEFORE" ] 2>/dev/null; then
    pass "and it actually advanced while backgrounded"
  else
    fail "the position did not advance while backgrounded"
  fi

  # The media centre listing is the session itself; the notification is a bonus that needs a runtime
  # permission this harness will not grant on the user's behalf.
  if sh_ dumpsys media_session | grep -q "$PKG_DEBUG"; then
    pass "the player is published to the media session"
  else
    fail "no media session was published"
  fi
fi

say "music survives the screen going off"
if [ "$(sh_ "[ -f $AUDIO_CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no audio-only clip"
else
  BEFORE=$(session_position)
  sh_ input keyevent KEYCODE_SLEEP
  sleep 9
  STATE=$(session_state)
  AFTER=$(session_position)
  sh_ input keyevent KEYCODE_WAKEUP > /dev/null
  sleep 1
  info "state $STATE, position ${BEFORE:-?} -> ${AFTER:-?}"
  if [ "$STATE" = "PLAYING(3)" ] && [ -n "${AFTER:-}" ] && [ "${AFTER:-0}" -gt "${BEFORE:-0}" ] 2>/dev/null; then
    pass "audio kept playing with the screen off"
  else
    fail "audio did not survive the screen going off (state ${STATE:-none})"
  fi
fi

say "video does not play in the background"
if [ "$(sh_ "[ -f $VIDEO_CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no video clip at $VIDEO_CLIP"
else
  play "$VIDEO_CLIP" video/mp4
  BEFORE_STATE=$(session_state)
  sh_ input keyevent KEYCODE_HOME
  sleep 5
  STATE=$(session_state)
  info "playing before: ${BEFORE_STATE:-none}; after HOME: ${STATE:-none}"
  if [ "$STATE" != "PLAYING(3)" ]; then
    pass "video paused when the app was backgrounded"
  else
    fail "video kept playing in the background"
  fi

  # And the screen going off must stop it too, which is the same requirement by another route.
  sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$VIDEO_CLIP" --es smoke_mime video/mp4 > /dev/null
  sleep 6
  sh_ input keyevent KEYCODE_SLEEP
  sleep 6
  STATE=$(session_state)
  sh_ input keyevent KEYCODE_WAKEUP > /dev/null
  info "video state with the screen off: ${STATE:-none}"
  if [ "$STATE" != "PLAYING(3)" ]; then
    pass "video paused when the screen went off"
  else
    fail "video kept playing with the screen off"
  fi
fi

say "cleanup"
sh_ input keyevent KEYCODE_WAKEUP > /dev/null
sh_ am force-stop "$PKG_DEBUG"
if [ -n "${ORIGINAL_VOLUME:-}" ]; then
  info "music volume was ${ORIGINAL_VOLUME} and was not changed by this suite"
fi

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
