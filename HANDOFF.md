# Session handoff — Spatial Player

Everything learned while building this, so the next session does not have to rediscover it.
Written to be read top-to-bottom; the "traps" section is the part that saves the most time.

---

## 1. Build and run

```bash
cd "/Applications/deepseek-harness-dsh-v0.1.6-alpha.1/Wrokspaces/Spatial Player"

# JDK 21 is REQUIRED. JDK 26 breaks Gradle 8.9.
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

ADB=$HOME/Library/Android/sdk/platform-tools/adb
PKG=com.gan.spatialplayer.debug          # debug package id; release is com.gan.spatialplayer

./gradlew :app:assembleDebug --no-daemon 2>&1 | grep -E "^e:|BUILD" | head -6
$ADB install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- **Media3 is pinned at 1.8.0** and nextlib at `io.github.anilbeesetti:nextlib-media3ext:1.8.0-0.9.0`.
  1.9+/1.11+ require `compileSdk 36`; only **android-35** is installed. Do not bump.
- `compileSdk 35`, minSdk 34, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9.
- **nextlib needs `EXTENSION_RENDERER_MODE_PREFER`.** With mode 0 its `buildAudioRenderers`
  returns early and registers no FFmpeg renderer at all. See `SpatialRenderersFactory`.
- The platform has **no** decoder for `audio/ac3`, `audio/eac3`, `audio/true-hd`,
  `audio/vnd.dts`, `audio/vnd.dts.hd`. FFmpeg extension is mandatory for any of them.
- ffmpeg on the host: `export PATH=/opt/homebrew/bin:$PATH`. `ffprobe`, `ffmpeg`, `yt-dlp` there.
- `ffmpeg` encoder limits: this build cannot encode 7.1 in ac3/eac3/truehd/flac (mono or ≤5.1
  only), so **there is no way to generate a 7.1 test file here.** Test the capability guard instead.

## 2. Device

| | |
|---|---|
| Model | Sony **XQ-DQ72** (Xperia 1 V), API 34, SM8550 |
| Display | 1096×2560 @ 420dpi → **2.625 px/dp**. HDR types `[2,3]` = HDR10 + HLG, **no Dolby Vision** |
| | maxLuminance 1000, maxAvg 500, min 0.01; `mHdrConversionMode=HDR_CONVERSION_SYSTEM` |
| Headset | **`1000X THE COLLEXION`**, A2DP, LDAC 96000/32-bit |
| Spatializer | `spatial_audio_enabled` has `8,58:18:62:86:42:C8,1,1,1`; vendor lib `libtsrspatializer.so` |
| Haptics | `AMPLITUDE_CONTROL`; effects CLICK, DOUBLE_CLICK, TICK, THUD, POP, HEAVY_CLICK, TEXTURE_TICK |

**Non-destructive rule is absolute**: never `pm clear`, uninstall, reboot, or `adb shell monkey`.
`KEYCODE_WAKEUP`/`KEYCODE_SLEEP`/`KEYCODE_HOME` are fine.

## 3. Traps that cost real time

### 3.1 Touch injection goes stale for a whole adb session
`input tap` returns success and the event is **dropped**, while `KEYCODE_HOME` still works. This is
indistinguishable from a broken app and cost most of one session. Detect it, then:

```bash
$ADB kill-server && sleep 2 && $ADB start-server && $ADB wait-for-device
```

`tools/verify-gestures.sh` now probes for this before asserting anything.

### 3.2 Read the raw screenshot, never through a helper that maps bytes
```bash
$ADB exec-out screencap -p > shot.png          # correct
adb_ exec-out screencap -p > shot.png          # WRONG if adb_ pipes through `tr -d '\r'`
```
`tr` corrupts binary PNG data and yields a **silent zero-byte file**, which then reads as a
luminance of 0 and looks exactly like "the chrome is hidden".

### 3.3 Volume measurement is a minefield when a headset is attached
- `dumpsys audio` → `streamVolume:` is the **active** device's volume.
- `settings get system volume_music_speaker` is an **unrelated per-device slot**.
- `media volume --stream 3 --set N` writes the **speaker** slot and is ignored while BT is routed.

Correct read: parse the per-device list under `Current:` for the device named on the `Devices:`
line. Correct write: `input keyevent KEYCODE_VOLUME_UP/DOWN`. Both are implemented in
`tools/verify-gestures.sh` (`music_volume`, `set_volume`).

### 3.4 `View.animate().cancel()` does NOT clear the listener
`View.animate()` returns one shared `ViewPropertyAnimator` per view; a listener set by a previous
animation stays attached and fires when the **next** animation ends. This caused chrome to be set
`GONE` immediately after being shown (measured `visibility=8` 200ms after an explicit show).

**Rule adopted: never animate `visibility`.** The view stays `VISIBLE`; alpha alone is the state.
`stop()` also calls `setListener(null)` and `setUpdateListener(null)` explicitly.

### 3.5 The controls overlay swallows every touch
It is a full-size sibling declared after the gesture layer, so gestures never reached the
controller. Gestures are dispatched from `PlayerActivity.dispatchTouchEvent`; a touch inside the
control capsule goes to its button, everything else becomes a gesture.

### 3.6 `setViewport` must actually be called
The controller scales drags by the viewport. It was never fed, so `viewWidth/viewHeight` stayed at
`1`, gain became `1/1 × 0.30`, and **every** vertical delta saturated its per-event cap. Symptom:
"a drag of less than a centimetre sends the volume through the roof".

### 3.7 Do not use greedy regexes to edit Kotlin
A `re.sub` with `.*?` across a file boundary deleted part of `PlayerGestureController.kt`
(the tap and double-tap handlers). Use targeted literal replacements. Verify with a build **and**
the suite after any structural edit.

### 3.8 Kotlin string/template constraints
- A nested quoted literal inside `${...}` is a **syntax error**. Build the string outside.
- `' '` inside a `"..."` string is a syntax error — use `"_"` / `" "`.
- File-level `@file:Suppress` must precede `package`.

### 3.9 State leaks between suites
A probe or dialog left foregrounded makes luminance assertions meaningless (read 121.5 instead of
~3.5/"hidden"). `verify-gestures.sh` asserts the player is the resumed activity and fails fast.

### 3.10 Ambient geometry is not recomputed on rotation
`configChanges` includes `orientation` in the manifest, so the activity is **never recreated** — and
nothing recomputed the picture rect. Fixed in `onConfigurationChanged` (called twice, because the
player view is not re-measured yet at that point). Also: rotating is not an app interaction, so it
must **not** haptic-buzz.

### 3.11 `assembleDebug` does not install, and a stale APK validates nothing
An afternoon went into re-measuring a bug that had already been fixed, because the on-device build
predated the edit. The tell: the pane rendered a **flat** `(18,18,18)` everywhere, which is the tint
of the **pre-API-33 gradient fallback** — i.e. `PixelCopy` was failing (the stale code asked for a
rectangle outside a 1280×720 buffer) and the view had fallen through to `drawFallbackGlass`.
"Flat and plausible" and "fallback" look identical in a screenshot; only the pixel values tell them
apart. Always `adb install -r` after `assembleDebug`, and check which APK is on the device
(`adb shell dumpsys package com.gan.spatialplayer.debug | grep lastUpdateTime`).

### 3.12 Getting a "raw" frame to compare the glass against
Freeze the clip (tap the pause button, then confirm with two screenshots one second apart — mean
difference must be **0.000**), then tap the picture to toggle the chrome off for the raw frame and on
for the glass frame. Two taps one second apart are two single taps; the double-tap window is 260 ms,
so keep taps ≳1 s apart or they seek. Use a **static high-contrast** clip for this: `testsrc2` clips
in the test folder are only 5 s long and end on a black frame, and the champagne clip is near-black
at most positions, which makes the comparison meaningless. Generate a static pattern instead:
`ffmpeg -f lavfi -i smptebars=size=1280x720:rate=30 -t 30 -c:v libx264 -pix_fmt yuv420p bars_720p.mp4`.

## 4. Verified findings worth not re-deriving

### 4.1 Stereo cannot be spatialised; 5.1 can (answers Q1)
Measured with a bare `AudioTrack`:
```
canBeSpatialized  stereo=false   5.1=true
bare AudioTrack   stereo → isSpatialized=false (mask 0x3)
                  5.1    → isSpatialized=true  (mask 0x3f)
