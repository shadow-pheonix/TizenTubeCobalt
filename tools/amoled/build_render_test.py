#!/usr/bin/env python3
"""Build a tiny Android GPU/decoder smoke test; no Gradle or third-party player."""
import argparse
from pathlib import Path
import subprocess
import zipfile
from prepare_updater import prepare, ROOT, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    tools = args.sdk / "build-tools/35.0.0"
    android = args.sdk / "platforms/android-35/android.jar"
    prepare(output / "native", android, tools)
    smoke = ROOT / "tools/amoled/android/smoke"
    classes = output / "test-classes"; classes.mkdir(exist_ok=True)
    run("javac", "-source", "8", "-target", "8", "-cp", f"{android}:{output / 'native/classes'}", "-d", classes, *smoke.glob("*.java"))
    jar = output / "smoke.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in classes.rglob("*.class"): archive.write(path, str(path.relative_to(classes)))
    run(tools / "d8", "--min-api", "24", "--lib", android, "--output", output, jar, output / "native/updater.jar")
    assets = output / "assets"; assets.mkdir(exist_ok=True)
    # Static color/orientation regions plus high-frequency diagonal detail for shader comparison.
    raw = bytearray()
    for y in range(180):
        for x in range(320):
            color = ((255, 0, 0) if x < 160 else (0, 220, 0)) if y < 90 else ((0, 0, 255) if x < 160 else (120, 120, 120))
            if 80 < x < 240 and 40 < y < 140: color = (240, 240, 240) if ((x + 2 * y) // 3) % 2 else (20, 20, 20)
            raw.extend(color)
    subprocess.run(["ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "320x180", "-framerate", "30", "-i", "pipe:0",
                    "-vf", "loop=loop=89:size=1:start=0", "-frames:v", "90", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "16", str(assets / "fixture.mp4")], input=raw, check=True)
    unsigned = output / "unsigned.apk"
    run(tools / "aapt", "package", "-f", "-M", smoke / "AndroidManifest.xml", "-I", android,
        "-A", assets, "-0", "mp4", "-F", unsigned)
    with zipfile.ZipFile(unsigned, "a") as archive: archive.write(output / "classes.dex", "classes.dex")
    key = output / "test.jks"
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-dname", "CN=Android Test", "-keyalg", "RSA", "-validity", "30")
    run(tools / "zipalign", "-f", "4", unsigned, output / "aligned.apk")
    run(tools / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--out", output / "render-test.apk", output / "aligned.apk")


if __name__ == "__main__": main()
