"""Reference implementation of the card parser: the executable spec for the Kotlin CardParser.

The Kotlin parser must pass every case in contracts/card_vectors.json. This file passes them too,
so a disagreement between the two is a bug in one of them, found by a test, not on the phone.

Design: FAIL CLOSED. A card that is not exactly right is dropped (and logged by the app without content).
A dropped card never changes state. Prose is never lost because of a bad card.

Strictness the Kotlin port must copy exactly: strict JSON (no duplicate keys, no NaN/Infinity), finite numbers
up to MAX_NUMBER, no control characters in strings, the planner's schedule grammar, and a plan card is only
valid when the app offered review options in PLAN_STATE (known["review_options"]).
"""
import json
import math
import re

from planner import parse_schedule, PlannerError  # one schedule grammar for cards and planner

CARD_TYPES = ["promise", "plan", "target_change", "backlog", "focus", "note", "mode"]
CHAT_TYPES = {
    "free": ["promise", "focus", "mode", "note"],
    "untangle": ["mode", "note"],
    "decide": ["promise", "mode", "note"],
    "plan": ["plan", "target_change", "backlog", "promise", "mode", "note"],
}
MAX_CARDS = 3
MAX_NUMBER = 100000        # no target, minimum or total is ever this large; bigger means a broken card
MAX_WEEKS = 52             # a ramp step lasts at most a year
LINE_RE = re.compile(r"^\[\[suggest:\s*(\{.*\})\s*\]\]$")
CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
ID_RE = {"goal": re.compile(r"^G\d+$"), "target": re.compile(r"^T\d+$"),
         "backlog": re.compile(r"^B\d+$"), "focus": re.compile(r"^F\d+$")}
NOTE_KINDS = ["pattern", "what_helps", "what_doesnt", "value", "thread"]
MODE_TARGETS = ["free", "untangle", "decide", "plan", "focus"]


class Bad(Exception):
    pass


