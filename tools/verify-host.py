#!/usr/bin/env python3
"""Run device-independent Kotlin checks using compiler jars cached by Gradle.

Requires JDK 21 in JAVA_HOME and Kotlin 2.0.21 dependencies already cached.
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
jars = [p for p in CACHE.rglob("*.jar") if any(part in str(p.relative_to(CACHE)) for part in (
    "org.jetbrains.kotlin/", "org.jetbrains.intellij.deps/trove4j/", "org.jetbrains/annotations/",
    "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/",
))]
compiler = [p for p in jars if p.name == "kotlin-compiler-embeddable-2.0.21.jar"]
stdlib = [p for p in jars if p.name == "kotlin-stdlib-2.0.21.jar"]
if not compiler or not stdlib:
    raise SystemExit("Kotlin 2.0.21 is not cached; resolve project dependencies with Gradle first.")
source = ROOT / "app/src/main/java/com/gan/spatialplayer"
sources = [source / "ui/VideoRectCalculator.kt", source / "media/UpmixMatrix.kt", source / "media/MatroskaChapters.kt"]
sources += sorted((ROOT / "tools/host").glob("*.kt"))
with tempfile.TemporaryDirectory(prefix="spatial-host-") as work:
    output = Path(work) / "classes"
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, compiler + jars)),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                    "-jvm-target", "17", "-classpath", os.pathsep.join(map(str, stdlib)),
                    "-d", str(output), *map(str, sources)], check=True)
    subprocess.run([str(JAVA), "-cp", os.pathsep.join(map(str, [output] + stdlib)), "HostChecksKt"], check=True)
