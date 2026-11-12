# Spatial Player

Android 14+ media player with hardware HDR10/HLG video, bundled FFmpeg surround audio,
and Android spatial audio with head tracking on supported headsets.

- Blu-ray M2TS with native HEVC/HDR and DTS-HD; AC3, EAC3, TrueHD, DTS/DTS-HD; stereo-to-5.1 presets and an editable channel matrix.
- Embedded and external subtitles, Matroska chapters, decoder selection, and scaling controls.
- Poweramp integration, picked folders, and MediaStore files.
- Background music with media controls; separate music/video background settings.
- GPU glass controls, progressive scroll-edge blur, live scrubbing and gesture values.
- Settings → Glass playground compares Kyant0 Backdrop with the current glass controls.

Build with **JDK 21** and **Android SDK 36**. Media3 and nextlib remain pinned in the version catalog.

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

Inter typeface is distributed under the SIL Open Font License; its license is bundled in the app.

Glass lens renderer adapted from [Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) under MIT; license and revision are bundled.

The isolated Backdrop playground and Shapes use Apache-2.0; upstream licenses and revision are bundled.
