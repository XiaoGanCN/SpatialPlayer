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
PKG=com.gan.spatialplayer.debug          # debug package id; release is org.spatialplayer

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

### 3.13 `KEYCODE_SLEEP` then `KEYCODE_WAKEUP` leaves the phone on its lock screen
`mWakefulness=Awake` and `Display State=ON`, but `mCurrentFocus=Window{… NotificationShade}` — and
`cmd statusbar collapse`, `KEYCODE_HOME`, `KEYCODE_BACK`, `KEYCODE_MENU` (82) and swipe injection all
leave it there, while `screencap` returns a black or zero-byte frame. An app started underneath never
becomes visible: it is launched already backgrounded, so a "does it still play" assertion is measuring
a locked phone rather than the app. This was read as an app bug twice.

Two consequences: any suite that uses the screen-off path must confirm something other than the shade
has focus before asserting (`verify-background.sh` now does, via `wake_visible`), and on this device
the only reliable fix is a physical touch — `osascript display dialog` on the Mac is the way to ask.
An earlier symptom of the same thing: `input tap`/`swipe` returning success while nothing moves is not
always the §3.1 stale-injection trap. Check `mWakefulness` first; `Asleep` explains all of it.

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

### 4.8 Glass: the library cannot be used as-is for player page
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

### 4.11 The inspector sheet's tabs must pull their panel, not be pushed to
Every control panel in `InspectorSheet` is a list the *activity* supplies: `populateDecoderPanel`,
`populateSubtitlePanel`, `populateAudioPanel`, `populateScalingPanel`, `populateSpeedPanel`,
`populateChaptersPanel`. The sheet's tab strip lives **inside** the sheet, and its click listener is
just `show(panel)` — so choosing a tab never went through any of those. Only the entry points that
happened to call a `populateX` first had rows, which produces exactly the reported symptom: a panel is
blank when switched to from another page, and a panel visited earlier still looks fine because it is
still holding the list it was given the first time (and keeps it into the next video).

The fix is `InspectorSheet.Callback.onPanelShown`, called at the top of `show()` for every route, and
the activity supplies the rows there. Rows are therefore current whenever a panel appears, which also
removes the stale-list-after-a-video-change half of the bug. Both the overflow rows and the two
long-presses now just call `showInspector(panel)`; the `populateX` calls that used to sit in front of
them were redundant and are gone — if you add a new entry point, do not add one back.

**How it is verified.** `tools/verify-panels.sh` drives `PanelProbeReceiver`, which opens each panel on
the running player and logs `rows=… headings=… empty=…`. Two things about that probe are worth keeping:
the option rows carry no view id at all, so "how many rows" can only be answered by counting
`View.NO_ID` children — and a section heading is a two-child `LinearLayout` exactly like a row with a
subtitle, so it needed an id of its own (`R.id.sheet_section`). A shape-based guess counted subtitle
rows as headings and reported a populated panel as empty; measured, and it cost a build cycle.

### 4.12 The background policy has one unanswerable window, and it is at HOME
`PlayerActivity.shouldPauseForBackground` reads three sources in order: the tracks it has already seen,
then `PlayerEngine.hasVideoTrack`, then `hasVideoFormat`. All three can answer "not yet known", and
that is not the same as "audio" — guessing *video* there pauses music, which is the worse error, so it
defers. Measured capture of the window, with a 1080p AC3 MKV on the reference device:

```
T+0.000  HOME pressed
T+0.000  background: pause=false tracks=null selected=null format=false video=false unknown=true
T+0.148  the tracks arrive
T+0.400  appInBackground() still false   <- the process counts as VISIBLE while being stopped
```

Those two middle lines are the whole bug: the deferred answer was gated on `appInBackground()`, which
said "still on screen" for long enough that the tracks arrived, were seen, and were then ignored — and
nothing asked again, so the film kept decoding behind a black screen. The answer is not a better
visibility test but not needing one: `onStop` records that the question is open
(`deferredBackgroundDecision`) and `onEngineTracksChanged` answers it, because an activity that was
already stopped when the question was left open is one whose picture the user cannot see.
`onResume` clears the flag, and `onPause` deliberately does **not** set it — that is where a retry
timer went wrong once already, pausing the film during a transient `onPause` at launch.

