"""Memory exam 2 (docs/MAX-PLAN.md, Phase 0 step 3): a harder exam over one invented year (exam2/).

For each question, asked as the first message of a new conversation at the fixture's now, it builds what the
app would send today and measures how much of the question's evidence is in it:

  Part 2 (frozen):   PROFILE, PEOPLE, NOTES, CHAPTERS, RECENT_SESSIONS, trimmed to 9,000 tokens with the drop
                     ranks of core/MemoryBlocks.kt (tools/context.py trim_blocks)
  Part 3 (volatile): NOW, PLAN_STATE, OPEN_PROMISES, CHECKIN_FACTS (never trimmed), LAST_CHAT and RELEVANT_PAST,
                     trimmed to 4,000 tokens (search results go first, then the oldest LAST_CHAT lines)

Search is the 0.1.12 mirror in memory_exam.py: keyword (terms, stems, one read per term) and, with --hybrid,
bge-small-en-v1.5 meaning search, fused by reciprocal rank. Only the new message is searched (today's app);
the lines in a question's "context" are earlier turns of the same new conversation.

It also counts leaks: anything a question marks forbidden (a note he deleted, an off-the-record conversation)
that reaches the coach. Leaks must stay at 0.

What the app does not send today scores 0 on purpose (old check-ins, letters, reviews, goal revisions, ended
targets and finished goals): this file is the baseline the later phases are measured against. When the app
changes what it sends, change the mirror here in the same commit and record the new score.

Run:   python3 tools/eval/memory_exam2.py              (keyword only, standard library)
       /tmp/venv/bin/python tools/eval/memory_exam2.py --hybrid
"""
import json
import os
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
import memory_exam as mx  # noqa: E402  (the 0.1.12 retrieval mirror)
import context as ctx  # noqa: E402  (tools/context.py, the context packet reference)

TZ = timezone(timedelta(hours=5))
LAST_CHAT_HOURS, LAST_CHAT_MESSAGES, LAST_CHAT_CHARS = 6, 8, 500
SNIPPET_CHARS = 280
RECENT_DAYS = 14
CHAPTERS = 2


def local(ms):
    return datetime.fromtimestamp(ms / 1000, TZ)


def outcome(p):
    """Mirror of PromiseText.outcome."""
    s = p["status"]
    if s == "kept":
        return "done"
    if s == "not_done":
        return "not done" + ((", reason: " + p["reason"]) if p.get("reason") else "")
    return {"moved": "moved to a new time", "let_go": "let go"}.get(s, "no answer yet")


def event_text(e):
    parts = [e["whenText"], e["situation"], ("felt " + e["feeling"]) if e["feeling"] else "", e["action"],
             ("it gave " + e["payoff"]) if e["payoff"] else "", e["outcome"]]
    return "; ".join(x for x in parts if x.strip())


class Index:
    """search_doc as SearchIndex.rebuild fills it: his messages, summaries, quotes, events, retired notes,
    promises; nothing from an off-the-record conversation, nothing of a deleted note."""

    def __init__(self, h, hybrid):
        import sqlite3
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE VIRTUAL TABLE search_doc USING fts4(kind, ref_id, title, body, tokenize=unicode61)")
        self.docs = {}
        off = {c["id"] for c in h["conversations"] if c["off_the_record"]}
        conv_at = {}
        for m in h["messages"]:
            conv_at.setdefault(m["conversation"], []).append(m["at"])
        for m in sorted(h["messages"], key=lambda m: m["id"]):
            if m["role"] == "user" and m["conversation"] not in off:
                self._add("message", m["id"], m["text"], m["conversation"], m["at"], m)
        for c in h["conversations"]:
            if c["summary"] and c["id"] not in off:
                self._add("summary", c["id"], c["summary"], c["id"], min(conv_at[c["id"]]))
        for q in h["quotes"]:
            self._add("quote", q["id"], q["text"], q["conversation"], q["at"])
        for e in h["events"]:
            self._add("event", e["id"], event_text(e), e["conversation"], e["at"])
        for n in h["notes"]:
            if n["status"] == "retired":
                self._add("note", n["id"], n["text"], None, 0)
        for p in h["promises"]:
            self._add("promise", p["id"], "%s (%s)" % (p["text"], outcome(p)), p["conversation"], p["due"])
        self.emb = None
        if hybrid:
            self.emb = mx.Embedder()
            self.vecs = {k: self.emb.embed(d["text"]) for k, d in self.docs.items()}

    def _add(self, kind, ident, text, conversation, at, msg=None):
        self.db.execute("INSERT INTO search_doc(kind, ref_id, title, body) VALUES (?,?,?,?)", (kind, str(ident), "", text))
        self.docs[(kind, ident)] = {"text": text, "session": conversation, "at": at, "msg": msg}

    keyword = mx.Index.keyword
    meaning = mx.Index.meaning

    def relevant_past(self, text):
        return mx.fuse(self.keyword(mx.terms(text)), self.meaning(text))[:mx.MAX_RESULTS]


