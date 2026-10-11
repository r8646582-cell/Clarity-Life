"""Reference implementation of the test bench: parsing prompts/testbench.md and running the automatic checks.
The executable spec for TestBench.kt; contracts/bench_vectors.json pins how every check is counted, so a
reply passes or fails the same way in Python and on the phone.

What each check looks at:
  - the reply is first split by the real card parser (tools/cards.py) using the scenario's chat type
    (a focus scenario uses the free-talk card list);
  - text checks look at the PROSE only (cards removed), compared case-insensitively with curly quotes made straight;
  - no_card fails on any card line at all, even one the parser threw away (the model still tried);
  - number_ok uses NumberCheck against the Setup, Prior and User lines of the scenario;
  - card ids and review options a card may use are read from the scenario's Setup lines;
  - card=TYPE needs a card the parser accepts; no_card=TYPE and max_cards also count card lines it threw away;
  - card_field=TYPE.FIELD=JSON checks one field of every accepted card of that type (for example remind=false).
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cards import parse_reply  # noqa: E402
from textcheck import number_check  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
LIST_RE = re.compile(r"(?m)^\s*([-*•]\s|\d+[.)]\s|#+\s|\*\*)")


def _banned():
    c = json.load(open(os.path.join(ROOT, "contracts", "prompts.json"), encoding="utf-8"))
    return c["banned_phrases_for_replies"]


def _norm(s):
    return s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"').lower()


def parse_bench(text):
    """Scenarios from testbench.md: list of dicts with name, type, flags, setup, prior, user, expect, checks."""
    body = text.split("\n---\n", 1)[1] if "\n---\n" in text else ""
    out = []
    for sec in re.split(r"(?m)^## ", body)[1:]:
        lines = sec.split("\n")
        s = {"name": lines[0].strip(), "type": None, "flags": [], "setup": [], "prior": [], "user": [],
             "expect": "", "checks": []}
        for l in lines[1:]:
            if l.startswith("Type:"):
                s["type"] = l[5:].strip()
            elif l.startswith("Flags:"):
                s["flags"] += l[6:].split()
            elif l.startswith("Setup:"):
                blk, _, val = l[6:].strip().partition(":")
                s["setup"].append([blk.strip(), val.strip()])
            elif l.startswith("Prior-User:"):
                s["prior"].append(["user", l[11:].strip()])
            elif l.startswith("Prior-Coach:"):
                s["prior"].append(["coach", l[12:].strip()])
            elif l.startswith("User:"):
                s["user"].append(l[5:].strip())
            elif l.startswith("Expect:"):
                s["expect"] = l[7:].strip()
            elif l.startswith("Check:"):
                s["checks"].append(l[6:].strip())
        out.append(s)
    return out


def _card_line_types(reply):
    types = []
    for l in reply.split("\n"):
        m = re.search(r'"type"\s*:\s*"([a-z_]+)"', l) if "[[suggest" in l else None
        if m:
            types.append(m.group(1))
    return types


def known_from_setup(s):
    """Ids and review options a bench card may use, read from the scenario's own Setup lines."""
    text = " ".join(v for _, v in s["setup"])
    known = {k: set(re.findall(r"\b%s\d+\b" % p, text)) for k, p in
             (("goal", "G"), ("target", "T"), ("backlog", "B"), ("focus", "F"))}
    m = re.search(r"Review options: (.*?)\.(?:\s|$)", text)
    if m:
        known["review_options"] = set(re.findall(r"(\d{1,2} [A-Z][a-z]{2}) \(", m.group(1)))
    return known


def run_check(check, reply, s):
    """True when the reply passes one Check line. s is a scenario from parse_bench."""
    ct = "free" if s["type"] == "focus" else s["type"]
    prose, cards, dropped = parse_reply(reply, ct, known_from_setup(s))
    tried = _card_line_types(reply)          # every card line the model wrote, valid or not
    valid = [c["type"] for c in cards]       # cards that would really be shown
    np = _norm(prose)
    name, _, arg = check.partition("=")
    if check == "no_card":
        return not tried and not cards
    if name == "card":
        return arg in valid
    if name == "no_card":
        return arg not in valid and arg not in tried
    if name == "max_cards":
        return len(tried) <= int(arg)
    if name == "max_questions":
        return prose.count("?") <= int(arg)
    if name == "max_words":
        return len(prose.split()) <= int(arg)
    if name == "min_words":
        return len(prose.split()) >= int(arg)
    if name == "contains":
        return _norm(arg) in np
    if name == "contains_any":
        return any(_norm(a) in np for a in arg.split("|"))
    if name == "not_contains":
        return _norm(arg) not in np
    if check == "no_list":
        return not LIST_RE.search(prose)
    if check == "no_em_dash":
        return "\u2014" not in prose and "\u2013" not in prose
    if check == "no_banned":
        return not any(_norm(b) in np for b in _banned())
    if check == "number_ok":
        return number_check(prose, [scenario_text(s)])["ok"]
    if name == "card_field":
        # card_field=promise.remind=false : every valid card of that type has the field with that JSON value
        path, _, want = arg.partition("=")
        typ, _, field = path.partition(".")
        hits = [c for c in cards if c["type"] == typ]
        return bool(hits) and all(json.dumps(c.get(field)) == want for c in hits)
    raise ValueError("unknown check " + check)


def scenario_text(s):
    return "\n".join([v for _, v in s["setup"]] + [v for _, v in s["prior"]] + s["user"])