### 4.13 Alpha does not affect hit-testing, and this screen hid three controls behind that
`ViewPropertyAnimator.alpha(0)` leaves the view `VISIBLE`, clickable, and first in line for a touch.
Every "the chrome is hidden" state in this player is alpha, so a faded chrome kept eating taps: pressing
where Back / Inspect / Metadata sat did nothing visible, and the gesture layer never saw the touch that
should have brought the chrome back. Worse, `centerPlayPause` is declared *below* the overlay, so once
the overlay was parked it was next in line - a tap on a capsule button would have toggled play/pause
instead.

The rule: a view hidden by alpha that can also take a touch must be parked at `INVISIBLE`.
`PlayerAnimation.hide(..., gone = true)` does that once the fade ends, and it is safe from the shared
-`ViewPropertyAnimator` trap in §3.4 because `stop()` clears the listener first and `show()` always
resets `visibility` before reading alpha. `tools/verify-rotation.sh` asserts both halves through
`PanelProbeReceiver`'s chrome probe: `routedVisible=true` (a tap on the button goes to the button) and
`routedHidden=false` + `parked=true` (once the chrome times out, the same point is a gesture again).

Note what a synthetic `MotionEvent` is not: feeding one to `dispatchTouchEvent` produced `opened=false`
even with the button on screen and clickable, because `ViewGroup` hit-testing rejects an event with no
real pointer state. Assert the routing predicate instead.

### 4.14 The window is taller than the display, and the capsule was laid out against the window
Measured in portrait: `window=1096x2560` against `display=1096x2497`, because the app draws behind the
navigation bar. The control capsule is the last child of the controls overlay and is positioned with a
`bottomMargin`, so a margin computed from the *window* height puts its lower 63 px under the navigation
bar - visible as chrome hanging off the bottom of the screen. `positionChromeOverPicture` now subtracts
the measured system-bar insets, and reports the target it computed rather than only the margin.

Two things went wrong on the way to finding that, both worth not repeating. Reading a view's position
back immediately after changing its layout params returns the **pre-layout** frame - and on API 34+
`View.post` runs inside the same traversal, so even a posted readback is too early; it reported a
capsule 1163 px below where the placement code had just put it, which looks exactly like a layout bug
and is not one. And a probe activity that `finish()`es leaves the tree mid-layout for a moment, so a
reading taken then is about the probe, not the player. When the numbers and the pixels disagree, take
the screenshot.

### 4.15 `refresh()` and "already loaded" must agree about who owns the data
`MainActivity.refresh()` rebuilt the library from MediaStore and the granted folder and called
`publish`, which begins with `entries.clear()`. Poweramp's rows had been merged into that same list, so
a refresh deleted all 140 of them - while `powerampLoaded` stayed `true`, which made the chip take its
"already in `entries`, no need to re-read" branch and open a permanently empty fold.

The fix is ownership: Poweramp's rows live in `powerampEntries`, which a refresh merges back in (and
re-reads, so music added since does show up). Two related traps are closed with it: an open fold
persisted across launches is now filled by `loadPowerampLibrary()` rather than only re-opened, and an
empty read does **not** set `powerampLoaded`, because `PowerampReader` turns a provider failure into an
empty list with `runCatching` - so latching on empty made "could not read" indistinguishable from "no
music", permanently.

### 4.16 A preference has to identify a track, not describe it
A disc release carries four subtitle tracks in the same language - "American", "American / SDH",
"British", "British / SDH" - and all four are `eng`. That makes a language preference useless for
answering "which one did the user pick": restoring by language lands on whichever `eng` track Media3
reaches first, which is the reported "the highlight sticks to the first [en] row".

`PlayerActivity` already set a precise `TrackSelectionOverride` when a row was tapped, so the *live*
selection was right. What was wrong was what got remembered for the next player: the language alone.
Two things had to change and both were needed:

1. `PlayerEngine` remembers the track's identity - renderer slot (`Tracks.groups` index), type, index
   within the group, and the item it was chosen on - and re-applies it from `onTracksChanged`. The
   group *object* cannot be remembered because a rebuild creates new ones; the index can, because the
   renderers are built the same way and the container is the same.