def item(text, drop):
    return {"text": text, "drop": drop}


def part2(h, now):
    """Mirror of MemoryBlocks (formats and drop ranks) for a conversation started at now."""
    blocks = []
    prof = [p for p in h["profile"]][:30]
    blocks.append({"name": "PROFILE", "items": [
        item("%s (%s): %s" % (p["key"], "confirmed" if p["confirmed"] else "guess", p["value"]),
             (2000 if p["confirmed"] else 4000) + i + 1) for i, p in enumerate(prof)], "ids": ["p:" + p["key"] for p in prof]})
    people = sorted(h["people"], key=lambda p: -p["updated"])
    blocks.append({"name": "PEOPLE", "items": [
        item("%s (%s): %s" % (p["name"], p["relation"], p["notes"]), 5000 + i + 1) for i, p in enumerate(people)],
        "ids": ["who:" + p["name"] for p in people]})
    active = [n for n in h["notes"] if n["status"] == "active"]
    base = {"confirmed": 3000, "likely": 6000, "guess": 7000}
    blocks.append({"name": "NOTES", "items": [
        item("note %d, %s, %s, seen %d times since %s: %s\n%s" % (
            n["id"], n["kind"], n["confidence"], n["times_seen"], n["first"], n["text"],
            "\n".join('  their words, %s: "%s"' % (e[2], e[1]) for e in n["evidence"][-2:])),
            base[n["confidence"]] + (999 - min(max(n["times_seen"], 1), 999)) + 1) for n in active],
        "ids": ["n:%d" % n["id"] for n in active]})
    chapters = sorted(h["chapters"], key=lambda c: c["start"])[-CHAPTERS:]
    blocks.append({"name": "CHAPTERS", "items": [
        item("%s to %s: %s. %s" % (c["start"], c["end"], c["title"], c["body"]), 8500 + len(chapters) - i)
        for i, c in enumerate(chapters)], "ids": ["ch:%d" % c["id"] for c in chapters]})
    # RECENT_SESSIONS: the five newest by their last message, off the record left out, each with its summary.
    last = {}
    for m in h["messages"]:
        last[m["conversation"]] = max(last.get(m["conversation"], 0), m["at"])
    sessions = sorted((c for c in h["conversations"] if c["summary"] and not c["off_the_record"] and last[c["id"]] <= now),
                      key=lambda c: -last[c["id"]])[:mx.RECENT_SESSIONS]
    blocks.append({"name": "RECENT_SESSIONS", "items": [
        item('%s "%s": %s' % (local(last[c["id"]]).strftime("%a %d %b"), c["title"], c["summary"]), 9000 + i + 1)
        for i, c in enumerate(sessions)][::-1], "ids": ["s:%d" % c["id"] for c in sessions][::-1]})
    return blocks


def trimmed_ids(blocks, order, budget):
    """Which ids survive trim_blocks: items are matched back by their text."""
    kept, dropped, over = ctx.trim_blocks([{"name": b["name"], "items": b["items"]} for b in blocks], order, budget)
    texts = {(b["name"], it["text"]) for b in kept for it in b["items"]}
    ids = set()
    for b in blocks:
        for it, i in zip(b["items"], b["ids"]):
            if (b["name"], it["text"]) in texts:
                ids.update(i if isinstance(i, (list, tuple, set)) else [i])
    return ids, dropped


