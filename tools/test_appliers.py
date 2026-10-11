"""Runs contracts/applier_vectors.json through tools/appliers.py. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import appliers as A


def main():
    data = json.load(open(os.path.join(os.path.dirname(__file__), "..", "contracts", "applier_vectors.json"),
                      encoding="utf-8"))
    fixtures = data["fixtures"]
    bad = 0
    for c in data["cases"]:
        args = dict(c["args"])
        if "ctx" in args and isinstance(args["ctx"], str):
            args["ctx"] = fixtures[args["ctx"]]
        got = json.loads(json.dumps(getattr(A, c["fn"])(**args)))
        exp = c["expect"]
        # "expect" may list only some keys; "dropped_count" checks the number of drop reasons only
        problems = []
        for k, v in exp.items():
            if k == "dropped_count":
                if len(got.get("dropped", [])) != v:
                    problems.append("dropped %d != %d %s" % (len(got.get("dropped", [])), v, got.get("dropped")))
            elif got.get(k) != v:
                problems.append("%s: got %s, expect %s" % (k, json.dumps(got.get(k))[:500], json.dumps(v)[:500]))
        if problems:
            bad += 1
            print("FAIL", c["name"], "|", " ; ".join(problems))
    print("%d vectors, %d failed" % (len(data["cases"]), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
