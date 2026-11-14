# Spatial Player — handoff

## Product and working rules

- Native HDR video takes priority. Keep video on `SurfaceView`; normal controls stay SDR.
- Design: calm Apple-inspired glass, readable touch targets, smooth motion, minimal status text.
  User authorizes a full redesign; old single-row geometry and flat-screen decisions are superseded.
- Work in this original repo on `main`; no fork/refactor branch. The user explicitly authorized
  pushing this work and publishing v0.2.0. Future publishing follows the current user request.
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
  The upgrade supports the production Backdrop/Compose renderer.
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
| Glass renderer | `ui/backdrop/NativeBackdropRenderer`, `BackdropSurface` — Backdrop 1.0.6, native View adapters, separate HDR surface |
| Library controls | `ui/components/` — adapted upstream catalog; `GlassPlaygroundActivity` remains a settings sandbox |
| Controls / settings | `PlayerPanels`, `InspectorSheet`, `OverflowSheet`, `GlassSlider`, `GlassSwitch`, `SettingsStore` |
| Material | `GlassDrawable`, `GlassStyle`, `GlassTheme`, `GlassInstaller`; legacy `GlassLensRenderer` / `GlassOptics` only for playback timeline |
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
- Production glass uses Kyant0 Backdrop 1.0.6 (real Gaussian blur, lens, dispersion, spring motion),
  with filtered sensor direction connected to its highlight angle. Catalog controls live in
  `ui/components`; Apache-2.0 license/revision are bundled. The old MIT optical shader remains only
  for the deliberately distinct playback timeline.
- `VideoGlassBackdrop` supplies a shared, downscaled SDR PixelCopy preview on display vsync, with at
  most two pending copies. Five buffers exclude displayed, previous and pending targets; serial and
  source-generation checks reject stale callbacks. Duplicate pixel copies do not swap the front or invalidate Gaussian layers. Capture uses up to
  two opportunities per source frame (declared rate or observed decoder cadence for Matroska),
  capped at 120; UI animation cadence is independent. Hidden-chrome ambient sampling is slow. New panes
  wake capture immediately. UNKNOWN format during same-surface codec rebuilds retains the last valid
  SDR preview and geometry; copies pause until a positive format, with old generations rejected. Native HDR SurfaceView is never replaced or modified by this preview.
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
- The player audio panel reports actual upmix activation versus native-surround bypass. Native
  surround spatial toggles use live Media3 audio attributes, preserving the video decoder. Gate an
  installed stereo processor for future configurations. If enabling from a sink without one, defer
  installation until a real stereo format arrives; check player identity and clear the pending flag
  on build. FFmpeg-only intentionally has no custom processor; never rebuild it in a loop.
- `setIsContentSpatialized(true)` means already binaural and disables platform head tracking; keep false.
- Float sink output skips Media3's custom processor chain. The stereo-upmix path requires int16 output.
- A 7.1 AudioTrack test-open can succeed while the real sink fails. Apply route capabilities and retain
  the channel-cap fallback; do not assume Bluetooth encoding channel count equals spatializer input count.
- Apps cannot force the vendor spatializer off or anchor Sony's recenter timer. The off setting is a hint.

### Glass and layout

- `RuntimeShader` cannot draw on a software canvas. Both renderers have a canvas-acceleration guard.
  Production buttons use the stateful glass drawable directly: `RippleDrawable` clips expanding
  content even with transparent ink. The debug probe retains an explicitly masked ripple to guard
  against the historical software-mask crash. Keep `verify-glass-touch.py`.
- `NativeBackdropRenderer` attaches transparent, inaccessible Compose bridge hosts to window decor;
  native Views retain interaction/accessibility contracts and draw the host's local display list.
  Each pane has a live native source RenderNode inside the cached Compose effect. Source pixels and
  coordinates update synchronously, without rewriting Compose state or re-recording the effect.
  Scrolls only invalidate position; stationary panes retain their source. Pre-draw catches pane
  movement during height morphs and native video geometry changes, including paused scaling.
  Sensor notifications alone update highlight angle (0.1° threshold); do not sample/write the angle
  on every scroll/video refresh. ART profiling found that caused repeated highlight/shadow recordings.
  Pane display lists are cached until animation/geometry/style invalidation. Skip offscreen controls;
  only align the host's physical position for input. Dialog decor needs ViewTree lifecycle owners.
  Preserve layout-parameter types on resize, dispose
  on detach, and defer removing decor siblings until root detachment finishes.
  Native buttons scale their full View around an explicit center pivot; the source node cancels
  owner/ancestor scale so the physical sampling area grows without magnifying its contents. Keep
  Compose material coordinates untransformed and invalidate sampling when physical scale changes. Ordinary parent rows allow expansion; viewports/sheet outlines clip it.
- Forwarded touches must rebase both raw and local coordinates. Slider taps and drags use live track
  width after rotation; continuous movement accumulates before the native integer value snaps.
  One full-track gesture handles DOWN and MOVE immediately. Native sliders reserve 32 dp at each
  end for the expanded glass handle/shadow; the separate playback timeline keeps its own geometry.
