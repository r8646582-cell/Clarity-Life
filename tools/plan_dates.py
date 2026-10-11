"""Reference implementation of PlanDates: reading the dates on a plan, backlog or target_change card in code.
The executable spec for PlanDates.kt; the shared cases are contracts/plan_date_vectors.json.

The model copies dates into a card in his local wording ("tomorrow", "Sat", "20 Nov", "2026-11-20"). The AI never
computes a date, so code reads them here and NEVER GUESSES. A plan card has no time picker, so anything this
cannot read exactly drops the save with a quiet line on the card ("A date on this card could not be read"); the
card always shows the dates it read before he taps, so he sees exactly what will be saved.

Rules (each one pinned by a vector):
1. `today` is his day key (the day boundary, 4 am by default, is already applied by the caller), so "today" and
   "tomorrow" count from the day his tracker is on.
2. A weekday name means the next such day on or after today.
3. A date without a year ("20 Nov", "Nov 20") means the first such date on or after today: this year, or next year
   when it has passed. A date with a year ("20 Nov 2026", "2026-11-20") is taken as written.
4. Anything else is unreadable: an unknown word, two dates, a date that does not exist, a weekday that disagrees
   with the date, a date before today, or more than MAX_YEARS ahead.

Card rules (`check_card`), on top of reading each date:
- plan: every target starts on or after today (no start = today) and ends on or after its start (no end = open);
  every backlog deadline is on or after today; review_date is read like any date and must be after today and at
  most REVIEW_MAX_DAYS ahead (so "17 Oct", tapped after 17 Oct has gone, never becomes next year's 17 Oct).
- target_change: a new end must be on or after today.
- backlog: a deadline (new, or changed) must be on or after today.

Grammar (case-insensitive; commas and the filler words on, the, by, from, until are ignored):
  today | tomorrow | tmrw | tmr | <weekday> | <day> <month> [year] | <month> <day> [year] | YYYY-MM-DD
  (a weekday may come before a date: "Sat 17 Oct")

Pure functions only: no clock. Dates are "YYYY-MM-DD".
"""
import re
from datetime import date, timedelta

WEEKDAYS = {"monday": 0, "mon": 0, "tuesday": 1, "tue": 1, "tues": 1, "wednesday": 2, "wed": 2,
            "thursday": 3, "thu": 3, "thur": 3, "thurs": 3, "friday": 4, "fri": 4, "saturday": 5, "sat": 5,
            "sunday": 6, "sun": 6}
MONTHS = {"january": 1, "jan": 1, "february": 2, "feb": 2, "march": 3, "mar": 3, "april": 4, "apr": 4,
          "may": 5, "june": 6, "jun": 6, "july": 7, "jul": 7, "august": 8, "aug": 8, "september": 9,
          "sep": 9, "sept": 9, "october": 10, "oct": 10, "november": 11, "nov": 11, "december": 12, "dec": 12}
FILLER = {"on", "the", "by", "from", "until"}
RELATIVE = {"today": 0, "tomorrow": 1, "tmrw": 1, "tmr": 1}
MAX_YEARS = 5
REVIEW_MAX_DAYS = 28  # review options are one or two weeks out, moved to a scheduled day

ISO_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})$")
DAY_NUM_RE = re.compile(r"^(\d{1,2})(?:st|nd|rd|th)?$")
YEAR_RE = re.compile(r"^(20\d\d)$")


class Unreadable(Exception):
    pass


def _make(y, m, d):
    try:
        return date(y, m, d)
    except ValueError:
        raise Unreadable()


def read_day(phrase, today):
    """The date a phrase names, as "YYYY-MM-DD", or None when it cannot be read exactly."""
    try:
        return _read(phrase, date.fromisoformat(today)).isoformat()
    except Unreadable:
        return None


def _read(phrase, today):
    if not isinstance(phrase, str):
        raise Unreadable()
    toks = [w for w in phrase.lower().replace(",", " ").split() if w not in FILLER]
    if not toks:
        raise Unreadable()
    if len(toks) == 1 and toks[0] in RELATIVE:
        return today + timedelta(days=RELATIVE[toks[0]])
    if len(toks) == 1 and ISO_RE.match(toks[0]):
        y, m, d = (int(x) for x in ISO_RE.match(toks[0]).groups())
        return _check(_make(y, m, d), today)
    weekday = None
    if toks[0] in WEEKDAYS:
        weekday = WEEKDAYS[toks[0]]
        toks = toks[1:]
        if not toks:
            return today + timedelta(days=(weekday - today.weekday()) % 7)
    day = month = year = None
    for w in toks:
        if w in MONTHS and month is None:
            month = MONTHS[w]
        elif DAY_NUM_RE.match(w) and day is None:
            day = int(DAY_NUM_RE.match(w).group(1))
        elif YEAR_RE.match(w) and year is None and day is not None and month is not None:
            year = int(w)
        else:
            raise Unreadable()
    if day is None or month is None:
        raise Unreadable()
    if year is not None:
        d = _make(year, month, day)
    else:
        d = None
        for y in (today.year, today.year + 1):
            try:
                cand = date(y, month, day)
            except ValueError:
                continue
            if cand >= today:
                d = cand
                break
        if d is None:
            raise Unreadable()
    if weekday is not None and d.weekday() != weekday:
        raise Unreadable()
    return _check(d, today)


def _check(d, today):
    if d < today:
        raise Unreadable()
    if d > _make(today.year + MAX_YEARS, today.month, min(today.day, 28)):
        raise Unreadable()
    return d


def check_card(card, today):
    """The dates of a validated card, read in code: {"ok": true, ...dates} or {"ok": false, "why": code}."""
    t = date.fromisoformat(today)

    def need(phrase, what):
        got = read_day(phrase, today)
        if got is None:
            raise _Bad("unreadable " + what)
        return got

    try:
        typ = card["type"]
        if typ == "plan":
            targets = []
            for item in card.get("targets", []):
                start = need(item["start"], "start") if "start" in item else today
                end = need(item["end"], "end") if "end" in item else None
                if end is not None and end < start:
                    raise _Bad("end before start")
                targets.append({"start": start, "end": end})
            backlog = [{"deadline": need(b["deadline"], "deadline")} for b in card.get("backlog", [])]
            review = need(card["review_date"], "review_date")
            if review <= t.isoformat():
                raise _Bad("review not after today")
            if review > (t + timedelta(days=REVIEW_MAX_DAYS)).isoformat():
                raise _Bad("review too far ahead")
            return {"ok": True, "targets": targets, "backlog": backlog, "review": review}
        if typ == "target_change":
            return {"ok": True, "end": need(card["end"], "end") if "end" in card else None}
        if typ == "backlog":
            return {"ok": True, "deadline": need(card["deadline"], "deadline") if "deadline" in card else None}
        raise _Bad("no dates on this card")
    except _Bad as e:
        return {"ok": False, "why": str(e)}


class _Bad(Exception):
    pass
