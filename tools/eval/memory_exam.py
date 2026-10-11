"""The memory exam's retrieval section, for the new app (spec section 14, stage B "done when").

The fixture (fixtures/history.json, fixtures/memory_exam.json) comes unchanged from the old repo's
tools/eval: an invented user, "Tester Zed", 14 conversations and 43 questions. For every answerable
question it measures how much of its gold evidence the coach could see: the Part 2 memory blocks plus what
RELEVANT_PAST brings for that question. No model answers anything; this is code only.

This file mirrors the Kotlin, as the old harness did: MemoryBlocks (PROFILE, PEOPLE, NOTES, RECENT_SESSIONS),
SearchIndex.relevantPast and Retrieval (keyword candidates newest first, scored, at least two shared words) and,
with --hybrid, the bge vectors fused by reciprocal rank fusion. When the Kotlin changes, change this too.

How evidence ids map onto the new app (it keeps no separate quote, event or old-profile archive; it indexes
his messages instead, which hold the same words):
  s:N   seen when conversation N's summary is in RECENT_SESSIONS, or its summary or one of his messages in
        conversation N is in RELEVANT_PAST
  q:N, e:N  seen when that quote or event, or one of his messages from its conversation, is in RELEVANT_PAST
  p:KEY for a profile entry that was later replaced: seen when one of his messages from its conversation is
        in RELEVANT_PAST (the app keeps no history of replaced profile values)
  p:KEY  (current entry) always seen, in PROFILE;  n:N  (active note) always seen, in NOTES
  pr:N  seen when the promise is in OPEN_PROMISES (open, or answered in the 14 days before the fixture's
        "today"; PromiseText.block), or the promise itself is in RELEVANT_PAST (SearchIndex indexes every
        promise with its outcome, PromiseText.searchText)

Run:   python3 tools/eval/memory_exam.py                      (keyword only, standard library)
       /tmp/venv/bin/python tools/eval/memory_exam.py --hybrid (needs onnxruntime, tokenizers, numpy)
"""
import json
import os
import re
import sqlite3
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
MODEL_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "models", "bge-small-en-v1.5")

# ---- mirror of core/Retrieval.kt ----
MAX_TERMS, MAX_RESULTS, CANDIDATES, FUSE_DEPTH = 8, 5, 200, 20
SEMANTIC_MIN, SEMANTIC_MIN_QUERY_WORDS, SEMANTIC_MIN_DOC_WORDS, RRF_K = 0.62, 3, 4, 60
RECENT_SESSIONS = 5
STOP = set("""about after again also always because been before being could didn does doesn doing done dont even ever
every from going gonna have having here into just know like make many maybe more most much need never only other
really said same should some still such sure than that their them then there these they thing things think this those
today very want wanna well were what when where which while will with would yeah your yours okay""".split())
WORD = re.compile(r"[a-z0-9]+")


def norm(s):
    s = unicodedata.normalize("NFKC", s).lower()
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return " ".join(s.split())


def terms(text):
    out = []
    for w in WORD.findall(norm(text)):
        if len(w) >= 4 and w not in STOP and not w.isdigit() and w not in out:
            out.append(w)
    return sorted(out, key=lambda w: (-len(w), w))[:MAX_TERMS]


def score(text, ts):
    words = set(WORD.findall(norm(text)))
    return sum(1 for t in ts if t in words)


def strong(s, n):
    return s >= min(2, n) and s > 0


def word_count(text):
    return len(WORD.findall(norm(text)))


def fuse(*lists):
    sc = {}
    for lst in lists:
        for i, k in enumerate(lst):
            sc[k] = sc.get(k, 0.0) + 1.0 / (RRF_K + i + 1)
    return [k for k, _ in sorted(sc.items(), key=lambda p: -p[1])]


def event_text(e):
    """Mirror of Retrieval.eventText."""
    parts = [e.get("whenText") or "", e.get("situation") or "",
             ("felt " + e["feelingBefore"]) if e.get("feelingBefore") else "", e.get("action") or "",
             ("it gave " + e["payoff"]) if e.get("payoff") else "", e.get("outcome") or ""]
    return "; ".join(p for p in parts if p.strip())


