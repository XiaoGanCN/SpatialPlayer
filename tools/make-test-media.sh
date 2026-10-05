#!/usr/bin/env bash
# Generates test media on the connected device using its own encoder.
#
# The host has no ffmpeg, and this keeps clips on the phone instead of copying gigabytes across
# adb. Android's `screenrecord` produces H.264 + AAC in MP4 - that is the whole of its capability,
# so it exercises the container, video-decode, audio-decode and subtitle paths but NOT the
# AC3/TrueHD/DTS paths. Those are verified separately by checking which decoder the player routes
# to (see tools/smoke-test.sh).
#
# Non-destructive: writes only into a project-owned folder so it is easy to delete afterwards.
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
DIR="/sdcard/Movies/SpatialPlayerTest"

say() { printf '\n=== %s ===\n' "$1"; }

say "device"
"$ADB" get-state
"$ADB" shell getprop ro.product.model | tr -d '\r'

# screenrecord cannot start while the display is asleep (INVALID_LAYER_STACK), so wake it first.
# KEYCODE_WAKEUP is not destructive: it does not unlock, dismiss anything, or change settings.
say "ensure display awake"
WAKE=$("$ADB" shell dumpsys power 2>/dev/null | grep -m1 "mWakefulness=" | tr -d '\r')
echo "  $WAKE"
if echo "$WAKE" | grep -q "Asleep"; then
  "$ADB" shell input keyevent KEYCODE_WAKEUP
  sleep 2
  echo "  woke: $("$ADB" shell dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | tr -d '\r')"
fi

say "prepare target dir"
"$ADB" shell "mkdir -p $DIR"
echo "target: $DIR"

say "encode H.264 + AAC clip (5 s, 720p)"
"$ADB" shell "rm -f $DIR/h264_aac_720p.mp4"
"$ADB" shell "screenrecord --time-limit 5 --size 1280x720 --bit-rate 8000000 $DIR/h264_aac_720p.mp4"
sleep 1

say "encode a second, 1080p clip"
"$ADB" shell "rm -f $DIR/h264_aac_1080p.mp4"
"$ADB" shell "screenrecord --time-limit 5 --size 1920x1080 --bit-rate 12000000 $DIR/h264_aac_1080p.mp4" || \
  echo "  1080p recording refused; the 720p clip remains the reference"
sleep 1

say "write sidecar subtitles (matching the main clip name)"
"$ADB" shell "cat > $DIR/h264_aac_720p.srt <<'EOF'
1
00:00:00,200 --> 00:00:02,000
Spatial Player test subtitle line one

2
00:00:02,100 --> 00:00:04,800
Second line, to confirm styling and timing

3
00:00:04,900 --> 00:00:05,600
Subtitle track end
EOF"

say "write an ASS sidecar too, to exercise the SSA parser"
"$ADB" shell "cat > $DIR/h264_aac_1080p.ass <<'EOF'
[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,40,40,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.20,0:00:03.00,Default,,0,0,0,,ASS subtitle rendering check
Dialogue: 0,0:00:03.10,0:00:05.00,Default,,0,0,0,,Second ASS cue
EOF"

say "result"
"$ADB" shell "ls -la $DIR"
