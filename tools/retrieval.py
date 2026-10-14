"""Reference implementation of the pure part of retrieval (RELEVANT_PAST, contracts/context.md): which words are
searched, how a match is scored, how the result lists are merged and which results are sent. The executable spec
for Retrieval.kt; the shared cases are contracts/retrieval_vectors.json. tools/eval/memory_exam.py and
memory_exam2.py measure retrieval with exactly these functions.

What changed in Max plan Phase 2 (docs/MAX-PLAN.md):
1. Search with the conversation, not just the last message. Besides the new message, the app searches a second
   query made of his last few messages in this conversation and the new one (`conversation_query`), so "yeah,
   same again" still finds the past it points at. The new message's own results weigh more.
2. Time and outcome words are read in code (tools/recall_time.py). When the message names a period, what falls in
   it is a result list of its own (`dated`), ordered here.
3. Results are merged by weighted reciprocal rank fusion (`fuse`), near-duplicates are left out, at most
   MAX_PER_CONVERSATION results come from one conversation (a pattern spread over a year needs its months, not
   five lines of one evening), and as many as MAX_RESULTS are sent (`choose_final`); the 4,000-token budget of
   Part 3 drops the weakest first.

Retrieval finds and ranks only. It never writes text and never changes a note's confidence (spec section 7).
Pure functions only.
"""
import re
import unicodedata

MAX_TERMS = 8
MAX_RESULTS = 12          # was 5 (0.1.12)
MAX_PER_CONVERSATION = 1  # results from one conversation (its messages, summary, quotes and events)
DATED_MAX = 10            # results the period list brings
CONTEXT_MESSAGES = 3      # his earlier messages in this conversation that join the conversation query
CANDIDATES_PER_TERM = 100
FUSE_DEPTH = 20
MEANING_CANDIDATES = 80   # meaning matches scored exactly before identical texts are counted once (was 40)
RARE_SHARE = 0.05         # a word found in at most this share of the index is rare enough to match alone
NEAR_DUPLICATE = 0.8      # share of word stems two results have in common to count as the same thing
RRF_K = 60

# How much each result list counts in the fusion. The period list is the most exact signal there is: it is
# read in code from his own words ("in August"). The conversation query helps a vague message and must never
# outweigh what the new message itself asks.
W_DATED = 2.0
W_NEW = 1.0
W_CONTEXT = 0.5

STOP = set("""about after again also always because been before being could didn does doesn doing done dont even ever
every from going gonna have having here into just know like make many maybe more most much need never only other
really said same should some still such sure than that their them then there these they thing things think this those
today very want wanna well were what when where which while will with would yeah your yours okay
the and you for but not are was has had his her she him our out can all any get got did its one too now how why who let
yes lol way off lot bit use see say put new own try ive don isn won ain hey huh omg idk btw may yet via per etc ill
whom youre cant wont didnt isnt thats
happen happens happened happening tell told""".split())
WORD = re.compile(r"[a-z0-9]+")


def norm(s):
    """Mirror of TextCheck.norm."""
    s = unicodedata.normalize("NFKC", s).lower()
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    return " ".join(s.split())


def terms(text):
    """The words worth searching for, longest first (longer words say more), at most MAX_TERMS."""
    out = []
    for w in WORD.findall(norm(text)):
        if len(w) >= 3 and w not in STOP and not w.isdigit() and w not in out:
            out.append(w)
    return sorted(out, key=lambda w: (-len(w), w))[:MAX_TERMS]


def stem(word):
    """A simple stem, so the forms of one word meet ("lectures", "lecturing": "lectur")."""
    w = word.lower()
    if len(w) > 4 and (w.endswith("ies") or w.endswith("ied")):
        w = w[:-3] + "y"
    elif len(w) > 5 and w.endswith("ing"):
        w = w[:-3]
    elif len(w) > 4 and w.endswith("ed"):
        w = w[:-2]
    elif len(w) > 3 and w.endswith("s") and not w.endswith("ss") and not w.endswith("us") and not w.endswith("is"):
        w = w[:-1]
    if len(w) > 3 and w[-1] == w[-2] and w[-1] not in "lsz":
        w = w[:-1]
    if len(w) > 3 and w.endswith("e"):
        w = w[:-1]
    return w


def term_query(term):
    """The FTS4 MATCH expression for one word: a prefix search on its stem, body and title only."""
    s = stem(term)
    prefix = s[:-1] if len(s) > 3 and s.endswith("y") else s
    return "body:%s* OR title:%s*" % (prefix, prefix)


def stems(text):
    return {stem(w) for w in WORD.findall(norm(text))}


def score(text, ts):
    """How many of the terms appear in the text, in any of their forms."""
    st = stems(text)
    return sum(1 for t in ts if stem(t) in st)