- `BackdropCapture` records ancestor backgrounds/children explicitly. Never call `draw()` on live
  scrolling or glass ancestors: it replays stateful edge effects and clears pending redraws, causing
  runaway stretch and frozen nested highlights. Preserve dirty leaf state after offscreen drawing.
  UI scrollers disable native stretch; their ordinary scrolling remains intact.
- Native video copies use decoder buffer coordinates, not view coordinates. Buffer exclusions avoid
  writing into the preview currently sampled by the render thread; don't prematurely recycle bitmaps.
- All sheets (including Stereo stage, value entry, diagnostics and output status) use `GlassSheetLayout`
  and the production Backdrop material. Natural height avoids tiny portrait scroll ranges; safe-area
  caps and clipping keep live-rotated landscape drawers inside the window. `SheetWindow` transfers
  one dialog and shell between Overflow and Inspector: no dismiss/reopen or chrome flash on navigation.
  The window has a fixed safe cap; its bottom-aligned pane animates natural height. Configure before
  showing and disable platform window motion. A shared 260 ms shell entrance supplies popup motion;
  submenu navigation retains the shell. Replacement pages stay alpha-zero until laid out and
  synchronously primed. Keep published and pending pages separate; supersede only pending content.
  Header input remains available; page input waits for publication. Option changes update rows and
  callbacks in place, including nullable subtitles. Same-panel updates preserve scroll position.
  Stereo stage also retains its channel/source/slider views. The wrapper uses the actual visible
  display frame for IME space. Numeric actions sit in a footer; very short landscape IME space uses
  one row containing the field and both actions. Cancel competing height morphs when IME space changes.
  Sheet headers blur/fade only scrolling content above the continuous sheet material. Clip the
  normal scroller to its header padding and sample its pages separately. The header foot reaches
  full content opacity and zero extra blur; never overlay raw scene pixels over the sheet glass.
  Transparent windows have compositor blur disabled to avoid double frost.
- Native cards, clips and ripple masks use the exact Kyant0 Shapes G2 path; circles/capsules remain circular.
- `TopFadeBlurView` uses a one-third-size GPU recording and four Gaussian levels with additive masks:
  blur intensity eases down the strip and opacity fades at the foot, with no scrim. It excludes itself
  from recording and never calls live scrolling ancestors' `draw()`. Three-sigma recording outsets
  prevent the top-edge flash. Keep the contrast and repeated source-change fixtures.
  Main/Settings fades include the physical scene even without video. Sheet content-only fades
  intentionally omit that second backdrop, avoiding a sharp seam over the existing glass.
  Capture completed glass/slider/switch display lists for the fade, never run nested live lenses.
  Cache scrolling foreground separately from live video and retain all four blur-level display lists.
  Check visible native descendants inside glass containers for updates. Plain label stamps include
  presentation/geometry; explicit layout requests always bypass them. Editable/spanned text stays live.
- Video previews clear stale frames on source/size changes and discard old PixelCopy callbacks.
- Player overlay is a FrameLayout: independent safe-area header, useful stream chips, width-capped
  two-row transport. Button radii derive from half the measured short side; square controls are circles.
- Main screen prioritizes Open file. Videos/Music are collapsed on each new launch; rotation preserves
  the current in-memory selection. There is no Add folder button; long-press Open retains folder picking.
  Collection counts and refresh overlay the list below the stable header, sharing its fade; no reserved black bar.
  Folder grants are migrated from the former Activity-scoped preference into stable `spatial_player`.
- Player drawer and submenus share `GlassSheetLayout`; keyed material roles preserve debug/action tags.
  Dialog glass panes keep the shared video preview refreshing while the underlying chrome is hidden.
- Horizontal surface seeking uses **100 ms per dp**, independent of duration/orientation. Its chip
  shows the signed offset and target time while moving; cancellation commits no seek. Timeline
  scrubbing still covers the full duration. Ambient and skip length live in Playback, not Scaling.
  Subtitle tracks load eight at a time, retain selected tracks and preserve scroll on More tracks;
  the obsolete track-count setting and slider reset hint are removed.
- Timeline scrubbing draws the live finger fraction and commits once on release. Cancellation cancels
  the seek. Its distinct handle animates glass engagement on both press and release. All other sliders
  and switches use the adapted Backdrop catalog with native accessibility wrappers.
- Continuous volume/brightness feedback must update without restarting its alpha entrance on MOVE.
- Haptics: `KEYBOARD_PRESS` on down, `KEYBOARD_RELEASE` on up, `SEGMENT_TICK` for sliders and volume /
  brightness steps. Do not substitute other effects. Share touch with glass; preserve numeric editors.
  Snapping sliders track held state independently of native dragging so a stationary hold becomes glass.
  Media-list scrolling and hiding player controls are silent; actual buttons retain feedback.
- Switches use the library's deforming glass thumb, a full-track gesture target, and active blue #0A84FF.
  Active options use blue tint and native selected state, with no checkmark. Native glass buttons
  use the catalog finger highlight plus centered native spring scaling of material and foreground.
  The obsolete Regular/Clear material selector and old circular seek icons have been removed.