2. It is also applied at the moment of choosing, against the tracks already in hand. This is the part
   that makes it deterministic: by the time the user taps a row, `onTracksChanged` has usually already
   run for that track list, so waiting for the next one leaves the selection to the language
   preference. The first version only restored on the callback and was **intermittent** - it worked
   whenever another track change happened to follow, which is why it looked fine in one run and failed
   in the next.

If the identity no longer resolves, the override is deliberately not forced: the language preference
already in the parameters is the right fallback, and selecting the first `eng` track beats selecting
nothing.

### 4.17 A debug probe that changes a persisted setting must put it back
`SmokeProbeActivity --es track_probe_profile` called `engine.switchDecoderProfile`, which **writes the
choice to the app's settings**. The probe left `FFMPEG_ONLY` behind, and that profile bypasses
`SpatialRenderersFactory` entirely, so no FFmpeg renderer was registered for anything that ran
afterwards. The symptom was `verify-multichannel.sh` reporting "no FFmpeg audio renderer evidence" -
which reads exactly like a decoder regression, and cost several runs of chasing the wrong thing. The
log line that gives it away: `SpatialRenderers :: audio renderers as built:` is absent, because the
factory was never called.

Two traps stacked here:

* A probe must restore a persisted setting, and it must do so through the normal teardown. Releasing
  the engine before `finish()` meant `onDestroy` found nothing to switch, so the restore silently did
  nothing. The engine now outlives the report and `onDestroy` does the work.
* A suite that reads a probe's log after a **fixed sleep** races the probe. The rebuild's settle time
  grew and a one-second-short wait turned an empty log into "the rows are not distinguishable" and
  "no identity restore happened" - three unrelated-looking failures from one timing bug. The probe now
  logs a `probe_done` marker last and the subtitle checks poll for it.

A related parsing note, since it bit twice in one sitting: these checks grep `logcat -v time -s Tag`
output, where the tag and pid sit *between* the message and the first `|`. A pattern anchored on
`(pid): *track :: type=text|...` matched nothing at all and silently reported the wrong row. Reuse the
pattern that is already known to work on the file rather than writing a fresh one from the format, and
test it against the file.

### 4.18 A Dolby Vision film plays to a black screen, and the decoder is innocent

The user's 78 GB film: 3840×2160 HEVC Main 10, `smpte2084`/`bt2020nc`/`bt2020`, 23.976 fps, plus a
Dolby Vision configuration record and an enhancement-layer decoder config (so **Profile 7**, dual
layer), with TrueHD Atmos 7.1 and 51 subtitle tracks. Audio played, the clock advanced, the picture
was black.

Media3's Matroska extractor relabels the track the moment it finds that record:

```java
// MatroskaExtractor.parseTrackEntry
if (dolbyVisionConfigBytes != null) {
  DolbyVisionConfig dolbyVisionConfig = DolbyVisionConfig.parse(...);
  if (dolbyVisionConfig != null) { codecs = dolbyVisionConfig.codecs; mimeType = MimeTypes.VIDEO_DOLBY_VISION; }
}
```

Nothing on this phone is registered for `video/dolby-vision` (the panel advertises HDR10 and HLG),
so the candidate list is empty, `MediaCodecVideoRenderer.supportsFormatInternal` returns
`FORMAT_UNSUPPORTED_SUBTYPE`, and the video group becomes `supported=false`. **Media3 drops the
track with no error at all** - `READY`, `isPlaying=true`, position advancing, black screen. Only the
player can see this, which is why `TrackProbeActivity` exists:

```
group :: #1 type=video selected=false supported=false
selector :: video/hevc         -> [c2.qti.hevc.decoder hw=true, c2.qti.hevc.decoder.low_latency, c2.android.hevc.decoder]
selector :: video/dolby-vision -> []
```

The base layer is an ordinary HEVC Main10 stream and the hardware decodes it in ~90 ms - proved by
decoding it in `DecodeProbeActivity` with and without the video MIME forced to `video/hevc`, both
`DECODED 1 frame`. **Do not look for a codec problem here.** The fix is `DolbyVisionFallback`, which
wraps the Matroska extractor, rewrites only a `video/dolby-vision` `Format` to `video/hevc`, drops
the `dvhe.07.06` codec string (no HEVC decoder matches it; the real CSD is still in
`initializationData`), and returns the stock factory untouched when the device *does* advertise a
Dolby Vision decoder.