def _no_duplicate_keys(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise Bad("duplicate key")  # parsers disagree on which copy wins, so neither does
    return dict(pairs)


def _no_constants(name):
    raise Bad("not a JSON number " + name)  # NaN, Infinity, -Infinity are not JSON


def loads_strict(text):
    """Strict JSON: no duplicate keys, no NaN or Infinity. The Kotlin parser must behave the same."""
    return json.loads(text, object_pairs_hook=_no_duplicate_keys, parse_constant=_no_constants)


def _str(d, key, maxlen, required=True):
    v = d.get(key)
    if v is None:
        if required:
            raise Bad("missing " + key)
        return None
    if not isinstance(v, str) or not v.strip():
        raise Bad("bad " + key)
    if len(v) > maxlen:
        raise Bad("too long " + key)
    if CONTROL_RE.search(v):
        raise Bad("control character in " + key)
    if "[[" in v or "]]" in v:
        raise Bad("brackets in " + key)
    return v.strip()


def _num(d, key, required=True, minimum=0.0, strict_positive=False):
    v = d.get(key)
    if v is None:
        if required:
            raise Bad("missing " + key)
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise Bad("not a number " + key)
    if not math.isfinite(v) or abs(v) > MAX_NUMBER:
        raise Bad("out of range " + key)
    if strict_positive and v <= 0:
        raise Bad("must be above zero " + key)
    if v < minimum:
        raise Bad("too small " + key)
    return v


def _id(d, key, kind, known, required=True):
    v = d.get(key)
    if v is None:
        if required:
            raise Bad("missing " + key)
        return None
    if not isinstance(v, str) or not ID_RE[kind].match(v):
        raise Bad("bad id " + key)
    if v not in known.get(kind, set()):
        raise Bad("unknown id " + v)
    return v


def _schedule(v):
    if not isinstance(v, str):
        raise Bad("bad schedule")
    try:
        parse_schedule(v)
    except PlannerError:
        raise Bad("bad schedule")
    return v.strip()


def _target_item(t):
    if not isinstance(t, dict):
        raise Bad("target item")
    out = {"name": _str(t, "name", 60), "target": _num(t, "target", strict_positive=True)}
    mn = _num(t, "min", required=False)
    if mn is not None:
        if mn > out["target"]:
            raise Bad("min above target")
        out["min"] = mn
    out["schedule"] = _schedule(t.get("schedule"))
    for k in ("unit", "start", "end"):
        s = _str(t, k, 40, required=False)
        if s:
            out[k] = s
    # A ramp step: how many weeks this target lasts. The app works out its end, and the next target with no
    # start begins the day after (plan_dates.check_card); the coach never counts the dates.
    w = t.get("weeks")
    if w is not None:
        if isinstance(w, bool) or not isinstance(w, (int, float)) or not math.isfinite(w) or w != int(w):
            raise Bad("weeks must be a whole number")
        if not 1 <= w <= MAX_WEEKS:
            raise Bad("weeks out of range")
        if "end" in out:
            raise Bad("weeks and end together")
        out["weeks"] = int(w)
    return out


def _backlog_item(b):
    if not isinstance(b, dict):
        raise Bad("backlog item")
    return {"name": _str(b, "name", 60), "total": _num(b, "total", strict_positive=True),
            "unit": _str(b, "unit", 20), "deadline": _str(b, "deadline", 40)}


def validate(card, known):
    """Return a normalized card dict or raise Bad."""
    if not isinstance(card, dict):
        raise Bad("not an object")
    t = card.get("type")
    if t not in CARD_TYPES:
        raise Bad("unknown type")
    out = {"type": t}
    if t == "promise":
        out["text"] = _str(card, "text", 140)
        out["when"] = _str(card, "when", 40)
        r = card.get("remind", False)
        if not isinstance(r, bool):
            raise Bad("remind must be true or false")
        out["remind"] = r
        why = _str(card, "why", 140, required=False)
        if why:
            out["why"] = why
    elif t == "plan":
        out["title"] = _str(card, "title", 80)
        out["rationale"] = _str(card, "rationale", 300)
        g = _id(card, "goal", "goal", known, required=False)
        if g:
            out["goal"] = g
        targets = card.get("targets", [])
        backlog = card.get("backlog", [])
        if not isinstance(targets, list) or not isinstance(backlog, list):
            raise Bad("targets and backlog must be lists")
        if len(targets) > 4 or len(backlog) > 2:
            raise Bad("too many items")
        if not targets and not backlog:
            raise Bad("plan needs a target or a backlog item")
        out["targets"] = [_target_item(x) for x in targets]
        out["backlog"] = [_backlog_item(x) for x in backlog]
        rd = _str(card, "review_date", 40)
        opts = known.get("review_options")
        if not opts:
            raise Bad("no review options were offered")  # fail closed: never accept a date the app did not offer
        if rd not in opts:
            raise Bad("review_date is not one of the review options")
        out["review_date"] = rd
    elif t == "target_change":
        out["target"] = _id(card, "target", "target", known)
        changed = False
        for k in ("target_value", "min"):
            v = _num(card, k, required=False, strict_positive=(k == "target_value"))
            if v is not None:
                out[k] = v
                changed = True
        if "target_value" in out and "min" in out and out["min"] > out["target_value"]:
            raise Bad("min above target")
        # when only one of the two is given, the app checks it against the stored value at save time
        if "schedule" in card:
            out["schedule"] = _schedule(card["schedule"])
            changed = True
        e = _str(card, "end", 40, required=False)
        if e:
            out["end"] = e
            changed = True
        if not changed:
            raise Bad("target_change changes nothing")
        out["reason"] = _str(card, "reason", 200)
    elif t == "backlog":
        b = _id(card, "backlog", "backlog", known, required=False)
        if b:
            out["backlog"] = b
        else:
            out["name"] = _str(card, "name", 60)
            out["unit"] = _str(card, "unit", 20)
        out["total"] = _num(card, "total", required=not b, strict_positive=True)
        out["deadline"] = _str(card, "deadline", 40, required=not b)
        if b and out["total"] is None and out["deadline"] is None:
            raise Bad("backlog change changes nothing")
        out["reason"] = _str(card, "reason", 200)
        out = {k: v for k, v in out.items() if v is not None}
    elif t == "focus":
        out["name"] = _str(card, "name", 60)
        out["why"] = _str(card, "why", 200)
    elif t == "note":
        k = card.get("kind")
        if k not in NOTE_KINDS:
            raise Bad("bad note kind")
        out["kind"] = k
        out["text"] = _str(card, "text", 200)
    elif t == "mode":
        to = card.get("to")
        if to not in MODE_TARGETS:
            raise Bad("bad mode")
        out["to"] = to
        if to == "focus":
            out["focus"] = _id(card, "focus", "focus", known)
    return out


def parse_reply(text, chat_type, known=None):
    """Split a model reply into (prose, cards, dropped).

    Only trailing lines of the exact form  [[suggest: {...}]]  can become cards.
    Any other line containing "[[" is removed from the prose (never shown raw) and counted as dropped.
    """
    known = known or {}
    allowed = CHAT_TYPES[chat_type]
    lines = text.replace("\r\n", "\n").split("\n")
    # trailing block of card-looking lines (blank lines between them are tolerated)
    end = len(lines)
    while end > 0 and not lines[end - 1].strip():
        end -= 1
    start = end
    while start > 0 and (lines[start - 1].strip().startswith("[[suggest:") or lines[start - 1].strip() == "[[cards: none]]" or not lines[start - 1].strip()):
        start -= 1
    tail = [l.strip() for l in lines[start:end] if l.strip()]
    head = lines[:start]
    cards, dropped, seen = [], [], {}
    for raw in tail:
        if raw == "[[cards: none]]":
            continue
        m = LINE_RE.match(raw)
        if not m:
            dropped.append("shape")
            continue
        try:
            obj = loads_strict(m.group(1))
            card = validate(obj, known)
        except (ValueError, Bad) as e:
            dropped.append(str(e))
            continue
        t = card["type"]
        if t not in allowed:
            dropped.append("not allowed in " + chat_type)
            continue
        limit = 2 if t == "promise" else 1
        if seen.get(t, 0) >= limit:
            dropped.append("duplicate " + t)
            continue
        if len(cards) >= MAX_CARDS:
            dropped.append("too many cards")
            continue
        seen[t] = seen.get(t, 0) + 1
        cards.append(card)
    clean = []
    for l in head:
        if "[[" in l or "]]" in l:
            dropped.append("stray brackets in prose")
            continue
        clean.append(l)
    # a code fence left empty by removing card lines is noise: drop the empty pair
    out = []
    for l in clean:
        if l.strip().startswith("```") and out and out[-1].strip().startswith("```"):
            out.pop()
            continue
        out.append(l)
    prose = "\n".join(out).rstrip()
    return prose, cards, dropped
