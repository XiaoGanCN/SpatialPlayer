#!/usr/bin/env python3
"""Run device-independent Kotlin checks using compiler jars cached by Gradle.

Requires JDK 21 in JAVA_HOME and Kotlin 2.3.10 dependencies already cached.
No Android runtime, device, network, or Gradle daemon is used.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", "")) / "bin/java"
if not JAVA.is_file():
    raise SystemExit("Set JAVA_HOME to a JDK 21 installation.")
version = subprocess.run([str(JAVA), "-version"], capture_output=True, text=True, check=True)
if 'version "21.' not in version.stderr:
    raise SystemExit("JDK 21 is required.")
def cached(group, artifact, version):
    matches = sorted((CACHE / group / artifact / version).rglob("*.jar"))
    if not matches:
        raise SystemExit(f"{artifact} {version} is not cached; resolve project dependencies with Gradle first.")
    return matches[0]

# Compiler runtime versions from its POM. Never mix historical FIR/compiler jars from the cache.
compiler = cached("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.10")
stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.10")
jars = [compiler, stdlib,
    cached("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.10"),
    cached("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    cached("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.3.10"),
    cached("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    cached("org.jetbrains", "annotations", "13.0"),
]

source = ROOT / "app/src/main/java/org/spatialplayer"
sources = [source / "ui/VideoRectCalculator.kt", source / "media/UpmixMatrix.kt", source / "media/MatroskaChapters.kt", source / "media/M2tsPackets.kt"]
sources += sorted((ROOT / "tools/host").glob("*.kt"))
with tempfile.TemporaryDirectory(prefix="spatial-host-") as work:
    output = Path(work) / "classes"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, jars)),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", str(stdlib),
                    "-d", str(output), *map(str, sources)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, [output, stdlib])), "HostChecksKt"], check=True)
