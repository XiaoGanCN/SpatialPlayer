#!/usr/bin/env python3
"""Real touch/viewport regression and sustained native HDR playback with visible SDR chrome."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
PKG = "org.spatialplayer.debug"
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "smoke/out/redesign"
OUT.mkdir(parents=True, exist_ok=True)
checks = 0

def adb(*args):
    if args and args[0] == "shell":
        args = ("shell", shlex.join(args[1:]))
    return subprocess.check_output([ADB, *args], text=True, timeout=35).strip()

def launch(name, *args):
    adb("shell", "am", "start", "-n", PKG + "/com.gan.spatialplayer." + name, *args)

def snapshot(screen="PLAYER", **extras):
    request_id = str(time.monotonic_ns())
    extras["request_id"] = request_id
    args = ["shell", "am", "broadcast", "-p", PKG, "-a", PKG + ".UI_" + screen]
    for key, value in extras.items():
        args.extend(["--ez" if isinstance(value, bool) else "--es", key, str(value).lower() if isinstance(value, bool) else str(value)])
    adb(*args)
    for _ in range(15):
        lines = [s for s in adb("logcat", "-d", "-v", "raw", "-s", "UiStateProbe:I").splitlines() if s.startswith("{")]
        replies = [json.loads(line) for line in lines]
        matches = [value for value in replies if value.get("requestId") == request_id]
        if matches:
            with (OUT / "states.jsonl").open("a") as stream:
                stream.write(json.dumps(matches[-1]) + "\n")
            return matches[-1]
        time.sleep(.15)
    raise AssertionError("No live UI snapshot for " + screen)

def tap(bounds):
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(.4)

def shot(name):
    with (OUT / name).open("wb") as output:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=output, check=True)

def nodes():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/spatial-redesign.xml")
    return list(ET.fromstring(adb("shell", "cat", "/data/local/tmp/spatial-redesign.xml")).iter("node"))

def text_tap(text):
    node = next(n for n in nodes() if n.get("text") == text)
    tap(list(map(int, re.findall(r"\d+", node.attrib["bounds"]))))

def verify(condition, label):
    global checks
    assert condition, label
    checks += 1
    print("PASS:", label, flush=True)

def main():
    original_rotation = adb("shell", "settings", "get", "system", "user_rotation")
    original_accel = adb("shell", "settings", "get", "system", "accelerometer_rotation")
    original_upmix = None
    try:
        adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        adb("shell", "settings", "put", "system", "user_rotation", "0")
        adb("shell", "am", "force-stop", PKG)
        launch("MainActivity")
        time.sleep(2)
        state = snapshot("LIBRARY")
        original_source = state.get("source", "NONE")
        original_upmix = state["upmixSettings"]
        (OUT / "original-upmix.json").write_text(json.dumps(original_upmix))
        if state.get("source") != "LIBRARY":
            tap(state["views"]["buttonLibrary"]["bounds"])
        state = snapshot("LIBRARY")
        verify(state["views"]["fileList"]["rows"] > 0 and state["views"]["fileList"]["height"] > 200, "video rows visible without rotating")
        tap(state["views"]["buttonPoweramp"]["bounds"])
        time.sleep(2)
        state = snapshot("LIBRARY")
        verify(state["views"]["fileList"]["rows"] > 0, "Poweramp rows visible without rotating")
        shot("music.png")
        tap(state["views"]["buttonSettings"]["bounds"])
        text_tap("Stereo stage")
        text_tap("Matrix")
        time.sleep(.5)
        text_tap("Rear R")
        text_tap("Left")
        state = snapshot("LIBRARY")
        verify(state["upmixSettings"]["matrix"].split("|")[5] == "100,0", "matrix source shortcut edits only the selected output")
        shot("matrix.png")
        adb("shell", "am", "force-stop", PKG)
        launch("MainActivity")
        time.sleep(1)
        snapshot("LIBRARY", restore_upmix=json.dumps(original_upmix))
        launch("SmokeTestActivity", "--es", "smoke_path", "/sdcard/Movies/SpatialPlayerTest/grid_720p.mp4", "--es", "smoke_mime", "video/mp4")
        time.sleep(2)
        adb("shell", "am", "broadcast", "-p", PKG, "-a", PKG + ".GLASS_CAPTURE", "--ez", "visible", "true")
        time.sleep(.7)
        state = snapshot()
        for name in ["buttonBack", "buttonInspect", "buttonMetadata", "seekBar", "buttonSpeed", "buttonMore", "controlGlass"]:
            v = state["views"][name]
            x0, y0, x1, y1 = v["bounds"]
            verify(v["visible"] and 0 <= x0 < x1 <= state["width"] and 0 <= y0 < y1 <= state["height"], name + " fully inside portrait viewport")
        verify(state["views"]["seekBar"]["width"] > 200, "portrait seek bar has a useful width")
        verify(state["surroundBypass"], "six-channel PCM bypasses the upmixer")
        shot("portrait.png")
        for rotation in [1, 0]:
            adb("shell", "settings", "put", "system", "user_rotation", str(rotation))
            time.sleep(1)
            rotated = snapshot(controls=True)
            time.sleep(.3)
            rotated = snapshot()
            for name in ["buttonBack", "buttonInspect", "buttonMetadata", "seekBar", "controlGlass"]:
                v = rotated["views"][name]
                x0, y0, x1, y1 = v["bounds"]
                verify(v["visible"] and 0 <= x0 < x1 <= rotated["width"] and 0 <= y0 < y1 <= rotated["height"], f"{name} inside rotation {rotation} viewport")
            shot("landscape.png" if rotation else "portrait-return.png")

        for name, title in [("buttonInspect", "Inspection"), ("buttonMetadata", "Metadata"), ("buttonSpeed", "Speed")]:
            state = snapshot(controls=True)
            time.sleep(.3)
            tap(state["views"][name]["bounds"])
            shot(name + ".png")
            state = snapshot()
            verify(state["sheetOpen"] and state["panel"] == {"buttonInspect":"INSPECTION", "buttonMetadata":"METADATA", "buttonSpeed":"SPEED"}[name], name + " opens its actual sheet via a real tap")
            if name == "buttonSpeed":
                tap(state["views"]["speed_1.5"]["bounds"])
                state = snapshot()
                verify(state["views"]["speed_readout"]["text"] == "1.50×", "speed preset updates the live speed readout")
                tap(state["views"]["speed_1.0"]["bounds"])
            tap(snapshot()["views"]["sheet_close"]["bounds"])
            time.sleep(.3)
        state = snapshot(controls=True)
        time.sleep(.3)
        tap(state["views"]["buttonBack"]["bounds"])
        focus = adb("shell", "dumpsys", "activity", "activities")
        verify(any("MainActivity" in line and "ResumedActivity" in line for line in focus.splitlines()), "player Close returns to the library")

        for cycle in range(3):
            for filename, codec, mime in [("grid_720p.mp4", "avc", "video/mp4"), ("spiderverse_dv7_truehd_30s.mkv", "hevc", "video/x-matroska")]:
                launch("SmokeTestActivity", "--es", "smoke_path", "/sdcard/Movies/SpatialPlayerTest/" + filename,
                       "--es", "smoke_mime", mime, "--ez", "smoke_muted", "true")
                active = None
                for _ in range(15):
                    time.sleep(.3)
                    try:
                        active = snapshot(controls=True)
                    except AssertionError:
                        continue
                    if (active.get("media") or "").endswith(filename) and codec in active.get("decoder", "").lower() and active["rendered"] > 0:
                        break
                verify(active is not None and (active.get("media") or "").endswith(filename) and codec in active.get("decoder", "").lower() and active["rendered"] > 0,
                       f"player lifetime transition {cycle + 1}: {filename} starts and renders")
                tap(active["views"]["buttonBack"]["bounds"])
                time.sleep(.3)

        launch("SmokeTestActivity", "--es", "smoke_path", "/sdcard/Movies/SpatialPlayerTest/spiderverse_dv7_truehd_30s.mkv", "--es", "smoke_mime", "video/x-matroska", "--ez", "smoke_muted", "true")
        time.sleep(3)
        first = snapshot(controls=True)
        samples = [first]
        for _ in range(3):
            time.sleep(3)
            samples.append(snapshot(controls=True))
        final = samples[-1]
        (OUT / "performance.json").write_text(json.dumps(samples, indent=2))
        (OUT / "film-log.txt").write_text(adb("logcat", "-d"))
        verify("hevc" in final["decoder"].lower() and "ffmpeg" not in final["decoder"].lower(), "film uses the actual hardware HEVC decoder: " + final["decoder"])
        verify(final["rendered"] - first["rendered"] >= 180, "film sustains at least 20 rendered fps with visible controls")
        verify(final["dropped"] - first["dropped"] <= 5, "film drops at most five frames during the timed sample")
        verify(not final["upmix"] and final["channels"] > 2, "multichannel film bypasses stereo upmix")
        layers = adb("shell", "dumpsys", "SurfaceFlinger")
        (OUT / "hdr-layers.txt").write_text(layers)
        blocks = layers.split("* Layer ")
        video = next(b for b in blocks if "SurfaceView[" + PKG + "/com.gan.spatialplayer.PlayerActivity](BLAST)" in b.split("\n")[0])
        ui = next(b for b in blocks if "(" + PKG + "/com.gan.spatialplayer.PlayerActivity#" in b.split("\n")[0])
        verify("dataspace=BT2020_ITU_PQ" in video, "video is a native PQ HDR layer")
        verify("dataspace=V0_SRGB" in ui, "controls remain on a separate SDR layer")
        (OUT / "performance.json").write_text(json.dumps(samples, indent=2))
        shot("hdr-player.png")
        print(f"PASS: {checks} redesign checks", flush=True)
    finally:
        adb("shell", "am", "force-stop", PKG)
        launch("MainActivity")
        time.sleep(.5)
        if original_upmix is not None:
            snapshot("LIBRARY", restore_upmix=json.dumps(original_upmix), restore_source=original_source)
        adb("shell", "settings", "put", "system", "user_rotation", original_rotation)
        adb("shell", "settings", "put", "system", "accelerometer_rotation", original_accel)

if __name__ == "__main__":
    main()