def strong(s, n):
    """A keyword match is strong when it shares at least two of the searched words (or the only one there is)."""
    return s >= min(2, n) and s > 0


def word_count(text):
    return len(WORD.findall(norm(text)))


def rare_terms(ts, found, total):
    """The searched words that are rare in everything kept: found {term: rows its search read (at most
    CANDIDATES_PER_TERM)}, total the rows in the index. A word in at most RARE_SHARE of the rows says a lot
    ("power", "physio"); one in many says little ("study")."""
    if total <= 0:
        return set()
    return {t for t in ts if 0 < found.get(t, 0) < CANDIDATES_PER_TERM and found[t] <= RARE_SHARE * total}


def choose(candidates, ts, max_results=None, rare=()):
    """Keyword results: candidates [{key, text, at}], the strong ones, the same text once, the most shared words
    first, then the most recent. A match of a single rare word ([rare_terms]) also counts, after the strong
    ones. Returns keys."""
    max_results = FUSE_DEPTH if max_results is None else max_results
    seen, out = set(), []
    rare_stems = {stem(t) for t in rare}
    for c in candidates:
        s = score(c["text"], ts)
        if not strong(s, len(ts)):
            if not (s == 1 and stems(c["text"]) & rare_stems):
                continue
        t = norm(c["text"])
        if t in seen:
            continue
        seen.add(t)
        out.append((c, s))
    out.sort(key=lambda p: (-p[1], -p[0]["at"]))
    return [c["key"] for c, _ in out][:max_results]


def distinct_texts(keys, texts):
    """Keys in order, each text once (the first, best one, is kept). Small talk repeats itself word for word
    ("Feeling low energy, not sure why."), and twenty copies of one line must not fill a result list."""
    seen, out = set(), []
    for k in keys:
        t = norm(texts[k])
        if t not in seen:
            seen.add(t)
            out.append(k)
    return out


def conversation_query(new, earlier, title=""):
    """The second query (step 1): his last CONTEXT_MESSAGES messages in this conversation before the new one,
    oldest first, then the new one, after the conversation's title when it has one. None when the conversation
    has nothing before the new message: the new message alone is searched then."""
    before = [m for m in earlier if m.strip()][-CONTEXT_MESSAGES:]
    if not before and not title.strip():
        return None
    return " ".join(([title.strip()] if title.strip() else []) + before + [new])


def fuse(lists):
    """Weighted reciprocal rank fusion. lists: [(weight, [key, ...best first]), ...]. A key scores the sum of
    weight / (RRF_K + rank) over the lists it is in. Ties keep the order of first appearance."""
    sc = {}
    for weight, keys in lists:
        for i, k in enumerate(keys):
            sc[k] = sc.get(k, 0.0) + weight / (RRF_K + i + 1)
    order = {k: i for i, k in enumerate(sc)}
    return sorted(sc, key=lambda k: (-sc[k], order[k]))


def near_duplicate(a, b):
    """Two texts are the same thing when most of their word stems are shared (Jaccard of the stem sets)."""
    sa, sb = stems(a), stems(b)
    if not sa or not sb:
        return norm(a) == norm(b)
    return len(sa & sb) / len(sa | sb) >= NEAR_DUPLICATE


def choose_final(keys, docs, max_results=None):
    """What RELEVANT_PAST sends, from the fused keys (best first). docs: {key: {text, conversation}}, where
    conversation is None for what belongs to no conversation (a letter, a review, a promise...). Keys without a
    doc are skipped. A near-duplicate of a result already chosen is left out, and at most MAX_PER_CONVERSATION
    results come from one conversation."""
    max_results = MAX_RESULTS if max_results is None else max_results
    out, per = [], {}
    for k in keys:
        d = docs.get(k)
        if d is None:
            continue
        c = d.get("conversation")
        if c is not None and per.get(c, 0) >= MAX_PER_CONVERSATION:
            continue
        if any(near_duplicate(d["text"], docs[o]["text"]) for o in out):
            continue
        out.append(k)
        if c is not None:
            per[c] = per.get(c, 0) + 1
        if len(out) >= max_results:
            break
    return out


KIND_ORDER = ["promise", "review", "letter", "summary", "checkin", "revision", "chapter"]


def dated(items, rest_terms, promises, outcome, max_results=None):
    """The period list (step 2): items [{key, kind, text, day}] are what falls in the period he named. Ordered by
    how many of his other searched words they share, then by kind (promises first when he asked about promises,
    and among them the outcome he named first), then the most recent day. When he named other words, only the
    items sharing at least one of them come, unless none does (then the period alone chooses). Returns keys."""
    max_results = DATED_MAX if max_results is None else max_results
    def rank(it):
        kind = it["kind"]
        if promises and kind == "promise":
            k = -2 if outcome is not None and it.get("outcome") == outcome else -1
        else:
            k = KIND_ORDER.index(kind) if kind in KIND_ORDER else len(KIND_ORDER)
        return k

    if promises:
        items = [it for it in items if it["kind"] == "promise"] or items
    scored = [(it, score(it["text"], rest_terms) if rest_terms else 0) for it in items]
    if rest_terms and any(s > 0 for _, s in scored):
        scored = [p for p in scored if p[1] > 0]
    scored.sort(key=lambda p: (-p[1], rank(p[0]), _neg_day(p[0]["day"]), key_text(p[0]["key"])))
    return [it["key"] for it, _ in scored][:max_results]


