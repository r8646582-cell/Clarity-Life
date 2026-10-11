#!/usr/bin/env bash
# Fails the build when the Android project contains something this project never allows.
# Runs only when an Android app exists. Run: bash tools/forbidden_patterns.sh
set -uo pipefail
cd "$(dirname "$0")/.."
[ -d app/src ] || { echo "no app yet, skipping"; exit 0; }
fail=0
# Source code plus the build files, where an analytics or crash SDK would be added as a dependency.
paths=(app/src)
for f in app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle/libs.versions.toml; do [ -f "$f" ] && paths+=("$f"); done
check() { # description, regex, allowed-path-regex
  hits=$(grep -rEn --include='*.kt' --include='*.java' --include='*.kts' --include='*.xml' --include='*.toml' \
         "$2" "${paths[@]}" 2>/dev/null | grep -Ev "${3:-^\$}" || true)
  if [ -n "$hits" ]; then echo "FORBIDDEN: $1"; echo "$hits"; fail=1; fi
}
check "destructive migration" "fallbackToDestructiveMigration"
check "hard-coded model name outside DefaultSettings" "\"(deepseek-[a-z0-9.-]+|claude-[a-z0-9.-]+|gpt-[a-z0-9.-]+)\"" "DefaultSettings\.kt|/test/|/androidTest/"
check "hard-coded provider URL outside DefaultSettings" "\"https://api\.(deepseek|anthropic|openai)\.com" "DefaultSettings\.kt|/test/|/androidTest/"
check "logging that may carry content" "(Log\.[dviwe]|println|Timber\.[dviwe])\(.*(content|text|message|body|apiKey|api_key|prompt|reply|quote|note)" "/test/|/androidTest/"
check "printing stack traces (they can carry content)" "\.printStackTrace\(\)" "/test/|/androidTest/"
check "analytics or crash-reporting SDK" "(firebase|crashlytics|appcenter|sentry|mixpanel|amplitude|bugsnag|instabug|google-analytics|play-services-measurement)"
check "cleartext traffic allowed" "usesCleartextTraffic=\"true\""
check "Android cloud backup of the database (would copy his data off the phone)" "allowBackup=\"true\""
check "hidden action syntax from the old app" "\[\[(promise|mode|step_done):|TOOL_CALL|record_promise"
check "prompt text inlined in Kotlin (prompts live in prompts/*.md)" "(Before every reply, think privately|You are .*long-term coach)"
# The manifest must say no to cloud backup explicitly (the default on many devices is yes).
manifest=app/src/main/AndroidManifest.xml
if [ -f "$manifest" ] && ! grep -q 'android:allowBackup="false"' "$manifest"; then
  echo "FORBIDDEN: $manifest must set android:allowBackup=\"false\""; fail=1
fi
exit $fail