# the old export's statuses, in the new app's words (PromiseText.outcome)
OUTCOME = {"open": "no answer yet", "kept": "done", "broken": "not done", "not_done": "not done",
           "moved": "moved to a new time", "let_go": "let go"}
RECENT_DAYS = 14


def promise_text(p):
    """Mirror of PromiseText.searchText."""
    return "%s (%s)" % (p["text"], OUTCOME.get(p["status"], "no answer yet"))


def promise_in_block(p, today):
    """Mirror of PromiseText.block: every open promise, and the ones answered in the last 14 days."""
    from datetime import date, timedelta
    if p["status"] == "open":
        return True
    due = p.get("dueAt")
    return bool(due) and date.fromisoformat(due) > date.fromisoformat(today) - timedelta(days=RECENT_DAYS)


class Index:
    """search_doc as the app fills it (SearchIndex.rebuild order): his messages, summaries, quotes, events,
    retired notes."""

    def __init__(self, data, hybrid):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE VIRTUAL TABLE search_doc USING fts4(kind, ref_id, title, body, tokenize=unicode61)")
        self.docs = {}
        for m in sorted(data["messages"], key=lambda m: m["id"]):
            if m["role"] == "user":
                self._add("message", m["id"], m["content"], m["sessionId"], m["createdAt"])
        for s in data["sessions"]:
            if s.get("summary"):
                self._add("summary", s["id"], s["summary"], s["id"], s["startedAt"])
        for q in data["quotes"]:
            self._add("quote", q["id"], q["text"], q["sessionId"], q["createdAt"])
        for e in data["behaviorEvents"]:
            if not e.get("deletedByUser"):
                self._add("event", e["id"], event_text(e), e["sessionId"], e["createdAt"])
        for n in data["notes"]:
            if n["status"] == "retired":
                self._add("note", n["id"], n["text"], None, n["lastSeen"])
        for p in data["promises"]:
            self._add("promise", p["id"], promise_text(p), p["sourceSessionId"], p["createdAt"])
        self.emb = None
        if hybrid:
            self.emb = Embedder()
            self.vecs = {k: self.emb.embed(d["text"]) for k, d in self.docs.items()}

    def _add(self, kind, ident, text, session, at):
        self.db.execute("INSERT INTO search_doc(kind, ref_id, title, body) VALUES (?,?,?,?)", (kind, str(ident), "", text))
        self.docs[(kind, ident)] = {"text": text, "session": session, "at": at}

    def keyword(self, ts):
        if not ts:
            return []
        q = " OR ".join("body:%s OR title:%s" % (t, t) for t in ts)
        rows = self.db.execute("SELECT kind, ref_id FROM search_doc WHERE search_doc MATCH ? ORDER BY rowid DESC LIMIT ?",
                               (q, CANDIDATES)).fetchall()
        cands = [(k, int(r)) for k, r in rows]
        scored = [(c, score(self.docs[c]["text"], ts)) for c in cands]
        scored = [p for p in scored if strong(p[1], len(ts))]
        scored.sort(key=lambda p: (-p[1], -self.docs[p[0]]["at"]))
        out, seen = [], set()
        for c, _ in scored:
            t = norm(self.docs[c]["text"])
            if t not in seen:
                seen.add(t)
                out.append(c)
        return out[:FUSE_DEPTH]

    def meaning(self, text):
        if self.emb is None or word_count(text) < SEMANTIC_MIN_QUERY_WORDS:
            return []
        q = self.emb.embed(text)
        sims = [(k, float(q @ v)) for k, v in self.vecs.items()]
        best = [k for k, s in sorted(sims, key=lambda p: -p[1]) if s >= SEMANTIC_MIN][:FUSE_DEPTH * 2]
        best = [k for k in best if k[0] == "summary" or word_count(self.docs[k]["text"]) >= SEMANTIC_MIN_DOC_WORDS]
        return best[:FUSE_DEPTH]

    def relevant_past(self, question):
        return fuse(self.keyword(terms(question)), self.meaning(question))[:MAX_RESULTS]