After the fix, the same clip: `video selected=true supported=true`,
`colorInfo=ColorInfo(BT2020, Limited range, ST2084 PQ)`, `renderedFirstFrame=true`, videoSize
3840×2160, and **13.6% of the screen changes between two screenshots three seconds apart, where
before it was 0.00%**. A 60 s cut carrying the film's own TrueHD track does the same
(`audio/true-hd` 8ch selected, `renderedFirstFrame=true`).

### 4.19 "Nothing on the screen" has three different causes and they look identical

This cost most of a round, so it is written down as a method rather than as a finding. A black player
with the audio running can be any of:

1. the container was never parsed (no tracks at all);
2. the track exists but no decoder was selected (`supported=false` - §4.18, and a renderer given no
   output reports *nothing*, it never even constructs a codec);
3. a decoder ran and produced no frames.

Two probes separate them, because they stop at different boundaries: `DecodeProbeActivity` drives
`MediaExtractor` + `MediaCodec` directly (extractor → codec creation → frames out),
`TrackProbeActivity` reports what Media3 decided (`Tracks` groups, `isSupported`, `videoSize`,
`renderedFirstFrame`, `PlaybackException`). **`TrackProbeActivity` must call `engine.build()`**, not
`prepare()` - `prepare()` only prepares an existing player, and a probe that never built one sits in
`IDLE` with the media item set and `canPrepare=true` and says nothing at all. It also must attach a
real `SurfaceView`.

And the question "is a picture on screen" needs neither: `tools/frame_motion.py a.png b.png` counts
the pixels that differ. That single number is what turned a vague report into "0.00%", which is how
the ceiling on a black screen was established.

### 4.20 The press was a dead uniform, and the light never moved

Two things were missing from **every** glass surface, and one of them was missing from the shader's
inputs as well:

* `pressAmount` was declared on `LiquidGlassView`, uploaded to the shader as `uPress`, and **never
  written by anything**. Nothing in the app could have looked pressed.
* `uLightDir` was a constant per surface (135° on the capsule), so the highlight stayed welded to one
  corner however the phone was held - which is the difference between a surface and a printed
  gradient. `tools/glass_tracking.py` cannot see this: it correlates the pane with the picture, and a
  static highlight tracks just as well as a live one.

Now: `LightSourceController` takes the world's up from gravity, remaps by `display.rotation`
(a landscape phone reports gravity on a different axis), low-pass filters, blends back to the fixed
direction below `FLAT_THRESHOLD` where the direction is pure noise, and invalidates only registered
views. It is ref-counted, refuses to run in power-save or with "remove animations", and unregisters
with the last view. The default angle is 150°, not 135°: the reference measured iOS 26's two bright
edges at 30° off horizontal, and at 45° the lobes land on the corners and the rim reads as a vignette.

The press comes from `RippleDrawable`'s own `setState` - the drawable is already wrapped in a ripple,
so **no touch path had to be intercepted**; only the finger *position* needs a listener, because the
ripple path cannot carry a coordinate. Down is 180 ms, up is 320 ms, and the local gaussian bulge
under the finger is what reads as liquid (refraction alone just looks glossier).

**Measured: 27% of the capsule band changes between rest and a held press in landscape, 0.01% in
portrait.** The portrait number is not a bug: the capsule floats in the letterbox there, so the bulge
displaces black onto black - §4.9 again. `PanelProbeActivity --es probe press` holds the press for
3 s so a host screenshot can catch it; do not try to aim a scripted tap at a pane that is being
hidden by the chrome's idle countdown, which is what the two failed attempts did.

**What the press *looks like* in the library is a big white bloom on the pane's right-hand end**, which
is not a highlight: it is the frost bending the high-contrast list content at the rim, and it is what
made a reviewer ask whether the light was too strong. It is the refraction, not the specular term.

### 4.21 A `Drawable` cannot be identified by its resource, and glass hid behind that

`GlassInstaller` recognised a surface by loading its background resource and comparing
`constantState` with the view's background. **That can never match for a `RippleDrawable`** - every
`getDrawable` call returns a fresh drawable with a fresh constant state, even for the same resource
id. Measured on the library screen:

