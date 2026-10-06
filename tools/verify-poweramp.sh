#!/usr/bin/env bash
# Verifies the optional Poweramp library integration.
#
# Poweramp is the only media library this player reads, and it exposes a REST-style provider whose
# schema is undocumented and easy to get wrong (it needs a `limit` parameter, its columns collide
# across joins, and Android 11+ package visibility hides it entirely unless declared in the
# manifest). This script asserts the whole path end to end.
#
# Non-destructive: read-only queries against Poweramp. Nothing is written or cleared.
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG_DEBUG="com.gan.spatialplayer.debug"
ACT="$PKG_DEBUG/com.gan.spatialplayer.SmokePowerampProbeActivity"
TAG="SpatialPlayerPoweramp"
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

say "is Poweramp installed on the device?"
# `pm path` is a direct lookup and avoids piping a very long package list through grep.
if [ -n "$(sh_ pm path com.maxmpz.audioplayer)" ]; then
  pass "com.maxmpz.audioplayer present"
else
  skip "Poweramp is not installed; nothing to verify"
  printf '  skipped %d\n' "$SKIP"
  exit 0
fi

say "device-side provider reachability"
if sh_ "content query --uri 'content://com.maxmpz.audioplayer.data/files?limit=1' --projection 'folder_files._id:folder_files.name'" | grep -q "Row:"; then
  pass "provider answers a well-formed query"
else
  fail "provider did not answer (see the schema notes in PowerampReader)"
fi

say "in-app reader"
sh_ am force-stop "$PKG_DEBUG"
sleep 1
adb_ logcat -c
sh_ am start -a com.gan.spatialplayer.SMOKE_POWERAMP -n "$ACT" > /dev/null
sleep 7
adb_ logcat -d -v time > "$OUT_DIR/poweramp.txt" 2>/dev/null
touch "$OUT_DIR/poweramp.txt"
LOG="$OUT_DIR/poweramp.txt"

# The package-visibility trap: without a <queries> entry this reports false even when installed.
if grep -q "installed :: true" "$LOG"; then
  pass "app can see Poweramp (package visibility declared)"
else
  fail "app reports Poweramp as not installed — check the <queries> block in AndroidManifest"
fi

COUNT=$(grep -oE "count :: [0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+")
if [ "${COUNT:-0}" -gt 0 ] 2>/dev/null; then
  pass "read $COUNT entries"
else
  fail "read no entries; status: $(grep -oE 'status :: .*' "$LOG" | tail -1)"
fi

# Names and durations are what make the entries usable in the list.
WITH_DURATION=$(grep -oE "with_duration=[0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+")
if [ "${WITH_DURATION:-0}" -gt 0 ] 2>/dev/null; then
  pass "$WITH_DURATION entries carry a duration"
else
  info "no durations reported"
fi

# Entries must resolve to a URI that can actually be opened. Poweramp's own per-file URI cannot
# be streamed, so the reader resolves through MediaStore; a regression here reintroduces the
# "source error" that made Poweramp tracks unplayable.
if grep -qE "entry :: .+content://media/external/audio/media/[0-9]+" "$LOG"; then
  pass "entries resolve to openable MediaStore URIs"
  grep -oE "entry :: .*" "$LOG" | head -2 | sed 's/^/      /'
elif grep -qE "entry :: .+content://com\.maxmpz\.audioplayer\.data/files/" "$LOG"; then
  fail "entries still use Poweramp's own URI, which cannot be opened (source error)"
else
  fail "entries have no playable URI"
fi

# Artist and album must actually resolve. The provider's files view joins them, but an earlier
# version read them from separate lookup tables and every album came back blank.
SUMMARY=$(grep -oE "summary :: .*" "$LOG" | head -1)
WITH_ARTIST=$(echo "$SUMMARY" | grep -oE "with_artist=[0-9]+" | grep -oE "[0-9]+")
WITH_ALBUM=$(echo "$SUMMARY" | grep -oE "with_album=[0-9]+" | grep -oE "[0-9]+")
TOTAL=$(echo "$SUMMARY" | grep -oE "total=[0-9]+" | grep -oE "[0-9]+")

if [ -n "$WITH_ARTIST" ] && [ "$WITH_ARTIST" = "$TOTAL" ]; then
  pass "all $TOTAL entries carry an artist"
  grep -oE "entry :: .*artist=[^|]*" "$LOG" | head -1 | sed 's/^/      /'
else
  fail "artist missing on some entries (${WITH_ARTIST:-0}/$TOTAL)"
fi

if [ -n "$WITH_ALBUM" ] && [ "$WITH_ALBUM" = "$TOTAL" ]; then
  pass "all $TOTAL entries carry an album"
  grep -oE "entry :: .*album=[^|]*" "$LOG" | head -1 | sed 's/^/      /'
else
  fail "album missing on some entries (${WITH_ALBUM:-0}/$TOTAL)"
fi

# And prove the resolved URI actually opens.
OPENED=$(grep -oE "openStream :: .*" "$LOG" | head -1)
if echo "$OPENED" | grep -q "ok firstByte"; then
  pass "a resolved URI opens for reading"
else
  info "openStream probe: ${OPENED:-not captured}"
fi

if grep -qE "FATAL EXCEPTION|E AndroidRuntime" "$LOG"; then
  fail "exception while reading Poweramp"
  grep -E "FATAL EXCEPTION|E AndroidRuntime" "$LOG" | head -3 | sed 's/^/      /'
else
  pass "no exceptions"
fi

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
printf '  artifacts: %s\n' "$OUT_DIR"
[ "$FAIL" -eq 0 ] || exit 1
