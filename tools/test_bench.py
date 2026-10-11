"""Runs contracts/bench_vectors.json through tools/bench.py, and checks that every scenario in
prompts/testbench.md parses and uses only known checks. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import bench as B

ROOT = os.path.join(os.path.dirname(__file__), "..")


def main():
    data = json.load(open(os.path.join(ROOT, "contracts", "bench_vectors.json"), encoding="utf-8"))
    bad = 0
    for c in data["cases"]:
        s = data["scenarios"][c["scenario"]]
        try:
            got = B.run_check(c["check"], c["reply"], s)
        except Exception as e:  # noqa
            got = "EXC %s" % e
        if got != c["expect"]:
            bad += 1
            print("FAIL", c["name"], "| got", got, "| expect", c["expect"])
    # every Check line in the real bench must be runnable
    scen = B.parse_bench(open(os.path.join(ROOT, "prompts", "testbench.md"), encoding="utf-8").read())
    for s in scen:
        for ch in s["checks"]:
            try:
                B.run_check(ch, "Okay.", s)
            except ValueError as e:
                bad += 1
                print("FAIL testbench.md #%s: %s" % (s["name"], e))
    print("%d vectors, %d bench scenarios, %d failed" % (len(data["cases"]), len(scen), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
