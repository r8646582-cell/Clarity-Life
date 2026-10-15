#!/usr/bin/env bash
# The app with the phone's largest text (Max plan Phase 7): sets the emulator's font size to 2x, opens the app
# and walks the three tabs and Settings, failing if the app crashes or a tab cannot be found. Runs in CI after
# tools/emulator_smoke.sh (which installs the app); puts the font size back at the end.
set -u
PKG=com.clarity.life
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }
alive() { adb shell pidof $PKG >/dev/null 2>&1; }
crashed() {
  if ! alive || adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "LARGE TEXT: THE APP CRASHED. Phone log follows."
    adb logcat -d | grep -B2 -A40 "FATAL EXCEPTION" | head -120
    finish 1
  fi
}
finish() { adb shell settings put system font_scale 1.0 >/dev/null 2>&1; exit "$1"; }
# tap_text <text>: tap the centre of the first node whose text or description is exactly <text>
tap_text() {
  dump
  local xy
  xy=$(python3 - "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[1]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    if want in (n.get('text'), n.get('content-desc')):
        a, b, c, d = map(int, re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds')).groups())
        print((a + c) // 2, (b + d) // 2)
        sys.exit(0)
sys.exit(1)
PY
  ) || { echo "LARGE TEXT: could not find \"$1\" on screen. What was there:"; sed 's/></>\n</g' /tmp/ui.xml | grep -o 'text="[^"]*"\|content-desc="[^"]*"' | sort -u | head -40; finish 1; }
  echo "LARGE TEXT: tap $1 at $xy"
  adb shell input tap $xy
  sleep 3
}

adb shell am force-stop $PKG
adb shell settings put system font_scale 2.0
adb logcat -c
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 10; crashed
dump; grep -q 'Hello' /tmp/ui.xml || { echo "LARGE TEXT: Talk home did not show"; finish 1; }
echo "LARGE TEXT: Talk home showed"
tap_text "Plan"; crashed
tap_text "What I know"; crashed
tap_text "Talk"; crashed
tap_text "Settings"; crashed
echo "LARGE TEXT: every tab and Settings opened with 2x text"
finish 0
