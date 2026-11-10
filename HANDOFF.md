# Spatial Player — handoff

## Product and working rules

- Native HDR video takes priority. Keep video on `SurfaceView`; normal controls stay SDR.
- Design: calm Apple-inspired glass, readable touch targets, smooth motion, minimal status text.
  User authorizes a full redesign; old single-row geometry and flat-screen decisions are superseded.
- Work in this original repo on `main`. Commit completed work here; no fork/refactor branch or push needed.
- Preserve device/user data: no `pm clear`, uninstall, reboot, `adb shell monkey`, or full movie copies.
  Keep test sound low/muted; restore changed volume, rotation, settings and preferences.
- If the phone needs reconnecting/ADB authorization/unlocking, use a Mac `osascript display dialog`
  with a beep. Notifications alone were missed.
- Keep README short. Git history contains old investigations; do not restore their session diaries here.

## Build and device

```sh
export JAVA_HOME="$HOME/Downloads/Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home"
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
$ADB install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- JDK **21**; `/usr/libexec/java_home -v 21` currently returns JDK 26 on this Mac and is unsuitable.
- SDK 35, minSdk 34, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9.
- Media3 **1.8.0**, nextlib **1.8.0-0.9.0**. Newer Media3 needs a newer SDK; don't upgrade incidentally.
- Application IDs: `org.spatialplayer.debug` / `org.spatialplayer`; Kotlin namespace `com.gan.spatialplayer`.
- Device: Sony Xperia 1 V / XQ-DQ72, API 34, 1096×2560, density 2.625. HDR10/HLG, no DV decoder.
- Headset: `1000X THE COLLEXION`, LDAC. A six-channel spatialized sink has been observed.
- Some screen-off tests wake into a locked NotificationShade. Confirm focus; otherwise mark checks skipped.

## Current architecture

| Area | Entry points |
| --- | --- |
| Library / Poweramp | `MainActivity`, `MediaRepository`, `PowerampReader`, `FileListAdapter` |
| Playback / background session | `PlayerActivity`, `media/PlayerEngine`, `PlaybackEngine`, `PlaybackService` |
| Audio | `SpatialRenderersFactory`, `StereoUpmixProcessor`, `UpmixMatrix`, `AudioOutputCapability` |
| Controls / settings | `PlayerPanels`, `InspectorSheet`, `OverflowSheet`, `GlassSlider`, `GlassSwitch`, `SettingsStore` |
| Material | `GlassLensRenderer`, `GlassOptics`, `GlassDrawable`, `GlassStyle`, `GlassTheme`, `GlassInstaller` |
| Backdrop | `GlassBackdrop` / `BackdropCapture` (GPU view recording); `VideoGlassBackdrop` (native video preview) |
| Debug entry points | `app/src/debug`; receiver registration is variant-specific `DebugProbes` |

## Verified technical constraints

### HDR and decoder selection

- nextlib needs `EXTENSION_RENDERER_MODE_PREFER` to register its audio decoder. **That also places
  FFmpeg video first by default.** Explicitly move software video behind `MediaCodecVideoRenderer`
  in Auto / FFmpeg-audio modes. Otherwise a 4K movie can software-decode despite a “hardware” policy label.
- Diagnostics now record the actual decoder initialization callback, not a guessed policy route.
- Player release is ownership-checked. Old service/activity teardown must not clear a newly acquired
  engine or listener, including while it is buffering. A service reattaches on every start command.
- DV Profile 7 Matroska: `DolbyVisionFallback` exposes the HDR10 HEVC base layer on this non-DV device,
  clearing the DV codec string while preserving HEVC initialization data and PQ/BT2020 metadata.
- A revised timed probe selected **`c2.qti.hevc.decoder`**, rendered **326 frames / 0 dropped / 0 audio
  underruns** over its 16 s run. SurfaceFlinger showed the video as **BT2020_ITU_PQ**, with HDR metadata,
  and a separate **V0_SRGB** app window. This verifies the native HDR path, not colorimetric accuracy.
- Never infer correct tone mapping from an SDR screenshot or merely from a rendered first frame.
- `TextureView` is not a substitute: Android's HDR guidance says it tone-maps video to SDR.
  Reference: https://developer.android.com/media/grow/hdr-playback
- Glass is now a direct port of the reference `GlassLensRenderer` optical shader / GPU Gaussian-blur
  chain, with its small-control scaling and sensor light model. MIT license and upstream revision are
  bundled under `assets/licenses/`. Source: https://github.com/QWEA0/Liquid-Glass-Android
- `VideoGlassBackdrop` supplies a shared double-buffered, downscaled PixelCopy preview only while
  controls are visible. UI backdrops use GPU view recording, not CPU bitmap capture or sparse blur taps.
  The native HDR video surface is never replaced or modified by this preview.
- Blu-ray M2TS: `M2tsDataSource` strips four timestamp bytes from each 192-byte packet and maps virtual
  188-byte seek offsets/lengths back to the original file. Other transport streams pass through.
  `BlurayTsExtractor` distinguishes HDMV's DTS-HD type 0x86 from broadcast SCTE-35, excludes the DV
  enhancement PID when a primary HEVC picture is present, and keeps the HDR10 base layer.
  `BlurayDtsReader` joins core/extension PES into DTS-HD access units; stock Media3 emits extensions
  separately as DTS Express, which causes nextlib decoder initialization failure. The PCR search window
  is enlarged for high-bitrate streams so duration / seeking does not depend on a broadcast-sized span.
- HDMV PGS subtitle extraction is still absent in the TS reader; external subtitles remain available.

### Spatial audio

- Stereo does not head-track on the reference route unless converted to 5.1. `StereoUpmixProcessor`
  activates only for two-channel 16-bit PCM at supported sample rates. Native 5.1+ must stay inactive.
- The player audio panel reports actual upmix activation versus native-surround bypass.
- `setIsContentSpatialized(true)` means already binaural and disables platform head tracking; keep false.
- Float sink output skips Media3's custom processor chain. The stereo-upmix path requires int16 output.
- A 7.1 AudioTrack test-open can succeed while the real sink fails. Apply route capabilities and retain
  the channel-cap fallback; do not assume Bluetooth encoding channel count equals spatializer input count.
- Apps cannot force the vendor spatializer off or anchor Sony's recenter timer. The off setting is a hint.

### Glass and layout

- `RuntimeShader` cannot draw on a software canvas. Both renderers have a canvas-acceleration guard.
  RippleDrawable gets an explicit opaque rounded mask; otherwise a held press rasterizes shader content
  into a software mask and crashes. Keep `verify-glass-touch.py`.
- The GPU lens shader is blurred **before** refraction/dispersion. Do not reintroduce unblurred RGB
  samples, sparse multi-tap frost, or a constant bright outline; these caused sharpening / false glass.
- `BackdropCapture` walks ancestor branches with public `draw()` and transition visibility to avoid
  re-entering active RenderNodes or creating cyclic display lists. Restore hidden views in `finally`.
- Native video copies use decoder buffer coordinates, not view coordinates. Double buffering avoids
  writing into the preview currently sampled by the render thread; don't prematurely recycle bitmaps.
- Floating + translucent dialog themes are required for compositor window blur. Keep controls SDR.
- Player overlay is a FrameLayout: independent safe-area header, useful stream chips, width-capped
  two-row transport. Button radii derive from half the measured short side; square controls are circles.
- Main screen prioritizes Open file / Open folder. Videos and Music disclose optional collections;
  no selection means a clean collapsed home. Persist and restore the selected source.
- Timeline scrubbing draws the live finger fraction and commits once on release. Cancellation cancels
  the seek. All sliders retain native seeking/accessibility and use an expanding glass handle.
- Continuous volume/brightness feedback must update without restarting its alpha entrance on MOVE.
- Haptics and glass deformation share one listener; don't replace `SliderReading`'s own gesture
  listener. Respect system haptics. Switch/slider widgets own their touch and feedback.
- Gesture ownership is decided on DOWN and retained for the gesture. Header buttons, seek bar and speed
  control are included in hit-testing. Alpha zero alone does not remove a touch target: park at INVISIBLE.
- `View.animate().cancel()` retains listeners. Clear stale listeners before reusing an animator.
- Expanded collections use a stable list. No animated layout weights or docking.

### Data

- Poweramp URI: `content://com.maxmpz.audioplayer.data/files?limit=...`; qualify joined columns
  (`folder_files._id/name/duration`). Title is `title_tag`; artist/album already exist in that view.
  Resolve playable URIs through MediaStore; Poweramp's own file URIs cannot be opened.
