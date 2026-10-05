#!/usr/bin/env bash
# Generates the media the smoke test needs, using ffmpeg on the host.
#
# Why this exists: the phone reports **no** platform decoder for AC3, EAC3, TrueHD or DTS, so
# those paths cannot be exercised by anything the device can produce itself (`screenrecord` is
# H.264 + AAC only). These clips are what actually prove the bundled FFmpeg extension works.
#
# The audio is a 5.1 "phase sweep": a tone panned across the six channels, so if playback collapses
# to stereo, or if a channel is dropped, it is audible rather than theoretical.
#
# Usage:
#   tools/make-host-test-media.sh              # default: ac3 + truehd + hdr10
#   tools/make-host-test-media.sh all          # everything
#   tools/make-host-test-media.sh ac3 truehd   # a specific subset
set -euo pipefail

FFMPEG="${FFMPEG:-/opt/homebrew/bin/ffmpeg}"
if ! command -v "$FFMPEG" >/dev/null 2>&1; then
  FFMPEG="$(command -v ffmpeg || true)"
fi
if [ -z "${FFMPEG:-}" ] || [ ! -x "$FFMPEG" ]; then
  echo "ffmpeg not found. Install it (brew install ffmpeg) or set FFMPEG=/path/to/ffmpeg." >&2
  exit 1
fi

OUT="${OUT:-$(cd "$(dirname "$0")/.." && pwd)/smoke/media}"
mkdir -p "$OUT"

# Short clips keep the repository-free test payload small while still being long enough to observe
# buffers filling, a seek, and a subtitle cue.
DURATION="${DURATION:-10}"
W=1280
H=720
FPS=25

# A visible moving pattern with a burned-in label, so a screenshot proves which file is playing.
video_source() {
  local label="$1"
  echo "-f lavfi -i testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION} -f lavfi -i sine=frequency=440:sample_rate=48000:duration=${DURATION}"
  :
}

# 5.1 tone bed: one sine per channel, so channel order and count are verifiable by ear and by
# inspecting the decoded output.
audio_51() {
  echo "-f lavfi -i sine=frequency=300:sample_rate=48000:duration=${DURATION} \
-f lavfi -i sine=frequency=400:sample_rate=48000:duration=${DURATION} \
-f lavfi -i sine=frequency=500:sample_rate=48000:duration=${DURATION} \
-f lavfi -i sine=frequency=600:sample_rate=48000:duration=${DURATION} \
-f lavfi -i sine=frequency=700:sample_rate=48000:duration=${DURATION} \
-f lavfi -i sine=frequency=800:sample_rate=48000:duration=${DURATION}"
  :
}

say() { printf '\n=== %s ===\n' "$1"; }

