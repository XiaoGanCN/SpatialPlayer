# Spatial Player

An Android video player for phones whose stock players get movie audio wrong.

Most phone players either decode TrueHD/AC3 and show HDR incorrectly, or tone-map correctly and
cannot decode the audio at all. This one does both, and hands multichannel audio to Android's
spatializer so it reaches a head-tracked headset as 5.1 rather than a stereo fold-down.

Minimum Android 14 (API 34).

## What it does

**Audio.** Decodes 5.1 / AC3 / EAC3 / TrueHD / DTS / DTS-HD with a bundled FFmpeg build, because
the reference device has *no* platform decoder for any of them. Output stays 5.1 (`channelMask=0x3f`)
and is declared spatializable, so Android routes it to a supported headset with head tracking.

**Video.** Hardware decode by default (H.264, HEVC, AV1), keeping HDR10/HLG metadata intact.
The Xperia 1 V panel reports HDR10 + HLG at 1000 nits; the system performs the tone mapping.

**Subtitles.** Embedded text tracks and external sidecars (`.srt`, `.ass`, `.ssa`, `.vtt`, `.ttml`),
picked up automatically when they sit next to the video.

**Interface.** The panels are real glass: they present in a separate translucent window that asks
the system compositor to blur what is behind it — including the video, which a view inside the
player's own window cannot reach because the picture is drawn by a `SurfaceView`. On Android 12+
this produces genuine frost; older versions fall back to a translucent gradient surface.

**Control.** Decoder policy is chosen automatically and can be overridden while playing. Standard
transport: play/pause, double-tap to seek ±10 s, draggable progress bar with smoothed motion,
pinch/cycle scaling that never distorts the aspect ratio. Device and stream facts — HDR format,
spatialiser state, decoder in use — are shown as small status chips, and the magnifier and info
buttons open the live inspection and metadata panels during playback.

No media library of its own. It lists what MediaStore indexed, plus any folder you grant, plus
Poweramp's library on request (see `PowerampReader` for the provider's schema quirks).

## Build

```sh
./gradlew :app:assembleDebug        # debug build (adds a smoke-test entry point)
./gradlew :app:assembleRelease      # release build
```

JDK 21, Android SDK 35. The FFmpeg decoder AAR is `io.github.anilbeesetti:nextlib-media3ext`.

## Test

```sh
tools/make-host-test-media.sh       # generate 5.1 AC3 / TrueHD / HDR10 clips with ffmpeg
tools/make-test-media.sh            # generate on-device H.264 clips + sidecar subtitles
tools/smoke-test.sh                 # install, launch, play, assert — no data is cleared
tools/verify-multichannel.sh        # assert real 5.1 decode, spatialisation, HDR, subtitles
tools/verify-poweramp.sh            # assert the optional Poweramp library integration
```

`tools/make-host-test-media.sh` builds the 5.1 clips with ffmpeg. To test with real material instead,
`yt-dlp` works: pick a format pair whose audio row shows 6 channels (for example `137+380` is
1080p H.264 with 5.1 AC-3) and merge to Matroska.

The scripts are non-destructive by design: they never clear app data, uninstall, reboot, change
system settings, touch your own media, or use `adb shell monkey`.

## Using it with headphones

Head tracking is owned by Android, not by this app. Enable **Settings › Sound & vibration ›
Spatial audio** and connect the headset; the player's audio panel reports what the platform says.

## Licensing

The app source is MIT (see `LICENSE`). **Distributed builds are GPL-3.0**, because they bundle
`nextlib-media3ext`, which is GPL-3.0. If you need a permissively licensed build, replace that
dependency with your own decoder build.