- Keep Poweramp rows separate when refreshing other sources, and load a persisted Music selection on launch.
- Subtitle selection uses exact track identity, not language: a remux can contain dozens of `eng` tracks.
- Media3 1.8 has no chapter API. `MatroskaChapters` is the project's defensive EBML parser.
- Video previews come from provider-cached thumbnails; pause preview jobs while the library is stopped.
  App-owned video retrievers can contend with the HDR decoder. Embedded music art is downsampled.

## Test media and evidence

Original movie (73 GB; never push the whole file):
`/Volumes/Storage/Medias/Spider-Man.Across.the.Spider-Verse.2023.UHD.Blu-ray.Remux.DV.HDR.HEVC.TrueHD.Atmos.7.1-CiNEPHiLES.mkv`

- Stream-copy test cut: `smoke/media/spiderverse_dv7_truehd_30s.mkv`, on device at
  `/sdcard/Movies/SpatialPlayerTest/`. It retains DV7, 4K HEVC, TrueHD 7.1, AC3 5.1 and subtitle streams.
- Other device fixtures include `grid_720p.mp4`, `ac3_51_720p.mkv`, `truehd_51_720p.mkv`,
  `stereo_48k.mp4`, `multi_eng_subs.mkv` and `chapters_720p.mkv` in that same folder.
