#!/usr/bin/env python3
"""Discover official releases, merge their source, and publish verified fork APKs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

UPSTREAM = "reisxd/TizenTubeCobalt"
ABIS = ("arm", "arm64")
REQUIRED_ASSETS = {
    "TizenTube-AMOLED-arm.apk", "TizenTube-AMOLED-arm64.apk",
    "TizenTube-AMOLED-arm.json", "TizenTube-AMOLED-arm64.json",
    "SHA256SUMS", "upstream-release.json",
}
OLD_URL = "https://cdn.jsdelivr.net/npm/@foxreis/tizentube/dist/userScript.js"
THEME_URL = "https://cdn.jsdelivr.net/gh/shadow-pheonix/TizenTube@amoled04/u.js"
SOURCE_PATH = "third_party/blink/renderer/core/dom/document.cc"


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def github(endpoint):
    return json.loads(run("gh", "api", endpoint, capture_output=True, text=True).stdout)


def safe_tag(tag):
    if not isinstance(tag, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,99}", tag):
        raise ValueError("Unsupported official release tag")
    return tag


def validate_manifest(manifest):
    if manifest.get("upstream_repository") != UPSTREAM:
        raise ValueError("Release must come from the official repository")
    safe_tag(manifest.get("upstream_version"))
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("upstream_commit", "")):
        raise ValueError("Invalid official source commit")
    if not isinstance(manifest.get("upstream_release_id"), int):
        raise ValueError("Missing official release ID")
    for arch in ABIS:
        asset = manifest.get("assets", {}).get(arch, {})
        if asset.get("name") != f"cobalt-{arch}.apk":
            raise ValueError(f"Missing official {arch} APK")
        if not re.fullmatch(r"[0-9a-f]{64}", asset.get("sha256", "")):
            raise ValueError(f"Missing official {arch} APK digest")
    return manifest


def read_manifest(path):
    return validate_manifest(json.loads(Path(path).read_text()))


def make_manifest(release, commit):
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("Only published stable official releases are supported")
    tag = safe_tag(release["tag_name"])
    assets = {}
    for arch in ABIS:
        matches = [item for item in release["assets"] if item["name"] == f"cobalt-{arch}.apk"]
        if len(matches) != 1:
            raise ValueError(f"The official release must contain exactly one {arch} APK")
        asset = matches[0]
        digest = asset.get("digest") or ""
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise ValueError(f"GitHub has not supplied a SHA-256 digest for {asset['name']}")
        assets[arch] = {"name": asset["name"], "id": asset["id"], "sha256": digest[7:]}
    return validate_manifest({
        "upstream_repository": UPSTREAM,
        "upstream_version": tag,
        "upstream_release_id": release["id"],
        "upstream_commit": commit,
        "assets": assets,
    })


def marker(manifest):
    root = Path(__file__).resolve().parents[2]
    sources = [*sorted((root / "cobalt/android/apk/app/src/main/java/dev/cobalt/updates").glob("*.java")),
               root / "tools/amoled/android/DexUpdater.java", root / "tools/amoled/prepare_updater.py",
               root / "tools/amoled/android/DexVideoPatcher.java", root / "tools/amoled/sgsr_shader.py",
               root / "tools/amoled/vendor/sgsr1/sgsr1_shader_mobile.frag", root / "tools/amoled/vendor/sgsr1/LICENSE",
               root / "tools/amoled/build_apk.py"]
    native_fingerprint = hashlib.sha256(b"".join(path.read_bytes() for path in sources)).hexdigest()
    build_inputs = {**manifest, "userscript_url": THEME_URL, "native_updater_sources": native_fingerprint}
    fingerprint = hashlib.sha256(json.dumps(build_inputs, sort_keys=True).encode()).hexdigest()
    return f"<!-- amoled-upstream:{fingerprint} -->"


def select_release(manifest, releases, force=False):
    prefix = manifest["upstream_version"] + "-amoled."
    drafts = []
    revisions = []
    for release in releases:
        if not force and not release.get("draft") and not release.get("prerelease"):
            names = {asset["name"] for asset in release.get("assets", [])}
            if marker(manifest) in (release.get("body") or "") and REQUIRED_ASSETS <= names:
                return False, release["tag_name"]
        tag = release["tag_name"]
        if tag.startswith(prefix) and tag[len(prefix):].isdigit():
            revisions.append(int(tag[len(prefix):]))
            if release.get("draft") and marker(manifest) in (release.get("body") or ""):
                drafts.append(tag)
    if drafts:
        return True, max(drafts, key=lambda tag: int(tag[len(prefix):]))
    return True, prefix + str(max(revisions, default=0) + 1)


def plan(args):
    endpoint = f"repos/{UPSTREAM}/releases/"
    endpoint += "tags/" + safe_tag(args.upstream_tag) if args.upstream_tag else "latest"
    release = github(endpoint)
    commit = github(f"repos/{UPSTREAM}/commits/{safe_tag(release['tag_name'])}")["sha"]
    manifest = make_manifest(release, commit)
    releases = []
    for page in range(1, 101):
        batch = github(f"repos/{args.repository}/releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError("Release history exceeds the supported limit")
    needed, tag = select_release(manifest, releases, args.force)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2) + "\n")
    outputs = {
        "needs_build": str(needed).lower(), "release_tag": safe_tag(tag),
        "upstream_tag": manifest["upstream_version"],
        "upstream_commit": manifest["upstream_commit"],
    }
    if args.github_output:
        with args.github_output.open("a") as stream:
            for key, value in outputs.items():
                stream.write(f"{key}={value}\n")
    print(json.dumps(outputs, indent=2))


def merge_source(args):
    manifest = read_manifest(args.manifest)
    commit = manifest["upstream_commit"]
    run("git", "config", "user.name", "github-actions[bot]")
    run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    # Fetch only enough filtered history to find a real common ancestor. Never
    # force a merge of unrelated histories or choose one side of a conflict.
    for depth in (64, 256, 1024, 4096):
        run("git", "fetch", "--filter=blob:none", f"--depth={depth}", "origin", args.base_commit)
        run("git", "fetch", "--filter=blob:none", f"--depth={depth}",
            f"https://github.com/{UPSTREAM}.git", commit)
        base = subprocess.run(["git", "merge-base", "HEAD", commit], capture_output=True, text=True)
        if base.returncode == 0:
            break
    else:
        raise ValueError("Unable to find common source history; manual merge required")
    run("git", "merge", "--no-edit", commit)
    source = Path(SOURCE_PATH)
    text = source.read_text()
    if OLD_URL in text:
        text = text.replace(OLD_URL, THEME_URL)
        source.write_text(text)
    if text.count(THEME_URL) != 2 or OLD_URL in text:
        raise ValueError("The official userscript loader changed; review compatibility before publishing")
    state = Path("tools/amoled/upstream-release.json")
    state.write_text(json.dumps(manifest, indent=2) + "\n")
    run("git", "add", "--sparse", state, source)
    changed = subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode
    if changed == 1:
        run("git", "commit", "-m", f"chore: prepare AMOLED build for {manifest['upstream_version']}")
    elif changed != 0:
        raise ValueError("Unable to inspect prepared source changes")


def publish(args):
    manifest = read_manifest(args.manifest)
    safe_tag(args.release_tag)
    actual_files = {path.name for path in args.directory.iterdir() if path.is_file()}
    if not REQUIRED_ASSETS <= actual_files:
        raise ValueError("Both APKs and verification reports are required before publishing")
    for arch in ABIS:
        report = json.loads((args.directory / f"TizenTube-AMOLED-{arch}.json").read_text())
        apk = args.directory / f"TizenTube-AMOLED-{arch}.apk"
        with apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if (report["apk_sha256"] != digest or
                report.get("fork_version") != args.release_tag or
                report["userscript_url"] != THEME_URL or
                report["upstream_apk_sha256"] != manifest["assets"][arch]["sha256"] or
                report["upstream_commit"] != manifest["upstream_commit"]):
            raise ValueError("APK provenance does not match this official release")
    if read_manifest(args.directory / "upstream-release.json") != manifest:
        raise ValueError("Published release metadata does not match the build plan")
    # Push merged source only after both APKs passed all checks. No force push.
    run("git", "push", "origin", "HEAD:refs/heads/main")
    head = run("git", "rev-parse", "HEAD", capture_output=True, text=True).stdout.strip()
    notes = f"""AMOLED build of official **{manifest['upstream_version']}**.