```
So **stereo → head tracking requires app-side upmix to 5.1** in an `AudioProcessor`. Feasible; it is
on the list as Q1.

### 4.2 `setIsContentSpatialized(true)` disables head tracking
It means "already binaural, pass through", so the platform skips spatialisation. Must be `false`.
This was the original head-tracking bug.

### 4.3 An app cannot turn platform spatialisation off (Q2 / C9)
`SPATIALIZATION_BEHAVIOR_NEVER` is only a hint. `android.media.Spatializer` has read-only members
and listeners only; **there is no app-facing disable API**. The user confirmed the toggle does work
in practice on their 5.1 test media, so keep it — but the label says "Ask the system not to
spatialise", deliberately worded as a request. Hijacking another app's audio needs root/Magisk
(AudioPlaybackCapture requires per-app consent and cannot re-render).

### 4.4 The 7.1 sink failure (the 70GB film)
Native error from the user's screenshot:
```
audio sink: AudioTrack init failed 0 Config(48000, 6396, 4, 9216000)
  Format(2, Dolby Atmos 7.1, audio/raw, [8, 48000])
```
The decoder was fine — the **sink refused 8 channels of raw PCM**. Audio policy for the BT output
advertises `channel masks: 0x0001, 0x0003` = **mono and stereo only**, so any 7.1 source fails over
A2DP. Fixed by `AudioOutputCapability`, which caps the chosen track.

**Critical subtlety**: `canOpenTrack(8)` returns **true** in isolation on this device while the real
sink still refuses during playback. So the guard trusts the advertised device capability, not a
test-open:
```
describe              :: stereo only · 1000X THE COLLEXION
advertisedMaxChannels :: 2
verifiedMaxChannels   :: 2      ← what the engine uses
canOpen 8ch=true                ← AudioTrack lies
```

### 4.5 Media3 1.8 has no chapter API at all
No `chapter` symbol in `Timeline`, `Metadata`, `MetadataEntry`, or the extractor. C8 needs a
hand-written Matroska EBML parser (`Segment → Chapters → EditionEntry → ChapterAtom`).
The reference film has 16 chapters.

Related: `Player` exposes seek increments as **getters only**; the only way to change them is to
rebuild the player (too heavy during a slider drag). The double-tap jump is therefore owned by the
activity and read directly by the gesture controller and the skip buttons.

### 4.6 Poweramp provider specifics
- `content://com.maxmpz.audioplayer.data/files` **requires a `limit` param**.
- `_id`, `name`, `duration` collide with the joined `folders` table → qualify `folder_files.*`.
- Title column is `title_tag`. There is **no `_data`**; `name` is the file name, `path` is the folder.
- Poweramp's own per-file URI **cannot be opened** (was the "source error"). Resolve through
  MediaStore instead → `content://media/external/audio/media/{id}`.
