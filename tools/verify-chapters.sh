#!/usr/bin/env bash
# Verifies that chapters are read out of a Matroska file.
#
# Media3 1.8 has no chapter API at all, so this is our own EBML walk and it is worth asserting on:
# the marker-bit asymmetry in EBML variable-length integers means an ID read as a size (or the
# reverse) silently produces plausible nonsense rather than an error, and a mis-skipped Cluster turns
# a two-second parse into a two-minute one.
#
# The assertions run against a probe activity rather than the UI. The player animates continuously,
# so `uiautomator dump` will not settle ("could not get idle state"), and the only alternative would
# be OCR of a screenshot.
#
# Non-destructive: pushes a small generated file, plays nothing.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT_PROBE="$PKG_DEBUG/com.gan.spatialplayer.SmokeChaptersProbeActivity"
CLIP="/sdcard/Movies/SpatialPlayerTest/chapters_720p.mkv"
HOST_CLIP="${HOST_CLIP:-/tmp/chapters_720p.mkv}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PASS=0; FAIL=0; SKIP=0
say()  { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; PASS=$((PASS+1)); }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; SKIP=$((SKIP+1)); }
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

say "test file"
if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  if [ -f "$HOST_CLIP" ]; then
    "$ADB" push "$HOST_CLIP" "$CLIP" >/dev/null 2>&1
    info "pushed $(basename "$HOST_CLIP")"
  else
    skip "no chaptered file at $CLIP - generate one with:
        printf ';FFMETADATA1\\n[CHAPTER]\\nTIMEBASE=1/1000\\nSTART=0\\nEND=5000\\ntitle=Opening Titles\\n' > /tmp/chapters.txt
        ffmpeg -i clip.mp4 -i /tmp/chapters.txt -map_metadata 1 -map 0:v -c:v copy -c:s copy $HOST_CLIP"
    exit 0
  fi
fi
pass "chaptered Matroska present"

say "read the chapters"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
sh_ logcat -c
sh_ am start -a com.gan.spatialplayer.SMOKE_CHAPTERS -n "$ACT_PROBE" \
    --es chapters_probe_path "$CLIP" >/dev/null
sleep 4

LOG="$(sh_ "logcat -d -s ChaptersProbe:I ChaptersProbe:W")"
if [ -z "$LOG" ]; then
  # Some builds only allow a tag filter through the full buffer.
  LOG="$(sh_ "logcat -d" | grep ChaptersProbe)"
fi
info "probe lines: $(printf '%s' "$LOG" | grep -c 'chapter ::')"

TOTAL="$(printf '%s' "$LOG" | grep -oE 'chapters total=[0-9]+' | grep -oE '[0-9]+' | tail -1)"
if [ "${TOTAL:-0}" = "5" ]; then
  pass "five chapters were parsed"
else
  fail "expected 5 chapters, got '${TOTAL:-none}'"
fi

for title in "Opening Titles" "The Long Hallway" "Interlude" "Finale" "Credits"; do
  if printf '%s' "$LOG" | grep -qF "$title"; then
    pass "title '${title}'"
  else
    fail "title '${title}' missing"
  fi
done

# Times are nanoseconds in the container and milliseconds in the API; getting that wrong is a
# factor-of-a-million error that would still look like a chapter list.
if printf '%s' "$LOG" | grep -qE 'chapter :: 1 \| 5000 \| 11000 \|'; then
  pass "second chapter starts at 5000 ms and ends at 11000 ms"
else
  fail "chapter times are not in milliseconds: $(printf '%s' "$LOG" | grep -m1 'chapter :: 1')"
fi

if printf '%s' "$LOG" | grep -qE 'chapter :: 0 \| 0 \| 5000 \|'; then
  pass "first chapter starts at 0 ms"
else
  fail "first chapter time wrong: $(printf '%s' "$LOG" | grep -m1 'chapter :: 0')"
fi

say "a file with no chapters"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
sh_ logcat -c
sh_ am start -a com.gan.spatialplayer.SMOKE_CHAPTERS -n "$ACT_PROBE" \
    --es chapters_probe_path /sdcard/Movies/SpatialPlayerTest/grid_720p.mp4 >/dev/null
sleep 4
ZERO="$(sh_ "logcat -d" | grep ChaptersProbe | grep -oE 'chapters total=[0-9]+' | grep -oE '[0-9]+' | tail -1)"
if [ "${ZERO:-x}" = "0" ]; then
  pass "a file without chapters reports none, and does not crash"
else
  fail "expected 0 chapters for the plain clip, got '${ZERO:-none}'"
fi

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
