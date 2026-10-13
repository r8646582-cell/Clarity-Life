"""Runs contracts/story_vectors.json through tools/story.py. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import story as S
from planner import PlannerError

def main():
    data = json.load(open(os.path.join(os.path.dirname(__file__), "..", "contracts", "story_vectors.json"), encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        try:
            got = getattr(S, c["fn"])(**c["args"])
        except PlannerError:
            got = "ERROR"
        want = "ERROR" if c.get("error") else c["expect"]
        if got != want:
            bad += 1
            print("FAIL", c["name"], "| got", got, "| expect", want)
    print("%d story vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
