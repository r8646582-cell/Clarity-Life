"""Reference implementation of the background-job validators: the executable spec for the Kotlin appliers.

These are the only paths by which model output reaches the database without a tap, so they are the strictest
code in the app. Each function is pure: it takes the model's raw text plus facts from the database, and returns
the list of operations the repository may apply in ONE transaction, plus the reasons anything was dropped.
contracts/applier_vectors.json pins the behaviour.

Fail closed, item by item: a broken top level (not JSON, wrong shape) rejects the whole result; a single bad
item is dropped and the rest is kept. Nothing is ever repaired by guessing, and confidence is never raised by
the model beyond what the rules allow:
  - a new note always starts as "guess" (whatever the model asked for);
  - an update may raise confidence by one step at most, only to "likely", only with seen_again and new
    verified evidence; "confirmed" only ever comes from his own tap or edit;
  - a note he edited or confirmed is never rewritten, merged or retired by a job;
  - a note close to one he deleted is never re-created, by shared words or by meaning (Max plan Phase 3).

Meaning: ctx may carry "vectors", {text: unit vector}, made by the app's on-phone model (bge-small-en-v1.5) for
the texts it compares. A text without a vector is compared by words only, as before Phase 3 (meaning search off).
The thresholds were measured on that model (tools/eval/note_meaning.py): rewordings of one note score about 0.73
to 0.95, and so do opposites ("helps you sleep" and "keeps you awake" score 0.95). So meaning guards deletions
generously (being too careful near something he deleted is the safe side), while a duplicate by meaning also
needs the same kind and the same "not" in both, and a high score.
"""
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cards import loads_strict, Bad  # noqa: E402  same strict JSON as the card parser
from textcheck import quote_is_his, verify_quotes_in_text, number_check, norm  # noqa: E402

NOTE_KINDS = ["pattern", "what_helps", "what_doesnt", "strength", "value", "thread"]
CONF = ["guess", "likely", "confirmed"]
CONTROL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")   # newlines are allowed only in long text fields
DELETED_SIMILARITY = 0.6
DUPLICATE_SIMILARITY = 0.8
DELETED_MEANING = 0.75    # cosine at or above which a text is too close in meaning to a note he deleted
DUPLICATE_MEANING = 0.9   # cosine at or above which a note repeats an active note of the same kind
NEGATIONS = {"not", "no", "never", "nor", "without", "cannot", "stop", "stopped", "none", "nothing"}
LIMITS = {"notes_add": 3, "notes_update": 6, "profile": 3, "people": 3, "events": 5, "his_words": 2,
          "observations": 8, "ideas": 5, "disagreements": 2, "contradictions": 2}


class Reject(Exception):
    pass


def _words(s):
    return set(re.findall(r"[a-z0-9']+", norm(s)))


def similarity(a, b):
    """Jaccard similarity of the word sets. Simple, deterministic, and the same in Kotlin."""
    wa, wb = _words(a), _words(b)
    if not wa or not wb:
        return 0.0
    return len(wa & wb) / len(wa | wb)


def meaning(ctx, a, b):
    """Cosine of the two texts' unit vectors, or None when either has none (meaning search off)."""
    vs = ctx.get("vectors") or {}
    va, vb = vs.get(a), vs.get(b)
    if va is None or vb is None or len(va) != len(vb):
        return None
    return sum(x * y for x, y in zip(va, vb))


def negated(text):
    """Whether a text says "not" in any of its usual ways: "doesn't" and "never" mean the opposite claim."""
    return any(w in NEGATIONS or w.endswith("n't") for w in _words(text))


def close_to_deleted(text, deleted, ctx):
    """A note he deleted is never re-created, nor anything close to it: in words, or in meaning."""
    for d in deleted:
        if similarity(text, d) >= DELETED_SIMILARITY:
            return True
        m = meaning(ctx, text, d)
        if m is not None and m >= DELETED_MEANING:
            return True
    return False


def duplicate_of_active(text, kind, active, ctx):
    """active: [(kind, text)]. A repeat in words (any kind), or in meaning (same kind, high score); never when one
    says "not" and the other does not: "You never keep promises to your sister" is a change, not a repeat."""
    for k, t in active:
        if negated(text) != negated(t):
            continue
        if similarity(text, t) >= DUPLICATE_SIMILARITY:
            return True
        if k == kind:
            m = meaning(ctx, text, t)
            if m is not None and m >= DUPLICATE_MEANING:
                return True
    return False