# ---------------------------------------------------------------------------
# AC3 5.1 in Matroska - the single most common "player can't do this" case.
# ---------------------------------------------------------------------------
gen_ac3() {
  say "ac3 5.1 (mkv)"
  local out="$OUT/ac3_51_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "aevalsrc=0.28*sin(2*PI*300*t)|0.28*sin(2*PI*400*t)|0.28*sin(2*PI*500*t)|0.28*sin(2*PI*600*t)|0.28*sin(2*PI*700*t)|0.28*sin(2*PI*800*t):s=48000:d=${DURATION}" \
    -filter_complex "[1:a]channelmap=channel_layout=5.1[a]" \
    -map 0:v -map "[a]" \
    -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
    -c:a ac3 -b:a 640k -ac 6 -ar 48000 \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# TrueHD 5.1 in Matroska - lossless, the format the user specifically needs.
# ---------------------------------------------------------------------------
gen_truehd() {
  say "truehd 5.1 (mkv)"
  local out="$OUT/truehd_51_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "aevalsrc=0.28*sin(2*PI*300*t)|0.28*sin(2*PI*400*t)|0.28*sin(2*PI*500*t)|0.28*sin(2*PI*600*t)|0.28*sin(2*PI*700*t)|0.28*sin(2*PI*800*t):s=48000:d=${DURATION}" \
    -filter_complex "[1:a]channelmap=channel_layout=5.1[a]" \
    -map 0:v -map "[a]" \
    -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
    -c:a truehd -strict -2 \
    "$out" || {
      echo "  truehd encode failed; see stderr above" >&2
      return 1
    }
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# E-AC3 (Dolby Digital Plus) 5.1.
# ---------------------------------------------------------------------------
gen_eac3() {
  say "eac3 5.1 (mkv)"
  local out="$OUT/eac3_51_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "aevalsrc=0.28*sin(2*PI*300*t)|0.28*sin(2*PI*400*t)|0.28*sin(2*PI*500*t)|0.28*sin(2*PI*600*t)|0.28*sin(2*PI*700*t)|0.28*sin(2*PI*800*t):s=48000:d=${DURATION}" \
    -filter_complex "[1:a]channelmap=channel_layout=5.1[a]" \
    -map 0:v -map "[a]" \
    -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
    -c:a eac3 -b:a 640k -ac 6 \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# DTS core 5.1.
# ---------------------------------------------------------------------------
gen_dts() {
  say "dts 5.1 (mkv)"
  local out="$OUT/dts_51_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "aevalsrc=0.28*sin(2*PI*300*t)|0.28*sin(2*PI*400*t)|0.28*sin(2*PI*500*t)|0.28*sin(2*PI*600*t)|0.28*sin(2*PI*700*t)|0.28*sin(2*PI*800*t):s=48000:d=${DURATION}" \
    -filter_complex "[1:a]channelmap=channel_layout=5.1[a]" \
    -map 0:v -map "[a]" \
    -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
    -c:a dca -strict -2 -b:a 768k \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# HDR10 (PQ / BT.2020) in HEVC - for the tone-mapping / HDR path.
# The mastering-display and content-light metadata make it a real HDR10 stream rather than a
# 10-bit SDR file wearing an HDR tag.
# ---------------------------------------------------------------------------
gen_hdr10() {
  say "hdr10 hevc (mkv)"
  local out="$OUT/hdr10_hevc_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
    -map 0:v -map 1:a \
    -c:v libx265 -preset veryfast -crf 22 -pix_fmt yuv420p10le \
    -x265-params "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
    -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc \
    -c:a aac -b:a 192k \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# HLG (BT.2020 / arib-std-b67) - the other transfer function this panel supports.
# ---------------------------------------------------------------------------
gen_hlg() {
  say "hlg hevc (mkv)"
  local out="$OUT/hlg_hevc_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
    -map 0:v -map 1:a \
    -c:v libx265 -preset veryfast -crf 22 -pix_fmt yuv420p10le \
    -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" \
    -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc \
    -c:a aac -b:a 192k \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# Plain baseline: H.264 + stereo AAC + embedded SubRip subtitles.
# ---------------------------------------------------------------------------
gen_baseline() {
  say "h264 + aac + embedded srt (mkv)"
  local srt="$OUT/_baseline.srt"
  cat > "$srt" <<'EOF'
1
00:00:01,000 --> 00:00:03,000
Embedded subtitle cue one

2
00:00:03,200 --> 00:00:05,500
Embedded subtitle cue two

3
00:00:05,700 --> 00:00:08,000
Embedded subtitle cue three
EOF
  local out="$OUT/h264_aac_subs_720p.mkv"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
    -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
    -i "$srt" \
    -map 0:v -map 1:a -map 2:s \
    -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p \
    -c:a aac -b:a 192k -ac 2 \
    -c:s srt \
    "$out"
  rm -f "$srt"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
# FLAC 5.1 lossless - checks that multichannel ordering is right for a non-Dolby codec too.
# ---------------------------------------------------------------------------
gen_flac() {
  say "flac 5.1 (mkv, audio only)"
  local out="$OUT/flac_51.mka"
  "$FFMPEG" -hide_banner -loglevel error -y \
    -f lavfi -i "aevalsrc=0.28*sin(2*PI*300*t)|0.28*sin(2*PI*400*t)|0.28*sin(2*PI*500*t)|0.28*sin(2*PI*600*t)|0.28*sin(2*PI*700*t)|0.28*sin(2*PI*800*t):s=48000:d=${DURATION}" \
    -filter_complex "[0:a]channelmap=channel_layout=5.1[a]" \
    -map "[a]" \
    -c:a flac -compression_level 5 \
    "$out"
  echo "  -> $out ($(du -h "$out" | cut -f1))"
}

# ---------------------------------------------------------------------------
TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then
  TARGETS=(ac3 truehd hdr10 hlg baseline)
fi
if [ "${TARGETS[0]}" = "all" ]; then
  TARGETS=(ac3 eac3 truehd dts hdr10 hlg baseline flac)
fi

say "ffmpeg"
"$FFMPEG" -hide_banner -version | head -1
echo "output dir: $OUT"

for target in "${TARGETS[@]}"; do
  case "$target" in
    ac3)      gen_ac3 ;;
    eac3)     gen_eac3 ;;
    truehd)   gen_truehd ;;
    dts)      gen_dts ;;
    hdr10)    gen_hdr10 ;;
    hlg)      gen_hlg ;;
    baseline) gen_baseline ;;
    flac)     gen_flac ;;
    *) echo "unknown target: $target" >&2; exit 1 ;;
  esac
done

say "produced"
ls -la "$OUT"