- **`artist` and `album` are already joined onto the `files` view.** Do not look them up via
  album_id/artist_id in separate `albums`/`artists` queries — an earlier version did exactly that and
  produced every artist but a **blank album for all 140 tracks**.
- Android 11+ package visibility needs the `<queries>` block or `getPackageInfo` reports absent.

### 4.7 Thumbnails
Use `MediaMetadataRetriever.getScaledFrameAtTime` — a full `getFrameAtTime` allocates ~35 MB per row
for 4K. Create one retriever per decode and `release()` in a `finally`; holding one per row leaks
file descriptors. Skip black lead-ins by trying several timestamps and rejecting frames below a
luma/coverage threshold (two HDR files showed black at t=1s and now show real frames).

### 4.8 Glass: the library cannot be used as-is
`QWEA0/Liquid-Glass-Android` (MIT) captures its backdrop by **drawing the view tree into a canvas**,
which cannot see a `SurfaceView` — so it needs a `TextureView`, which gives up HDR passthrough.
Also `setCustomBackdropCapture` makes its AGSL lens path bail out
(`tryDrawLensGlass` → `if (customBackdropCapture != null) return false`), so it is video backdrop
**or** the good lens, not both.

Its `GlassLensRenderer.kt` **is self-contained** (only `android.graphics` + `RuntimeShader`, no
API-36 or library-local refs), so the model was ported. `GlassRuntimeEffects` returns API-36
`RuntimeColorFilter`/`RuntimeXfermode` and would **not** compile against android-35.

Reference source is at `/tmp/lg` (re-clone `https://github.com/QWEA0/Liquid-Glass-Android` if gone);
the optics doc is `/tmp/lg/docs/LIQUID_GLASS_V2.md`.

### 4.9 The glass has little to refract — layout, not shader
In **portrait** the control capsule sits in the letterbox below the picture, so the backdrop behind it
is black and the ported shader has nothing to work with. In **landscape** the pane already overlaps
the picture (the video is full-height there), which is why the effect only shows up when the phone is
turned. The reference layout puts the bar **inside the video frame** in every orientation, and that
single change is what will make the effect pay off. Must be addressed in B3.

### 4.10 Glass backdrop: three different coordinate spaces (fixed, verified)
The single biggest bug in the ported glass, and the reason it looked like it was "sampling the whole
screen at a made-up aspect ratio". `PixelCopy`'s source rectangle is in the surface's **buffer**
pixels, not in view pixels:

