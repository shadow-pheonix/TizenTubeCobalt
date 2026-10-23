#!/bin/bash
set -euo pipefail
mkdir -p build/render-results
adb install -r build/render-test/render-test.apk
adb logcat -c
adb shell am start -n dev.cobalt.upscalertest/.SmokeActivity
for attempt in {1..60}; do
  adb shell run-as dev.cobalt.upscalertest cat files/result.txt > build/render-results/result.txt 2>/dev/null || true
  if grep -qE 'PASS|FAIL' build/render-results/result.txt; then break; fi
  sleep 1
done
adb logcat -d > build/render-results/logcat.txt
for cycle in 0 1; do
  adb exec-out run-as dev.cobalt.upscalertest cat "files/sgsr1-$cycle.png" > "build/render-results/sgsr1-$cycle.png" || true
done
cat build/render-results/result.txt
grep -q '^PASS:' build/render-results/result.txt
