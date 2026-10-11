"""Runs contracts/context_vectors.json through tools/context.py. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import context as C


def main():
    data = json.load(open(os.path.join(os.path.dirname(__file__), "..", "contracts", "context_vectors.json"),
                      encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        try:
            got = getattr(C, c["fn"])(**c["args"])
        except Exception as e:  # noqa
            got = "EXC %s: %s" % (type(e).__name__, e)
        if isinstance(got, tuple):
            got = list(got)
        if got != c["expect"]:
            bad += 1
            print("FAIL", c["name"], "\n   got   ", json.dumps(got)[:400], "\n   expect", json.dumps(c["expect"])[:400])
    print("%d vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
