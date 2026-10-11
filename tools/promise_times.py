"""Reference implementation of PromiseTimes: resolving a promise card's "when" in code (contracts/context.md,
"Resolving 'when' in a promise card"). The executable spec for PromiseTimes.kt; the shared cases are
contracts/promise_time_vectors.json.

The model writes "when" in his own local wording ("tomorrow 17:00", "Sunday 8am", "today 22:30"). The AI never
computes a date, so code does it here, and NEVER GUESSES:
- "ok":   the phrase names exactly one moment in the future. The card shows it ("Sat 10 Oct, 5:00 pm") and Set
          it saves it.
- "pick": anything else. The card opens its time picker instead of saving, with the most likely reading
          preselected when there is one (date and/or time may be None). Both None means the picker is empty.

Rules (each one pinned by a vector):
1. Between midnight and the day boundary (4 am by default) their day still counts as the day before, so "today",
   "tonight", "tomorrow" and a bare time are ambiguous: always "pick", with the calendar day preselected (at
   12:40 am on Sunday, "tomorrow 10:00" most likely means Sunday 10:00, but it might mean Monday).
2. A weekday name means the next such day on or after today; if that moment has already passed today, the
   same weekday next week. "next Monday" is ambiguous English, so it is a "pick" with the coming Monday.
3. A time needs a colon (17:00), am/pm (5pm, 5:30 pm), "noon", or an hour of 13 and above. A bare 1 to 12 is
   only read as a time when the words around it say which half of the day it is (tonight 10, morning 7);
   otherwise it is a "pick" with that hour preselected as written.
4. A day with no time, or a time with no day that has already passed, is a "pick".
5. Anything not in the small grammar below (an unknown word, two days, two times, "midnight", a date that does
   not exist, a weekday that disagrees with the date, more than a year ahead) is a "pick" with nothing
   preselected. Fail closed: a promise is never saved at a time he did not see.

Grammar (case-insensitive; commas and the filler words at, on, this, the, by are ignored):
  day:  today | tonight | tomorrow | tmrw | tmr | <weekday> | next <weekday> | <day> <month> [year] |
        <month> <day> [year]  (a weekday may come before a date: "Sat 10 Oct")
  time: H:MM | HH:MM | H[:MM]am | H[:MM]pm | H [:MM] am|pm | noon | a bare hour
  part of the day: morning | afternoon | evening | night (tonight implies evening)

Pure functions only: no clock, no time zone. `now` is his local date and time as "YYYY-MM-DDTHH:MM".
"""
import re
from datetime import date, datetime, time, timedelta

WEEKDAYS = {"monday": 0, "mon": 0, "tuesday": 1, "tue": 1, "tues": 1, "wednesday": 2, "wed": 2,
            "thursday": 3, "thu": 3, "thur": 3, "thurs": 3, "friday": 4, "fri": 4, "saturday": 5, "sat": 5,
            "sunday": 6, "sun": 6}
MONTHS = {"january": 1, "jan": 1, "february": 2, "feb": 2, "march": 3, "mar": 3, "april": 4, "apr": 4,
          "may": 5, "june": 6, "jun": 6, "july": 7, "jul": 7, "august": 8, "aug": 8, "september": 9,
          "sep": 9, "sept": 9, "october": 10, "oct": 10, "november": 11, "nov": 11, "december": 12, "dec": 12}
FILLER = {"at", "on", "this", "the", "by"}
RELATIVE = {"today": 0, "tonight": 0, "tomorrow": 1, "tmrw": 1, "tmr": 1}
PARTS = {"morning": "am", "afternoon": "pm", "evening": "pm", "night": "pm"}
MAX_DAYS_AHEAD = 365

TIME_RE = re.compile(r"^(\d{1,2})(?::(\d{2}))?(am|pm)?$")
DAY_NUM_RE = re.compile(r"^(\d{1,2})(?:st|nd|rd|th)?$")
YEAR_RE = re.compile(r"^(20\d\d)$")


def pick(d=None, t=None):
    return {"status": "pick", "date": d.isoformat() if d else None, "time": t.strftime("%H:%M") if t else None}


def ok(d, t):
    return {"status": "ok", "date": d.isoformat(), "time": t.strftime("%H:%M")}


class _Unreadable(Exception):
    pass


def _tokens(phrase):
    s = phrase.lower().replace(",", " ").replace("a.m.", "am").replace("p.m.", "pm")
    raw = [w for w in s.split() if w not in FILLER]
    out = []
    for w in raw:
        # "5 pm" and "5:30 pm" become one token, like "5pm"
        if w in ("am", "pm") and out and re.match(r"^\d{1,2}(?::\d{2})?$", out[-1]):
            out[-1] += w
        else:
            out.append(w)
    return out


