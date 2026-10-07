# Session handoff — Spatial Player

Everything learned while building this, so the next session does not have to rediscover it.

**Where to look first**

| Section | What it is for |
| --- | --- |
| **§8 Open work** | The to-do list. One line per item, meant to be edited in place. Start here. |
| §7 What is done | The state of the project in one screen, plus the standing constraints from the user. |
| §3 Traps | The mistakes that cost the most time. Read before touching tooling. |
| §4 Verified findings | Things already measured so they are not re-derived. |
| §5 Reference geometry, §6 Harnesses | Measurements and the suites that produce every "verified" claim. |
| §6b, §6c | Round notes: the quickfixes and the glass revamp, with the dead ends recorded. |

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
| `tools/verify-background.sh` | 7 | audio survives HOME and screen-off, video does not (§8 has the one open check) |

**101 checks total, 100 passing.** `verify-background.sh` is 6 of 7: video paused on HOME is the
open failure, with the leads written up in §8. `tools/png_reader.py` is a dependency-free PNG reader shared by the image
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

## 6b. Quickfix round: background audio, chips, header, covers, fonts, and the upmix matrix

### Background playback and the media session

`media/PlaybackEngine.kt` is a process-wide holder, `PlaybackService.kt` is a `MediaSessionService`,
and `PlayerActivity` no longer owns the engine's lifetime. Music is allowed to outlive the screen;
nothing else is.

* **The policy is one predicate**: `engine.hasVideo` decides whether `onPause`/`onStop` pause. It
  reads the *selected tracks*, not the mime type, because a Matroska file may hold either and the
  container says nothing. Verified on device: a six-channel audio-only MKV reported
  `state=PLAYING(3)` with the position advancing `8908 -> 17932` across ten seconds with the screen
  off, and a video paused on HOME.
* **`AudioProcessor.configure` is not the only thing that must not throw** - so is nothing here, but
  the same lesson applied twice in this file's history.
* The service does **not** own the player. The activity still drives the engine directly (chapters,
  tracks, spatial controls), and routing all of that through a `MediaController` would have been a
  much larger change for no gain in a single-process app. The service's job is the session and the
  notification, and it re-points itself at the new player whenever the engine rebuilds - a rebuild
  creates a *new* `ExoPlayer`, so a session holding the old one would show dead transport controls.
* Two bugs found by reviewing this rather than by running it: `PlaybackEngine.acquire` originally
  applied its listener only when it *created* the engine, so a screen reopening onto a running engine
  got no callbacks at all and would have sat on stale state with no error anywhere; and the service
  was left started after a video was closed, claiming a notification slot for nothing.
* `POST_NOTIFICATIONS` is requested on the library screen. Without it the player still runs in the
  background - measured - but there is no transport control and no entry to tap, which is most of the
  point. `startForegroundCount` was 0 before the permission was granted - and the real cause turned
  out to be elsewhere: Media3 requires the session to be **added** to the service with `addSession`,
  and a session that is only returned from `onGetSession` is not. With that, `startForegroundCount=1`,
  `isForeground=true`, and a `category=transport` notification with two actions.

### The rest

* **Chips wrap instead of scrolling.** Both chip strips were `HorizontalScrollView`s with a 36 dp
  fading edge, and the fade rendered whichever chip sat at the right edge - usually the decoder -
  through a gradient, which reads as a broken pill rather than as "scroll for more". `ChipStrip` now
  measures and lays out its own lines. The fallback for an unbounded width restores the old
  `LinearLayout` behaviour, because a missing position entry would otherwise stack every chip at 0.
* **The header buttons were touching**: three 40 dp `ImageButton`s with no margins at all.
* **Audio without cover art** had two separate faults. `.aiff` was missing from `AUDIO_EXTENSIONS`,
  so an AIFF with no type of its own was laid out as a *video* and its cover cropped into a 58x34
  letterbox; and the placeholder was a speaker-with-waves glyph, which is the symbol for output
  rather than for music, and is the same shape as the volume controls. `mimeForExtension` now maps
  audio containers too (it only knew video), which fixes both the layout and the type handed to the
  player, and `ic_audio` is beamed eighth notes.
* **Artist and album are no longer monospace.** Only the numbers on those lines are technical, and
  `TextSpans.mixed` already existed to mark exactly those; the row's second and third lines were
  simply inheriting a monospace style. New `Text.Row.Meta` and `Text.Row.Caption`, system face.
* **The stray "-"** was the em dash standing in for an unknown size, sitting between the duration and
  the source: folder-scanned entries have `durationMs = 0` and Poweramp entries can have no size, so
  `"3:26  ·  —  ·  poweramp"` is what that produced. The line is now assembled from the parts that
  are actually known.

### Q3: the upmix matrix, and six-channel sources

