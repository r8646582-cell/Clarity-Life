"""Runs contracts/textcheck_vectors.json through tools/textcheck.py. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import textcheck as T

def main():
    data = json.load(open(os.path.join(os.path.dirname(__file__), "..", "contracts", "textcheck_vectors.json"), encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        got = getattr(T, c["fn"])(**c["args"])
        if got != c["expect"]:
            bad += 1
            print("FAIL", c["name"], "| got", got, "| expect", c["expect"])
    print("%d vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
