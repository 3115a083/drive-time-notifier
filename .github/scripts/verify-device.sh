#!/usr/bin/env bash
set -uo pipefail

test_status=0
gradle connectedDebugAndroidTest --stacktrace || test_status=$?
mkdir -p overlay-screenshots
for capture_name in overlay-light.png overlay-dark.png overlay-start-failure.png overlay-start-failure.xml; do
  if adb shell test -f "/data/local/tmp/$capture_name"; then
    adb pull "/data/local/tmp/$capture_name" overlay-screenshots/ || true
  fi
done
exit "$test_status"
