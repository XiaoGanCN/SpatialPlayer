#!/usr/bin/env python3
"""Regression: held glass presses and software ripple-mask rendering must not crash."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
PKG = "org.spatialplayer.debug"
OUT = Path(__file__).resolve().parents[1] / "smoke/out/glass-touch"
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    return subprocess.check_output([ADB, *args], text=True).strip()

def pid():
    return adb("shell", "pidof", PKG)

def nodes():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/spatial-touch.xml")
    return list(ET.fromstring(adb("shell", "cat", "/data/local/tmp/spatial-touch.xml")).iter("node"))

def hold(node):
    bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    before = pid()
    # An instantaneous tap can release before RippleDrawable rebuilds its alpha mask.
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "800")
    time.sleep(0.5)
    assert pid() == before, "App died or restarted during a held press"

adb("shell", "am", "force-stop", PKG)
adb("logcat", "-c")
adb("logcat", "-b", "crash", "-c")
adb("shell", "am", "start", "-n", PKG + "/com.gan.spatialplayer.MainActivity")
time.sleep(1)
initial_pid = pid()
for _ in range(3):
    hold(next(n for n in nodes() if n.get("resource-id", "").endswith("/buttonRefresh")))
for _ in range(2):
    hold(next(n for n in nodes() if n.get("resource-id", "").endswith("/buttonSettings")))
    hold(next(n for n in nodes() if n.get("content-desc") == "Close"))
assert pid() == initial_pid
adb("shell", "am", "start", "-n", PKG + "/com.gan.spatialplayer.GlassRenderProbeActivity")
time.sleep(2)
report = adb("logcat", "-d", "-s", "GlassRenderProbe:I")
assert "PASS: initialized glass" in report, "Software rendering probe did not pass"
assert pid() == initial_pid, "Software rendering probe killed the app"
crashes = adb("logcat", "-d", "-b", "crash")
(OUT / "after-fix.txt").write_text(crashes + "\n" + report + "\n")
assert "Process: " + PKG not in crashes, "App crash was logged"
print("PASS: 7 real held presses across Library/Settings; initialized glass and pressed ripple on software canvas; process unchanged")