| Space | Landscape, 4K film | Portrait, 4K film |
|---|---|---|
| Window | 2560 × 1096 | 1096 × 2560 |
| `SurfaceView` bounds (the picture's rect on screen) | 1948 × 1096 at (306, 0) | 1096 × 616 at (0, 972) |
| **Buffer** (what `PixelCopy` wants) | **3840 × 2160** | **3840 × 2160** |

`PlayerView` sizes the `SurfaceView` **itself** to the picture's aspect; the decoder's buffer is then
stretched across those bounds. So the conversion is `buffer = (window - pictureOrigin) × frame /
pictureSize`, and the frame size has to come from the player (`videoSize`), not from the view.
`getLocationInWindow` on a scaled view returns the transformed origin, so dividing by
`scaleX/scaleY` also covers zoom/fill/stretch modes.

Feeding view pixels to `PixelCopy` is not a small error. In portrait the requested rectangle
collapsed to a **1-pixel-tall row** (the view's 615th row clamped inside a 616px-tall surface),
which the rim then stretched over the whole pane as **vertical stripes**. Confirm geometry with
`adb shell dumpsys SurfaceFlinger` — the `SurfaceView[...](BLAST)` layer prints `geomBufferSize`,
`geomLayerBounds` and `geomLayerTransform`, and the `Background for SurfaceView[...]` layer prints
the view's own bounds. Those two lines are ground truth for all three spaces.

Two consequences that also had to be handled:

* The copy covers only the part of the pane with picture behind it, so the shader takes an
  `uBackdropOrigin`/`uBackdropScale` **and** a `uBackdropTexSize` mask. Without the mask the texture's
  clamped edge texel is smeared across the letterbox part of the pane.
* When nothing is behind the pane, the shader still runs with every sample masked out (a 2×2 token
  buffer keeps the shader path alive) so the pane is tint + bevel + rim over black — *not* the
  gradient fallback, which looks different.

**Verified on-device** with a static SMPTE-bars clip (`bars_720p.mp4`, generated on the host,
`smptebars`): freeze the frame, photograph it with the chrome hidden and shown
(`tools/`-style script), then correlate. Glass tracks the raw frame at **r = 0.957** with the peak at
a **zero-pixel offset** in both axes (0.93 at ±6 px, 0.90 at ±12 px), i.e. the mapping is exact.
Over the pillarbox the same test gives r = 0.15 — no picture bleed.

## 5. Reference UI geometry (measured from the user's screenshot)

User's screenshot: 2016 px wide → device 1096 px ⇒ **×0.5437**; then ÷2.625 for dp.

| Element | Reference px | Device |
|---|---|---|
| Capsule | 1790 × 72 | **~377 × 15 dp**, pill corner |
| Seek track | 12 tall | **2.5 dp** |
| Thumb | 32 ⌀ | **6.6 dp** (2.7× track) |
| Text / icons | ~34 | ~7 dp |
| Row order | one row | `⏪15 ▶ ⏩15 · 00:02 · ▬▬●▬▬ · 01:00 · ⏩⏩` |

Insets ~12 dp sides / 14 dp bottom. Thumb rests ~33.5 % along the track.
**It is a single row, not the two-row bar currently in `activity_player.xml`.**

User's phone screenshots live in `/sdcard/Pictures/Screenshots/` — pull them with
`$ADB pull` and read with `read_image`. This is how the 7.1 error was diagnosed: the capture carried
the full native text that no log line produced.

## 6. Test harnesses

| Script | Checks | Notes |
|---|---|---|
| `tools/smoke-test.sh` | 23 | basic play/seek/state |
| `tools/verify-multichannel.sh` | 22 | codec routing + **same-language subtitle identity** |
| `tools/verify-poweramp.sh` | 11 | provider, playable URI opens, artist+album 140/140 |
| `tools/verify-spatial-audio.sh` | 13 | `isSpatialized=true`, head tracking non-DISABLED |
| `tools/verify-gestures.sh` | 11 | sensitivity, chrome tap/timeout — injects real swipes/taps |
| `tools/verify-glass-backdrop.sh` | 4 | glass samples the picture behind it (§4.10) |
| `tools/verify-chapters.sh` | 10 | Matroska chapter parse via the probe, plus the no-chapter case |

**94 checks total.** `tools/png_reader.py` is a dependency-free PNG reader shared by the image
harnesses; `tools/chrome_presence.py` reports how much structure is in the **top bar** as the
"chrome is visible" proxy, and `tools/glass_tracking.py` adds the freeze/tracking maths.
Probes: `SmokeChaptersProbeActivity` (`SMOKE_CHAPTERS`, `--es chapters_probe_path`) logs one flat
line per chapter. It exists because the player animates continuously, so `uiautomator dump` will not
settle on it - the UI cannot be introspected, and the alternative would be OCR of a screenshot. The
same trick is worth reaching for whenever a suite needs to assert on something the UI renders.

**Chrome visibility had to be re-thought** when the capsule moved over the picture: the old probe
averaged a fixed band in the bottom eighth, and the capsule now floats just inside the *picture's*
bottom edge, which moves with the aspect ratio (≈54–60% of the height for a 16:9 clip). The top bar
is anchored to the window instead. Brightness is a poor discriminator there (9.1 up against 1.9
down); the **spread** is not (28.5 against 2.7), so the probe returns the standard deviation and the
harness thresholds at 10.

`verify-glass-backdrop.sh` is the tool to run after **any** change to the glass or to the chrome's
placement. It forces landscape (restoring the rotation settings on exit), plays a static pattern,
pauses, then photographs the same frame with the chrome hidden and shown and correlates the two at
identical coordinates. It needs the pattern on the device; the script prints the `ffmpeg` line if it
is missing. Generate it into `/tmp/grid_720p.mp4` (or pass `HOST_CLIP=`).

Debug probes (register in `app/src/debug/AndroidManifest.xml`):
`SmokeTestActivity` (`SMOKE_PLAY`), `SmokeProbeActivity`/`SmokeTrackProbeActivity` (`SMOKE_TRACKS`,
extras `track_probe_path`, `track_probe_uri`, `track_probe_select_text_index`,
`track_probe_select_audio`, `track_probe_profile`), `SmokePowerampProbeActivity` (`SMOKE_POWERAMP`),
`SmokeSpatialProbeActivity` (`SMOKE_SPATIAL`, `--ez spatial_probe_stereo true`),
`SmokeSpatialToggleProbeActivity` (`SMOKE_SPATIAL_TOGGLE`),
`SmokeAudioOutputProbeActivity` (`SMOKE_AUDIO_OUTPUT`).

Test media: `tools/make-host-test-media.sh` (ffmpeg; **per-stream `-ac:a:N` is required or the flag
lands on the wrong stream**) and `tools/make-test-media.sh` (on-device screenrecord; **must
`KEYCODE_WAKEUP` first or you get `INVALID_LAYER_STACK`**).

On device: `/sdcard/Movies/SpatialPlayerTest/` holds `ac3_51_720p.mkv`, `truehd_51_720p.mkv`,
`hdr10_hevc_720p.mkv`, `hlg_hevc_720p.mkv`, `h264_aac_subs_720p.mkv`, `multi_stream_test.mkv`,
`multi_eng_subs.mkv`, `yt_1080p_ac3_51.mkv`, `yt_hdr10_av1_ac3_51.mkv` (4K HDR, 388 MB), `broken.mkv`,
`grid_720p.mp4` (static SMPTE bars + grid lines, 30 s — use this one for anything pixel-level, §3.12).

Commit style: `git -c user.name="XiaoGanCN" -c user.email="76635216+XiaoGanCN@users.noreply.github.com"`.
Use `commit -F -` with a heredoc; backticks in `-m` get shell-expanded.
Repo: https://github.com/XiaoGanCN/SpatialPlayer (branch `main`). Release `v0.1.0` has a **stale** asset.
GPL-3.0 applies to distributed builds (nextlib); app source is MIT.

---

## 7. Task state

### Done and verified on-device
1. Tap-to-controls: first tap works; timeout **6 s** (was ~0.5 s); suppressed while a sheet is open.
2. Error card: moved to the top so it never covers the controls; shows full native error; Copy button.
3. Subtitle/audio **selection identity**: keyed by `"group#trackIndex"`, not `indexOf` on a data class.
   4 × `eng` tracks now distinguishable and the 3rd highlights the 3rd.
4. Gestures: routing via `dispatchTouchEvent`; 48 dp slop + 60 ms hold + per-event cap; viewport fed.
5. Haptics: amplitude compositions (snap/open/close/bump/error) alongside platform constants.
6. Poweramp: plays (was source error) + artist **and** album on 140/140.
7. Thumbnails: real frames, black lead-ins skipped, LRU + 2-thread pool.
8. Library: collapsed behind a glass disclosure header showing `321 · 1 folders`, persisted.
9. External open: `ACTION_VIEW` + `ACTION_SEND`/`SEND_MULTIPLE` + `clipData` + Matroska/HLS filters.
10. Head tracking survives seek (attributes re-asserted on `DISCONTINUITY_REASON_SEEK`; failures
    swallowed so the repair cannot invent an error).
11. Ambient glow recomputed on rotation.
12. Spatial on/off toggle exposed (user-confirmed working).
13. Double-tap jump configurable + persisted, shared with the skip buttons; split at the midline.
14. **Remember last position until app quit** — in-memory `PlaybackMemory`, verified `30665 → 30665`.
15. **7.1 sink guard** — `AudioOutputCapability`, the 70 GB film's failure.
16. **Glass optics ported** — noise removed, bevel profile + two-lobe rim; capture 192×108.
17. **Glass backdrop coordinate mapping** — window → picture → buffer, plus the out-of-picture mask.
    Verified pixel-exact (§4.10). This is the fix for "it is sampling the whole screen at a
    made-up aspect ratio".
18. **Player capsule is one row** — `⏪ ▶ ⏩ · elapsed · seek · remaining · ⋯`, the reference's shape.
    Speed, scaling, subtitles and audio are behind `⋯` in `ui/OverflowSheet.kt`, and each row reports
    its current value (`1.00×`, `Fit`, `Off`, `stereo · MP4A-LATM`) so the readout the old button row
    gave at a glance is not lost. The sheet reuses the `InspectorSheet` window treatment, including
    the **real compositor blur** — which is the only place in this app where genuine background blur
    works today (§4.8), so it is worth copying again. It also has to hide the system bars itself: a
    dialog window does not inherit the activity's immersive state, so the status bar reappeared over
    the picture the first time it opened. It also takes the chrome away while it is up
    (`hideControls()` on show, `showControlsTemporarily()` when it is dismissed) — a sheet is a window
    over the activity, so a chrome that is still drawn underneath shows through the translucent rows.
    The existing `InspectorSheet` is routed through `showInspector()` for the same reason.
19. **Main screen is one action chip** — `[Open file (largest, accent)] [Poweramp ⌄] [Library ⌄]`,
    replacing a bottom bar plus a separate "Library" disclosure row above the list. All three segments
    are a fixed 44 dp so they are exactly the same height; sized to their own content they came out
    105 px / 83 px / 89 px with three different label baselines.
    **Poweramp and Library are two folding libraries over one list**, mutually exclusive: Poweramp
    holds the music, Library holds everything else, and the filter decides which is on screen. Each
    has its own chevron, and both animate on the same 280 ms curve. When one opens the chip docks
    under the status chips (measured: chips bottom 338 → chip top 364 = +10 dp) and the list reserves
    room for it; the position is recomputed from the layout so rotation and font-scale changes are
    covered. The pref key changed (`folded_library`), so an old install starts folded, which is fine.
20. **Chips no longer cut off mid-pill** — both chip strips have horizontal fading edges, so the
    strip dissolving at the edge is the affordance that there is more to scroll.
21. **Audio rows have cover art, square** — `ThumbnailLoader` had
    `if (mimeType?.startsWith("video/") != true) return`, so recordings could never get a preview. It
    now takes an `isAudio` flag and loads art via `ContentResolver.loadThumbnail` with
    embedded-picture as the fallback. The *bounds* are made square too (40 dp, with a matching end
    margin so titles still line up with video rows) — a square cover inside a 16:9 frame leaves two
    dead panels of frame either side and reads as a broken image.
22. **Refresh button icon** — was `ic_ambient`, two concentric circles, i.e. an empty ring that read
    as a toggle in the "off" state. Now `ic_refresh`. The user explicitly did not want a text label.
23. **Ambient glow on rotation** — `onConfigurationChanged` posted `updateVideoRect()` two extra
    times and still raced the layout pass: the posted run could see the *pre-rotation* width, so the
    wash was drawn for the old orientation and its bands landed on top of the picture. The player view
    now has an `addOnLayoutChangeListener` that recomputes when its size actually changes. Verified by
    rotating back and forth twice: the picture's black areas inside the frame read `(0,0,0)` and the
    glow is confined to the pillarbox/letterbox.
24. **Press and lift haptics** — `Haptics.attachTo(view)` installs the pair in one place (VIRTUAL_KEY
    on `ACTION_DOWN`, a new and deliberately lighter `Haptics.lift` on `ACTION_UP`). `ACTION_CANCEL`
    is ignored on purpose: that is a parent taking the gesture over, and buzzing on the way out of a
    scroll is noise. Applied to the player chrome, the chip, the header buttons and the overflow rows.
25. **Capsule hit-testing is per control, not per capsule** — `isInsideControls` used the capsule's
    bounds, which turned the full-width pill into a dead band: a vertical volume drag starting on the
    chrome did nothing. It now tests the actual controls, so the glass between them behaves like the
    picture (tap toggles the chrome, drag adjusts).

### Functional items — done this round
- **C8 chapters.** `media/MatroskaChapters.kt` (Media3 1.8 has no chapter API at all), a `Chapters`
  panel, a **Chapters** row in the overflow showing "3 / 16", and `tools/verify-chapters.sh`
  (10 checks). Verified on device against a five-chapter MKV: the panel lists the right titles and
  times, and the parser reports none for a file without them.
- **C5 settings.** `SettingsStore` (one home for every persisted value - there was none before, each
  screen owned its own literals and defaults) and `SettingsActivity`, reached from the header gear.
  Sections: Playback (spatial, ambient, skip length, subtitle cap), Decoding (decoder profile,
  policy), Gestures (drag sensitivity), Appearance (glass material), Device and build, Diagnostics
  (copy a report). **Choice rows show every option at once with the current one marked** - the user's
  complaint that speed and scaling "just click and change state" applies here too, so nothing cycles.
  The material setting is applied, not decorative: CLEAR darkens the picture where REGULAR lightens.
- **C11, with a real bug found while verifying it.** A file with fifty untitled subtitle tracks put
  each one in its own Media3 group of one, so `labelFor` numbered them *within the group* and every
  row read `Track 1 [application/x-subrip]` - fifty identical rows with no way to tell which was the
  thirty-seventh. Labels now number against the whole type (`Track 7 of 50`), verified on device, and
  the cap is exact rather than one short: the old `take(limit - selected.size)` plus a de-duplicating
  `distinctBy` listed 19 of a 20 cap whenever a selected track fell inside the first block.
  The cap itself: the picker lists at most `subtitleTrackLimit` tracks (default 20, settable), keeps
  whatever is selected visible regardless of where it falls, and offers "show all N" for the rest -
  the reference film has 51. A channel downmix now reports itself: a new
  `onEngineAudioDownmixed(from, to)` listener callback raises a "7.1 → 5.1" chip and a one-line
  message, instead of the film quietly playing as 5.1 with nothing to say it was not 5.1.
- **Q1 stereo upmix.** `media/StereoUpmixProcessor.kt`, inserted via
  `SpatialRenderersFactory.buildAudioSink`. Front L/R bit-exact; centre `(L+R)/2` at -3 dB; LFE a
  120 Hz second-order Butterworth sum at -6 dB; rears the difference signal at -6 dB with a 12 ms
  delay. **Gated on `AudioOutputCapability.canOpenTrack(6, sampleRate)` - upmixing onto a sink that
  then refuses the layout would turn a track that plays into one that does not. Needing `configure`
  to *decline* is worth knowing: the return type is not nullable, so the way to opt out is to throw
  `UnhandledAudioFormatException` (Media3 catches it and drops the processor).
- **Q1: DONE, and it needed a Media3 behaviour to be found first.** Stereo is upmixed to 5.1 and the
  platform now spatialises it, so head tracking can engage for music. Verified on device: a stereo
  AAC file logs `upmixing 48000 Hz stereo to 5.1`, `dumpsys audio` shows the output configured
  `channelMask=0x3f` and `isSpatialized=true`. `tools/verify-spatial-audio.sh` asserts all three
  (13 checks). The reason it took three attempts:

  **`DefaultAudioSink.configure` in Media3 1.8 skips the custom `AudioProcessorChain` entirely
  whenever float output is used.** From `javap` on the 1.8.0 artifact:
  ```
  70: ifeq 86        // shouldUseFloatOutput(pcmEncoding) == false -> the int path
  73: add(toFloatPcmAudioProcessor)
  83: goto 111       // <-- jumps past the chain
  86: add(toInt16PcmAudioProcessor)
  99: add(audioProcessorChain.getAudioProcessors())   // only reachable on the int path
  ```
  `shouldUseFloatOutput` is `enableFloatOutput && isEncodingHighResolutionPcm(pcmEncoding)`, and
  nextlib's FFmpeg audio renderer decodes to **float** — which is exactly why this app enabled float
  output. With it on, the sink configures happily, raises nothing, and simply never calls
  `getAudioProcessors()`: a **silent** bypass, which is why this looked like the processor was not in
  the chain at all. The symptom that gave it away was a negative one — `SonicAudioProcessor` lives in
  the same chain and speed still worked, because speed is handled by `AudioTrack` playback params
  rather than by `SonicAudioProcessor`.

  The upmix is therefore installed only when spatial audio is on, and with float output off. That
  costs a float-to-16-bit conversion, which is a deliberate trade: the case this feature exists for
  is a Bluetooth headset whose link is lossy (LDAC) long before 16 bits matter, and switching spatial
  audio off in Settings restores the bit-perfect float path for music that does not want to be
  spatialised.

  **Declining a format must not throw.** The processor originally declined non-stereo input by
  throwing `UnhandledAudioFormatException`, which is the other documented way to opt out — but
  `DefaultAudioSink.configure` converts it into `AudioSink.ConfigurationException`, which is *fatal*:
  an 8-channel file failed outright with `UnhandledAudioFormatException: Unhandled input format:
  AudioFormat[..., channelCount=8]`. It now returns the input format unchanged and reports inactive,
  which makes `AudioProcessingPipeline.configure` skip it cleanly (it only advances the format and
  records the processor when `isActive()` is true). Verified afterwards: 8-channel passes through
  with zero errors, 5.1 passes through, stereo upmixes. The 7.1 PCM asset earned its keep by catching
  this.
- **C6 leftover**: the user reported the real error only occurred on the 70 GB film; confirm the
  guard resolves it.

### Glass backlog — user-reported, explicitly deferred ("we will address this later")
The four below are the user's own list, in their words, and they are the acceptance criteria for
calling the glass finished. Do not re-litigate the coordinate fix (§4.10) — it was necessary (the
pane was sampling an unrelated corner of the frame) but it only made the backdrop *correct*, not the
optics *complete*.

1. **"The blur are gone."** Correct, and this is a real omission, not a regression: the reference has
   a dedicated blur stage (`/tmp/lg/liquidglass/src/main/java/com/example/liquidglass/AdvancedFastBlur.kt`
   — downscale to 0.4, box blur, upscale, with a bitmap pool) and the port took the refraction, bevel,
   dispersion and rim model but **never that pass**. Add a blur of the sampled backdrop before the
   refraction offsets are applied.
2. **"Fold refraction at the very edge are also gone."** The rim bend is in the shader
   (`uRefract`/`uFalloff`) but is no longer visible. Suspects, in order: `uBevel` (16 dp) and
   `uRefract` (12 dp) are far too small relative to a pane this size; the one-texel-per-~1.3-view-px
   resolution of the copy limits how far the rim can reach before it visibly stair-steps; and the
   bevel profile may be being swamped by the tint. The probe to write is a still frame with the pane
   over a high-contrast edge (the bars/grid clip) zoomed 8x at the left rim.
3. **"Refresh rate ... super slow compared to the original repo demo that reaches 240fps easily."**
   Expected from the design: this polls `PixelCopy` every `REFRESH_MS = 90` (≈11 Hz) because a
   `SurfaceView` cannot be sampled from the view tree, while the reference draws its host view tree
   into a bitmap in-process on every frame (`BackdropCapture` + `AsyncRenderer`) and so runs at
   display rate. Closing this gap means changing *how* the backdrop is obtained, not tuning the timer
   — and every alternative (TextureView) gives up HDR passthrough, which is the player's reason to
   exist. §4.8 has the full trade-off.
4. **"Can't we just render the panel in HDR as well?"** The picture is HDR on its own `SurfaceView`
   layer, but the UI window is an SDR `V0_SRGB` layer (verified in the `dumpsys SurfaceFlinger` dump),
   so the chrome is composited in SDR next to an HDR layer. Chrome drawn *over* the picture is
   therefore tone-mapped against it by SurfaceFlinger, not by us. A genuinely HDR panel means making
   the window HDR, which is not something a normal app can switch on. Worth answering honestly with
   the layer dump rather than attempting.

### Remaining — UI/shader (user reprioritised: **UI first**, glass later)
The user's latest instruction is explicit: *"Fix other ui related issue first (buttons, progress bar,
chips etc.), we will address this later"* — "this" being the four glass items above.
- **B2** component set: `GlassButton`, `GlassToggle`, `GlassSlider`, `GlassSheet`, `GlassPopup`,
  `GlassMaterial` (REGULAR/CLEAR), `Motion.kt` (260 ms standard, 120 ms press, 320 ms layout).
  Replace `bg_glass_*.xml` and `AlertDialog`. All elements must follow the design, not just the bar.
  **Still open** — the single-row capsule and the chip merge below are layout/behaviour, and they
  deliberately kept the existing `bg_glass_*.xml` look rather than restyling everything at once.
- **Launcher icon**: the user rejected earlier attempts as "nearly unusable" — needs a real
  double-check. `README.md`: keep it terse, no excessive explanation.

### Answers already given (do not re-derive)
- **Q1** feasible but needs app-side upmix (§4.1). **Q2** not possible without root (§4.3).