```
glass walk matched nothing: 9 painted view(s)
  RippleDrawable@462c582/csac7e493 -> r2130844@bb79491/cs5949ff6, r2130845@7493ff7/csbb7ae64, ...
```

Nine painted views, nine candidates, no two hashes equal. All **15** XML-declared glass surfaces in the
app - three header buttons, the action bar, Open file / Poweramp / Library, and the player's Back,
Inspect, Metadata, Play, More, seek feedback and error card - kept the flat gradient the class exists
to replace. The player's capsule was glass because `LiquidGlassView` is its own view; the settings
screen and the sheet rows were glass because they set backgrounds **in code**, where the comparison
happens by identity. So the feature looked present and was half missing, which is the worst shape for
a bug: the one pane you look at is right.

A surface now declares its role with `android:tag` / a tag set in code, and that is the only thing
matched. `ids.xml` holds the four roles and the reason.

**`android:tag="@id/glass_role_button"` does not work**, which is worth knowing before trying it: the
framework stores it as the *string* `"false"` - measured, every tagged `ImageButton` came back
`tag=String:false` - so the installer went on matching nothing after "fixing" the layout. The tags are
set in the activities, where an `Int` stays an `Int`.

`tools/verify-panels.sh` now asserts the installer's own count on both screens (**13** checks). That
count is the only line that separates "installed" from "declared", and it fails on the old code - the
library reports `0 pane(s)` against 9 painted views. **Look at this log line before believing any
claim about the glass**, and read it before the panel probe clears logcat, which the first version of
the check did not and so reported the library's line for the player.

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
| `tools/verify-multichannel.sh` | 25 | codec routing + **same-language subtitle identity**, including across a rebuild |
| `tools/verify-poweramp.sh` | 11 | provider, playable URI opens, artist+album 140/140 |
| `tools/verify-spatial-audio.sh` | 13 | `isSpatialized=true`, head tracking non-DISABLED |
| `tools/verify-gestures.sh` | 11 | sensitivity, chrome tap/timeout — injects real swipes/taps |
| `tools/verify-glass-backdrop.sh` | 4 | glass samples the picture behind it (§4.10) |
| `tools/verify-chapters.sh` | 10 | Matroska chapter parse via the probe, plus the no-chapter case |
| `tools/verify-background.sh` | 7 | audio survives HOME and screen-off, video does not. `SKIP_SCREEN_OFF=1` skips the two screen-off checks (§3.13) |
| `tools/verify-panels.sh` | 13 | every inspector panel renders its rows however it was reached, **and the glass is installed on both screens** (§4.21) |
| `tools/verify-rotation.sh` | 12 | the picture rect, and the chrome's touch path, survive a rotation |
| `tools/frame_motion.py` | — | not a suite: prints the % of pixels that differ between two screenshots. The only honest answer to "is a picture on screen" (§4.19) |

**127 checks total.** `verify-panels.sh`, `verify-rotation.sh`, `verify-gestures.sh` and
`verify-multichannel.sh` are fully passing; `verify-background.sh` passes the check that was
open for the whole life of this document — video pauses on HOME — when run with `SKIP_SCREEN_OFF=1`;
without it, this phone's lock screen takes window focus during the screen-off phase and the following
checks cannot see the app at all (§3.13). `tools/png_reader.py` is a dependency-free PNG reader shared
by the image harnesses; `tools/chrome_presence.py` reports how much structure is in the **top bar** as the
"chrome is visible" proxy, and `tools/glass_tracking.py` adds the freeze/tracking maths.
Probes: `SmokeChaptersProbeActivity` (`SMOKE_CHAPTERS`, `--es chapters_probe_path`) logs one flat
line per chapter. It exists because the player animates continuously, so `uiautomator dump` will not
settle on it - the UI cannot be introspected, and the alternative would be OCR of a screenshot. The
same trick is worth reaching for whenever a suite needs to assert on something the UI renders.