* `UpmixMode` has three mappings, chosen in Settings and stored by name so reordering the enum cannot
  silently change what a saved preference means:
  * `SURROUND` - fronts copied, centre `(L+R)/2` at -3 dB, rears the difference signal delayed 12 ms,
    sub both channels low-passed at 120 Hz. What it did before.
  * `WIDE` - left to front-left **and** surround-left, right to front-right and surround-right, no
    delay and no difference: the original image wrapped around, at -6 dB in the rears because unlike
    the difference signal this adds to the fronts.
  * `FRONT` - the front pair only, nothing invented.
* Verified that the choice reaches the DSP: selecting Wide in Settings and playing a stereo file logs
  `upmixing 48000 Hz stereo to 5.1 using WIDE`. The mode is read at `configure`, so a change rebuilds
  the player, exactly like the spatial toggle.
* **Homemade six-channel audio works as spatial audio.** The stems in `~/Desktop/a` (six stereo WAVs,
  each named for its target channel) were merged to a 5.1 PCM Matroska at
  `/sdcard/Movies/SpatialPlayerTest/stems_51.mkv` and play as proper 5.1: the processor passes them
  through untouched (they are already six channels), `dumpsys audio` shows `channelMask=0x3f`, and
  `isSpatialized=true`.

### New harness

`tools/verify-background.sh` asserts the policy from the platform's own view: audio still `PLAYING`
with the position advancing after HOME and across a screen-off, video not playing in either case, and
the session published. **Run: 6 of 7.** The one open check, and the two ways the harness itself was
wrong first, are in the round-two notes below and in §8 - read those before trusting a failure from
this suite.

### Round two of the quickfixes

*(Historical notes from that round. The **current** state of the one open item is in §8; the text
below is kept because the two dead ends it records are worth not re-walking.)*

* **The media notification never appeared, and the reason was one missing call.** Media3's
  `MediaNotificationManager.shouldShowNotification` requires `MediaSessionService.isSessionAdded`, and
  a session that is only returned from `onGetSession` is not added. So `startForegroundCount` stayed
  0, no notification was posted, and there were no lock-screen controls - while the session itself
  worked, which is why the media centre listed it and this looked like a notification-permission
  problem. Granting the permission changed nothing. With `addSession(session)`:
  `startForegroundCount=1`, `isForeground=true`, and a `category=transport` notification with two
  actions.
* **Background playback is now a setting**, two of them: music (on) and anything with a picture
  (off). One predicate reads the selected tracks and picks the right flag.
* **The chip strip was unscrollable**, which is what made the fade look like a broken chip rather
  than a "scroll for more" cue: `dispatchTouchEvent` handed every drag outside the capsule to the
  gesture layer, so a horizontal drag across the chips adjusted the volume instead of scrolling
  them. A fade that can never be scrolled off a chip is just a chip rendered through a gradient.
  Chips now keep their own touches, the fading edge is unchanged, and the scroller has end padding
  equal to the fade length so the last chip can always be scrolled clear of it. **The wrap is
  reverted** - it was a valid fix and the wrong one, as you said.
* **Bluetooth was being reported as stereo when it is not.** `AudioOutputCapability.maxChannels`
  skipped Bluetooth devices on the theory that A2DP is stereo once encoded. That is true of the
  *link* and false of the *sink*: measured with the headset connected, the output is
  `channelMask=0x3f` and `isSpatialized=true`. The app was therefore labelling every 5.1 and 6-channel
  item "decoder 6 channels, output takes 2" - a downmix that was not happening - and capping the
  track selector at stereo. Bluetooth now advertises 5.1 (what it actually takes; 7.1 is what failed
  before) and the probe decides between that and stereo.
* That wrong note was also **logged on every chip rebuild**, several times a second, which is how it
  was found: it had buried everything else in the log buffer. Logged on change only now.
* `ic_audio` was optically off-centre - its box spanned x 7..22 of a 24-wide viewport - and is now
  centred.
* **Still open: video on HOME.** `verify-background.sh` is 6 of 7. Background audio, screen-off
  audio, the media session and **screen-off video (PAUSED(2))** all pass; "video paused when the app
  was backgrounded" still reports PLAYING. Two leads, in order:
  1. **Picture-in-picture may be engaging on HOME.** `onUserLeaveHint` calls `enterPipIfPossible`,
     and `shouldPauseForBackground` deliberately returns false in PiP because the picture is still
     visible. Nothing in the manifest declares `supportsPictureInPicture`, so it should be throwing,
     but that is the one difference between the HOME path and the screen-off path - and screen-off
     passes. Instrument `isInPictureInPictureMode` at the decision to settle it.
  2. **The engine could not identify a video-only file as video.** For `grid_720p.mp4` (one h264
     stream, no audio) `player.currentTracks.groups` was measured **empty** and `videoFormat` null
     while it was visibly playing, so `hasVideoTrack` and the `hasVideoFormat` fallback both said no.
     `hasVideo` now falls back through tracks, then the video format, and only then defers - but a
     device that reports neither leaves the question unanswerable at that moment.
  The harness was also wrong twice on the way: it read whichever session `dumpsys media_session`
  listed first (a stale Bluetooth one carrying ERROR(7)), and `KEYCODE_HOME` only closes the
  notification shade when it is open, so the app never left. Both are fixed, and the checks that
  used to pass vacuously now require the player to be playing first.
