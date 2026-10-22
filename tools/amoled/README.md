# AMOLED APK build

These APKs use the official **v2.0.3** native Android runtime, with its two
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

The **Build AMOLED APKs** workflow runs on GitHub-hosted Ubuntu runners and
builds ARM64 and ARM32 variants. Its artifacts include APKs and JSON provenance
reports. A release tag beginning with `amoled-` also publishes the APKs on that
tag's release.

Signing secrets are `AMOLED_KEYSTORE_BASE64` and `AMOLED_STORE_PASSWORD`.
The keystore alias is `amoled`; the same persistent signing key signs both
variants and subsequent builds. No private signing material is included in the
repository or downloadable artifacts.

## Build locally

Requires Python 3.11+, Java 17+, Android SDK Build Tools 35.0.0, and a keystore.

```sh
gh release download v2.0.3 --repo reisxd/TizenTubeCobalt \
  --pattern 'cobalt-*.apk' --dir build/upstream
export AMOLED_STORE_PASSWORD='your-keystore-password'
python3 tools/amoled/build_apk.py --arch arm64 \
  --upstream-apk build/upstream/cobalt-arm64.apk \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --keystore /path/to/amoled-release.jks \
  --output build/TizenTube-AMOLED-arm64.apk
```

Use `--arch arm` and the ARM APK for the 32-bit variant.

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