def plan_state(h):
    """What PLAN_STATE carries: active goals, their active targets and backlogs (finished goals, ended targets
    and goal revisions are not in it)."""
    goals = [g for g in h["goals"] if g["status"] == "active"]
    targets = [t for t in h["targets"] if t["status"] == "active"]
    lines = ['G%d "%s"%s' % (g["id"], g["title"], (", deadline " + g["deadline"]) if g["deadline"] else "") for g in goals]
    lines += ['T%d "%s": %s %s a day (minimum %s), %s, since %s' % (t["id"], t["name"], t["target"], t["unit"], t["minimum"],
                                                                  t["schedule"], t["start"]) for t in targets]
    lines += ['B%d "%s": %s %s by %s' % (b["id"], b["name"], b["total"], b["unit"], b["deadline"])
              for b in h["backlogs"] if b["status"] == "active"]
    return [item(l, 0) for l in lines], ["g:%d" % g["id"] for g in goals] + ["t:%d" % t["id"] for t in targets]


def open_promises(h, now):
    today = local(now).date()
    shown = [p for p in h["promises"] if p["status"] == "open" or
             (local(p["due"]).date() > today - timedelta(days=RECENT_DAYS) and p["due"] <= now)]
    return [item('P%d "%s": %s.' % (p["id"], p["text"], outcome(p)), 0) for p in shown], ["pr:%d" % p["id"] for p in shown]


def checkin_facts(h, now):
    """CHECKIN_FACTS has lines for a target when it has at least 3 answers (or scheduled days) in the last 14 days."""
    today = local(now).date()
    first = (today - timedelta(days=14)).isoformat()
    lines, ids = [], []
    for t in h["targets"]:
        if t["status"] != "active":
            continue
        rows = [c for c in h["checkins"] if c["target"] == t["id"] and first <= c["day"] < today.isoformat()]
        if len(rows) >= 3:
            lines.append(item('T%d "%s", last 14 days: %d answered.' % (t["id"], t["name"], len(rows)), 0))
            ids.append("ckf:%d" % t["id"])
    return lines, ids


def last_chat(h, now, exclude):
    last = {}
    for m in h["messages"]:
        last[m["conversation"]] = max(last.get(m["conversation"], 0), m["at"])
    cands = [c for c in h["conversations"] if not c["off_the_record"] and c["id"] != exclude and last[c["id"]] <= now]
    if not cands:
        return [], []
    other = max(cands, key=lambda c: last[c["id"]])
    if now - last[other["id"]] >= LAST_CHAT_HOURS * 3600 * 1000:
        return [], []
    msgs = [m for m in h["messages"] if m["conversation"] == other["id"]][-LAST_CHAT_MESSAGES:]
    items = [item('Your other conversation "%s", its last messages:' % other["title"], 0)]
    ids = [["s:%d" % other["id"]]]
    for i, m in enumerate(msgs):
        items.append(item(("They: " if m["role"] == "user" else "Coach: ") + m["text"][:LAST_CHAT_CHARS], len(msgs) - i))
        ids.append(["s:%d" % other["id"]] + (["m:%d.%d" % (other["id"], m["k"])] if m["role"] == "user" else []))
    return items, ids


