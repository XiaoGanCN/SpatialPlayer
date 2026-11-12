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
- Compile SDK 36, target 35, minSdk 34; AGP 8.13.2, Kotlin 2.3.10, Gradle 8.13.
  The upgrade is required by the isolated Backdrop/Compose playground.
- Media3 **1.8.0**, nextlib **1.8.0-0.9.0**. Keep the tested decoder pair pinned when changing UI dependencies.
- Application IDs: `org.spatialplayer.debug` / `org.spatialplayer`; Kotlin/source namespace `org.spatialplayer` (all source sets and custom XML views).
  A legacy launcher alias and folder-preference key preserve existing external launch intents / grants.
- Device: Sony Xperia 1 V / XQ-DQ72, API 34, 1096×2560, density 2.625. HDR10/HLG, no DV decoder.
- Headset: `1000X THE COLLEXION`, LDAC. A six-channel spatialized sink has been observed.
- Some screen-off tests wake into a locked NotificationShade. Confirm focus; otherwise mark checks skipped.

## Current architecture

| Area | Entry points |
| --- | --- |
| Library / Poweramp | `MainActivity`, `MediaRepository`, `PowerampReader`, `FileListAdapter` |
| Playback / background session | `PlayerActivity`, `media/PlayerEngine`, `PlaybackEngine`, `PlaybackService` |
| Audio | `SpatialRenderersFactory`, `StereoUpmixProcessor`, `UpmixMatrix`, `AudioOutputCapability` |
| Experimental glass | `GlassPlaygroundActivity`, `playground/` — real Backdrop 1.0.6 + adapted upstream catalog controls; no playback settings persisted |
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
  engine or listener, including while it is buffering. Background pause also checks listener/media ownership.
  A service reattaches on every start command. `PlayerEngine`'s constructor argument must stay named
  `initialListener`: naming it `listener` made anonymous callbacks capture the original activity instead
  of reading the mutable property, breaking music → video preview / controls and retaining old screens.
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
- `BackdropCapture` records ancestor backgrounds/children explicitly. Never call `draw()` on live
  scrolling or glass ancestors: it replays stateful edge effects and clears pending redraws, causing
  runaway stretch and frozen nested highlights. Preserve dirty leaf state after offscreen drawing.
  UI scrollers disable native stretch; their ordinary scrolling remains intact.
- Native video copies use decoder buffer coordinates, not view coordinates. Double buffering avoids
  writing into the preview currently sampled by the render thread; don't prematurely recycle bitmaps.
- All sheets (including Stereo stage, value entry, diagnostics and output status) use `GlassSheetLayout`
  and the actual lens shader. Transparent windows have compositor blur disabled to avoid double frost.
- Native cards use a continuous-curvature corner profile shared by shader, clip and ripple mask;
  circles/capsules remain exactly circular. The playground uses Kyant0 Shapes' own continuous corners.
- `TopFadeBlurView` uses a one-third-size GPU recording and four Gaussian levels with additive masks:
  blur intensity eases down the strip and opacity fades at the foot, with no scrim. It excludes itself
  from recording and never calls live scrolling ancestors' `draw()`. Keep the contrast fixture.
- Video previews clear stale frames on source/size changes and discard old PixelCopy callbacks.
- Player overlay is a FrameLayout: independent safe-area header, useful stream chips, width-capped
  two-row transport. Button radii derive from half the measured short side; square controls are circles.
- Main screen prioritizes Open file. Videos/Music are collapsed on each new launch; rotation preserves
  the current in-memory selection. There is no Add folder button; long-press Open retains folder picking.
  Collection counts and refresh live below the stable header; loading reserves its height.
  Folder grants are migrated from the former Activity-scoped preference into stable `spatial_player`.
- Player drawer and submenus share `GlassSheetLayout`; keyed material roles preserve debug/action tags.
  Dialog glass panes keep the shared video preview refreshing while the underlying chrome is hidden.
