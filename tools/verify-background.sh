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
PKG_DEBUG="org.spatialplayer.debug"
ACT_SMOKE="$PKG_DEBUG/org.spatialplayer.SmokeTestActivity"
DIR="/sdcard/Movies/SpatialPlayerTest"
AUDIO_CLIP="$DIR/stems_51.mkv"       # audio only: six channels, no video track
VIDEO_CLIP="$DIR/yt_1080p_ac3_51.mkv" # a minute of video and audio; the short grid clip ended mid-test

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

# The player's own published state. `position` is what proves progress: a paused session keeps its
# position, so a state string alone would pass even if playback had stopped.
# Our session's state, not whichever session `dumpsys media_session` happens to list first - other
# players on the device publish sessions too, and reading theirs produced a "video kept playing"
# failure against a player that had correctly paused. `PLAYING(3)` keeps the numeric state, because
# stripping it made the audio checks fail on a correct player rather than the reverse.
session_state() {
  sh_ dumpsys media_session | awk -v pkg="$PKG_DEBUG" '
    # Anchored on the session package line itself. Matching the package name anywhere caught an
    # earlier mention of it and then reported the next state in the dump - which belonged to a stale
    # Bluetooth session carrying "Bluetooth audio disconnected", i.e. ERROR(7).
    $1 == ("package=" pkg) { ours = 1; n = 0; next }
    ours { n++ }
    ours && n < 12 && /state=PlaybackState/ {
      line = $0
      if (match(line, /state=[A-Z]+\([0-9]+\)/)) {
        print substr(line, RSTART + 6, RLENGTH - 6)
        exit
      }
    }
  '
}

session_position() {
  sh_ dumpsys media_session | awk -v pkg="$PKG_DEBUG" '
    $1 == ("package=" pkg) { ours = 1; n = 0; next }
    ours { n++ }
    ours && n < 12 && /state=PlaybackState/ {
      line = $0
      if (match(line, /position=[0-9]+/)) {
        print substr(line, RSTART + 9, RLENGTH - 9)
        exit
      }
    }
  '
}

music_volume() {
  sh_ dumpsys audio | awk '
    /STREAM_MUSIC:/ { f = 1; next }
    f && /streamVolume:/ { v = $0; sub(/.*streamVolume:/, "", v); sub(/[^0-9].*/, "", v); print v; exit }
  '
}
# Leaves the app. `KEYCODE_HOME` alone is not enough: if the notification shade happens to be open it
# only closes the shade, the activity is never stopped, and the suite reports a player that "kept
# playing in the background" when it was on screen the whole time. Measured exactly that.
go_home() {
  sh_ cmd statusbar collapse > /dev/null 2>&1
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null 2>&1
  sleep 1
}

# The window that currently has focus, or empty when the dump could not be read.
focused_window() {
  sh_ dumpsys window | grep -oE "mCurrentFocus=Window\{[^}]*\}" | head -1
}

# Wakes the screen and waits until something other than the notification shade has focus.
#
# `KEYCODE_WAKEUP` on its own is not enough on this device. Measured: after the screen-off check the
# phone wakes onto its lock screen, the shade holds `mCurrentFocus`, and an app started underneath it
# never becomes visible - so the video is launched, `onStop` runs, and the check reports "video was
# not playing while visible". That was read as an app bug twice before the window dump showed what
# was actually in front. `cmd statusbar collapse`, a swipe-up, the menu key and `wm dismiss-keyguard`
# are all attempted because none of them works in every state - dismiss-keyguard clears an *insecure*
# keyguard and nothing else, and on this phone it works about as often as it does not.
#
# When none of them land, the shade is the phone asking for a credential, and no shell command can
# give it one. The remaining move is a physical touch, which the standing instructions say to ask
# for on the Mac with a modal dialog and a sound. `ASK_TOUCH=0` turns the prompt off for headless
# runs; `wake_visible` then fails loudly rather than silently testing nothing.
wake_visible() {
  local focus i
  for ((i = 0; i < 8; i++)); do
    sh_ input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    sh_ cmd statusbar collapse > /dev/null 2>&1
    sh_ wm dismiss-keyguard > /dev/null 2>&1
    sh_ input keyevent 82 > /dev/null 2>&1
    sh_ input swipe 548 2200 548 600 300 > /dev/null 2>&1
    sleep 1
    focus=$(focused_window)
    case "$focus" in
      *NotificationShade*|"") continue ;;
      *) info "woke onto: ${focus}"; return 0 ;;
    esac
  done

  # `wm dismiss-keyguard` can briefly drop the adb transport while it works, so the state is read
  # once more before declaring defeat - the dump above may have raced it.
  sleep 2
  focus=$(focused_window)
  case "$focus" in
    *NotificationShade*|"") ;;
    *) info "woke onto: ${focus}"; return 0 ;;
  esac

  if [ "${ASK_TOUCH:-1}" = "1" ] && command -v osascript > /dev/null 2>&1; then
    osascript -e 'display dialog "A Spatial Player test run needs the phone, and its lock screen is holding window focus - adb cannot dismiss it. Please swipe up on the phone (PIN if it asks), then click OK." with title "Phone needs a touch" buttons {"OK"} default button 1 with icon caution' > /dev/null 2>&1
    focus=$(focused_window)
    case "$focus" in
      *NotificationShade*|"")
        info "the notification shade still holds focus after asking; a background assertion now would be meaningless"
        return 1
        ;;
      *) info "woke onto: ${focus} (after a touch)"; return 0 ;;
    esac
  fi

  info "the notification shade still holds focus (${focus:-unknown}); a background assertion now would be meaningless"
  return 1
}

