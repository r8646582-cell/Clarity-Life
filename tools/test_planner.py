"""Runs contracts/planner_vectors.json through tools/planner.py, then checks the generated PLAN_STATE text
against the copies of it in the prompts and the context contract (they are code output, so they must match
it exactly). Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import planner as P

ROOT = os.path.join(os.path.dirname(__file__), "..")

def norm(x):
    if isinstance(x, tuple): x = list(x)
    if isinstance(x, list): return [norm(i) for i in x]
    if isinstance(x, dict): return {k: norm(v) for k, v in x.items()}
    if hasattr(x, "isoformat"): return x.isoformat()
    return x

def same(a, b):
    a, b = norm(a), norm(b)
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool) and not isinstance(b, bool):
        return abs(a - b) < 1e-9
    return a == b

def main():
    data = json.load(open(os.path.join(ROOT, "contracts", "planner_vectors.json"), encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        try:
            got = getattr(P, c["fn"])(**c["args"])
        except Exception as e:  # noqa
            got = "EXC %s: %s" % (type(e).__name__, e)
        if not same(got, c["expect"]):
            bad += 1
            print("FAIL", c["name"], "\n   got   ", norm(got), "\n   expect", c["expect"])
    # the PLAN_STATE text the coach sees is code-made; prompts that show it must match it exactly
    hist = next(c for c in data["cases"] if c["name"].startswith("typical pace window"))["args"]["checkins"]
    today = "2026-10-09"
    tp = P.typical_pace(hist, "Sat-Thu", today)
    best = P.best_pace(hist, "Sat-Thu", today)
    samples = [
        # (files that must contain it, backlog name, goal title, total, done, deadline)
        (("prompts/examples.md", "contracts/context.md"), "Chemistry lectures", "Clear the chemistry lectures", 40, 0, "2026-11-20"),
        (("prompts/testbench.md",), "Exam lectures", "Clear the exam lectures", 50, 6, "2026-11-20"),
        (("prompts/testbench.md",), "Exam lectures", "Clear the exam lectures", 48, 6, "2026-11-27"),
    ]
    for files, name, title, total, done, deadline in samples:
        left = P.days_left("Sat-Thu", today, deadline)
        proj = P.project(total - done, left, tp["pace"], best)
        lines = P.backlog_lines("B1", name, total, done, "Sat-Thu", proj, tp["answered"], tp["answer_rate"])
        lines.append(P.goal_line("G2", title, "%d Nov" % int(deadline[-2:])))
        lines.append(P.target_line("T3", name, "G2", 1, 1, "Sat-Thu", tp["done"], tp["answered"]))
        lines.append(P.review_options_line(P.review_options("Sat-Thu", today)))
        for fname in files:
            text = open(os.path.join(ROOT, fname), encoding="utf-8").read()
            flat = " ".join(text.split())
            for ln in lines:
                if " ".join(ln.split()) not in flat:
                    bad += 1
                    print("FAIL %s does not contain the generated line: %s" % (fname, ln.strip()))
    print("%d vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