def _text(v, maxlen, multiline=False):
    if not isinstance(v, str) or not v.strip():
        raise Bad("empty or not text")
    if len(v) > maxlen:
        raise Bad("too long")
    if "[[" in v or "]]" in v:
        raise Bad("brackets")
    if CONTROL_RE.search(v) or (not multiline and "\n" in v):
        raise Bad("control character")
    return v.strip()


def _opt_text(v, maxlen):
    if v is None or v == "":
        return ""
    return _text(v, maxlen)


def _list(obj, key, limit, dropped):
    v = obj.get(key, [])
    if v is None:
        return []
    if not isinstance(v, list):
        raise Reject("%s is not a list" % key)
    if len(v) > limit:
        dropped.append("%s: over the limit of %d, extras dropped" % (key, limit))
    return v[:limit]


def _count_in_facts(flat, checkin_facts):
    """A count is copied from one CHECKIN_FACTS line, whole: "2 answered of 2" is not in "12 answered of 20"."""
    for line in checkin_facts.split("\n"):
        fl = " ".join(line.split())
        i = fl.find(flat)
        while i >= 0:
            j = i + len(flat)
            left_ok = i == 0 or not flat[0].isalnum() or not fl[i - 1].isalnum()
            right_ok = j == len(fl) or not flat[-1].isalnum() or not fl[j].isalnum()
            if left_ok and right_ok:
                return True
            i = fl.find(flat, i + 1)
    return False


def _verify_evidence(items, his_messages, checkin_facts, dropped, where):
    ok = []
    if not isinstance(items, list):
        dropped.append(where + ": evidence is not a list")
        return ok
    for e in items:
        if not isinstance(e, dict) or e.get("kind") not in ("quote", "count") or not isinstance(e.get("text"), str):
            dropped.append(where + ": bad evidence item")
            continue
        if e["kind"] == "quote" and quote_is_his(e["text"], his_messages):
            ok.append(("quote", e["text"].strip()))
        elif e["kind"] == "count" and len(e["text"].strip()) >= 6 and _count_in_facts(" ".join(e["text"].split()), checkin_facts):
            ok.append(("count", " ".join(e["text"].split())))
        else:
            dropped.append(where + ": unverified %s evidence" % e["kind"])
    return ok