- Host checks: `python3 tools/verify-host.py` (cached Kotlin compiler, JDK 21; Gradle unit tests have no sources).
- Real UI/native HDR checks: `python3 tools/verify-redesign.py`; artifacts in `smoke/out/redesign/`.
- New controls / picker / live scrub / gesture HUD / switch / GPU blur: `python3 tools/verify-controls.py`.
  Test artifacts live in `smoke/out/controls/`. The stripe probe measures the actual rendered Gaussian blur.
- New source: `/Volumes/Storage/Medias/Wandering Earth 2/00004.m2ts` (80 GB; never push whole).
  Its unmodified 96 MB packet-aligned prefix is `earth2_original_12s.m2ts` (~16.8 s) in both fixture folders.
- Crash regression: `python3 tools/verify-glass-touch.py` (real held presses + software-canvas rendering).
- Codec/identity regression: `tools/verify-multichannel.sh`; spatial tests: `tools/verify-spatial-audio.sh`.
- Other harnesses cover Poweramp refresh, chapters, background policy, panels, gestures, rotation and glass.
  Update geometry assumptions when the design changes; do not lower thresholds to conceal a failure.
- Historical pass counts are not proof of the new UI. Record current test results below after verification.
- Raw screenshots: `$ADB exec-out screencap -p > image.png`; never pipe binary PNG through `tr`.

## Current verification / remaining work

- Debug/release builds and debug lint pass. Host checks: **36 passed**, including fragmented M2TS
  reads, seek boundaries, and virtual length mapping.
- M2TS native probe: hardware HEVC **3840×2160 PQ**, FFmpeg DTS-HD **5.1**, **205 frames / 0 dropped /
  0 underruns** in the timed prefix sample; stereo upmix inactive. Full 80 GB playback is unverified.
- New controls: **20 passed** (real pickers, collapsing collections, live seek thumb/timecode, live
  brightness/volume HUD, glass sliders/switches, haptic dispatch, flat-sensor stability, real GPU blur).
  Stripe probe: standard deviation **127.5 → 0.0** under the pane. Physical tilt remains a manual check.
- Native HDR/UI regression: **39 passed**, including rotations, actual header taps, repeated player
  opens, hardware Spider-Verse playback, native PQ video and separate SDR controls.
- Blu-ray/TS regression: **12 passed**, including mid-file seeks on M2TS and ordinary 188-byte TS,
  production M2TS playback with visible glass (**55 → 185 frames, 0 dropped**) and native PQ output. `python3 tools/verify-bluray.py`.
- Held-touch/software-canvas regression: **7 real holds passed**, process unchanged.
- Physical HDR color accuracy of either full movie remains unverified; the system/vendor tone maps.
- Sony headset recentering and the device lock-screen test limitation remain outside this redesign.
