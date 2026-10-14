"""Mutation check: proves the shared vectors would catch real bugs.

Each mutation below plants one plausible bug (most of them are holes that existed in an earlier version) in a
copy of the reference code, then runs that file's vector test. The test MUST fail. If a mutation survives,
the vectors have a blind spot and this script fails, so CI goes red. Run: python3 tools/mutation_check.py
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

MUTATIONS = [
    ("appliers.py", "if then_q is not None and not days[then_q] < days[now_q]:", "if False:", "test_appliers.py",
     "a story's now quote older than its then quote"),
    ("appliers.py", "if isinstance(v, bool) or not isinstance(v, int) or v not in days:", "if v not in days:",
     "test_appliers.py", "true accepted as quote id 1"),
    ("appliers.py", "            if not number_check(text, inputs)[\"ok\"]:\n                raise Bad(\"a number not in the inputs\")\n", "",
     "test_appliers.py", "an overcame line with an invented number published"),
    ("appliers.py", "        items = items[:STORY_OVERCAME]", "        pass", "test_appliers.py", "more than three overcame items"),
    ("appliers.py", "STORY_WORDS = (40, 120)", "STORY_WORDS = (1, 120)", "test_appliers.py", "a one-line story published"),
    ("appliers.py", "BRIEF_WORDS = (15, 90)", "BRIEF_WORDS = (1, 900)", "test_appliers.py", "a one-line or letter-length brief published"),
    ("story.py", "if cur.weekday() in val and cur not in rest:\n                    total += st[\"target\"]",
     "if cur.weekday() in val:\n                    total += st[\"target\"]", "test_story.py",
     "a planned rest day still asked for in the plan line"),
    ("story.py", "hi = b if e is None else min(b, e)", "hi = b", "test_story.py",
     "a ramp step drawn past its end, so the stairs never step"),
    ("story.py", "lo = max(a, s)", "lo = a", "test_story.py", "a step counted before it started"),
    ("story.py", "if weeks <= MAX_WEEK_BARS:", "if weeks < MAX_WEEK_BARS:", "test_story.py",
     "26 weeks drawn as months"),
    ("story.py", "out.append((cur, min(cur + timedelta(days=6), last)))", "out.append((cur, cur + timedelta(days=6)))",
     "test_story.py", "the week in progress asks for days that have not come yet"),
    ("story.py", "    if today.day < first.day:\n        m -= 1\n", "", "test_story.py",
     "a month counted before its day comes round"),
    ("story.py", "return {\"from\": max(a, first).isoformat()", "return {\"from\": a.isoformat()", "test_story.py",
     "a view that starts before the story began"),
    # Memory exam 2's leak guard: what he deleted or said off the record must never reach the coach.
    ("eval/memory_exam2.py", 'if m["role"] == "user" and m["conversation"] not in off:', 'if m["role"] == "user":',
     "test_memory_exam2.py", "an off-the-record conversation's words indexed for search"),
    ("eval/memory_exam2.py", 'active = [n for n in h["notes"] if n["status"] == "active"]',
     'active = [n for n in h["notes"] if n["status"] != "retired"]', "test_memory_exam2.py",
     "a note he deleted sent in NOTES"),
    # The backup file (contracts/backups/): an old or damaged backup must open, or fail, exactly as the app does.
    ("backup_format.py", "    if nxt is None:\n        raise BadBackup(\"the file is cut short\")\n", "", "test_backup_format.py",
     "a backup with no data after its header opens as empty"),
    ("backup_format.py", "        if len(body) < length:\n            raise BadBackup(\"the file is cut short\")\n", "",
     "test_backup_format.py", "a file cut short reported as a wrong passphrase"),
    ("backup_format.py", "    if header[\"iterations\"] < min_iterations:", "    if False:", "test_backup_format.py",
     "a doctored header makes the key cheap to guess"),
    ("backup_format.py", "    if header[\"format\"] not in (FORMAT, FORMAT_STREAM):", "    if False:", "test_backup_format.py",
     "a backup from a newer app read as if it were this format"),
    ("backup_format.py", "    if diff:\n        return None\n", "", "test_backup_format.py", "a wrong passphrase or a changed byte not caught"),
    ("backup_format.py", "    return line + struct.pack(\">iB\", index, 1 if last else 0)", "    return line + struct.pack(\">iB\", index, 0)",
     "test_backup_format.py", "the last chunk not sealed as the last, so a dropped chunk goes unnoticed"),
    ("backup_format.py", "        nonce[IV_BYTES - 1 - i] ^= (index >> (8 * i)) & 0xFF\n", "        pass\n", "test_backup_format.py",
     "every chunk sealed with the same nonce"),
    # (file, exact text, replacement, test to run, what the bug would be)
    ("cards.py", "if not opts:", "if False:", "test_cards.py", "plan card accepted without review options"),
    ("cards.py", 'limit = 2 if t == "promise" else 1', "limit = 2", "test_cards.py", "two plans in one reply"),
    ("cards.py", "if not math.isfinite(v) or abs(v) > MAX_NUMBER:", "if False:", "test_cards.py", "NaN or huge numbers"),
    ("cards.py", 'if "[[" in l or "]]" in l:', "if False:", "test_cards.py", "raw markers shown in prose"),
    ("cards.py", "object_pairs_hook=_no_duplicate_keys, ", "", "test_cards.py", "duplicate JSON keys"),
    ("planner.py", "return min(remaining, round_down(raw, step))", "return min(remaining, round_half_up(raw, step))",
     "test_planner.py", "projection rounded in the plan's favour"),
    ("planner.py", "return math.ceil(x / step - EPS) * step", "return round_half_up(x, step)", "test_planner.py",
     "required pace rounded down"),
    ("planner.py", "MIN_ANSWERED = 7", "MIN_ANSWERED = 6", "test_planner.py", "pace from too little data"),
    ("planner.py", 'and c["level"] != "rest"\n', "and True\n", "test_planner.py", "rest days counted as answers"),
    ("planner.py", "end = min(min(days) + timedelta(days=WINDOW - 1), last_end)",
     "end = min(days) + timedelta(days=WINDOW - 1)", "test_planner.py", "best fortnight missing for young history"),
    ("textcheck.py", "if left_ok and right_ok:", "if True:", "test_textcheck.py", "partial-word quotes accepted"),
    ("appliers.py", "            if left_ok and right_ok:\n                return True", "            return True",
     "test_appliers.py", "a count evidence that cuts a number in half"),
    ("textcheck.py", "MIN_CHARS = 6", "MIN_CHARS = 5", "test_textcheck.py", "a five-character quote stored as evidence"),
    ("textcheck.py", "if text.count('\"') % 2:", "if False:", "test_textcheck.py", "unbalanced quotes accepted"),
    ("textcheck.py", " | _word_numbers(text)", "", "test_textcheck.py", "numbers in words not checked"),
    ("textcheck.py", 'phrases += re.findall(r\'"([^"]*)"\', text)', 'phrases += re.findall(r\'"([^"\\n]*)"\', text)',
     "test_textcheck.py", "quotes across a line break skipped"),
    ("appliers.py", 'ops.append(["note.add", a["kind"], text, "guess", ev])',
     'ops.append(["note.add", a["kind"], text, a.get("confidence", "guess"), ev])', "test_appliers.py",
     "model sets a new note's confidence"),
    ("appliers.py", "if any(similarity(text, d) >= DELETED_SIMILARITY", "if any(False and similarity(text, d) >= DELETED_SIMILARITY",
     "test_appliers.py", "deleted note re-created"),
    ("appliers.py", 'if entry is None or entry["confirmed"]:', "if entry is None:", "test_appliers.py",
     "gardening rewrites a confirmed profile entry"),
    ("appliers.py", "not all(isinstance(i, int) and not isinstance(i, bool) for i in ids) or \\\n"
     "                len(set(ids)) != len(ids) or not all(i in people",
     "len(set(ids)) != len(ids) or not all(i in people", "test_appliers.py",
     "gardening merges people by a non-whole id"),
    ("planner.py", "if start is not None and d(start) > first:", "if False:", "test_planner.py",
     "days before a plan existed counted as unanswered (0% on day one)"),
    ("planner.py", "        checkins = [c for c in checkins if d(c[\"day\"]) >= d(start)]\n", "        pass\n", "test_planner.py",
     "best fortnight counts check-ins from before the plan"),
    ("cards.py", "if not 1 <= w <= MAX_WEEKS:", "if False:", "test_cards.py", "a ramp step of 0 or 53 weeks accepted"),
    ("cards.py", "if \"end\" in out:\n            raise Bad(\"weeks and end together\")", "if False:\n            pass",
     "test_cards.py", "a step with both weeks and an end accepted"),
    ("plan_dates.py", "                elif step_end is not None:\n", "                elif False:\n", "test_plan_dates.py",
     "ramp steps all start today instead of one after another"),
    ("plan_dates.py", "timedelta(days=7 * item[\"weeks\"] - 1)", "timedelta(days=7 * item[\"weeks\"])", "test_plan_dates.py",
     "a one-week step lasting eight days"),
    ("planner.py", "if start <= cur and (end is None or cur <= end) and cur.weekday() in days:",
     "if start <= cur and (end is None or cur <= end):", "test_planner.py", "plan finish counts days off as work days"),
    ("planner.py", "        if cur not in rest:\n            for start, end, days, target in parsed:",
     "        if True:\n            for start, end, days, target in parsed:", "test_planner.py", "plan finish counts rest days"),
    ("planner.py", "    cur = today + timedelta(days=1) if today_answered else today\n", "    cur = today\n", "test_planner.py",
     "plan finish counts today twice when it is already answered"),
    ("planner.py", "        if kind != \"days\":\n            out[\"status\"] = \"count_schedule\"", "        if False:\n            out[\"status\"] = \"count_schedule\"",
     "test_planner.py", "an Nx/week plan dated as if every day were scheduled"),
    ("context.py", 'while "[[" in text or "]]" in text:', "if True:", "test_context.py", "escaping in a single pass"),
    ("context.py", 'if b["name"] in NEVER_TRIM:', "if False:", "test_context.py", "PLAN_STATE or NOW trimmed"),
    ("context.py", 'lines.append("[[suggest: %s]]" % payload)', "pass", "test_context.py",
     "history sent without the cards the coach offered"),
    ("context.py", 'if "[[" in payload or "]]" in payload', 'if "[[" in payload', "test_context.py",
     "a stored card with a bracket pair sent as a broken card line"),
    ("promise_times.py", "            d = cal\n            sure_day = False\n", "            d = day_key + timedelta(days=offset)\n",
     "test_promise_times.py", "after midnight, tomorrow saved without asking"),
    ("promise_times.py", "    late = n.hour < max(boundary_hour, LATE_NIGHT_END)\n", "    late = n.hour < boundary_hour\n",
     "test_promise_times.py", "with a midnight day boundary, tomorrow at 12:40 am saved without asking"),
    ("promise_times.py", "            sure_time = False\n", "", "test_promise_times.py", "a bare hour saved as a guess"),
    ("promise_times.py", "            if t is not None and datetime.combine(d, t) <= n:\n                d += timedelta(days=7)\n", "",
     "test_promise_times.py", "a weekday promise saved in the past"),
    ("promise_times.py", "        else:\n            raise _Unreadable()\n        i += 1", "        i += 1",
     "test_promise_times.py", "an unknown word ignored instead of failing closed"),
    ("bench.py", "return arg in valid\n", "return arg in valid or arg in tried\n", "test_bench.py",
     "a rejected card counted as a pass"),
    ("plan_dates.py", "    if d < today:\n        raise Unreadable()\n", "", "test_plan_dates.py",
     "a plan date in the past accepted"),
    ("plan_dates.py", "    if weekday is not None and d.weekday() != weekday:\n        raise Unreadable()\n", "",
     "test_plan_dates.py", "a weekday that disagrees with the date ignored"),
    ("plan_dates.py", "        else:\n            raise Unreadable()\n    if day is None", "    if day is None",
     "test_plan_dates.py", "an unknown word ignored instead of failing closed"),
    ("plan_dates.py", "            if review > (t + timedelta(days=REVIEW_MAX_DAYS)).isoformat():\n                raise _Bad(\"review too far ahead\")\n",
     "", "test_plan_dates.py", "a stale review date saved as next year's"),
    ("plan_dates.py", "                if end is not None and end < start:\n                    raise _Bad(\"end before start\")\n", "",
     "test_plan_dates.py", "a target that ends before it starts"),
]


def main():
    survived = 0
    for fname, old, new, test, what in MUTATIONS:
        src = open(os.path.join(ROOT, "tools", fname), encoding="utf-8").read()
        if src.count(old) != 1:
            print("BROKEN mutation (text not found exactly once) in %s: %r" % (fname, old))
            survived += 1
            continue
        tmp = tempfile.mkdtemp(prefix="clarity-mut-")
        try:
            for d in ("tools", "contracts", "prompts", "db"):
                shutil.copytree(os.path.join(ROOT, d), os.path.join(tmp, d),
                                ignore=shutil.ignore_patterns("__pycache__"))
            with open(os.path.join(tmp, "tools", fname), "w", encoding="utf-8") as f:
                f.write(src.replace(old, new))
            r = subprocess.run([sys.executable, os.path.join(tmp, "tools", test)], capture_output=True,
                               env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
            if r.returncode == 0:
                survived += 1
                print("SURVIVED: %s (%s) was not caught by %s" % (what, fname, test))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    print("mutation check: %d mutations, %d survived" % (len(MUTATIONS), survived))
    return 1 if survived else 0


if __name__ == "__main__":
    sys.exit(main())