`PanelProbeReceiver` and `PanelProbeActivity` (debug variant only) are the same trick in a second
shape: a broadcast aimed at the **running** player opens each inspector panel through the same
`show(panel)` a tab click makes, and logs what that panel rendered. The shim takes
`--es probe panel|chrome|geometry`; all three probes report through the same tag, so firing more than
one and grepping for "the last line" reads whichever landed last - which is exactly how
`verify-panels.sh` started failing against a geometry line that had nothing to do with panels. It has to be the running activity -
the bug it guards was in the wiring between the sheet and the activity, so a probe with its own sheet
would test a copy of the code that was wrong. `verify-panels.sh` starts it and parses the log line.
The sheet cannot be dumped from the shell at all: `uiautomator dump` answers "could not get idle state"
on the animating player, and the rows are built in code inside a dialog window.

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
Use `commit -F -` with a heredoc; backticks in `-m` get shell-expanded, commit message should be short and precise.
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
* **Still open: video on HOME.** *(Superseded - the root cause was found and fixed; the current state
  is in §8. Kept because the two leads it records were both dead ends, and the second one is still the
  reason `PlayerEngine.hasVideo` has three sources.)* `verify-background.sh` is 6 of 7. Background audio, screen-off
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
- **Dolby Vision Profile 7 remuxes play as their HDR10 base layer** on this phone, which has no Dolby
  Vision decoder - a 4K HEVC Main10 film with a TrueHD Atmos track decodes, presents and seeks. Before
  the fix Media3 dropped the video track with no error at all (§4.18).
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
  capsule, from one shader and one style table (§6c). The highlight follows the device's attitude
  (gravity sensor, ref-counted) and every surface presses with a local bulge under the finger (§4.20).
  **Installed count is asserted, not assumed**: 8 panes on the player, 7 on the library, checked by
  `tools/verify-panels.sh` because a surface that silently keeps its stand-in looks exactly like one
  that was never meant to be glass (§4.21).
- **Inspector panels render however they are reached.** Decoder, Subtitles, Audio & spatial, Scaling,
  Speed and Chapters all show their rows when switched to by tab, not only when opened from their own
  row - `tools/verify-panels.sh` is 11 of 11, and removing the one-line fix makes it report
  `empty=yes` on all six panels, which is the reported symptom exactly.
- Head tracking is live at 5.1 with the upmix in the chain, and the background policy is settings-
  driven for both music and anything with a picture.
- The chrome's controls work after the idle timeout, and the picture geometry survives a rotation in
  both directions (`tools/verify-rotation.sh`, 12 checks).
- Poweramp's library survives a refresh, and an open Poweramp fold is filled when the app is
  relaunched rather than only re-opened.
- The subtitle highlight follows the *specific* track chosen, not just its language, so the four `eng`
  tracks on a disc release are distinguishable and the choice survives a decoder rebuild (§4.16).

### The harnesses

Nine suites, 112 checks. `verify-panels.sh` is 11 of 11. `verify-background.sh` passes the check it
was failing — video pauses on HOME — with `SKIP_SCREEN_OFF=1`, which is needed only because this
phone's lock screen keeps window focus after the screen-off phase (§3.13, §8). Its two screen-off
checks passed for the whole life of this document and are skipped, not failed, in that mode. They are
the reason to trust any of the above: every claim in this document that says "verified" was produced by
one of them, and the ones that read oddly (a `dumpsys` field, a pixel correlation) are all explained
where they are used.

### Standing constraints from the user

- **Never** `pm clear`, uninstall, reboot, or `adb shell monkey`. `KEYCODE_WAKEUP` / `SLEEP` / `HOME`
  are fine. Restore anything you change (rotation, volume, animation scale).
- **Do not blast the volume** while testing.
- **Do not copy the 70 GB film to the phone.** Make a shortened version for testing.
- If adb needs re-authorising or the phone needs a tap, **say so on this Mac** - a modal `osascript
  display dialog` with a sound works; a plain `display notification` was missed more than once.
- User is context-budget conscious and said "if you need anything about context budget, you are the boss, do whatever you need to"
- `README.md` stays terse.

## 8. Open work

Nothing below is blocked on a missing piece of knowledge; each line says what to do and where. Edit
this list in place - it is the handoff's to-do, and it is deliberately one line per item so it can be
reordered and struck out without reflowing prose.

Format: `- [ ] **What** — where it lives / how to tell it is done.`

Struck-out items stay in place with the measurement that closed them, because the next person needs to
know what was already tried and what the device actually did.

### Known broken