def apply_reflection(raw, ctx):
    """ctx: his_messages (only the part not reflected before), his_earlier_quotes (verified quotes already stored),
    checkin_facts (text), notes {id: {kind, confidence, status, edited, text}}, deleted_notes [text],
    people [name], profile {key: {"confirmed": bool}}, in_focus (bool)."""
    dropped, ops = [], []
    try:
        obj = loads_strict(raw.strip())
    except (ValueError, Bad) as e:
        return {"ok": False, "ops": [], "dropped": ["not strict JSON: %s" % e]}
    try:
        if not isinstance(obj, dict):
            raise Reject("not an object")
        try:
            title = _text(obj.get("title"), 80)
            summary = _text(obj.get("summary"), 1500, multiline=True)
            tone = _text(obj.get("tone"), 40)
        except Bad as e:
            raise Reject("title, summary or tone: %s" % e)
        sig = obj.get("significance")
        if isinstance(sig, bool) or not isinstance(sig, int) or not 1 <= sig <= 5:
            raise Reject("significance must be 1 to 5")
        notes_obj = obj.get("notes", {}) or {}
        if not isinstance(notes_obj, dict):
            raise Reject("notes is not an object")
        his = ctx["his_messages"]
        facts = ctx.get("checkin_facts", "")
        ops.append(["conversation.set", title, sig, tone])

        # observations are grounding only; nothing is stored from them
        for o in _list(obj, "observations", LIMITS["observations"], dropped):
            if not isinstance(o, dict) or not isinstance(o.get("quote"), str) or not quote_is_his(o["quote"], his, 1):
                dropped.append("observation: quote not found in his messages")

        for p in _list(obj, "profile_updates", LIMITS["profile"], dropped):
            try:
                key, value = _text(p.get("key"), 40), _text(p.get("value"), 300)
            except (Bad, AttributeError) as e:
                dropped.append("profile: %s" % e)
                continue
            quote = p.get("quote")
            verified = isinstance(quote, str) and quote_is_his(quote, his)
            existing = ctx["profile"].get(key)
            if existing and existing["confirmed"] and not verified:
                dropped.append("profile: %s is confirmed by him; a change needs his exact words" % key)
                continue
            ops.append(["profile.set", key, value, verified])

        known_people = {n.lower(): n for n in ctx.get("people", [])}
        for p in _list(obj, "people_updates", LIMITS["people"], dropped):
            try:
                name = _text(p.get("name"), 60)
                rel, pn = _opt_text(p.get("relation"), 60), _opt_text(p.get("notes"), 200)
            except (Bad, AttributeError) as e:
                dropped.append("person: %s" % e)
                continue
            ops.append(["person.update" if name.lower() in known_people else "person.add",
                        known_people.get(name.lower(), name), rel, pn])

        for ev in _list(obj, "events", LIMITS["events"], dropped):
            if not isinstance(ev, dict):
                dropped.append("event: not an object")
                continue
            try:
                fields = [_opt_text(ev.get(k), 200) for k in
                          ("when", "situation", "feeling_before", "action", "payoff", "outcome")]
            except Bad as e:
                dropped.append("event: %s" % e)
                continue
            if not fields[3] and not fields[1]:
                dropped.append("event: no situation and no action")
                continue
            ops.append(["event.add"] + fields)

        notes = {int(k): v for k, v in ctx.get("notes", {}).items()}
        active = [(n["kind"], n["text"]) for n in notes.values() if n["status"] == "active"]
        touched = set()
        for a in _list(notes_obj, "add", LIMITS["notes_add"], dropped):
            try:
                if a.get("kind") not in NOTE_KINDS:
                    raise Bad("bad kind")
                text = _text(a.get("text"), 220)
                if len(text.split()) > 40:
                    raise Bad("over 40 words")
            except (Bad, AttributeError) as e:
                dropped.append("note.add: %s" % e)
                continue
            if close_to_deleted(text, ctx.get("deleted_notes", []), ctx):
                dropped.append("note.add: too close to a note he deleted")
                continue
            if duplicate_of_active(text, a["kind"], active, ctx):
                dropped.append("note.add: duplicate of an active note")
                continue
            ev = _verify_evidence(a.get("evidence"), his, facts, dropped, "note.add")
            if not ev:
                dropped.append("note.add: no verified evidence")
                continue
            ops.append(["note.add", a["kind"], text, "guess", ev])
            active.append((a["kind"], text))

        for u in _list(notes_obj, "update", LIMITS["notes_update"], dropped):
            nid = u.get("id") if isinstance(u, dict) else None
            if isinstance(nid, bool) or not isinstance(nid, int) or nid not in notes:
                dropped.append("note.update: unknown id")
                continue
            n = notes[nid]
            if n["status"] != "active" or nid in touched:
                dropped.append("note.update %d: not active or already changed" % nid)
                continue
            ev = _verify_evidence(u.get("evidence", []), his, facts, dropped, "note.update %d" % nid)
            text = None
            if u.get("text") is not None and not n["edited"] and n["confidence"] != "confirmed":
                try:
                    text = _text(u["text"], 220)
                except Bad as e:
                    dropped.append("note.update %d: %s" % (nid, e))
                    continue
                if close_to_deleted(text, ctx.get("deleted_notes", []), ctx):
                    dropped.append("note.update %d: new text too close to a note he deleted" % nid)
                    continue
            elif u.get("text") is not None and u.get("text") != n["text"]:
                dropped.append("note.update %d: his own note, text kept" % nid)
            conf = n["confidence"]
            want = u.get("confidence", conf)
            if want in CONF and CONF.index(want) < CONF.index(conf) and not n["edited"] and conf != "confirmed":
                conf = want                                  # lowering is always safe
            elif want == "likely" and conf == "guess" and u.get("seen_again") is True and ev:
                conf = "likely"                              # one step, with new evidence
            elif want != conf:
                dropped.append("note.update %d: confidence change to %s not allowed" % (nid, want))
            seen = u.get("seen_again") is True and bool(ev)
            if text is None and conf == n["confidence"] and not ev:
                dropped.append("note.update %d: changes nothing" % nid)
                continue
            touched.add(nid)
            ops.append(["note.update", nid, text, conf, seen, ev])

        for rid in _list(notes_obj, "resolve", 5, dropped):
            if isinstance(rid, bool) or not isinstance(rid, int) or rid not in notes:
                dropped.append("note.resolve: unknown id")
            elif notes[rid]["status"] != "active" or notes[rid]["edited"] or notes[rid]["confidence"] == "confirmed" \
                    or rid in touched:
                dropped.append("note.resolve %d: protected or already changed" % rid)
            else:
                touched.add(rid)
                ops.append(["note.retire", rid])

        for q in _list(obj, "his_words", LIMITS["his_words"], dropped):
            if isinstance(q, str) and quote_is_his(q, his):
                ops.append(["quote.add", q.strip()])
            else:
                dropped.append("his_words: not found word for word")

        everything_his = his + ctx.get("his_earlier_quotes", [])
        for dsg in _list(obj, "disagreements", LIMITS["disagreements"], dropped):
            try:
                claim = _text(dsg.get("claim"), 200)
            except (Bad, AttributeError) as e:
                dropped.append("disagreement: %s" % e)
                continue
            if isinstance(dsg.get("his_position"), str) and quote_is_his(dsg["his_position"], his):
                ops.append(["disagreement.add", claim, dsg["his_position"].strip()])
            else:
                dropped.append("disagreement: his position not found word for word")

        for c in _list(obj, "contradictions", LIMITS["contradictions"], dropped):
            if isinstance(c, dict) and all(isinstance(c.get(k), str) and quote_is_his(c[k], everything_his)
                                           for k in ("quote_a", "quote_b")) and norm(c["quote_a"]) != norm(c["quote_b"]):
                ops.append(["contradiction.add", c["quote_a"].strip(), c["quote_b"].strip()])
            else:
                dropped.append("contradiction: quotes not found word for word")

        for tag in _list(obj, "ideas_used", LIMITS["ideas"], dropped):
            try:
                ops.append(["idea.add", _text(tag, 40)])
            except Bad:
                dropped.append("idea: bad tag")

        fs = obj.get("focus_summary")
        if fs is not None:
            if not ctx.get("in_focus"):
                dropped.append("focus_summary: this chat is not in a focus")
            else:
                try:
                    fs = _text(fs, 2000, multiline=True)
                    if len(fs.split()) > 260:
                        raise Bad("over 260 words")
                    ops.append(["focus.summary", fs])
                except Bad as e:
                    dropped.append("focus_summary: %s" % e)

        ops.append(["conversation.summary", summary])
    except Reject as e:
        return {"ok": False, "ops": [], "dropped": [str(e)]}
    return {"ok": True, "ops": ops, "dropped": dropped}