The official release source was merged with this fork's AMOLED loader. Both ARM64 and ARM32 APKs were built and signed on GitHub Actions with this fork's persistent signing key. Enable **TizenTube Settings → AMOLED / Pitch black theme**.

**Video quality:** the playback quality menu exposes all playable resolutions and reported video bitrates, plus **Highest available**. Set **TizenTube Settings → Video Player Settings → Default resolution → 2160p** to request 2160p on each video, falling back to the best available lower resolution. **Prefer highest bitrate** selects the highest reported bitrate among playable variants at that resolution. Manual choices affect only the current video. Unknown bitrates are labeled unavailable; device and account restrictions still apply.

**In-app fork updates:** after installing this release once, the app checks this fork at startup and hourly while open. It downloads the matching APK, verifies its digest, package, fork revision, architecture and signing key, then asks **Install** or **Later**. Install opens Android's package installer; if needed, Android first asks to allow installs from this app. Installation always requires your confirmation. Earlier APKs pin an older bundle and need this one-time manual update to gain the new updater.

**Video upscaler:** open **TizenTube Settings → Video Player Settings → Video upscaler (SGSR1)** and choose **Auto**, **Quality**, or **Off**; start the next video after changing it. Auto is the default and uses a single GPU shader pass for eligible SDR enlargements, falling back to bilinear on sustained renderer stalls. Quality keeps edge-aware upscaling enabled when enlarging video. HDR, protected, tunneled and decode-to-texture output bypass the filter. Native-resolution frames are not sharpened. Source quality/bitrate selection is preserved. [Research, alternatives and limitations](https://github.com/shadow-pheonix/TizenTube/blob/amoled04/docs/video-upscaler.md).

These packages reuse the official native runtime and add compiled Android updater/upscaler helpers, three narrowly patched Java bridge methods and three decoder hooks; they are not a full Chromium/Cobalt compilation. Official input digests and signing certificate, output signatures, 16 KiB alignment, and unchanged APK entries were verified. JSON provenance reports and SHA256SUMS are attached. Android policy checks, browser settings checks and an Android emulator decoder/GPU smoke check passed. Physical Android TV playback, GPU performance, HDR transitions and installer behavior still require device testing; no physical-device FPS or battery improvement is claimed.

Existing users of this fork can install updates over its previous APKs. Users of the official app must uninstall it first because its signing key differs; uninstalling clears local settings and sign-in.

[Official release](https://github.com/{UPSTREAM}/releases/tag/{manifest['upstream_version']}) · [Merged source](https://github.com/{args.repository}/tree/{head}) · [Build run]({args.run_url})

{marker(manifest)}
"""
    with tempfile.TemporaryDirectory() as directory:
        notes_path = Path(directory) / "notes.md"
        notes_path.write_text(notes)
        existing = subprocess.run(["gh", "release", "view", args.release_tag,
                                   "--repo", args.repository, "--json", "isDraft"],
                                  capture_output=True, text=True)
        if existing.returncode == 0:
            if not json.loads(existing.stdout)["isDraft"]:
                raise ValueError("Refusing to replace an existing published release")
            run("gh", "release", "edit", args.release_tag, "--repo", args.repository,
                "--notes-file", notes_path, "--target", head)
        else:
            # Check the API is reachable before treating an absent release as new.
            github(f"repos/{args.repository}")
            run("gh", "release", "create", args.release_tag, "--repo", args.repository,
                "--target", head, "--draft", "--title", f"TizenTube AMOLED — {manifest['upstream_version']}",
                "--notes-file", notes_path)
        files = [args.directory / name for name in sorted(REQUIRED_ASSETS)]
        run("gh", "release", "upload", args.release_tag, "--repo", args.repository, "--clobber", *files)
        run("gh", "release", "edit", args.release_tag, "--repo", args.repository, "--draft=false", "--latest")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("plan")
    command.add_argument("--repository", required=True)
    command.add_argument("--upstream-tag", default="")
    command.add_argument("--force", action="store_true")
    command.add_argument("--output", type=Path, required=True)
    command.add_argument("--github-output", type=Path)
    command.set_defaults(handler=plan)
    command = commands.add_parser("merge-source")
    command.add_argument("--manifest", type=Path, required=True)
    command.add_argument("--base-commit", required=True)
    command.set_defaults(handler=merge_source)
    command = commands.add_parser("publish")
    command.add_argument("--manifest", type=Path, required=True)
    command.add_argument("--directory", type=Path, required=True)
    command.add_argument("--release-tag", required=True)
    command.add_argument("--repository", required=True)
    command.add_argument("--run-url", required=True)
    command.set_defaults(handler=publish)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
