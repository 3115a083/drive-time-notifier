#!/usr/bin/env bash
set -uo pipefail

test_status=0
gradle connectedDebugAndroidTest --stacktrace || test_status=$?
if [ "$test_status" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for path in Path("app/build/outputs/androidTest-results").rglob("*.xml"):
    try:
        root=ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for kind in ("failure","error"):
                for failure in case.findall(kind):
                    print(f"DEVICE FAILURE {case.get('classname')}.{case.get('name')}: {failure.get('message','')}",flush=True)
                    print((failure.text or "")[:18000],flush=True)
        for failure in root.findall("failure"):
            print(ET.tostring(failure,encoding="unicode")[:18000],flush=True)
    except ET.ParseError:
        pass
PY
fi
mkdir -p overlay-screenshots
for capture_name in overlay-light.png overlay-dark.png overlay-start-failure.png overlay-start-failure.xml; do
  if adb shell test -f "/data/local/tmp/$capture_name"; then
    adb pull "/data/local/tmp/$capture_name" overlay-screenshots/ || true
  fi
done
exit "$test_status"