def apply_gardening(raw, ctx):
    """ctx: notes {id: {kind, confidence, status, edited, times_seen}}, people [id], profile {key: {confirmed}},
    deleted_notes [text] and vectors {text: unit vector} (both optional): no merged or rewritten text may come
    close to a note he deleted (rule 5), in words or in meaning."""
    deleted = ctx.get("deleted_notes", [])
    dropped, ops, used = [], [], set()
    try:
        obj = loads_strict(raw.strip())
    except (ValueError, Bad) as e:
        return {"ok": False, "ops": [], "dropped": ["not strict JSON: %s" % e]}
    if not isinstance(obj, dict):
        return {"ok": False, "ops": [], "dropped": ["not an object"]}
    notes = {int(k): v for k, v in ctx["notes"].items()}

    def free(i):
        n = notes.get(i)
        return (n is not None and n["status"] == "active" and not n["edited"] and n["confidence"] != "confirmed"
                and i not in used)

    def lst(key):
        v = obj.get(key, [])
        return v if isinstance(v, list) else []

    for m in lst("merge"):
        ids = m.get("ids") if isinstance(m, dict) else None
        # types first: set() of an object or a list would crash instead of dropping the item
        if not isinstance(ids, list) or len(ids) < 2 or \
                not all(isinstance(i, int) and not isinstance(i, bool) for i in ids) or \
                len(set(ids)) != len(ids) or not all(free(i) for i in ids):
            dropped.append("merge: ids missing, protected or already used")
            continue
        kinds = {notes[i]["kind"] for i in ids}
        if len(kinds) != 1 or not isinstance(m.get("kind"), str) or m.get("kind") not in kinds:
            dropped.append("merge: notes of different kinds")
            continue
        top = max((notes[i]["confidence"] for i in ids), key=CONF.index)
        if m.get("confidence") != top:
            dropped.append("merge: confidence must be the highest existing (%s)" % top)
            continue
        try:
            text = _text(m.get("text"), 220)
        except Bad as e:
            dropped.append("merge: %s" % e)
            continue
        if close_to_deleted(text, deleted, ctx):
            dropped.append("merge: too close to a note he deleted")
            continue
        used.update(ids)
        ops.append(["note.merge", sorted(ids), text, top, sum(notes[i]["times_seen"] for i in ids)])

    for r in lst("rewrite"):
        i = r.get("id") if isinstance(r, dict) else None
        if not isinstance(i, int) or isinstance(i, bool) or not free(i):
            dropped.append("rewrite: id missing, protected or already used")
            continue
        try:
            text = _text(r.get("text"), 220)
        except Bad as e:
            dropped.append("rewrite: %s" % e)
            continue
        if close_to_deleted(text, deleted, ctx):
            dropped.append("rewrite: too close to a note he deleted")
            continue
        used.add(i)
        ops.append(["note.rewrite", i, text])

    for r in lst("retire"):
        i = r.get("id") if isinstance(r, dict) else None
        if not isinstance(i, int) or isinstance(i, bool) or not free(i):
            dropped.append("retire: id missing, protected or already used")
            continue
        used.add(i)
        ops.append(["note.retire", i])

    people = set(ctx.get("people", []))
    used_people = set()
    for m in lst("merge_people"):
        ids = m.get("ids") if isinstance(m, dict) else None
        # whole numbers only: 10.0 would otherwise match person 10
        if not isinstance(ids, list) or len(ids) < 2 or \
                not all(isinstance(i, int) and not isinstance(i, bool) for i in ids) or \
                len(set(ids)) != len(ids) or not all(i in people and i not in used_people for i in ids):
            dropped.append("merge_people: unknown or repeated ids")
            continue
        try:
            name = _text(m.get("name"), 60)
            rel, pn = _opt_text(m.get("relation"), 60), _opt_text(m.get("notes"), 200)
        except Bad as e:
            dropped.append("merge_people: %s" % e)
            continue
        used_people.update(ids)
        ops.append(["person.merge", sorted(ids), name, rel, pn])

    for p in lst("rewrite_profile"):
        key = p.get("key") if isinstance(p, dict) else None
        entry = ctx.get("profile", {}).get(key) if isinstance(key, str) else None
        if entry is None or entry["confirmed"]:
            dropped.append("rewrite_profile: unknown or confirmed key")
            continue
        try:
            ops.append(["profile.set", key, _text(p.get("value"), 300), False])
        except Bad as e:
            dropped.append("rewrite_profile: %s" % e)
    return {"ok": True, "ops": ops, "dropped": dropped}


