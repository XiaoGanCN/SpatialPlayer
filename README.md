# Spatial Player

Android 14+ media player with hardware HDR10/HLG video, bundled FFmpeg surround audio,
and Android spatial audio with head tracking on supported headsets.

- AC3, EAC3, TrueHD, DTS/DTS-HD; stereo-to-5.1 presets and an editable channel matrix.
- Embedded and external subtitles, Matroska chapters, decoder selection, and scaling controls.
- Poweramp integration, picked folders, and MediaStore files.
- Background music with media controls; separate music/video background settings.
- Glass controls, gesture seeking, and live settings with editable/resettable slider values.

Build with **JDK 21** and **Android SDK 35**. Media3 and nextlib remain pinned in the version catalog.

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
python3 tools/verify-host.py         # JAVA_HOME=JDK 21; cached Kotlin compiler required
```

Device suites are in `tools/verify-*.sh`; `tools/smoke-test.sh` covers basic playback.
Generate fixtures with `tools/make-host-test-media.sh` or `tools/make-test-media.sh`.
The harnesses preserve app data; settings changed during checks must be restored.
See `HANDOFF.md` for device constraints, known issues, and test details.

Enable spatial audio in Android settings and connect a compatible headset. Head tracking and
recentering are controlled by Android and the headset.

App source: MIT ([LICENSE](LICENSE)). Distributed builds bundle GPL-3.0 nextlib and are GPL-3.0.