def key_text(key):
    """A key as the app writes it, "kind:id", for the last tie-break."""
    return "%s:%s" % key if isinstance(key, tuple) else key


def _neg_day(day):
    """Sorts the most recent day first."""
    return -int(day.replace("-", ""))


# ---- what each kind of record reads like in search and in RELEVANT_PAST (SearchText in Kotlin) ----
# Phase 2 step 4: everything they keep is searchable, not only messages, summaries, quotes, events, retired notes
# and promises. Each text starts with what the record is ("Letter", "Goal", "Check-in"), so a question that names
# the kind of thing ("which letter...", "my September review") finds it, and dates are written out with the month
# name, so "in August" also matches by word.

CHECKIN_DAYS = 7  # the period list brings single check-ins only for a period of at most a week

MONTH_ABBR = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
MONTH_FULL = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
              "November", "December"]


def day_text(day):
    """2026-10-05 -> 5 Oct 2026."""
    y, m, d = (int(x) for x in day.split("-"))
    return "%d %s %d" % (d, MONTH_ABBR[m - 1], y)


def amount_text(x):
    """2.0 -> 2, 1.5 -> 1.5."""
    x = float(x)
    return str(int(x)) if x == int(x) else ("%.2f" % x).rstrip("0")


def outcome_text(status, reason=None):
    """Mirror of PromiseText.outcome."""
    if status == "kept":
        return "done"
    if status == "not_done":
        return "not done" + ((", reason: " + reason) if reason and reason.strip() else "")
    return {"moved": "moved to a new time", "let_go": "let go"}.get(status, "no answer yet")


def promise_text(text, status, reason=None):
    """Mirror of PromiseText.searchText."""
    return "Promise: %s (%s)" % (text, outcome_text(status, reason))


def letter_text(title, body):
    return "Letter \"%s\": %s" % (title, body)


def review_text(kind, first, last, facts, narrative):
    """kind week or month; facts is the review's facts as text (ReviewFacts), narrative its note."""
    if kind == "month":
        y, m = int(first[:4]), int(first[5:7])
        head = "Month review, %s %d" % (MONTH_FULL[m - 1], y)
    else:
        head = "Week review, %s to %s" % (day_text(first), day_text(last))
    body = " ".join(x for x in (" ".join(facts.split()), " ".join(narrative.split())) if x)
    return head + (": " + body if body else "")


def chapter_text(title, first, last, body):
    return "Chapter \"%s\", %s to %s: %s" % (title, day_text(first), day_text(last), body)


GOAL_STATUS = {"active": "active", "done": "finished", "archived": "set aside"}


def goal_text(title, status, deadline):
    return "Goal \"%s\" (%s%s)" % (title, GOAL_STATUS.get(status, status),
                                   (", deadline " + day_text(deadline)) if deadline else "")


def revision_text(goal_title, day, text, reason):
    r = " ".join(reason.split()) if reason else ""
    return "Goal \"%s\" changed on %s: %s%s" % (goal_title, day_text(day), text, (". Reason: " + r) if r else "")


TARGET_STATUS = {"active": "active", "paused": "paused", "ended": "ended"}


def target_text(name, target, minimum, unit, schedule, start, end, status):
    u = (" " + unit) if unit else ""
    return "Target \"%s\": %s%s (minimum %s), %s, from %s%s, %s" % (
        name, amount_text(target), u, amount_text(minimum), schedule, day_text(start),
        (" to " + day_text(end)) if end else "", TARGET_STATUS.get(status, status))


LEVEL_TEXT = {"rest": "rest day", "not": "marked Not", "minimum": "marked Part (minimum)", "target": "done (target)",
              "beyond": "done, beyond the target"}


def checkin_text(target_name, day, level, amount, unit, reason, note):
    parts = [LEVEL_TEXT.get(level, level)]
    if level in ("minimum", "target", "beyond") or float(amount) > 0:
        parts.append("%s%s" % (amount_text(amount), (" " + unit) if unit else ""))
    if reason and reason.strip():
        parts.append("reason: " + reason.strip())
    if note and note.strip():
        parts.append("note: " + " ".join(note.split()))
    return "Check-in \"%s\", %s: %s" % (target_name, day_text(day), ", ".join(parts))
