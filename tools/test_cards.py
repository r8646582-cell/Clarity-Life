"""Runs contracts/card_vectors.json through the reference parser. Exit code 1 on any failure."""
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from cards import parse_reply_full

def main():
    root = os.path.join(os.path.dirname(__file__), "..", "contracts", "card_vectors.json")
    data = json.load(open(root, encoding="utf-8"))
    known = {k: set(v) for k, v in data["known_ids"].items()}
    bad = 0
    for v in data["vectors"]:
        kn = {k: set(x) for k, x in v["known_ids"].items()} if "known_ids" in v else known
        prose, cards, dropped, said_none = parse_reply_full(v["reply"], v["chat_type"], kn)
        got = [c["type"] for c in cards]
        problems = []
        if got != v["expect_cards"]:
            problems.append("cards %s != %s" % (got, v["expect_cards"]))
        if len(dropped) != v["expect_dropped"]:
            problems.append("dropped %s != %s (%s)" % (len(dropped), v["expect_dropped"], dropped))
        if said_none != v.get("expect_said_none", False):
            problems.append("said_none %s != %s" % (said_none, v.get("expect_said_none", False)))
        if "prose_has" in v and v["prose_has"] not in prose:
            problems.append("prose missing %r" % v["prose_has"])
        if "prose_not" in v and v["prose_not"] in prose:
            problems.append("prose contains %r" % v["prose_not"])
        if problems:
            bad += 1
            print("FAIL", v["name"], "|", "; ".join(problems))
    print("%d vectors, %d failed" % (len(data["vectors"]), bad))
    return 1 if bad else 0

if __name__ == "__main__":
    sys.exit(main())