CLUSTER_MEANING = 0.85   # cosine at or above which two notes are suggested to gardening as one
CLUSTER_WORDS = 0.5      # or this share of words in common
CLUSTER_SIZE = 4         # at most this many notes in one suggestion
CLUSTERS = 10            # at most this many suggestions


def gardening_clusters(notes, ctx):
    """Notes the code finds close, for gardening to consider merging (Max plan Phase 3 step 5). Suggestions only:
    the model decides, and apply_gardening checks every merge as before. notes: {id: {kind, confidence, status,
    edited, text}}. Only notes a job may touch (active, not edited, not confirmed), of the same kind, saying "not"
    the same way, close in meaning (CLUSTER_MEANING) or in words (CLUSTER_WORDS). The closest pairs join first,
    a group never grows past CLUSTER_SIZE; groups come out by their lowest id, each id list sorted."""
    free = sorted(int(i) for i, n in notes.items()
                  if n["status"] == "active" and not n["edited"] and n["confidence"] != "confirmed")
    text = {i: notes[str(i)]["text"] if str(i) in notes else notes[i]["text"] for i in free}
    kind = {i: notes[str(i)]["kind"] if str(i) in notes else notes[i]["kind"] for i in free}
    pairs = []
    for x in range(len(free)):
        for y in range(x + 1, len(free)):
            a, b = free[x], free[y]
            if kind[a] != kind[b] or negated(text[a]) != negated(text[b]):
                continue
            m = meaning(ctx, text[a], text[b])
            w = similarity(text[a], text[b])
            if (m is not None and m >= CLUSTER_MEANING) or w >= CLUSTER_WORDS:
                pairs.append((m if m is not None else w, a, b))
    pairs.sort(key=lambda p: (-p[0], p[1], p[2]))
    group = {i: [i] for i in free}
    for _, a, b in pairs:
        ga, gb = group[a], group[b]
        if ga is gb or len(ga) + len(gb) > CLUSTER_SIZE:
            continue
        ga.extend(gb)
        for i in gb:
            group[i] = ga
    seen, out = set(), []
    for i in free:
        g = group[i]
        if len(g) >= 2 and id(g) not in seen:
            seen.add(id(g))
            out.append(sorted(g))
    return out[:CLUSTERS]


