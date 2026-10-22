# AMOLED APK build

These APKs use the latest stable official native Android runtime, with its two
userscript URLs redirected to the pinned public feature bundle:

https://cdn.jsdelivr.net/gh/shadow-pheonix/TizenTube@amoled01/u.js

Feature source and browser checks:
https://github.com/shadow-pheonix/TizenTube/tree/feature/amoled-pitch-black

This is a repackaged runtime, **not a full Chromium/Cobalt native compilation**.
The matching native source change is in
`third_party/blink/renderer/core/dom/document.cc`. Each URL replacement is the
same byte length, preserving ELF layout. The build verifies upstream APK
checksums and signatures, replaces exactly two occurrences, aligns native
libraries to 16 KiB, signs the APK, verifies its signature and alignment, and
checks all other archive entries against upstream.

## Build on GitHub

The **Build AMOLED APKs** workflow is installed on the default `main` branch.
It checks `reisxd/TizenTubeCobalt/releases/latest` every hour, at minute 17 UTC
(GitHub can delay scheduled runs). An upstream release in a different
repository cannot directly trigger this fork without an external webhook, so
the workflow polls the official release feed. Drafts and prereleases are excluded.

For a new official release it:

1. Resolves the official release tag to a commit and records both APK asset
   digests from GitHub in `upstream-release.json`.
2. Merges that official source commit with the fork's current `main`, preserves
   the AMOLED loader, and records the source and APK input metadata.
3. Downloads both official APKs and verifies their digests and the known official
   signing certificate. It checks that both loader URLs can still be replaced.
4. Packages, aligns, signs, and verifies ARM64 and ARM32 APKs on GitHub runners.
5. Pushes the successfully merged source to `main` without a force push and
   publishes a release named `<official-tag>-amoled.<revision>`, containing both
   APKs, JSON provenance reports, official release metadata, and SHA256SUMS.

Published builds are identified by the official release metadata plus their
complete asset set, so hourly checks skip releases already processed. If an
upload is interrupted, the next run resumes its draft. Releases remain drafts
until all required assets are uploaded. A merge conflict, changed loader, missing
digest, or changed official signing certificate fails the workflow for review
instead of publishing an incompatible APK.

Use **Actions → Build AMOLED APKs → Run workflow**, with branch **main**, to run
the same process manually. Leave `upstream_tag` empty for the newest stable
release, turn off `publish` for an artifact-only trial, or enable `force_rebuild`
to publish a new fork revision. Push-triggered runs build and verify artifacts;
they never publish releases. GitHub may disable scheduled workflows after long
repository inactivity; the Actions page shows the workflow's enabled state.

Signing secrets are `AMOLED_KEYSTORE_BASE64` and `AMOLED_STORE_PASSWORD`.
The keystore alias is `amoled`; the same persistent signing key signs both
variants and subsequent builds. No private signing material is included in the
repository or downloadable artifacts.

## Build locally

Requires Python 3.11+, Java 17+, Android SDK Build Tools 35.0.0, and a keystore.

```sh
gh release download v2.0.3 --repo reisxd/TizenTubeCobalt \
  --pattern 'cobalt-*.apk' --dir build/upstream
python3 tools/amoled/upstream_release.py plan \
  --repository shadow-pheonix/TizenTubeCobalt --upstream-tag v2.0.3 \
  --output build/upstream-release.json
export AMOLED_STORE_PASSWORD='your-keystore-password'
python3 tools/amoled/build_apk.py --arch arm64 \
  --upstream-apk build/upstream/cobalt-arm64.apk \
  --release-manifest build/upstream-release.json \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --keystore /path/to/amoled-release.jks \
  --output build/TizenTube-AMOLED-arm64.apk
```

Use `--arch arm` and the ARM APK for the 32-bit variant.

Run `python3 -m unittest discover -s tools/amoled/tests -v` to check release
selection, duplicate prevention, draft retries, input validation, signer checks,
successful source merging, and merge-conflict behavior.

## Install and enable

Choose ARM64 for a device with a 64-bit Android OS, or ARM for 32-bit Android.
This APK has a separate signature, so uninstall the official TizenTube Cobalt
first. Uninstalling clears that app's local settings and sign-in state.

After installing, open **TizenTube Settings → AMOLED / Pitch black theme** and
enable it. It takes effect immediately and is saved across restarts. Disable it
to restore your previous custom navigation and content colors.

The fork still uses the upstream updater. Installing an official update will
require switching back to the upstream signature and will remove this fork's
feature loader; use this fork's APKs to retain the theme feature.

Browser checks cover theme changes, persistence, dynamically added panels,
custom-color restoration, CSP nonces, focus visibility, and transparent video
surfaces. APK checks verify packaging, not playback on physical Android TV
hardware.
