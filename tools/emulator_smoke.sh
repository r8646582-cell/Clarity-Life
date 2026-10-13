#!/usr/bin/env bash
# Drives the installed debug app on an emulator the way a person does: open Settings, paste a key, tap Save,
# go back, send a chat message. Fails and prints the phone's crash log if the app dies. Runs only in CI,
# inside the emulator step (needs adb and python3).
set -u
PKG=com.clarity.life
APK=app/build/outputs/apk/debug/app-debug.apk

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1; }

# tap <attr> <value> [class]: tap the centre of the first node whose attribute equals/contains the value
tap() {
  dump
  local xy
  xy=$(python3 - "$1" "$2" "${3:-}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
attr, val, cls = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    root = ET.parse('/tmp/ui.xml').getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    if cls and n.get('class') != cls:
        continue
    if val in (n.get(attr) or ''):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds'))
        a, b, c, d = map(int, m.groups())
        print((a + c) // 2, (b + d) // 2)
        sys.exit(0)
sys.exit(1)
PY
  ) || { echo "SMOKE: could not find $1~$2 on screen"; cp /tmp/ui.xml /tmp/last_ui.xml 2>/dev/null; sed 's/></>\n</g' /tmp/ui.xml | grep -o 'text="[^"]*"\|content-desc="[^"]*"' | sort -u | head -40; return 1; }
  echo "SMOKE: tap $2 at $xy"
  adb shell input tap $xy
  sleep 2
}

alive() { adb shell pidof $PKG >/dev/null 2>&1; }

crashed() {
  if ! alive || adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "SMOKE: THE APP CRASHED. Phone log follows."
    adb logcat -d | grep -B2 -A40 "FATAL EXCEPTION" | head -120
    exit 1
  fi
}

adb install -r "$APK" >/dev/null || { echo "SMOKE: install failed"; exit 1; }
adb logcat -c
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 10; crashed

tap content-desc "Settings" || exit 1; crashed
# The key box sits in the folded Coach group.
tap text "Coach" || exit 1; crashed
# The group opens below the health check; scroll until the key box is on screen.
for i in 1 2 3 4; do dump; grep -q 'Paste your key' /tmp/ui.xml && break; adb shell input swipe 160 500 160 200 300; sleep 1; done
tap text "Paste your key" || tap class "" android.widget.EditText || exit 1
adb shell input text "sk-test-0123456789"; sleep 1
tap text "Save" || exit 1; sleep 3; crashed
echo "SMOKE: Save survived"
# Close the keyboard if it is open, then scroll back to the top, where Back is.
dump; grep -q 'content-desc="Back"' /tmp/ui.xml || adb shell input keyevent 111
for i in 1 2 3 4 5; do dump; grep -q 'content-desc="Back"' /tmp/ui.xml && break; adb shell input swipe 160 200 160 500 300; sleep 1; done
tap content-desc "Back" || exit 1; crashed
tap text "Message" || exit 1
adb shell input text "hello"; sleep 1
tap content-desc "Send" || exit 1; sleep 10; crashed
echo "SMOKE: sending a message survived"
tap text "What I know" || exit 1; sleep 3; crashed
tap text "Patterns" || exit 1; sleep 2; crashed
echo "SMOKE: What I know survived"
tap text "Plan" || exit 1; sleep 3; crashed
tap text "Goals" || exit 1; crashed
tap text "Write a goal" || exit 1; crashed
tap class "" android.widget.EditText || exit 1
adb shell input text "Pass%sthe%sexam"; sleep 1
# Close the keyboard (Back only hides it), then scroll the form's Save into view.
adb shell input keyevent 4; sleep 2; crashed
for i in 1 2 3; do adb shell input swipe 160 400 160 150 300; sleep 1; done
tap text "Save" || exit 1; sleep 3; crashed
dump; grep -q "No goals yet" /tmp/ui.xml && { echo "SMOKE: the goal was not saved"; exit 1; }
# back to the top of the list, where the new goal is
for i in 1 2 3; do adb shell input swipe 160 120 160 320 300; sleep 1; done
tap text "Pass the exam" || exit 1; sleep 2; crashed
# With one goal, Goals opens it directly, with Edit at the top.
dump; grep -q 'text="Edit"' /tmp/ui.xml || { echo "SMOKE: the goal page did not open"; exit 1; }
echo "SMOKE: a goal was written and opened"
tap text "Reviews" || exit 1; sleep 2; crashed
tap text "Today" || exit 1; sleep 2; crashed
echo "SMOKE: Plan, Goals, Reviews and Today survived"
sed 's/></>\n</g' /tmp/ui.xml >/dev/null
exit 0
