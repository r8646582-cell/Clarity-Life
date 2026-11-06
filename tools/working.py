"""Reference implementation of the "what you are working on" rules (docs/CLARITY-V2-BUILD-SPEC.md sections 6 and 7):
the executable spec for WorkingRules.kt. contracts/working_vectors.json pins the behaviour.

Pure functions only. Dates are 'YYYY-MM-DD' strings; the caller supplies today. The AI never supplies a date:
the keeper returns the owner's own words and this code reads a date out of them, or says it is unclear.
"""
import datetime
import re

MAX_ACTIVE = 3          # the cap on active items
STALE_DAYS = 21         # no mention for this long makes an item eligible for one "still on this?" question
ROLL_OVER_DAYS = 60       # a date without a year that is further back than this means next year
MIN_EVIDENCE_DAYS = 2   # a pause or drop needs the owner's words on two separate days, or a tap

MONTHS = {
    "january": 1, "jan": 1, "february": 2, "feb": 2, "march": 3, "mar": 3, "april": 4, "apr": 4, "may": 5,
    "june": 6, "jun": 6, "july": 7, "jul": 7, "august": 8, "aug": 8, "september": 9, "sep": 9, "sept": 9,
    "october": 10, "oct": 10, "november": 11, "nov": 11, "december": 12, "dec": 12,
}
_MONTH_RE = "|".join(sorted(MONTHS, key=len, reverse=True))
_DAY = r"(\d{1,2})(?:st|nd|rd|th)?"
_DAY_MONTH = re.compile(r"\b" + _DAY + r"(?:\s+of)?\s+(" + _MONTH_RE + r")\b(?:\s*,?\s*(20\d\d))?")
_MONTH_DAY = re.compile(r"\b(" + _MONTH_RE + r")\s+" + _DAY + r"\b(?:\s*,?\s*(20\d\d))?")
_ISO = re.compile(r"\b(20\d\d)-(\d{2})-(\d{2})\b")


def _valid(y, m, d):
    try:
        return datetime.date(y, m, d)
    except ValueError:
        return None


def explicit_date(quote, today):
    """The one calendar date the owner named in [quote], or None. Only an explicit day and month count
    ("12 February", "February 12th 2027", "2027-02-12"). "Next Friday", "end of the month", "in 3 weeks" and
    "12/02" are not dates here: they stay unclear and the coach asks. Two different dates in one quote are
    ambiguous (None). A date without a year means its next occurrence on or after today; one that is only just
    past (within ROLL_OVER_DAYS) is unclear (None). A date in the past is None."""
    text = (quote or "").lower()
    t = datetime.date.fromisoformat(today)
    found = set()
    for m in _DAY_MONTH.finditer(text):
        found.add((int(m.group(1)), MONTHS[m.group(2)], m.group(3)))
    for m in _MONTH_DAY.finditer(text):
        found.add((int(m.group(2)), MONTHS[m.group(1)], m.group(3)))
    dates = set()
    for d, mo, y in found:
        if y:
            dt = _valid(int(y), mo, d)
        else:
            dt = _valid(t.year, mo, d)
            if dt is not None and dt < t:
                # Long past: surely next year ("12 February", said in November). Only just past: unclear.
                dt = _valid(t.year + 1, mo, d) if (t - dt).days > ROLL_OVER_DAYS else None
        if dt is None:
            return None
        dates.add(dt)
    for m in _ISO.finditer(text):
        dt = _valid(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        if dt is None:
            return None
        dates.add(dt)
    if len(dates) != 1:
        return None
    only = next(iter(dates))
    return only.isoformat() if only >= t else None


def propose(active_count, deadline_quote, today):
    """What the keeper's proposal becomes. At most MAX_ACTIVE items are active: a proposal beyond that is stored
    paused and unconfirmed, and the coach asks which one pauses (ask_tradeoff). A deadline is confirmed only when
    code reads an explicit date out of the owner's own words; any other words about a date make it unclear."""
    status = "active" if active_count < MAX_ACTIVE else "paused"
    quote = (deadline_quote or "").strip()
    if not quote:
        state, date = "none", None
    else:
        date = explicit_date(quote, today)
        state = "confirmed" if date else "unclear"
    return {"status": status, "confirmed": 0, "deadline_state": state, "deadline_date": date,
            "ask_tradeoff": status == "paused"}


def change_allowed(change, evidence_days, tapped, venting, active_count):
    """May the keeper make this change to an item? A mood is not a decision: nothing said in frustration or
    tiredness changes anything. A pause or a drop needs the owner's words on MIN_EVIDENCE_DAYS separate days, or
    their tap. Making an item active again needs room under the cap."""
    if venting:
        return False
    if change in ("paused", "dropped"):
        return tapped or evidence_days >= MIN_EVIDENCE_DAYS
    if change == "active":
        return active_count < MAX_ACTIVE
    if change in ("done", "deadline", "progress"):
        return True
    return False


def is_stale(last_mentioned_day, today, asked_stale):
    """Eligible for the one "still on this?" question: not mentioned for STALE_DAYS and never asked."""
    gap = (datetime.date.fromisoformat(today) - datetime.date.fromisoformat(last_mentioned_day)).days
    return gap >= STALE_DAYS and not asked_stale


def _short(day):
    d = datetime.date.fromisoformat(day)
    return "%d %s %d" % (d.day, d.strftime("%b"), d.year)


def context_line(item, today):
    """One WORKING_ON line (contracts/context.md). The day count appears only for a confirmed deadline."""
    t = datetime.date.fromisoformat(today)
    if item["deadline_state"] == "confirmed" and item.get("deadline_date"):
        dl = datetime.date.fromisoformat(item["deadline_date"])
        n = (dl - t).days
        if n > 1:
            left = "%d days left" % n
        elif n == 1:
            left = "1 day left"
        elif n == 0:
            left = "today"
        elif n == -1:
            left = "1 day ago"
        else:
            left = "%d days ago" % -n
        deadline = "deadline %s (%s)" % (_short(item["deadline_date"]), left)
    else:
        deadline = "deadline: unknown"
    size = (", size: %s" % item["size_text"]) if item.get("size_text") else ""
    return '%s: %s, %s, %s%s, said on %s: "%s", last mentioned %s.' % (
        item["name"], item["status"], "confirmed" if item["confirmed"] else "unconfirmed", deadline, size,
        _short(item["origin_day"]), item["origin_quote"], _short(item["last_mentioned_day"]))


def top_line(day, active_items):
    d = datetime.date.fromisoformat(day)
    date = "%s %d %s" % (d.strftime("%A"), d.day, d.strftime("%B"))
    if active_items <= 0:
        return date
    return "%s · Working on %d thing%s" % (date, active_items, "" if active_items == 1 else "s")
