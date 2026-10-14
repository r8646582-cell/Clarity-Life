"""Kotlin mutation check (Max plan Phase 7): proves the app's own tests would catch real bugs.

The same idea as tools/mutation_check.py, for the Kotlin app instead of the Python references: each entry plants
one plausible bug in the app's code, runs the Kotlin test that should notice, and expects that test to FAIL.
A bug the tests miss ("survived") makes this script fail, so CI goes red. Every file is put back afterwards,
also when the script is stopped. No extra tool: it uses the project's own Gradle.

Run (needs the Android SDK, so normally only in CI): python3 tools/kotlin_mutation_check.py
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
MAIN = "app/src/main/java/com/clarity/life/"

# (file under MAIN, exact text, planted bug, test class pattern, what the bug would mean for the owner)
MUTATIONS = [
    ("core/CardParser.kt", "if (cards.size >= MAX_CARDS) {", "if (cards.size > MAX_CARDS) {",
     "com.clarity.life.core.CardParserTest", "a reply offering one card more than allowed"),
    ("core/TextCheck.kt", "if (q.length < minChars) return false", "if (q.length < 1) return false",
     "com.clarity.life.core.TextCheckTest", "a two-letter scrap accepted as their own words"),
    ("core/ContextBuilder.kt", 'if (stripped.startsWith("## ") || stripped == "##") "  $line" else line', "line",
     "com.clarity.life.core.ContextBuilderTest", "their text able to start a fake section in what the coach reads"),
    ("core/ReflectionApplier.kt", 'wantStr == "likely" && conf == "guess" && seenAgainFlag && ev.isNotEmpty()',
     'wantStr == "likely" && conf == "guess" && seenAgainFlag', "com.clarity.life.core.ApplierTest",
     "a note's confidence raised with no new words of theirs as evidence"),
    ("core/TextJobCheck.kt", "val BRIEF_WORDS = 15 to 90", "val BRIEF_WORDS = 1 to 900",
     "com.clarity.life.core.ApplierTest", "a one-line or letter-length brief published"),
    ("core/Planner.kt", "if (weekday(cur) in parsed.weekdays && cur !in restDates) out.add(cur)",
     "if (weekday(cur) in parsed.weekdays) out.add(cur)", "com.clarity.life.core.PlannerTest",
     "a planned rest day counted as a day to work"),
    ("core/Story.kt", "if (weeks <= MAX_WEEK_BARS) {", "if (weeks < MAX_WEEK_BARS) {",
     "com.clarity.life.core.StoryTest", "the story chart switching to months a week early"),
    ("core/RecallTime.kt", "var y = if (m <= today.monthValue) today.year else today.year - 1", "var y = today.year",
     "com.clarity.life.core.RecallTimeTest", "\"in November\" said in October read as a month still to come"),
    ("core/Retrieval.kt", "n in 1 until CANDIDATES_PER_TERM && n <= RARE_SHARE * total",
     "n in 1 until CANDIDATES_PER_TERM && n <= total", "com.clarity.life.core.Retrieval*",
     "common words treated as rare, so search finds the wrong past"),
    ("core/TimeFacts.kt", "hour < maxOf(boundaryHour, PromiseTimes.LATE_NIGHT_END) -> \"late night\"",
     "hour < boundaryHour -> \"late night\"", "com.clarity.life.core.TimeFactsTest",
     "12:40 am called morning with a midnight day end"),
    ("ui/talk/Cards.kt", 'Buttons("Keep it", { actions.keepNote(card.id) }', 'Buttons("Keep it", { actions.notNow(card.id) }',
     "com.clarity.life.ui.quality.CardScreensTest", "Keep it on a note card doing something else"),
    ("ui/theme/Color.kt", "val Muted = Color(0xFFA9A4B6)", "val Muted = Color(0xFF55516A)",
     "com.clarity.life.ui.quality.ContrastTest", "grey text too faint to read"),
]


def gradle(test):
    cmd = ["./gradlew", "--console=plain", "testDebugUnitTest", "--tests", test]
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)


def main():
    only = sys.argv[1:]  # optional: run only the entries whose file contains one of these words
    survived, broken, ran = [], [], 0
    for fname, old, new, test, what in MUTATIONS:
        if only and not any(w in fname for w in only):
            continue
        path = os.path.join(ROOT, MAIN, fname)
        with open(path, encoding="utf-8") as f:
            src = f.read()
        if src.count(old) != 1:
            broken.append("%s: the text is not there exactly once: %r" % (fname, old))
            continue
        ran += 1
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src.replace(old, new))
            r = gradle(test)
        finally:
            with open(path, "w", encoding="utf-8") as f:
                f.write(src)
        out = r.stdout + r.stderr
        if r.returncode == 0:
            survived.append("%s (%s) was not caught by %s" % (what, fname, test))
        elif "Compilation error" in out or "compileDebugKotlin FAILED" in out or "e: file://" in out:
            # A planted bug that does not compile proves nothing; the entry needs fixing.
            broken.append("%s: the planted bug does not compile" % fname)
        print("%-9s %s" % ("SURVIVED" if r.returncode == 0 else "caught", what), flush=True)
    for line in broken:
        print("BROKEN: " + line)
    for line in survived:
        print("SURVIVED: " + line)
    print("kotlin mutation check: %d mutations, %d survived, %d broken" % (ran, len(survived), len(broken)))
    return 1 if survived or broken else 0


if __name__ == "__main__":
    sys.exit(main())
