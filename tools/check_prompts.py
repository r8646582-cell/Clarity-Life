"""Static checks for the prompt set. Run: python3 tools/check_prompts.py   (exit code 1 on any error)

What it guarantees before any prompt reaches the model:
  - each prompt file has exactly the placeholders the app fills, nothing more or less
  - required rules are still present; forbidden old syntax and em dashes are absent
  - prompts stay under their size budgets
  - chat prompts only mention context blocks that exist
  - every card in examples.md passes the real card parser, and numbers in number-sensitive examples
    appear in the example's own context
  - examples follow the voice rules (no banned phrases, no lists, one question per reply)
  - testbench.md is well formed (types, setup blocks, check names)
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path.insert(0, HERE)
from cards import parse_reply, CARD_TYPES  # noqa: E402

errors = []


def err(where, msg):
    errors.append("%s: %s" % (where, msg))


def words(s):
    return len(re.findall(r"\S+", s))


def numbers(s):
    return set(re.findall(r"\d+(?:\.\d+)?", s))


def norm(s):
    return s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"').lower()


contract = json.load(open(os.path.join(ROOT, "contracts", "prompts.json"), encoding="utf-8"))
pdir = os.path.join(ROOT, "prompts")
texts = {}
for name in sorted(os.listdir(pdir)):
    if name.endswith(".md"):
        texts[name] = open(os.path.join(pdir, name), encoding="utf-8").read()

# 1 to 5: files, placeholders, rules, size
for name in contract["prompts"]:
    if name not in texts:
        err(name, "missing file")
for name in texts:
    if name not in contract["prompts"]:
        err(name, "file is not in contracts/prompts.json")
for name, spec in contract["prompts"].items():
    t = texts.get(name)
    if t is None:
        continue
    found = set(re.findall(r"\{\{([A-Z_]+)\}\}", t))
    want = set(spec["placeholders"])
    if found != want:
        err(name, "placeholders differ: missing %s, unexpected %s" % (sorted(want - found), sorted(found - want)))
    for phrase in spec["must_contain"]:
        if phrase.lower() not in t.lower():
            err(name, "missing required text: %r" % phrase)
    for pat in contract["forbidden_everywhere"]:
        m = re.search(pat, t)
        if m:
            err(name, "forbidden text %r near %r" % (pat, t[max(0, m.start() - 20):m.end() + 20]))
    n = words(t)
    if n > spec["max_words"]:
        err(name, "%d words is over the budget of %d" % (n, spec["max_words"]))

# 5b: the fixed Part 1 prefix stays within its budget for every chat type
from context import estimate_tokens  # noqa: E402
filled = {k: v.replace("{{USER_NAME}}", "this person").replace("{{ALLOWED_CARDS}}", "plan, target_change, backlog, promise, mode, note")
          for k, v in texts.items()}
for mode in (None, "mode_untangle.md", "mode_decision.md", "mode_plan.md", "mode_focus.md"):
    part1 = "\n\n".join(filled.get(n, "") for n in ("persona.md", "examples.md", "cards.md") + ((mode,) if mode else ()))
    est = estimate_tokens(part1)
    if est > contract["part1_budget"]["max_estimated_tokens"]:
        err("part 1", "%s: about %d tokens (estimated) is over the budget of %d"
            % (mode or "free talk", est, contract["part1_budget"]["max_estimated_tokens"]))

# 6: chat prompts only mention known context blocks
allowed_tokens = set(contract["block_names"]) | {"JSON"}
for name in contract["chat_prompts_for_block_check"]:
    t = re.sub(r"\{\{[A-Z_]+\}\}", "", texts.get(name, ""))
    for tok in set(re.findall(r"\b[A-Z][A-Z_]{3,}\b", t)):
        if tok not in allowed_tokens:
            err(name, "mentions unknown block or capitalised token %s" % tok)

# 7: examples
ex = texts.get("examples.md", "")
sections = re.split(r"(?m)^## ", ex)[1:]
known = {"goal": {"G1", "G2"}, "target": {"T1", "T3"}, "backlog": {"B1"}, "focus": {"F1"},
         "review_options": {"17 Oct", "24 Oct"}}
banned = [norm(b) for b in contract["banned_phrases_for_replies"]]
for sec in sections:
    title = sec.split("\n", 1)[0].strip()
    where = "examples.md #" + title
    context_lines = [l for l in sec.split("\n") if l.startswith("[") and not l.startswith("[[")]
    context = " ".join(context_lines)
    sensitive = bool(re.search(r"PLAN_STATE|CHECKIN_FACTS|FOCUS", context))
    user_text = " ".join(l[5:] for l in sec.split("\n") if l.startswith("User:"))
    # split into turns
    turns, cur = [], None
    for line in sec.split("\n"):
        if line.startswith("Coach:"):
            cur = [line[len("Coach:"):].lstrip()]
            turns.append(cur)
        elif line.startswith("User:") or line.startswith("---") or (line.startswith("[") and not line.startswith("[[")):
            cur = None
        elif cur is not None:
            cur.append(line)
    if not turns:
        err(where, "no coach turns")
    for tl in turns:
        reply = "\n".join(tl).strip()
        prose, cards, dropped = parse_reply(reply, "plan", known)
        if dropped:
            err(where, "card or stray brackets rejected by the card parser: %s" % dropped)
        if prose.count("?") > 1:
            err(where, "more than one question in a coach turn: %r" % prose[:60])
        for b in banned:
            if b in norm(prose):
                err(where, "banned phrase %r in a coach turn" % b)
        if re.search(r"(?m)^\s*([-*•]\s|\d+[.)]\s|#+\s|\*\*)", prose):
            err(where, "list, heading or bold in a coach turn")
        for c in cards:
            if c["type"] == "promise" and c.get("remind") and "remind" not in user_text.lower():
                err(where, "a promise card sets a reminder he never asked for")
        if sensitive:
            ctx_nums = numbers(context + " " + user_text + " " + "".join(c.get("review_date", "") for c in cards))
            for nmb in numbers(prose) - ctx_nums:
                err(where, "number %s in a coach turn does not appear in the example's context" % nmb)


# 7b: the sample cards inside cards.md must also pass the real card parser
for m in re.finditer(r"`(\[\[suggest: .*?\]\])`", texts.get("cards.md", "")):
    if ", ...}" in m.group(1):
        continue  # the format template, not a sample
    ok = False
    for ct in ("free", "untangle", "decide", "plan"):
        _, got, dropped = parse_reply(m.group(1), ct, known)
        ok = ok or (not dropped and len(got) == 1)
    if not ok:
        err("cards.md", "sample card rejected: %s" % m.group(1)[:60])

# 8: testbench structure
tb = texts.get("testbench.md", "")
scen = re.split(r"(?m)^## ", tb.split("\n---\n", 1)[1] if "\n---\n" in tb else "")[1:]
if len(scen) < 40:
    err("testbench.md", "only %d scenarios found" % len(scen))
names = set()
check_re = re.compile(
    r"^(no_card|card=(%s)|no_card=(%s)|max_cards=\d+|max_questions=\d+|max_words=\d+|min_words=\d+|"
    r"contains=.+|contains_any=.+\|.+|not_contains=.+|no_list|no_em_dash|no_banned|number_ok|"
    r"card_field=(%s)\.[a-z_]+=.+)$"
    % ("|".join(CARD_TYPES), "|".join(CARD_TYPES), "|".join(CARD_TYPES)))
blocks = set(contract["block_names"])
for s in scen:
    lines = s.split("\n")
    nm = lines[0].strip()
    where = "testbench.md #" + nm
    if nm in names:
        err(where, "duplicate scenario name")
    names.add(nm)
    typ = None
    users = 0
    expect = False
    prior = []
    for l in lines[1:]:
        if l.startswith("Type:"):
            typ = l[5:].strip()
            if typ not in ("free", "untangle", "decide", "plan", "focus"):
                err(where, "bad Type %r" % typ)
        elif l.startswith("User:"):
            users += 1
        elif l.startswith("Expect:"):
            expect = True
        elif l.startswith("Setup:"):
            m = re.match(r"Setup:\s*([A-Z_]+):\s*\S", l)
            if not m or m.group(1) not in blocks:
                err(where, "bad Setup line %r" % l[:50])
        elif l.startswith("Prior-User:"):
            prior.append("u")
        elif l.startswith("Prior-Coach:"):
            prior.append("c")
        elif l.startswith("Flags:"):
            for f in l[6:].split():
                if not re.match(r"^(listen_only|tough_love=(gentle|balanced|firm))$", f):
                    err(where, "bad flag %r" % f)
        elif l.startswith("Check:"):
            c = l[6:].strip()
            if not check_re.match(c):
                err(where, "bad Check %r" % c)
            if typ == "untangle" and c == "card=promise":
                err(where, "untangle cannot expect a promise card")
        elif l.strip() and not l.startswith("Check"):
            err(where, "unrecognised line %r" % l[:50])
    if typ is None:
        err(where, "no Type")
    if users == 0:
        err(where, "no User line")
    if not expect:
        err(where, "no Expect line")
    for i in range(1, len(prior)):
        if prior[i] == prior[i - 1]:
            err(where, "Prior lines must alternate user and coach")
            break

if errors:
    print("\n".join("ERROR " + e for e in errors))
    print("\n%d problem(s)" % len(errors))
    sys.exit(1)
print("prompt checks passed: %d prompt files, %d example sections, %d test scenarios" % (len(texts), len(sections), len(scen)))