- Timeline scrubbing draws the live finger fraction and commits once on release. Cancellation cancels
  the seek. All sliders retain native seeking/accessibility and use an expanding glass handle.
- Continuous volume/brightness feedback must update without restarting its alpha entrance on MOVE.
- Haptics: `KEYBOARD_PRESS` on down, `KEYBOARD_RELEASE` on up, `SEGMENT_TICK` for sliders and volume /
  brightness steps. Do not substitute other effects. Share touch with glass; preserve numeric editors.
  Snapping sliders track held state independently of native dragging so a stationary hold becomes glass.
- Switch track/thumb radii use matching 3 dp resting / 1.5 dp pressed gaps; active color is #0A84FF.
- Gesture ownership is decided on DOWN and retained for the gesture. Header buttons, seek bar and speed
  control are included in hit-testing. Alpha zero alone does not remove a touch target: park at INVISIBLE.
- `View.animate().cancel()` retains listeners. Clear stale listeners before reusing an animator.
- Expanded collections use a stable list. No animated layout weights or docking.

### Data

- Poweramp URI: `content://com.maxmpz.audioplayer.data/files?limit=...`; qualify joined columns
  (`folder_files._id/name/duration`). Title is `title_tag`; artist/album already exist in that view.
  Resolve playable URIs through MediaStore; Poweramp's own file URIs cannot be opened.
- Keep Poweramp rows separate when refreshing other sources. Load Music when explicitly opened.
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
- `python3 tools/verify-glass-experiments.py`: stable collection geometry, measured progressive blur,
  actual typed percentage input / 150% endpoint, Stereo stage, isolated library controls and repeated
  audio-only → video preview recovery. Generates/pushes only a 2.3 MB silent WAV fixture.
- Crash regression: `python3 tools/verify-glass-touch.py` (real held presses + software-canvas rendering).
- Codec/identity regression: `tools/verify-multichannel.sh`; spatial tests: `tools/verify-spatial-audio.sh`.
- Other harnesses cover Poweramp refresh, chapters, background policy, panels, gestures, rotation and glass.
  Update geometry assumptions when the design changes; do not lower thresholds to conceal a failure.
- Historical pass counts are not proof of the new UI. Record current test results below after verification.
- Raw screenshots: `$ADB exec-out screencap -p > image.png`; never pipe binary PNG through `tr`.

## Current verification / remaining work

- Debug/release builds and debug lint pass with the Compose playground; **36 host checks pass**.
- Current device checks: **33 glass experiment checks** and **23 UI polish checks** pass. They cover
  progressive blur contrast, 150% / typed sensitivity, Stereo stage, new library interaction, stable
  collections, repeated music → video preview recovery, glass holds and menu navigation.
  Stationary descendant updates refresh the fade; idle fades do not poll. Optics sliders expose
  named accessibility ranges/actions.
- Existing controls suite: **21 checks pass**, including the real file/folder pickers, live seeking,
  volume / brightness feedback, haptics and GPU Gaussian blur. Folder picker tests now verify the
  foreground activity (background broadcasts alone can misleadingly report hidden app geometry).
- **39 HDR/UI checks** and **7 held presses + software-canvas regression** pass. The timed film
  sample rendered 61 → 292 frames with **0 dropped**, using hardware HEVC, native PQ video and SDR UI.
- Existing M2TS probe: hardware 4K PQ + FFmpeg DTS-HD 5.1, 205 frames / 0 dropped / 0 underruns.
  `tools/verify-bluray.py` covers ordinary TS and M2TS seeking; extractor / decoder code is unchanged.
- Optional logo direction: `design/logo-c.svg` simplifies the spatial orbit and increases small-size
  contrast. Proposal only; the installed launcher icon remains unchanged.
- Physical HDR color accuracy / full original movies and physical tilt remain manual checks.
- HDMV PGS subtitle extraction, Sony recentering and the lock-screen test limitation remain open.