def _plain_text_problems(text):
    probs = []
    if "[[" in text or "]]" in text:
        probs.append("brackets")
    if "—" in text or "–" in text:
        probs.append("em dash")
    if re.search(r"(?m)^\s*([-*•]\s|\d+[.)]\s|#+\s)", text) or "**" in text:
        probs.append("markdown list, heading or bold")
    return probs


BRIEF_WORDS = (15, 90)   # the morning brief: a few lines, never a letter


def check_text_job(kind, text, inputs, his_messages):
    """Verify AI-written text before it is published: weekly review note, letter, chapter, morning brief.
    inputs = every string the model was given (for NumberCheck). Returns {"ok", "problems", "title", "body"}."""
    text = text.strip()
    title, body = "", text
    probs = []
    if kind in ("letter", "chapter"):
        title, _, body = text.partition("\n")
        title, body = title.strip(), body.strip()
        if not title or len(title) > 100:
            probs.append("title missing or over 100 characters")
    lo, hi = {"review_weekly": (30, 230), "letter": (120, 700), "chapter": (120, 700), "brief": BRIEF_WORDS}[kind]
    n = len(body.split())
    if not lo <= n <= hi:
        probs.append("length %d words is outside %d to %d" % (n, lo, hi))
    probs += _plain_text_problems(body if kind != "chapter" else re.sub(r"(?m)^\s*-\s", "", body))
    q = verify_quotes_in_text(text, his_messages)
    if not q["ok"]:
        probs.append("quotes not his: %s" % "; ".join(q["bad"]))
    nc = number_check(text, inputs)
    if not nc["ok"]:
        probs.append("numbers not in the inputs: %s" % ", ".join(nc["bad"]))
    return {"ok": not probs, "problems": probs, "title": title, "body": body}


def apply_monthly_review(raw, known_ids, inputs, his_messages):
    try:
        obj = loads_strict(raw.strip())
    except (ValueError, Bad) as e:
        return {"ok": False, "problems": ["not strict JSON: %s" % e]}
    probs = []
    if not isinstance(obj, dict):
        return {"ok": False, "problems": ["not an object"]}
    head, narr, rev = obj.get("headline"), obj.get("narrative"), obj.get("revisit", [])
    if not isinstance(head, str) or not head.strip() or len(head.split()) > 16:
        probs.append("headline missing or too long")
    if not isinstance(narr, str) or not 100 <= len(narr.split()) <= 350:
        probs.append("narrative missing or outside 100 to 350 words")
    if not isinstance(rev, list) or len(rev) > 3:
        probs.append("revisit must be a list of at most 3")
        rev = []
    out = []
    for r in rev:
        ref = r.get("ref") if isinstance(r, dict) else "bad"
        qn = r.get("question") if isinstance(r, dict) else None
        if ref is not None and ref not in known_ids:
            probs.append("revisit: unknown ref")
        elif not isinstance(qn, str) or not qn.strip() or len(qn) > 200 or "[[" in qn:
            probs.append("revisit: bad question")
        else:
            out.append({"ref": ref, "question": qn.strip()})
    if probs:
        return {"ok": False, "problems": probs}
    all_text = "\n".join([head, narr] + [r["question"] for r in out])
    probs += _plain_text_problems(narr)
    q = verify_quotes_in_text(all_text, his_messages)
    if not q["ok"]:
        probs.append("quotes not his: %s" % "; ".join(q["bad"]))
    nc = number_check(all_text, inputs + list(known_ids))
    if not nc["ok"]:
        probs.append("numbers not in the inputs: %s" % ", ".join(nc["bad"]))
    return {"ok": not probs, "problems": probs}


