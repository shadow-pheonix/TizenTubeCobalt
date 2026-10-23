#!/usr/bin/env python3
"""Compile and verify the native fork updater and its narrow dex bridge patch."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile
from sgsr_shader import DESTINATION, generated_source

TOOL_URL = "https://github.com/baksmali/smali/releases/download/3.0.10/baksmali-3.0.10-fat-release.jar"
TOOL_SHA256 = "37ae4a41a8886e15c20b8362fa4250f96bbdb55e1a608199ad8b5dff068b588f"
ROOT = Path(__file__).resolve().parents[2]
JAVA_SOURCES = ROOT / "cobalt/android/apk/app/src/main/java/dev/cobalt/updates"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def prepare(output, android_jar, build_tools):
    if DESTINATION.read_text() != generated_source():
        raise ValueError("Generated SGSR1 shader differs from its pinned source")
    output.mkdir(parents=True, exist_ok=True)
    tool = output / "baksmali.jar"
    if not tool.is_file():
        with urllib.request.urlopen(TOOL_URL, timeout=60) as response, tool.open("wb") as target:
            while data := response.read(1024 * 1024):
                target.write(data)
    if hashlib.sha256(tool.read_bytes()).hexdigest() != TOOL_SHA256:
        raise ValueError("Pinned dex-tool checksum mismatch")
    patcher = output / "patcher"
    classes = output / "classes"
    tests = output / "tests"
    for folder in (patcher, classes, tests):
        folder.mkdir(exist_ok=True)
    run("javac", "-cp", tool, "-d", patcher, ROOT / "tools/amoled/android/DexUpdater.java", ROOT / "tools/amoled/android/DexVideoPatcher.java")
    run("javac", "-source", "8", "-target", "8", "-cp", android_jar, "-d", classes, *sorted(JAVA_SOURCES.glob("*.java")))
    run("javac", "-source", "8", "-target", "8", "-cp", classes, "-d", tests, ROOT / "tools/amoled/android/UpdatePolicyTest.java")
    run("java", "-cp", os.pathsep.join(map(str, (classes, tests))), "UpdatePolicyTest")
    run("javac", "-source", "8", "-target", "8", "-cp", classes, "-d", tests, ROOT / "tools/amoled/android/UpscalePolicyTest.java")
    run("java", "-cp", os.pathsep.join(map(str, (classes, tests))), "UpscalePolicyTest")
    jar = output / "updater.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, str(path.relative_to(classes)))
    run(build_tools / "d8", "--min-api", "24", "--lib", android_jar, "--output", output, jar)
    if not (output / "classes.dex").is_file():
        raise ValueError("Native updater dex was not produced")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.output.resolve(), args.android_jar.resolve(), args.build_tools.resolve())
