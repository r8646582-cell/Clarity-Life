"""Runs contracts/retrieval_vectors.json through tools/retrieval.py. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import retrieval as R


def main():
    data = json.load(open(os.path.join(os.path.dirname(__file__), "..", "contracts", "retrieval_vectors.json"), encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        got = getattr(R, c["fn"])(**c["args"])
        if isinstance(got, set):
            got = sorted(got)
        if got != c["expect"]:
            bad += 1
            print("FAIL", c["name"], "| got", got, "| expect", c["expect"])
    print("%d retrieval vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
