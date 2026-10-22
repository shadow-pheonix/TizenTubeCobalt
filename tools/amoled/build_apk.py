#!/usr/bin/env python3
"""Package a verified official native runtime with the forked feature loader."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from upstream_release import read_manifest

UPSTREAM_SIGNER_SHA256 = "378ea3935af1b95c32f6ce193f4ff7f420cdc2d7160c8c866d28d6a91042ec9a"
ABIS = {"arm": "armeabi-v7a", "arm64": "arm64-v8a"}
OLD_URL = b"https://cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js"
NEW_URL = b"https://cdn.jsdelivr.net/gh/shadow-pheonix/TizenTube@amoled02/u.js"


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def verify_official_signature(apksigner, apk):
    result = subprocess.run(
        [str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    )
    print(result.stdout)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
    if signers != [UPSTREAM_SIGNER_SHA256]:
        raise ValueError("The official APK signing certificate changed; review before building")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arch", choices=ABIS, required=True)
    parser.add_argument("--upstream-apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--key-alias", default="amoled")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release-manifest", type=Path,
                        default=Path(__file__).with_name("upstream-release.json"))
    args = parser.parse_args()

    if not os.environ.get("AMOLED_STORE_PASSWORD"):
        parser.error("AMOLED_STORE_PASSWORD must be set")
    manifest = read_manifest(args.release_manifest)
    upstream_hash = digest(args.upstream_apk)
    if upstream_hash != manifest["assets"][args.arch]["sha256"]:
        raise ValueError("Upstream APK checksum does not match official release metadata")
    if len(OLD_URL) != len(NEW_URL):
        raise ValueError("The native loader URL replacement must have the same length")

    apksigner = args.build_tools / "apksigner"
    zipalign = args.build_tools / "zipalign"
    verify_official_signature(apksigner, args.upstream_apk)
    native_path = f"lib/{ABIS[args.arch]}/libchrobalt.so"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as directory:
        unsigned = Path(directory) / "unsigned.apk"
        aligned = Path(directory) / "aligned.apk"
        with zipfile.ZipFile(args.upstream_apk) as source, zipfile.ZipFile(unsigned, "w") as target:
            if native_path not in source.namelist():
                raise ValueError("APK architecture does not match --arch")
            native_before = None
            native_after = None
            for entry in source.infolist():
                if re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", entry.filename, re.I):
                    continue
                data = source.read(entry)
                if entry.filename == native_path:
                    if data.count(OLD_URL) != 2:
                        raise ValueError("Expected exactly the script URL and its HEAD-check URL")
                    native_before = hashlib.sha256(data).hexdigest()
                    data = data.replace(OLD_URL, NEW_URL)
                    native_after = hashlib.sha256(data).hexdigest()
                target.writestr(entry, data)
        run(zipalign, "-P", "16", "-f", "4", unsigned, aligned)
        run(apksigner, "sign", "--ks", args.keystore, "--ks-key-alias", args.key_alias,
            "--ks-pass", "env:AMOLED_STORE_PASSWORD", "--key-pass", "env:AMOLED_STORE_PASSWORD",
            "--out", args.output, aligned)

    run(zipalign, "-c", "-P", "16", "4", args.output)
    run(apksigner, "verify", "--verbose", "--print-certs", args.output)
    # Check that signing and alignment preserved every other APK entry byte for byte.
    with zipfile.ZipFile(args.upstream_apk) as source, zipfile.ZipFile(args.output) as built:
        for entry in built.infolist():
            if entry.filename.startswith("META-INF/"):
                continue
            data = built.read(entry)
            original = source.read(entry.filename)
            expected = original.replace(OLD_URL, NEW_URL) if entry.filename == native_path else original
            if data != expected:
                raise ValueError(f"Unexpected change in {entry.filename}")
    report = {
        "build_method": "repackaged official native runtime; not a full native compilation",
        "upstream_repository": "reisxd/TizenTubeCobalt",
        "upstream_version": manifest["upstream_version"],
        "upstream_release_id": manifest["upstream_release_id"],
        "upstream_commit": manifest["upstream_commit"],
        "architecture": ABIS[args.arch],
        "upstream_apk_sha256": upstream_hash,
        "upstream_signer_sha256": UPSTREAM_SIGNER_SHA256,
        "userscript_url": NEW_URL.decode(),
        "native_library_before_sha256": native_before,
        "native_library_after_sha256": native_after,
        "native_url_replacements": 2,
        "apk_sha256": digest(args.output),
    }
    args.output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
