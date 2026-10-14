"""Reflection exam (Max plan Phase 3 step 1): can the job that writes notes after a chat see the earlier
conversations a pattern is made of?

Exam 2's questions (exam2/questions.json) name the conversations their answer is spread over. For every question
that names two or more (time questions aside: those are linked by a date, not by what happened), the latest
conversation is the one being reflected, and each earlier one is a pair: is that earlier conversation in front of
reflection when it reflects the latest?

  before Phase 3: reflection saw the 12 most recent events (RECENT EVENTS) and no earlier summary;
  Phase 3:        it also sees EARLIER CONVERSATIONS AND EVENTS LIKE THIS ONE: tools/retrieval.py's
                  reflection_queries and reflection_choose over the same index memory_exam2.py builds (as
                  SearchIndex.earlierForReflection does), only from conversations that ended before.

Run:   python3 tools/eval/reflection_exam.py               (keyword only, standard library)
       /tmp/venv/bin/python tools/eval/reflection_exam.py --hybrid
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
import memory_exam2 as m2  # noqa: E402
import retrieval as rv  # noqa: E402

RECENT_EVENTS = 12  # ReflectionInput.RECENT_EVENTS


def run(hybrid):
    h = json.load(open(os.path.join(HERE, "exam2", "history.json"), encoding="utf-8"))
    qs = json.load(open(os.path.join(HERE, "exam2", "questions.json"), encoding="utf-8"))["questions"]
    index = m2.Index(h, hybrid)
    his = {}
    for m in sorted(h["messages"], key=lambda m: m["id"]):
        his.setdefault(m["conversation"], []).append(m)
    last = {c: max(m["at"] for m in ms) for c, ms in his.items()}

    def earlier(b):
        """What reflection of conversation b is shown, as the app finds it (only what existed by then)."""
        q = rv.reflection_queries([m["text"] for m in his[b] if m["role"] == "user"])
        lists = [(rv.W_NEW, index.keyword(rv.terms(q["all"])))]
        for text in q["each"]:
            lists.append((rv.W_CONTEXT, index.keyword(rv.terms(text))))
            lists.append((rv.W_CONTEXT, index.meaning(text)))
        before = {k: dict(d, kind=k[0]) for k, d in index.docs.items()
                  if d["conversation"] is not None and last.get(d["conversation"], float("inf")) < last[b]}
        recent = sorted((e for e in h["events"] if e["at"] < last[b]), key=lambda e: e["id"])[-RECENT_EVENTS:]
        shown = {("event", e["id"]) for e in recent}
        chosen = rv.reflection_choose(rv.fuse(lists), before, b, shown)
        return {index.docs[k]["conversation"] for k in chosen}, {e["conversation"] for e in recent}

    pairs = []
    for q in qs:
        convs = [int(e[2:]) for e in q["evidence"] if e.startswith("s:")]
        if len(convs) < 2 or q["kind"] == "time":
            continue
        b = max(convs, key=lambda c: last[c])
        found, recent = earlier(b)
        for a in convs:
            if a != b and last[a] < last[b]:
                pairs.append({"question": q["id"], "kind": q["kind"], "reflected": b, "earlier": a,
                              "before": a in recent, "phase3": a in found or a in recent})
    share = lambda k: round(sum(p[k] for p in pairs) / len(pairs), 4)
    return {
        "search": "keyword + bge-small-en-v1.5" if hybrid else "keyword only",
        "pairs": len(pairs),
        "within_reach_before": share("before"),
        "within_reach_phase3": share("phase3"),
        "missed": [p for p in pairs if not p["phase3"]],
        "per_pair": pairs,
    }


if __name__ == "__main__":
    result = run("--hybrid" in sys.argv)
    print(json.dumps({k: v for k, v in result.items() if k != "per_pair"}, indent=1))
    if "--save" in sys.argv:
        with open(sys.argv[sys.argv.index("--save") + 1], "w", encoding="utf-8") as f:
            json.dump(result, f, indent=1, ensure_ascii=False)
            f.write("\n")