STORY_WORDS = (40, 120)
STORY_OVERCAME = 3
STORY_TITLE_CHARS = 60
STORY_LINE_CHARS = 200


def _quote_id(v, days):
    """A quote reference: null, or the id of a quote the model was given (never a bool, never a guess)."""
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, int) or v not in days:
        raise Bad("unknown quote id")
    return v


def _story_line(v, maxlen):
    if not isinstance(v, str) or not v.strip() or len(v) > maxlen or "\n" in v.strip() or "[[" in v:
        raise Bad("missing, too long or not one line")
    return v.strip()


def apply_story(raw, quotes, inputs, his_messages):
    """The monthly story job (spec 2.5 and 8). quotes = [{"id", "day"}] the model was given; inputs = every
    string it was given (NumberCheck). The short version must pass or nothing is published. The quote pair and
    each overcame item are checked on their own: a bad one is dropped and named in "dropped", the rest kept.
    Returns {"ok", "problems", "short", "then_quote", "now_quote", "overcame", "dropped"}."""
    fail = {"ok": False, "short": "", "then_quote": None, "now_quote": None, "overcame": [], "dropped": []}
    try:
        obj = loads_strict(raw.strip())
    except (ValueError, Bad) as e:
        return dict(fail, problems=["not strict JSON: %s" % e])
    if not isinstance(obj, dict):
        return dict(fail, problems=["not an object"])
    short = obj.get("short")
    probs = []
    if not isinstance(short, str) or not STORY_WORDS[0] <= len(short.split()) <= STORY_WORDS[1]:
        probs.append("short missing or outside %d to %d words" % STORY_WORDS)
    else:
        short = short.strip()
        probs += _plain_text_problems(short)
        q = verify_quotes_in_text(short, his_messages)
        if not q["ok"]:
            probs.append("quotes not his: %s" % "; ".join(q["bad"]))
        nc = number_check(short, inputs)
        if not nc["ok"]:
            probs.append("numbers not in the inputs: %s" % ", ".join(nc["bad"]))
    if probs:
        return dict(fail, problems=probs)
    days = {q["id"]: q["day"] for q in quotes}
    dropped = []
    then_q = now_q = None
    try:
        then_q = _quote_id(obj.get("then_quote"), days)
        now_q = _quote_id(obj.get("now_quote"), days)
        if (then_q is None) != (now_q is None):
            raise Bad("then and now come together")
        if then_q is not None and not days[then_q] < days[now_q]:
            raise Bad("the then quote must be from an earlier day than the now quote")
    except Bad as e:
        dropped.append("quotes: %s" % e)
        then_q = now_q = None
    items = obj.get("overcame", [])
    if not isinstance(items, list):
        dropped.append("overcame: not a list")
        items = []
    if len(items) > STORY_OVERCAME:
        dropped.append("overcame: more than %d, the rest dropped" % STORY_OVERCAME)
        items = items[:STORY_OVERCAME]
    out = []
    for i, it in enumerate(items):
        try:
            if not isinstance(it, dict):
                raise Bad("not an object")
            item = {"title": _story_line(it.get("title"), STORY_TITLE_CHARS),
                    "then_quote": _quote_id(it.get("then_quote"), days),
                    "did": _story_line(it.get("did"), STORY_LINE_CHARS),
                    "now": _story_line(it.get("now"), STORY_LINE_CHARS)}
            text = "\n".join([item["title"], item["did"], item["now"]])
            if _plain_text_problems(text):
                raise Bad("not plain text")
            if not verify_quotes_in_text(text, his_messages)["ok"]:
                raise Bad("a quotation that is not his")
            if not number_check(text, inputs)["ok"]:
                raise Bad("a number not in the inputs")
            out.append(item)
        except Bad as e:
            dropped.append("overcame %d: %s" % (i + 1, e))
    return {"ok": True, "problems": [], "short": short, "then_quote": then_q, "now_quote": now_q,
            "overcame": out, "dropped": dropped}