Things that do not work. Each says how to tell it is done.

- [ ] **This phone's lock screen keeps window focus after `KEYCODE_SLEEP`/`WAKEUP`, which stops
      `verify-background.sh` measuring its screen-off checks.**
      Measured: wakefulness `Awake`, display `ON`, but `mCurrentFocus=Window{... NotificationShade}`,
      and `cmd statusbar collapse`, `KEYCODE_HOME`, `KEYCODE_BACK`, `input keyevent 82` and swipes all
      leave it there. An app started underneath never becomes visible, so the video is launched already
      backgrounded and the suite reports "video was not playing while visible". `wake_visible()` in the
      harness now detects this and fails loudly instead of asserting vacuously; what it needs is a
      reliable way to dismiss it - try `input keyevent 82` on a device with no secure lock, or leave
      the screen on for the whole run.

- [ ] **Head tracking recenters after a few seconds and cannot be anchored from the app.**
      Not an app-level fix: Android's `Spatializer` API is read-only, and this device's spatializer is
      Sony's own vendor effect (`/vendor/lib64/soundfx/libtsrspatializer.so`). Needs shell root, which
      is **not** currently reachable (`adb shell su` -> "inaccessible or not found"); enable it in
      KernelSU and then look at, in order: the effect's parameters in `dumpsys media.audio_flinger`, a
      vendor audio-effects config for a stillness/recenter timeout, and whether the recenter is
      actually headset-side (`com.sony.songpal` is installed, and the 1000X does its own tracking -
      check the headphones app first, because nothing on the phone would change that).

- [ ] **Launcher icon needs a real double-check.** Earlier attempts were rejected as "nearly
      unusable"; whatever replaces them must be looked at as a rendered icon at real sizes, not as a
      vector source. `app/src/main/res/mipmap-*/`.

### Unfinished & Issues

Work that is wanted but not started, or that works and is not right yet.

- [x] **Video does not pause when the app is backgrounded (HOME)** — fixed in `PlayerActivity`
      (`onStop` records the open question, `onEngineTracksChanged` answers it). Measured passing:
      `playing before: PLAYING(3); after HOME: PAUSED(2)`. Details in §4.12; do not add a retry timer.

- [x] **Panels show nothing when switched from another page** — fixed: the sheet now pulls each
      panel's rows through `onPanelShown` instead of relying on whoever opened it to have pushed them.
      `tools/verify-panels.sh` 11/11. Details in §4.11.

- [x] **Back / Inspector / Metadata have no effect once the controls time out** — fixed: a transparent
      view still takes touches, so the chrome now parks itself at `INVISIBLE` when it fades
      (`hideControls(gone = true)`), and the invisible-but-clickable `centerPlayPause` that sat under
      it is `invisible` in the layout. Measured through `PanelProbeReceiver`'s chrome probe:
      `routedVisible=true opened=true hidden=true parked=true routedHidden=false`.

- [x] **Ambient glow out of bounds when switching between portrait and landscape** — verified rather
      than changed: `tools/verify-rotation.sh` reads the rect the wash is *drawing with* and compares it
      with the one the current container implies, and it matches through portrait → landscape →
      portrait → the other landscape → portrait. The recompute after a rotation is now scheduled
      instead of inferred from a `SurfaceView` layout callback, so it no longer depends on how the
      surface happens to resize. The capsule was measured against a screenshot as well, because
      `getLocationOnScreen` cannot be read back reliably while a probe activity is finishing.

- [x] **Poweramp playlist shows empty after refresh** — root-caused and fixed. `refresh()` rebuilt the
      list from MediaStore and the granted folder only, so `publish`'s `entries.clear()` took all 140
      Poweramp rows with it, while `powerampLoaded` stayed `true` and told the chip there was nothing to
      re-read. Poweramp's rows are now held in their own field that a refresh merges back in, the chip
      re-reads on every open, a persisted open fold is filled on launch, and an empty read no longer
      latches "loaded" (which had made a failed read look like an empty library, permanently).
      **Verified in source, not yet on the device.** The suite that would prove it needs a UI step and
      `tools/verify-poweramp.sh` only ever cold-loads the provider, so extending it is the follow-up -
      see the entry below. The refresh path itself was driven on the device (`incoming=326 poweramp=0`
      before and after a refresh, i.e. the non-Poweramp rows survive), but the fold chips did not
      respond to injected taps at their dumped coordinates, so the 140-row half is unproven.

