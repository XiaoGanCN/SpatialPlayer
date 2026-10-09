#!/usr/bin/env bash
# Verifies that every inspector panel renders its rows however it was reached, and that the glass is
# actually installed on the screens those panels appear over.
#
# The bug this guards against: the tab strip lives inside the sheet, so switching a panel by tab
# never went through the activity's populate methods. Decoder, Subtitles, Audio, Scaling, Speed and
# Chapters therefore came up empty when switched to from another page, while a page that had been
# opened earlier still looked fine because it was still holding the list it was given the first time.
#
# Why the assertion comes from a probe rather than from the screen:
#   * `uiautomator dump` cannot be used on this screen. Measured: "ERROR: could not get idle state" -
#     the player animates continuously and the dump will not settle.
#   * The sheet is a dialog whose rows are built in code, so a screenshot can only say "something is
#     or is not in the lower two-thirds of the screen".
# So `PanelProbeReceiver` (debug builds only) opens each panel on the *running* player - the same
# `show(panel)` a tab click makes - and logs what that panel rendered.
#
# Non-destructive: plays a file, opens and closes a sheet, changes no settings.
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
info() { printf '  ·  %s\n' "$1"; }
sh_()  { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
adb_() { "$ADB" "$@" 2>/dev/null | tr -d '\r'; }

say "preflight"
if [ "$(adb_ get-state)" != "device" ]; then fail "no authorised device"; exit 1; fi
pass "device: $(sh_ getprop ro.product.model)"

if [ "$(sh_ "[ -f $CLIP ] && echo yes || echo no")" != "yes" ]; then
  fail "no clip at $CLIP - the probe needs a player that is really playing"
  exit 1
fi
pass "clip present"

say "start playback"
sh_ input keyevent KEYCODE_WAKEUP > /dev/null
sh_ am force-stop "$PKG"
sleep 1
sh_ am start -a com.gan.spatialplayer.SMOKE_PLAY -n "$ACT_SMOKE" \
    --es smoke_path "$CLIP" --es smoke_mime "$MIME" > /dev/null
sleep 8

RESUMED=$(sh_ "dumpsys activity activities" | grep -oE "ResumedActivity: ActivityRecord\{[^}]*\}" | head -1)
if echo "$RESUMED" | grep -q "com.gan.spatialplayer"; then
  pass "the player is the foreground activity"
else
  fail "the player is not foreground (${RESUMED:-none}) - the probe would have nothing to drive"
  exit 1
fi

# Read the player's glass line now, before the panel probe clears logcat - the installer logs once,
# when the activity is set up, and `logcat -c` below would take it away. This was measured: the first
# version of this check read the log after the probe and found the library's line instead.
GLASS_PLAYER=$(adb_ logcat -d -s GlassInstaller 2>/dev/null | grep -oE "installed on [0-9]+ pane" | tail -1 | grep -oE "[0-9]+")
if [ "${GLASS_PLAYER:-0}" -ge 1 ] 2>/dev/null; then
  pass "glass installed on the player ($GLASS_PLAYER pane(s))"
else
  fail "glass installed on 0 panes on the player - the installer is matching nothing again"
fi

say "probe every panel"
sh_ logcat -c
sh_ am start -n "$ACT_PROBE" --es probe panel > /dev/null
sleep 3

REPORT=$(sh_ "logcat -d -s PanelProbe:I" | grep -o "panel probe :: .*" | tail -1 | sed 's/panel probe :: //')
if [ -z "$REPORT" ]; then
  fail "the probe produced no report (look for PanelProbe in logcat)"
  printf '\n  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
  exit 1
fi
# The probe reports through the same channel whether it worked or not, so a probe-side failure has to
# be caught here rather than being read as six panel failures.
if printf '%s' "$REPORT" | grep -q "error="; then
  fail "the probe could not run: $REPORT"
  printf '\n  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
  exit 1
fi
pass "the probe reported"
info "$REPORT"

# One assertion per panel, from the probe's own line:
#   SPEED rows=5 headings=1 empty=no title=Speed
# "empty=no" is the bug's own marker - a panel with no rows renders a single "—" row - and rows>=1
# is the floor for a control panel: every one of them lists at least one option.
for panel in SPEED DECODER SUBTITLES AUDIO SCALING CHAPTERS; do
  entry=$(printf '%s' "$REPORT" | tr ';' '\n' | grep -E "^\s*$panel " | head -1)
  if [ -z "$entry" ]; then
    fail "$panel was not reported at all"
    continue
  fi
  rows=$(printf '%s' "$entry" | grep -oE "rows=[0-9]+" | grep -oE "[0-9]+")
  empty=$(printf '%s' "$entry" | grep -oE "empty=[a-z]+" | cut -d= -f2)
  if [ "$empty" != "no" ]; then
    fail "$panel rendered empty ($entry)"
  elif [ "${rows:-0}" -lt 1 ] 2>/dev/null; then
    fail "$panel rendered only $rows row(s) ($entry)"
  else
    pass "$panel rendered $rows rows"
  fi
done

say "the sheet is gone again"
# Asked of the activity rather than inferred from the window dump: this phone's lock screen keeps
# `mCurrentFocus` on its notification shade for long stretches, so "the player is focused" is not a
# reliable proxy for "no dialog is up" here. The probe reports the sheet's own state instead.
if printf '%s' "$REPORT" | grep -q "closed=yes"; then
  pass "the probe dismissed the sheet"
else
  fail "the sheet was still open when the probe finished ($REPORT)"
fi

say "the glass is installed, not just declared"
# The bug this guards against is invisible by construction. `GlassInstaller` used to recognise a
# surface by loading its background resource and comparing `constantState`, which can never match for
# a `RippleDrawable` - every `getDrawable` call builds a fresh one. The result was that every
# XML-declared glass surface in the app kept its flat gradient stand-in while the code said glass was
# everywhere, and nothing failed: the player still played, the panels still rendered, and the only
# symptom was a user saying the UI looked unchanged.
#
# So the assertion is the installer's own count. It is the one line that separates "installed" from
# "declared", and it caught the real thing: the main screen reported 0 panes against 9 painted views.
sh_ am force-stop "$PKG"
sleep 1
adb_ logcat -c
sh_ am start -n "$PKG/com.gan.spatialplayer.MainActivity" > /dev/null
sleep 4
GLASS_LIB=$(adb_ logcat -d -s GlassInstaller 2>/dev/null | grep -oE "installed on [0-9]+ pane" | tail -1 | grep -oE "[0-9]+")
if [ "${GLASS_LIB:-0}" -ge 1 ] 2>/dev/null; then
  pass "glass installed on the library ($GLASS_LIB pane(s))"
else
  fail "glass installed on 0 panes on the library - flat stand-ins are back"
fi

say "cleanup"
sh_ am force-stop "$PKG"

say "summary"
printf '  passed %d   failed %d   skipped %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
