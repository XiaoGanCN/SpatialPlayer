#!/usr/bin/env python3
"""Verify decoded pictures and seeking on a short cut of the reference Dolby Vision film.

Push a stream-copy cut (not the full movie) to the device first. The default fixture is a
30-second cut starting at 00:05:00, retaining DV Profile 7, TrueHD 7.1, AC3 5.1, and subtitles.
The debug probe is muted; this test never changes system volume or clears app data.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
from png_reader import read_png

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--clip", default="/sdcard/Movies/SpatialPlayerTest/spiderverse_dv7_truehd_30s.mkv")
args = parser.parse_args()
adb = os.environ.get("ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
pkg = "org.spatialplayer.debug"
out = Path(__file__).resolve().parents[1] / "smoke/out/film"
out.mkdir(parents=True, exist_ok=True)

def run(*argv):
    return subprocess.check_output([adb, *argv], text=True).strip()

def log():
    return run("logcat", "-d", "-v", "brief", "-s", "TrackProbe:I", "PlayerEngine:I", "AndroidRuntime:E")

assert run("get-state") == "device", "No authorized device"
assert run("shell", "test", "-f", args.clip) == "", "Fixture is missing"
run("shell", "am", "force-stop", pkg)
run("logcat", "-c")
run("shell", "am", "start", "-n", pkg + "/org.spatialplayer.TrackProbeActivity",
    "-a", "android.intent.action.VIEW", "-d", "file://" + args.clip,
    "-t", "video/x-matroska", "--ez", "track_probe_mute", "true",
    "--el", "track_probe_seek_ms", "9000")
try:
    deadline = time.monotonic() + 7
    while "renderedFirstFrame :: true" not in log() and time.monotonic() < deadline:
        time.sleep(0.1)
    assert "renderedFirstFrame :: true" in log(), "No decoded first frame"
    for name in ["a.png", "b.png"]:
        with (out / name).open("wb") as image:
            subprocess.run([adb, "exec-out", "screencap", "-p"], stdout=image, check=True)
        if name == "a.png":
            time.sleep(1)
    while "released :: true" not in log() and time.monotonic() < deadline + 8:
        time.sleep(0.2)
    report = log()
    (out / "probe.txt").write_text(report + "\n")
    assert "type=video selected=true supported=true" in report, "Video track not selected/supported"
    assert "video/hevc" in report and "3840x2160" in report, "HDR10 base-layer fallback missing"
    assert "BT2020" in report and "ST2084" in report, "HDR color metadata missing"
    assert "seekRequested :: 9000" in report, "Seek was not exercised"
    snapshot = re.search(r"t\+6s state=READY playing=true position=(\d+)", report)
    assert snapshot and int(snapshot[1]) > 9000, "Playback did not advance after seeking"
    assert "released :: true" in report, "Player teardown did not complete"
    assert "FATAL EXCEPTION" not in report and "track probe :: error ::" not in report, "Playback error"
    w, h, channels, a = read_png(str(out / "a.png"))
    w2, h2, channels2, b = read_png(str(out / "b.png"))
    assert (w, h, channels) == (w2, h2, channels2), "Screenshot sizes differ"
    # A SurfaceView-only probe has no animated player chrome to create a false motion result.
    changed = sum(sum(abs(a[i + c] - b[i + c]) for c in range(3)) > 24
                  for i in range(0, w * h * channels, channels)) / (w * h)
    assert changed > 0.005, f"No moving picture ({changed:.2%} changed)"
    print(f"PASS: DV7 film selects HEVC 4K HDR, renders moving pictures ({changed:.2%}), and seeks; no errors")
    print(f"Evidence: {out}")
finally:
    run("shell", "am", "force-stop", pkg)