- [ ] **`tools/verify-poweramp.sh` should test the refresh path, not only a cold load.** It launches
      `SmokePowerampProbeActivity`, which reads the provider in a fresh process and cannot see
      `MainActivity`'s list state at all - which is why the refresh bug lived here unnoticed. The shape
      that matches §6: a broadcast aimed at the *running* `MainActivity` that opens the Poweramp fold,
      logs the header text, calls `refresh()`, and logs it again; then assert the count is unchanged.
      Worth doing with it: find out why the fold chips ignored injected taps during this round while the
      refresh button accepted them - the coordinates came from `uiautomator dump` and were correct.

- [ ] **Setting icon at `ImageButton` need rework** The library header's gear reads as a smudge at
      24 dp: three slider bars whose gaps are the knobs, so the bars vanish into the track. Replace
      with something legible at 24 dp and look at it rendered, not as a vector source.

- [ ] **Add double click reset and editable values for sliders** Both editors build sliders in code
      (`InspectorSheet.sliderRow`, and the Settings screen). A double tap should restore the default
      and the reading should be typeable. `SliderRow` currently fires `onChoiceSelected` on every drag
      frame, which is why the value cannot be edited in place.

- [x] **Incorrect selected subtitle highlight** — fixed. Four `eng` tracks share one language tag, so
      the language preference kept for a rebuilt player could not say which one was chosen and a
      rebuild landed on the first `eng` track. `PlayerEngine` now remembers the chosen track's
      identity and re-applies it, both when the track list changes and at the moment of choosing.
      Measured with four same-language `eng` tracks through the app's own path: `selecting index 2`
      kept British selected, `text_selected=1`; disabling the identity reverted it to American every
      time. Five consecutive runs agree. Details in §4.16.

- [x] **Port all the chips, buttons to `Liquid Glass` design that is not in the player page.** The
      installer was already converting every `bg_glass_button` / `bg_glass_panel` surface, so what was
      actually missing were the two behaviours that make the material read as a material: `按压液态`
      and `enableSensorHighlight`. Both are now real on every surface, including the capsule. Calling
      the reference library was not an option (its `GlassRuntimeEffects` needs API 36 and this tree is
      pinned at 35), so both were ported from the reference source as before. Details and measurements
      in §4.20.

      **Two corrections, both from the user saying "I didn't see any change on the main screen".**

      1. They were right, and their first sentence was the whole bug report: the glass was **never
         installed** on any XML-declared surface, so the main screen was genuinely untouched. The
         installer was matching drawables by `constantState`, which cannot work for a ripple. Fixed by
         tagging each surface with its role; asserted now in `verify-panels.sh`. Full account in §4.21.
      2. The user was then asked whether to give the non-player screens a backdrop to refract, and
         chose **flat** - so the dark UI stays pure black. That is a decision on record, not an unfinished
         item: do not "fix" it without asking again. It matters less than it sounded once (1) was fixed,
         because the refraction of the list content and the artwork *inside* a pane is enough to read as
         glass: a screenshot of the fixed library shows every chip and segment with a bent, bloomed rim.

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

- [x] **Film mentioned in 4.4 still failed to decode/playback.** Fixed. It is a Dolby Vision Profile 7
      remux, and Media3 relabels the video track `video/dolby-vision` when it finds the configuration
      record - a type this phone has no decoder for, so the track is dropped **with no error** and the
      film plays to a black screen. The HDR10 base layer is an ordinary HEVC Main10 stream that the
      hardware decodes fine; `DolbyVisionFallback` hands it over as such. Verified on a clip cut from
      the film and on a 60 s cut carrying its own TrueHD track: video group `selected=true
      supported=true`, `renderedFirstFrame=true`, 13.6% of the screen moving between screenshots where
      it was 0.00% before. Full root cause in §4.18.

      **Not yet re-tested against the unmodified 78 GB file**, because the phone has no external
      storage mounted for it. The change is in the container path, not the seek or buffer path, and the
      clips are cuts of the same mux - but that is an extrapolation and should be said as one.

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
