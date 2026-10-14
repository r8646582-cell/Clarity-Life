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
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
sys.path.insert(0, os.path.join(HERE, ".."))
import retrieval as rv  # noqa: E402  (tools/retrieval.py: the retrieval reference)
import recall_time as rt  # noqa: E402
MODEL_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "models", "gte-base-en-v1.5")
MODEL_NAME = "gte-base-en-v1.5"

# ---- core/Retrieval.kt: the pure part is tools/retrieval.py (Phase 2), used here as it is ----
MAX_TERMS, CANDIDATES_PER_TERM, FUSE_DEPTH = rv.MAX_TERMS, rv.CANDIDATES_PER_TERM, rv.FUSE_DEPTH
MAX_RESULTS = rv.MAX_RESULTS
SEMANTIC_MIN, SEMANTIC_MIN_QUERY_WORDS, SEMANTIC_MIN_DOC_WORDS, RRF_K = 0.42, 3, 4, 60
RECENT_SESSIONS = 5
STOP, WORD = rv.STOP, rv.WORD
norm, terms, stem, term_query, score, strong, word_count = (
    rv.norm, rv.terms, rv.stem, rv.term_query, rv.score, rv.strong, rv.word_count)


def fuse(*lists):
    """Reciprocal rank fusion with equal weights."""
    return rv.fuse([(1.0, lst) for lst in lists])


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


STATUS = {"broken": "not_done"}


def promise_text(p):
    """Mirror of PromiseText.searchText."""
    return rv.promise_text(p["text"], STATUS.get(p["status"], p["status"]))


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
            self._add("promise", p["id"], promise_text(p), None, p["createdAt"])
        self.promises = data["promises"]
        self.emb = None
        if hybrid:
            self.emb = Embedder()
            self.vecs = {k: self.emb.embed(d["text"]) for k, d in self.docs.items()}

    def _add(self, kind, ident, text, session, at):
        self.db.execute("INSERT INTO search_doc(kind, ref_id, title, body) VALUES (?,?,?,?)", (kind, str(ident), "", text))
        self.docs[(kind, ident)] = {"text": text, "session": session, "at": at}

    def keyword(self, ts):
        """Mirror of SearchIndex.keywordHits: one read per word, newest first, then Retrieval.choose (Phase 2:
        a single rare word counts too)."""
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
        return rv.choose([{"key": c, "text": self.docs[c]["text"], "at": self.docs[c]["at"]} for c in cands], ts,
                         rare=rv.rare_terms(ts, found, total))

    def meaning(self, text):
        if self.emb is None or word_count(text) < SEMANTIC_MIN_QUERY_WORDS:
            return []
        q = self.emb.embed(text)
        sims = [(k, float(q @ v)) for k, v in self.vecs.items()]
        best = [k for k, s in sorted(sims, key=lambda p: -p[1]) if s >= SEMANTIC_MIN][:rv.MEANING_CANDIDATES]
        best = [k for k in best if k[0] != "message" or word_count(self.docs[k]["text"]) >= SEMANTIC_MIN_DOC_WORDS]
        return rv.distinct_texts(best, {k: self.docs[k]["text"] for k in best})[:FUSE_DEPTH]

    def relevant_past(self, question, today):
        """Phase 2 (tools/retrieval.py): the period list (promises only in this fixture), keyword and meaning
        results, weighted fusion, one result per conversation, at most MAX_RESULTS."""
        period = rt.parse(question, today)
        rest = period["rest"] if period["start"] else question
        lists = []
        if period["start"] is not None or period["promises"]:
            items = [{"key": ("promise", p["id"]), "kind": "promise", "outcome": STATUS.get(p["status"], p["status"]),
                      "text": self.docs[("promise", p["id"])]["text"], "day": p["dueAt"]}
                     for p in self.promises if p.get("dueAt") and (period["start"] is None or period["start"] <= p["dueAt"] <= period["end"])]
            lists.append((rv.W_DATED, rv.dated(items, rv.terms(rest), period["promises"], period["outcome"])))
        lists.append((rv.W_NEW, self.keyword(rv.terms(rest))))
        lists.append((rv.W_NEW, self.meaning(question)))
        docs = {k: {"text": d["text"], "conversation": d["session"]} for k, d in self.docs.items()}
        return rv.choose_final(rv.fuse(lists), docs)


class Embedder:
    """Mirror of memory/Embedder.kt: the bundled model (its two parts joined), CLS pooling, normalised, no query
    prefix (Max plan Phase 4: gte-base-en-v1.5; bge-small-en-v1.5 before)."""

    def __init__(self):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import BertWordPieceTokenizer
        self.np = np
        self.tok = BertWordPieceTokenizer(os.path.join(MODEL_DIR, "vocab.txt"), lowercase=True)
        self.tok.enable_truncation(512)
        parts = sorted(n for n in os.listdir(MODEL_DIR) if n.startswith("model_quantized.onnx.part"))
        self.s = ort.InferenceSession(b"".join(open(os.path.join(MODEL_DIR, n), "rb").read() for n in parts))

    def embed(self, text):
        np = self.np
        ids = np.array([self.tok.encode(text).ids], dtype=np.int64)
        v = self.s.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": np.ones_like(ids)})[0][0, 0]
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
        hits = index.relevant_past(q["question"], data["today"])
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
        "retriever": "new app, " + ("keyword + " + MODEL_NAME if hybrid else "keyword only"),
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