play() {
  sh_ am force-stop "$PKG_DEBUG"
  sleep 1
  sh_ am start -a org.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
      --es smoke_path "$1" --es smoke_mime "$2" > /dev/null
  sleep 7
}

say "preflight"
if [ "$(sh_ get-state 2>/dev/null || "$ADB" get-state 2>/dev/null)" != "device" ]; then
  fail "no authorised device"; printf '\n  passed %d   failed %d\n' "$PASS" "$FAIL"; exit 1
fi
pass "device authorised"
sh_ input keyevent KEYCODE_WAKEUP > /dev/null
sh_ am force-stop "$PKG_DEBUG"
sleep 2
ORIGINAL_VOLUME=$(music_volume)
info "music volume starts at ${ORIGINAL_VOLUME:-unknown}"

say "music keeps playing in the background"
if [ "$(sh_ "[ -f $AUDIO_CLIP ] && echo yes || echo no")" != "yes" ]; then
  skip "no audio-only clip at $AUDIO_CLIP"
else
  play "$AUDIO_CLIP" video/x-matroska
  BEFORE=$(session_position)
  go_home
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
if [ "${SKIP_SCREEN_OFF:-0}" = "1" ]; then
  skip "screen-off checks skipped (SKIP_SCREEN_OFF=1)"
elif [ "$(sh_ "[ -f $AUDIO_CLIP ] && echo yes || echo no")" != "yes" ]; then
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
  # The screen has to be genuinely usable first, or the video is launched behind a lock screen and
  # pauses for a reason that has nothing to do with the policy being tested. See `wake_visible`.
  wake_visible || fail "the phone is not showing anything but its lock screen"
  play "$VIDEO_CLIP" video/mp4
  sleep 3
  BEFORE_STATE=$(session_state)
  # Without this the check is vacuous: a video that was already paused "passes" having proved
  # nothing. It has to be playing first for the pause to mean anything.
  if [ "$BEFORE_STATE" != "PLAYING(3)" ]; then
    fail "video was not playing while visible (state ${BEFORE_STATE:-none}), so the pause is unproven"
  else
    go_home
    sleep 5
    STATE=$(session_state)
    info "playing before: ${BEFORE_STATE}; after HOME: ${STATE:-none}"
    if [ "$STATE" != "PLAYING(3)" ]; then
      pass "video paused when the app was backgrounded"
    else
      fail "video kept playing in the background"
    fi
  fi

  # And the screen going off must stop it too, which is the same requirement by another route.
  #
  # Skippable with `SKIP_SCREEN_OFF=1`, because the screen-off half is what leaves this phone on its
  # lock screen (see `wake_visible`), and on a bad run that costs the HOME check above an honest
  # reading. When the device is behaving, run the whole suite.
  if [ "${SKIP_SCREEN_OFF:-0}" = "1" ]; then
    skip "screen-off video check skipped (SKIP_SCREEN_OFF=1)"
  else
  wake_visible || true
  sh_ am start -a org.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
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
