"""Memory exam 2 (docs/MAX-PLAN.md, Phase 0 step 3): a harder exam over one invented year (exam2/).

For each question, asked as the first message of a new conversation at the fixture's now, it builds what the
app would send today and measures how much of the question's evidence is in it:

  Part 2 (frozen):   PROFILE, PEOPLE, NOTES, CHAPTERS, RECENT_SESSIONS, trimmed to 9,000 tokens with the drop
                     ranks of core/MemoryBlocks.kt (tools/context.py trim_blocks)
  Part 3 (volatile): NOW, PLAN_STATE, OPEN_PROMISES, CHECKIN_FACTS (never trimmed), LAST_CHAT and RELEVANT_PAST,
                     trimmed to 4,000 tokens (search results go first, then the oldest LAST_CHAT lines)

Search (Max plan Phase 2) is tools/retrieval.py and tools/recall_time.py, used as they are: the period the
question names (read in code), keyword (terms, stems, one read per term, a rare word alone counts) and, with
--hybrid, bge-small-en-v1.5 meaning search, for the question and for the question with the lines in its
"context" (earlier turns of the same new conversation), merged by weighted fusion, one result per conversation.
Letters, reviews, chapters, goals, goal changes, targets and check-ins are searchable as SearchIndex makes them.
PHASE2 = False measures the 0.1.12 baseline again.

It also counts leaks: anything a question marks forbidden (a note he deleted, an off-the-record conversation)
that reaches the coach. Leaks must stay at 0.

When the app changes what it sends, change the mirror here in the same commit and record the new score.

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
import memory_exam as mx  # noqa: E402  (the embedder and the meaning-search thresholds)
import retrieval as rv  # noqa: E402  (tools/retrieval.py, the retrieval reference)
import recall_time as rt  # noqa: E402  (tools/recall_time.py, periods and outcomes)
import context as ctx  # noqa: E402  (tools/context.py, the context packet reference)

TZ = timezone(timedelta(hours=5))
PHASE2 = True  # False measures the 0.1.12 baseline (search only)
PHASE3 = True  # False: PROFILE without the replaced values (before Max plan Phase 3)
RARE = True
LAST_CHAT_HOURS, LAST_CHAT_MESSAGES, LAST_CHAT_CHARS = 6, 8, 500
SNIPPET_CHARS = 280
LABEL = "x" * 60
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
    """search_doc as SearchIndex.rebuild fills it (Phase 2): his messages, summaries, quotes, events, retired
    notes, promises, and now letters, reviews, chapters, goals, goal revisions, targets and the check-ins that
    carry a reason or a note; nothing from an off-the-record conversation, nothing of a deleted note."""

    def __init__(self, h, hybrid):
        import sqlite3
        self.h = h
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE VIRTUAL TABLE search_doc USING fts4(kind, ref_id, title, body, tokenize=unicode61)")
        self.docs = {}
        self.off = {c["id"] for c in h["conversations"] if c["off_the_record"]}
        off = self.off
        conv_at = {}
        for m in h["messages"]:
            conv_at.setdefault(m["conversation"], []).append(m["at"])
        self.conv_span = {c: (min(v), max(v)) for c, v in conv_at.items()}
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
            self._add("promise", p["id"], rv.promise_text(p["text"], p["status"], p.get("reason")), None, p["due"])
        if PHASE2:
            goals = {g["id"]: g for g in h["goals"]}
            targets = {t["id"]: t for t in h["targets"]}
            for l in h["letters"]:
                end = (datetime.fromisoformat(l["week_of"]) + timedelta(days=6)).date().isoformat()
                self._add("letter", l["id"], rv.letter_text(l["title"], l["body"]), None, day_ms(end))
            for r in h["reviews"]:
                first, last = review_period(r)
                self._add("review", r["id"], rv.review_text(r["kind"], first, last, r["facts"], r["note"]), None, day_ms(last))
            for c in h["chapters"]:
                self._add("chapter", c["id"], rv.chapter_text(c["title"], c["start"], c["end"], c["body"]), None, day_ms(c["end"]))
            for g in h["goals"]:
                self._add("goal", g["id"], rv.goal_text(g["title"], g["status"], g["deadline"]), None, day_ms(g["created"]))
            for r in h["revisions"]:
                self._add("revision", r["id"], rv.revision_text(goals[r["goal"]]["title"], r["at"], r["text"], r["reason"]), None, day_ms(r["at"]))
            for t in h["targets"]:
                self._add("target", t["id"], target_text(t), None, day_ms(t["start"]))
            for c in h["checkins"]:
                if c["reason"] or c["note"]:
                    self._add("checkin", c["id"], checkin_text(c, targets[c["target"]]), None, day_ms(c["day"]))
        self.emb = None
        if hybrid:
            self.emb = mx.Embedder()
            self.vecs = {k: self.emb.embed(d["text"]) for k, d in self.docs.items()}

    def _add(self, kind, ident, text, conversation, at, msg=None):
        self.db.execute("INSERT INTO search_doc(kind, ref_id, title, body) VALUES (?,?,?,?)", (kind, str(ident), "", text))
        self.docs[(kind, ident)] = {"text": text, "conversation": conversation, "session": conversation, "at": at, "msg": msg}

    def keyword(self, ts):
        """Mirror of SearchIndex.keywordHits: one read per word, newest first, then Retrieval.choose."""
        if not ts:
            return []
        cands, found = [], {}
        for t in ts:
            rows = self.db.execute("SELECT kind, ref_id FROM search_doc WHERE search_doc MATCH ? ORDER BY rowid DESC LIMIT ?",
                                   (rv.term_query(t), rv.CANDIDATES_PER_TERM)).fetchall()
            found[t] = len(rows)
            for k, r in rows:
                c = (k, int(r))
                if c not in cands:
                    cands.append(c)
        total = self.db.execute("SELECT count(*) FROM search_doc").fetchone()[0]
        rare = rv.rare_terms(ts, found, total) if RARE else set()
        return rv.choose([{"key": c, "text": self.docs[c]["text"], "at": self.docs[c]["at"]} for c in cands], ts, rare=rare)

    def meaning(self, text):
        if self.emb is None or rv.word_count(text) < mx.SEMANTIC_MIN_QUERY_WORDS:
            return []
        q = self.emb.embed(text)
        sims = [(k, float(q @ v)) for k, v in self.vecs.items()]
        best = [k for k, s in sorted(sims, key=lambda p: -p[1]) if s >= mx.SEMANTIC_MIN][:rv.MEANING_CANDIDATES]
        best = [k for k in best if k[0] != "message" or rv.word_count(self.docs[k]["text"]) >= mx.SEMANTIC_MIN_DOC_WORDS]
        return rv.distinct_texts(best, {k: self.docs[k]["text"] for k in best})[:rv.FUSE_DEPTH]

    def dated_items(self, period, promises):
        """What falls in the period (SearchIndex.datedItems): promises due in it, conversations held in it,
        letters, reviews and chapters about it, goal revisions made in it, and the check-ins of a period of at
        most a week. With no period but a question about promises, every promise."""
        h = self.h
        out = []
        if period["start"] is None:
            if promises:
                for p in h["promises"]:
                    out.append({"key": ("promise", p["id"]), "kind": "promise", "outcome": p["status"],
                                "text": self.docs[("promise", p["id"])]["text"], "day": local(p["due"]).date().isoformat()})
            return out
        s, e = period["start"], period["end"]
        inside = lambda d: s <= d <= e
        overlap = lambda a, b: a <= e and b >= s
        for p in h["promises"]:
            d = local(p["due"]).date().isoformat()
            if inside(d):
                out.append({"key": ("promise", p["id"]), "kind": "promise", "outcome": p["status"], "text": self.docs[("promise", p["id"])]["text"], "day": d})
        if not PHASE2:
            return out
        for c in h["conversations"]:
            if c["off_the_record"] or not c["summary"]:
                continue
            a, b = self.conv_span[c["id"]]
            da, db_ = local(a).date().isoformat(), local(b).date().isoformat()
            if overlap(da, db_):
                out.append({"key": ("summary", c["id"]), "kind": "summary", "text": c["summary"], "day": db_})
        for l in h["letters"]:
            end = (datetime.fromisoformat(l["week_of"]) + timedelta(days=6)).date().isoformat()
            if overlap(l["week_of"], end):
                out.append({"key": ("letter", l["id"]), "kind": "letter", "text": self.docs[("letter", l["id"])]["text"], "day": end})
        for r in h["reviews"]:
            a, b = review_period(r)
            if overlap(a, b):
                out.append({"key": ("review", r["id"]), "kind": "review", "text": self.docs[("review", r["id"])]["text"], "day": b})
        for c in h["chapters"]:
            if overlap(c["start"], c["end"]):
                out.append({"key": ("chapter", c["id"]), "kind": "chapter", "text": self.docs[("chapter", c["id"])]["text"], "day": c["end"]})
        for r in h["revisions"]:
            if inside(r["at"]):
                out.append({"key": ("revision", r["id"]), "kind": "revision", "text": self.docs[("revision", r["id"])]["text"], "day": r["at"]})
        if (datetime.fromisoformat(e) - datetime.fromisoformat(s)).days < rv.CHECKIN_DAYS:
            targets = {t["id"]: t for t in h["targets"]}
            for c in h["checkins"]:
                if inside(c["day"]):
                    key = ("checkin", c["id"])
                    if key not in self.docs:
                        self.docs[key] = {"text": checkin_text(c, targets[c["target"]]), "conversation": None, "session": None,
                                          "at": day_ms(c["day"]), "msg": None}
                    out.append({"key": key, "kind": "checkin", "text": self.docs[key]["text"], "day": c["day"]})
        return out

    def relevant_past(self, text, earlier=(), today=None):
        if not PHASE2:
            return rv.fuse([(1.0, self.keyword(mx.terms(text))), (1.0, self.meaning(text))])[:mx.MAX_RESULTS]
        period = rt.parse(text, today)
        rest = period["rest"] if period["start"] else text
        lists = []
        if period["start"] is not None or period["promises"]:
            lists.append((rv.W_DATED, rv.dated(self.dated_items(period, period["promises"]), rv.terms(rest),
                                                period["promises"], period["outcome"])))
        lists.append((rv.W_NEW, self.keyword(rv.terms(rest))))
        lists.append((rv.W_NEW, self.meaning(text)))
        ctx_q = rv.conversation_query(text, list(earlier))
        if ctx_q is not None:
            lists.append((rv.W_CONTEXT, self.keyword(rv.terms(ctx_q))))
            lists.append((rv.W_CONTEXT, self.meaning(ctx_q)))
        return rv.choose_final(rv.fuse(lists), self.docs)


def day_ms(day):
    return int(datetime.fromisoformat(day + "T12:00").replace(tzinfo=TZ).timestamp() * 1000)


def review_period(r):
    if r["kind"] == "month":
        y, m = (int(x) for x in r["period"].split("-"))
        first = "%04d-%02d-01" % (y, m)
        nxt = datetime(y + (m == 12), m % 12 + 1, 1)
        return first, (nxt - timedelta(days=1)).date().isoformat()
    end = (datetime.fromisoformat(r["period"]) + timedelta(days=6)).date().isoformat()
    return r["period"], end


def target_text(t):
    return rv.target_text(t["name"], t["target"], t["minimum"], t["unit"], t["schedule"], t["start"], t["end"], t["status"])


def checkin_text(c, t):
    return rv.checkin_text(t["name"], c["day"], c["level"], c["amount"], t["unit"], c["reason"], c["note"])


def item(text, drop):
    return {"text": text, "drop": drop}


def part2(h, now):
    """Mirror of MemoryBlocks (formats and drop ranks) for a conversation started at now."""
    blocks = []
    prof = [p for p in h["profile"]][:30]
    # Max plan Phase 3: each entry carries the newest value it replaced (profile_past), so a replaced fact is in
    # the request, marked as the old one. The fixture has no days; "1 Jan 2026" stands in for the app's days.
    old = {o["key"]: o for o in h["old_profile"]} if PHASE3 else {}
    def prof_text(p):
        text = "%s (%s%s): %s" % (p["key"], "confirmed" if p["confirmed"] else "guess", ", since 1 Jan 2026" if PHASE3 else "", p["value"])
        if p["key"] in old:
            text += "; before that, until 1 Jan 2026: " + old[p["key"]]["value"]
        return text
    blocks.append({"name": "PROFILE", "items": [
        item(prof_text(p), (2000 if p["confirmed"] else 4000) + i + 1) for i, p in enumerate(prof)],
        "ids": [["p:" + p["key"]] + (["pold:" + p["key"]] if p["key"] in old else []) for p in prof]})
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
        hits = index.relevant_past(q["question"], q.get("context", []), h["today"])
        rp_items = []
        rp_ids = []
        for i, k in enumerate(hits):
            d = index.docs[k]
            text = " ".join(d["text"].split())
            # LABEL stands in for the app's label ("Wed 14 Oct, 9:05 am (today), in \"Title\", they wrote"), so the
            # budget counts it.
            rp_items.append(item(LABEL + text[:SNIPPET_CHARS] + ("..." if len(text) > SNIPPET_CHARS else ""), 100 + len(hits) - i))
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
            prefix = {"letter": "l", "review": "rv", "chapter": "ch", "goal": "g", "revision": "rev", "target": "t"}.get(k[0])
            if prefix:
                ids.append("%s:%d" % (prefix, k[1]))
            if k[0] == "checkin":
                c = next(c for c in h["checkins"] if c["id"] == k[1])
                ids += ["ck:%d" % c["id"], "ckd:%d:%s" % (c["target"], c["day"])]
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