def run(hybrid):
    h = json.load(open(os.path.join(HERE, "exam2", "history.json"), encoding="utf-8"))
    qs = json.load(open(os.path.join(HERE, "exam2", "questions.json"), encoding="utf-8"))["questions"]
    now = h["now"]
    index = Index(h, hybrid)

    seen2, dropped2 = trimmed_ids(part2(h, now), ctx.PART2_ORDER, ctx.PART2_BUDGET)
    plan_items, plan_ids = plan_state(h)
    prom_items, prom_ids = open_promises(h, now)
    ck_items, ck_ids = checkin_facts(h, now)
    lc_items, lc_ids = last_chat(h, now, exclude=None)
    now_item = [item(local(now).strftime("It is %A %d %B %Y, %H:%M."), 0)]
    old_sources = {o["key"]: o["sources"] for o in h["old_profile"]}
    retired = {n["id"] for n in h["notes"] if n["status"] == "retired"}

    rows, leaks, dropped3_total = [], [], 0
    for q in qs:
        hits = index.relevant_past(q["question"])
        rp_items = []
        rp_ids = []
        for i, k in enumerate(hits):
            d = index.docs[k]
            text = " ".join(d["text"].split())
            rp_items.append(item(text[:SNIPPET_CHARS] + ("..." if len(text) > SNIPPET_CHARS else ""), 100 + len(hits) - i))
            ids = []
            if d["session"] is not None:
                ids.append("s:%d" % d["session"])
            if k[0] == "message":
                ids.append("m:%d.%d" % (d["msg"]["conversation"], d["msg"]["k"]))
                for key, src in old_sources.items():
                    if d["session"] in src:
                        ids.append("pold:" + key)
            if k[0] == "summary":
                for key, src in old_sources.items():
                    if d["session"] in src:
                        ids.append("pold:" + key)
            if k[0] == "note":
                ids.append("n:%d" % k[1])
            if k[0] == "promise":
                ids.append("pr:%d" % k[1])
            rp_ids.append(ids)
        part3 = [
            {"name": "NOW", "items": now_item, "ids": [[]]},
            {"name": "PLAN_STATE", "items": plan_items, "ids": [[i] for i in plan_ids] + [[]] * (len(plan_items) - len(plan_ids))},
            {"name": "OPEN_PROMISES", "items": prom_items, "ids": [[i] for i in prom_ids]},
            {"name": "CHECKIN_FACTS", "items": ck_items, "ids": [[i] for i in ck_ids]},
            {"name": "LAST_CHAT", "items": lc_items, "ids": lc_ids},
            {"name": "RELEVANT_PAST", "items": rp_items, "ids": rp_ids},
        ]
        part3 = [b for b in part3 if b["items"]]
        seen3, dropped3 = trimmed_ids(part3, ctx.PART3_ORDER, ctx.PART3_BUDGET)
        dropped3_total += dropped3
        seen = seen2 | seen3
        # A retired note is only seen through search.
        seen = {s for s in seen if not (s.startswith("n:") and int(s[2:]) in retired and s not in seen3)}

        def visible(ev):
            if ev.startswith("xn:"):
                return ("n:" + ev[3:]) in seen
            if ev.startswith("xs:"):
                return ("s:" + ev[3:]) in seen
            return ev in seen

        gold = set(q["evidence"])
        for f in q.get("forbidden", []):
            if visible(f):
                leaks.append((q["id"], f))
        if q["kind"] == "trap" or not gold:
            continue
        got = {e for e in gold if visible(e)}
        rows.append({"id": q["id"], "kind": q["kind"], "coverage": len(got) / len(gold), "missing": sorted(gold - got)})

    mean = lambda xs: round(sum(xs) / len(xs), 4) if xs else None
    return {
        "retriever": "Clarity 0.1.12 mirror, " + ("keyword + bge-small-en-v1.5 (RRF)" if hybrid else "keyword only"),
        "questions": len(rows),
        "traps": len([q for q in qs if q["kind"] == "trap"]),
        "mean_coverage": mean([r["coverage"] for r in rows]),
        "by_kind": {k: mean([r["coverage"] for r in rows if r["kind"] == k]) for k in sorted({r["kind"] for r in rows})},
        "leaks": leaks,
        "part2_items_dropped": dropped2,
        "part3_items_dropped_total": dropped3_total,
        "per_question": rows,
    }


def check():
    """CI (tools/check_all.sh): the committed history is what build_history.py makes, and nothing he deleted or
    said off the record reaches the coach. Keyword only, standard library."""
    sys.path.insert(0, os.path.join(HERE, "exam2"))
    import build_history
    with open(os.path.join(HERE, "exam2", "history.json"), encoding="utf-8") as f:
        if f.read() != build_history.render():
            sys.exit("memory exam 2: exam2/history.json is not what build_history.py makes; run it and commit")
    result = run(False)
    if result["leaks"]:
        sys.exit("memory exam 2: forbidden evidence reached the coach: %s" % result["leaks"])
    print("memory exam 2: %d questions, keyword-only coverage %.1f%%, 0 leaks" % (
        result["questions"], 100 * result["mean_coverage"]))


if __name__ == "__main__":
    if "--check" in sys.argv:
        check()
        sys.exit(0)
    result = run("--hybrid" in sys.argv)
    for r in result["per_question"]:
        if r["missing"]:
            print("%-5s %-10s %.2f missing %s" % (r["id"], r["kind"], r["coverage"], ", ".join(r["missing"])))
    print(json.dumps({k: v for k, v in result.items() if k != "per_question"}, indent=1))
    out = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--save=")), None)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=1)
            f.write("\n")