class Embedder:
    """Mirror of memory/Embedder.kt: the bundled model, CLS pooling, normalised, no query prefix."""

    def __init__(self):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import BertWordPieceTokenizer
        self.np = np
        self.tok = BertWordPieceTokenizer(os.path.join(MODEL_DIR, "vocab.txt"), lowercase=True)
        self.tok.enable_truncation(512)
        self.s = ort.InferenceSession(os.path.join(MODEL_DIR, "model_quantized.onnx"))

    def embed(self, text):
        np = self.np
        ids = np.array([self.tok.encode(text).ids], dtype=np.int64)
        v = self.s.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids), "token_type_ids": np.zeros_like(ids)})[0][0, 0]
        return v / np.linalg.norm(v)


def run(hybrid):
    data = json.load(open(os.path.join(HERE, "fixtures", "history.json"), encoding="utf-8"))
    questions = json.load(open(os.path.join(HERE, "fixtures", "memory_exam.json"), encoding="utf-8"))["questions"]
    index = Index(data, hybrid)

    current_profile = {p["key"] for p in data["profile"] if not p.get("retired") and not p.get("deletedByUser")}
    old_profile = {p["key"]: [int(x) for x in str(p.get("sourceSessionIds") or "").split(",") if x.strip().isdigit()]
                   for p in data["profile"] if p.get("retired")}
    active_notes = {n["id"] for n in data["notes"] if n["status"] == "active"}
    recent = {s["id"] for s in sorted((s for s in data["sessions"] if s.get("summary")), key=lambda s: s["startedAt"])[-RECENT_SESSIONS:]}
    quote_session = {q["id"]: q["sessionId"] for q in data["quotes"]}
    event_session = {e["id"]: e["sessionId"] for e in data["behaviorEvents"]}
    in_block = {p["id"] for p in data["promises"] if promise_in_block(p, data["today"])}

    rows = []
    for q in questions:
        gold = set(q["evidence"])
        if q["kind"] == "trap" or not gold:
            continue
        hits = index.relevant_past(q["question"])
        sessions_hit = {index.docs[h]["session"] for h in hits if index.docs[h]["session"] is not None}

        def seen(ev):
            kind, _, ref = ev.partition(":")
            if kind == "s":
                return int(ref) in recent or int(ref) in sessions_hit
            if kind == "p":
                return ref in current_profile or any(s in sessions_hit for s in old_profile.get(ref, []))
            if kind == "n":
                return int(ref) in active_notes or ("note", int(ref)) in hits
            if kind == "q":
                return ("quote", int(ref)) in hits or quote_session.get(int(ref)) in sessions_hit
            if kind == "e":
                return ("event", int(ref)) in hits or event_session.get(int(ref)) in sessions_hit
            if kind == "pr":
                return int(ref) in in_block or ("promise", int(ref)) in hits
            return False

        got = {e for e in gold if seen(e)}
        no_pr = {e for e in gold if not e.startswith("pr:")}
        rows.append({"id": q["id"], "kind": q["kind"], "coverage": len(got) / len(gold),
                     "coverage_without_promises": (len(got & no_pr) / len(no_pr)) if no_pr else None,
                     "missing": sorted(gold - got)})
    mean = lambda xs: round(sum(xs) / len(xs), 4) if xs else None
    return {
        "retriever": "new app, " + ("keyword + bge-small-en-v1.5 (RRF)" if hybrid else "keyword only"),
        "questions": len(rows),
        "mean_coverage": mean([r["coverage"] for r in rows]),
        "mean_coverage_without_promises": mean([r["coverage_without_promises"] for r in rows if r["coverage_without_promises"] is not None]),
        "by_kind": {k: mean([r["coverage"] for r in rows if r["kind"] == k]) for k in sorted({r["kind"] for r in rows})},
        "per_question": rows,
    }


if __name__ == "__main__":
    result = run("--hybrid" in sys.argv)
    for r in result["per_question"]:
        if r["missing"]:
            print("%-4s %-10s %.2f missing %s" % (r["id"], r["kind"], r["coverage"], ", ".join(r["missing"])))
    print(json.dumps({k: v for k, v in result.items() if k != "per_question"}, indent=1))