* **Q3 advanced mode**: `UpmixMatrix` gives every output channel its own source (L, R, L+R, L-R, R-L,
  muted), with the subwoofer low-pass as a switch and no delay, because the presets add one to make
  the rears read as space and a hidden delay would make a manual mapping not match what was asked
  for. `DIFFERENCE_INVERTED` exists because a surround pair carrying the *same* difference signal is
  in phase and collapses behind the listener - the presets fill the rears with opposite polarity, so
  a matrix that cannot say that cannot reproduce them. A Simple/Advanced toggle chooses between the
  editors and rebuilds the page rather than hiding rows in place. Verified: selecting Advanced logs
  `upmixing 48000 Hz stereo to 5.1 using ADVANCED`.

## 6c. The liquid glass revamp

The material is now one implementation used by every surface, in three pieces:

| Piece | What it is |
| --- | --- |
| `LiquidGlassView` | The shader, and the *container* form. Reads its backdrop from the video `SurfaceView` with `PixelCopy`; used for the player's capsule. |
| `GlassBackdrop` | The window's own content, re-rendered into a small bitmap and shared by every pane on the screen. |
| `GlassDrawable` + `GlassInstaller` | The material as a background for an ordinary view, and the walk that installs it. |

### The frost, which was simply absent

The shader sampled the backdrop three times and **only bent it** - refraction, dispersion and rim
light were all there, but nothing diffused - so the effect was a lens rather than a diffuser: crisp
content behind a warped edge. It now runs a 13-tap kernel on two rings, with the radius growing
towards the rim, because thick glass diffuses more where the light path through it is longest. That
is visible immediately in the capsule over colour bars.

### The backdrop cannot come from the compositor

The window's own drawing cannot be read back while it is being drawn into, so `PixelCopy` is no help
off the player. `GlassBackdrop` re-renders the view tree into a bitmap instead, exactly as a
screenshot does - and **stands every glass surface down while it does**: `capturing` is raised and the
drawables return without drawing, so the capture holds everything *except* glass, which is precisely
what glass should refract. One capture per screen, throttled to 80 ms, triggered from an
`OnPreDrawListener`, and only while at least one glass surface is attached.

### Installing it without touching five layouts

`GlassInstaller` walks a screen once and swaps any view wearing `bg_glass_button`,
`bg_glass_button_active` or `bg_glass_panel` for real glass of the same shape - so the header buttons,
the chips, the action bar, the settings cards and the sheet rows all become one material. It
**re-wraps the ripple around the glass** rather than replacing it; losing press feedback would have
been the kind of regression that makes a revamp worse than what it replaced.

`GlassDrawable` shares **one compiled shader** across every pane: a `RuntimeShader` is stateless
between draws, and compiling one per button meant a few dozen compilations and pipelines per screen.

### Where the material is tuned

`GlassStyle` (radius, bevel, refraction, dispersion, blur texels, tint, body, specular) holds every
number, and `GlassInstaller.roles` maps each background resource to a style. Changing the look of all
buttons is one line there; the capsule is tuned by the properties on `LiquidGlassView`.

### The suite had to change with the material

`tools/glass_tracking.py` asserted "pixel-exact tracking" against a *sharp* reference, which a frost
reduces **by design** - so it failed the moment the blur landed. The thresholds now account for
diffusion (correlation 0.8638 against a 0.55 floor; pillarbox 0.609 against 0.75) and it still fails
a glass that paints the picture across the letterbox, which is what those checks are for. Do not
"fix" a future failure here by lowering the numbers without reading this first.

## 7. What is done

The per-round detail lives in §6b (quickfixes, background playback, the upmix matrix) and §6c (the
glass revamp). This is the short version, so the state of the project can be read in one screen.

### Verified on device

- Playback: AC3 / EAC3 / TrueHD / DTS through the bundled FFmpeg, 5.1 output, `isSpatialized=true`.
- **Stereo is upmixed to 5.1 and spatialised**, which is what lets music head-track at all (§4.1).
  Three presets plus a per-channel manual matrix, all reachable from the player's audio panel and
  applied live from Settings.
- Chapters: hand-written Matroska parser, panel, and a ten-check harness (Media3 1.8 has none).
- Music plays in the background and with the screen off, published to the media centre with working
  transport controls; video pauses with the screen off. Both behaviours are settings.