- `SpatialPlayerApplication` / `HighRefreshRate` request up to 120Hz at the current resolution for
  activity and dialog UI windows; API 35+ also receives the high frame-rate category. This is a UI
  vote, not a resolution or HDR-surface mode change. OS thermal/power/vendor policy still applies.
  Reference: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate
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

Original movie (73 GB; never copy/upload the whole file):
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
  actual typed percentage input / 150% endpoint, Stereo stage, production/library controls and repeated
  audio-only → video preview recovery. Generates/pushes only a 2.3 MB silent WAV fixture.
- Migration: `python3 tools/verify-backdrop-migration.py` (real library execution, quiet gestures,
  live-resized controls/drawers, ambient colors, repeated top-edge pixels and idle behavior).
- Follow-up: `python3 tools/verify-glass-followup.py` verifies real cached lens pixel changes,
  live bar grabs, both held endpoints, high-refresh vote, stable drawer identity/bottom anchor,
  tint selection, and gap-free header fades in both orientations. Artifacts: `smoke/out/glass-followup/`.
- Release UI regression: `python3 tools/verify-release-ui.py`; actual screen-space stripe sampling,
  gesture HUD/time mapping, warm navigation and paging. Artifacts: `smoke/out/release-020/`.
- Crash regression: `python3 tools/verify-glass-touch.py` (real held presses + software-canvas rendering).
- Drawer regression: `python3 tools/verify-drawers.py` checks stable selection/slider identities,
  actual matrix gains, centered held presses, header bounds and field/actions above the real IME.
  Preview-liveness harnesses use a small moving 60fps fixture; the SMPTE grid has static pixels,
  so correct duplicate suppression can stop its distinct-preview counter while decoding continues.
- Codec/identity regression: `tools/verify-multichannel.sh`; spatial tests: `tools/verify-spatial-audio.sh`.
- Other harnesses cover Poweramp refresh, chapters, background policy, panels, gestures, rotation and glass.
  Update geometry assumptions when the design changes; do not lower thresholds to conceal a failure.
- Historical pass counts are not proof of the new UI. Record current test results below after verification.
- Raw screenshots: `$ADB exec-out screencap -p > image.png`; never pipe binary PNG through `tr`.

## Current verification / remaining work

- Debug/release builds and debug lint pass with production Backdrop; **36 host checks pass**.
- **22 release UI checks pass** (stationary physical lens sampling, fixed-time seeking across
  durations, warmed pages, rapid replacement, subtitle paging and native-surround live toggles).
- **20 drawer checks pass**, including real GPU cache freshness inside a glass container, idle
  settling, retained option/matrix controls, centered presses and full numeric field/actions above
  the keyboard in portrait/landscape. A device recording confirms whole-shell popup motion.
  Astra's rapid-replacement and pagination scroll findings are fixed and reviewed.
- **33 glass experiment checks** pass, including
  progressive blur contrast, 150% / typed sensitivity, Stereo stage, new library interaction, stable
  collections, repeated music → video preview recovery, glass holds and menu navigation.
  Stationary descendant updates refresh the fade; idle fades do not poll. Optics sliders expose
  named accessibility ranges/actions.
- **25 production Backdrop checks pass**, including switch track ends, exact fractional drags across
  live rotation, slow sub-step accumulation, safe clipped drawers, ambient updates with hidden chrome,
  no list/hide haptics and a stable top strip across six source flips. Astra reviewed the migration;
  its switch hit-area and stale slider-width findings are fixed and checked on-device.
- Existing controls suite: **21 checks pass**, including the real file/folder pickers, live seeking,
  volume / brightness feedback, haptics and GPU Gaussian blur. Folder picker tests now verify the
  foreground activity (background broadcasts alone can misleadingly report hidden app geometry).
- **39 HDR/UI checks** and **7 held presses + software-canvas regression** pass. The timed film
  sample used hardware HEVC with **0 dropped**, native PQ video and a separate SDR UI.
- Existing M2TS probe: hardware 4K PQ + FFmpeg DTS-HD 5.1, 205 frames / 0 dropped / 0 underruns.
  `tools/verify-bluray.py` covers ordinary TS and M2TS seeking; extractor / decoder code is unchanged.
- Optional logo direction: `design/logo-c.svg` simplifies the spatial orbit and increases small-size
  contrast. Proposal only; the installed launcher icon remains unchanged.
- The reference phone reports a 120Hz window vote and 120Hz display refresh. This does not
  guarantee 120 rendered UI fps. Moving fixtures exercise preview updates; native video FPS and
  UI animation cadence remain independent.
- Same-device subtitle scrolling comparison against `8a95341`: missed frames **58% → 38%**,
  p95 **57 → 42 ms**, with hardware HEVC and zero video drops. Initial subtitle batches now have
  eight tracks. Menu timing varies with temperature, sensor motion and scroll distance; occasional
  hitches remain. Artifacts: `smoke/out/release-020/`. Do not promise uninterrupted 120 rendered fps.
- Physical HDR color accuracy / full original movies and physical tilt remain manual checks.
- HDMV PGS subtitle extraction, Sony recentering and the lock-screen test limitation remain open.