def _parse(phrase):
    """Returns the pieces the phrase names, or raises _Unreadable."""
    toks = _tokens(phrase)
    if not toks:
        raise _Unreadable()
    found = {"relative": None, "weekday": None, "next": False, "dom": None, "month": None, "year": None,
             "time": None, "bare_hour": None, "half": None}
    days_named = 0
    i = 0

    def setonce(key, value):
        if found[key] is not None:
            raise _Unreadable()
        found[key] = value

    while i < len(toks):
        w = toks[i]
        nxt = toks[i + 1] if i + 1 < len(toks) else None
        if w in RELATIVE:
            setonce("relative", w)
            days_named += 1
            if w == "tonight":
                setonce("half", "pm")
        elif w == "next" and nxt in WEEKDAYS:
            setonce("weekday", WEEKDAYS[nxt])
            found["next"] = True
            days_named += 1
            i += 1
        elif w in WEEKDAYS:
            setonce("weekday", WEEKDAYS[w])
        elif w in MONTHS:
            setonce("month", MONTHS[w])
            # the day of the month sits next to the month name: "10 Oct" or "Oct 10"
            if nxt is not None and DAY_NUM_RE.match(nxt) and found["dom"] is None:
                found["dom"] = int(DAY_NUM_RE.match(nxt).group(1))
                i += 1
            if i + 1 < len(toks) and YEAR_RE.match(toks[i + 1]):
                setonce("year", int(toks[i + 1]))
                i += 1
            days_named += 1
        elif DAY_NUM_RE.match(w) and nxt in MONTHS:
            setonce("dom", int(DAY_NUM_RE.match(w).group(1)))
        elif w in PARTS:
            setonce("half", PARTS[w])
        elif w == "noon":
            setonce("time", time(12, 0))
        elif TIME_RE.match(w):
            m = TIME_RE.match(w)
            h, mm, ampm = int(m.group(1)), m.group(2), m.group(3)
            minute = int(mm) if mm is not None else 0
            if minute > 59:
                raise _Unreadable()
            if ampm:
                if not 1 <= h <= 12:
                    raise _Unreadable()
                h = h % 12 + (12 if ampm == "pm" else 0)
                setonce("time", time(h, minute))
            elif mm is not None or h >= 13:
                if h > 23:
                    raise _Unreadable()
                setonce("time", time(h, minute))
            else:
                setonce("bare_hour", h)
        else:
            raise _Unreadable()
        i += 1
    if found["time"] is not None and found["bare_hour"] is not None:
        raise _Unreadable()
    # a weekday before a date ("Sat 10 Oct") is one day, not two
    weekday_alone = found["weekday"] is not None and not found["next"]
    if weekday_alone and found["month"] is None:
        days_named += 1
    if days_named > 1:
        raise _Unreadable()
    if found["dom"] is not None and found["month"] is None:
        raise _Unreadable()
    if found["month"] is not None and found["dom"] is None:
        raise _Unreadable()
    return found


def resolve(phrase, now, boundary_hour=4):
    """Resolve a card's "when" against his local `now` ("YYYY-MM-DDTHH:MM"). See the module docstring."""
    n = datetime.strptime(now, "%Y-%m-%dT%H:%M")
    cal = n.date()
    late = n.hour < boundary_hour
    day_key = cal - timedelta(days=1) if late else cal
    try:
        f = _parse(phrase)
    except _Unreadable:
        return pick()

    # ---- the time of day ----
    sure_time = True
    t = f["time"]
    half = f["half"]
    if t is not None and half is not None:
        if (half == "pm") != (t.hour >= 12):
            return pick()
    if f["bare_hour"] is not None:
        h = f["bare_hour"]
        if half == "pm" and 1 <= h <= 11:
            t = time(h + 12, 0)
        elif half == "am" and 1 <= h <= 11:
            t = time(h, 0)
        elif h <= 23:
            t = time(h, 0)
            sure_time = False
        else:
            return pick()

    # ---- the day ----
    sure_day = True
    if f["month"] is not None:
        year = f["year"] if f["year"] is not None else cal.year
        try:
            d = date(year, f["month"], f["dom"])
        except ValueError:
            return pick()
        if f["year"] is None and d < cal:
            try:
                d = date(year + 1, f["month"], f["dom"])
            except ValueError:
                return pick()
        if f["weekday"] is not None and d.weekday() != f["weekday"]:
            return pick()
    elif f["weekday"] is not None:
        if f["next"]:
            d = cal + timedelta(days=(f["weekday"] - cal.weekday() - 1) % 7 + 1)
            sure_day = False
        else:
            d = cal + timedelta(days=(f["weekday"] - cal.weekday()) % 7)
            if t is not None and datetime.combine(d, t) <= n:
                d += timedelta(days=7)
    elif f["relative"] is not None or t is not None:
        offset = RELATIVE.get(f["relative"], 0)
        if late:
            # his "today" is still yesterday on the calendar; the likeliest meaning is the coming day
            d = cal
            sure_day = False
        else:
            d = day_key + timedelta(days=offset)
    else:
        return pick(None, t)

    if (d - cal).days > MAX_DAYS_AHEAD or d < cal:
        return pick()
    if t is None:
        return pick(d, None)
    if datetime.combine(d, t) <= n:
        return pick(None, t)
    if not (sure_day and sure_time):
        return pick(d, t)
    return ok(d, t)