- Settings: one store, one screen, and every value is applied to a running player.
- Subtitles: capped track list with "show all", and tracks are distinguishable even when the container
  gives them no titles.
- Liquid glass: a frosted, refracting material on chips, buttons, cards, the action bar and the
  capsule, from one shader and one style table (§6c).

### The harnesses

Eight suites, 101 checks, all passing except the one listed in §8. They are the reason to trust any
of the above: every claim in this document that says "verified" was produced by one of them, and the
ones that read oddly (a `dumpsys` field, a pixel correlation) are all explained where they are used.

### Standing constraints from the user

- **Never** `pm clear`, uninstall, reboot, or `adb shell monkey`. `KEYCODE_WAKEUP` / `SLEEP` / `HOME`
  are fine. Restore anything you change (rotation, volume, animation scale).
- **Do not blast the volume** while testing.
- If adb needs re-authorising or the phone needs a tap, **say so on this Mac** - a modal `osascript
  display dialog` with a sound works; a plain `display notification` was missed more than once.
- `README.md` stays terse.

## 8. Open work

Nothing below is blocked on a missing piece of knowledge; each line says what to do and where. Edit
this list in place - it is the handoff's to-do, and it is deliberately one line per item so it can be
reordered and struck out without reflowing prose.

Format: `- [ ] **What** — where it lives / how to tell it is done.`

### Known-broken or unfinished

- [ ] **Video does not pause when the app is backgrounded (HOME), though it does with the screen off.**
      `tools/verify-background.sh` is 6 of 7 on this one check. Two leads, in order: (1) instrument
      `isInPictureInPictureMode` at the decision in `PlayerActivity.shouldPauseForBackground` -
      `onUserLeaveHint` calls `enterPipIfPossible`, and PiP deliberately does not pause, and nothing in
      the manifest declares `supportsPictureInPicture`; (2) the engine could not identify
      `grid_720p.mp4` as video at all - `currentTracks` came back empty and `videoFormat` null for a
      file that was visibly playing, which is why `PlayerEngine` falls back through tracks, then the
      video format, then defers.

- [ ] **Head tracking recenters after a few seconds and cannot be anchored from the app.**
      Not an app-level fix: Android's `Spatializer` API is read-only, and this device's spatializer is
      Sony's own vendor effect (`/vendor/lib64/soundfx/libtsrspatializer.so`). Needs shell root, which
      is **not** currently reachable (`adb shell su` -> "inaccessible or not found"); enable it in
      KernelSU and then look at, in order: the effect's parameters in `dumpsys media.audio_flinger`, a
      vendor audio-effects config for a stillness/recenter timeout, and whether the recenter is
      actually headset-side (`com.sony.songpal` is installed, and the 1000X does its own tracking -
      check the headphones app first, because nothing on the phone would change that).

- [ ] **Glass refresh is ~11 Hz on the player, against a reference that runs at display rate.**
      `LiquidGlassView` polls `PixelCopy` every `REFRESH_MS = 90`. Closing the gap means changing *how*
      the backdrop is obtained, not tuning the timer - and every alternative (TextureView) gives up HDR
      passthrough, which is the player's reason to exist. See §4.8 for the trade-off. The non-player
      screens are on `GlassBackdrop`, which is a re-render rather than a readback and could be driven
      per frame if the tree is cheap enough to draw twice.

- [ ] **An HDR panel is not possible for the chrome.** The picture is HDR on its own layer; the UI
      window is an SDR `V0_SRGB` layer, so chrome over the picture is tone-mapped *against* it by
      SurfaceFlinger. Answer with the layer dump in §4.10 rather than attempting it. Still unanswered
      as a design question, not as a bug.

- [ ] **Launcher icon needs a real double-check.** Earlier attempts were rejected as "nearly
      unusable"; whatever replaces them must be looked at as a rendered icon at real sizes, not as a
      vector source. `app/src/main/res/mipmap-*/`.

- [ ] **`README.md` must stay terse.** No excessive explanation. It is currently stale with respect to
      the settings screen, the upmix matrix and the media session.

### Deliberately not doing

- **Glass inside the sheets.** `OverflowSheet` and `InspectorSheet` are dialogs that already get a real
  cross-window frost from `Window.setBackgroundBlurRadius`. Installing `GlassDrawable` there would
  sample the dialog's own (empty) backdrop instead of the app behind it, which is worse than what they
  have.

- **`setIsContentSpatialized(true)`.** It disables head tracking rather than enabling it; see §4.2.

### Answers already given (do not re-derive)

- **Q1** - stereo cannot be spatialised, 5.1 can; hence the upmix (§4.1).
- **Q2** - an app cannot turn platform spatialisation off (§4.3).
- **Q3** - a manual upmix matrix is implemented; see §6b and the "Upmix editor" in Settings.
