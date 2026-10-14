"""Reference implementation of RecallTime: the time and outcome words in a message, read in code, so search can
look in the right part of their life (docs/MAX-PLAN.md, Phase 2 step 2). The executable spec for RecallTime.kt;
the shared cases are contracts/recall_time_vectors.json.

"Which promises did I break in August?" has no word in common with the promises it asks about, and no meaning
search finds "August" in a promise due on 21 Aug. Code reads the period instead ("in August" is 1 Aug to 31 Aug,
the most recent August that has begun) and search then brings what falls in it: promises due in it, conversations
held in it, letters, reviews and chapters about it, and the check-ins of a short period. The AI never works out a
date (rule 2); this only chooses what is looked up, and every date the coach then reads is written by code.

Rules (each one pinned by a vector):
1. `today` is their day key (the day boundary already applied by the caller). Periods are whole days, inclusive.
2. A month without a year means the most recent such month that has begun: "in March" in October is this March,
   "in November" in October is last November. "last March" is the same, except that in March itself it means the
   March of last year. A month counts only after a word that makes it a time (in, during, since, of, last, this,
   early, mid, late, ...) or beside a day number, so "march on" and "I may study" are never months; "may" beside a
   day number counts only after the day ("12 may") or with a year, as in DayCounts.
3. A day and month ("12 October", "Oct 12th") without a year is this year's when that day is not after today,
   and last year's otherwise; with a year it is taken as written. A day that does not exist is ignored.
4. today, this morning, this afternoon, this evening, tonight, earlier today: today. yesterday, last night: the
   day before. tomorrow: the day after (what is due). N days ago: that day.
5. this week: Monday to today. last week: the Monday to Sunday before. this month, last month, this year, last
   year, in 2025: as named (this ... ends today). past/last N days or weeks: the N days (7 N days) up to today.
   N weeks ago: the Monday to Sunday week holding the day 7 N days ago. N months ago: that calendar month.
6. first, second, third, fourth or last week of a month: days 1-7, 8-14, 15-21, 22-28, or its last 7 days.
   early, mid, late (and beginning of, middle of, end of) a month: days 1-10, 11-20, 21 to its end.
7. last Sunday, on Sunday, this past Sunday, Sunday's: the most recent Sunday before today. A plain weekday or a
   plural ("on Tuesdays") is not a period.
8. since <month>: from the first of that month to today.
9. The first period in the text wins; a text with none has no period. A period that ends before it starts, or
   starts after tomorrow, is no period.
10. Promises: the words promise, promised, promises, commit, committed, commitment set `promises`; with them,
   break, broke, broken, miss, missed, fail, failed, skip, skipped give the outcome not_done; keep, kept, managed
   give kept; moved, postponed, pushed give moved; let go, dropped, gave up give let_go. The outcome orders the
   promises found; it never hides the others (a question "did I keep it?" must still find the one he did not).

`rest` is the text with the matched period phrase taken out, for keyword search.

Pure functions only: no clock. Dates are "YYYY-MM-DD".
"""
import calendar
import re
from datetime import date, timedelta

MONTHS = {"january": 1, "jan": 1, "february": 2, "feb": 2, "march": 3, "mar": 3, "april": 4, "apr": 4,
          "may": 5, "june": 6, "jun": 6, "july": 7, "jul": 7, "august": 8, "aug": 8, "september": 9,
          "sep": 9, "sept": 9, "october": 10, "oct": 10, "november": 11, "nov": 11, "december": 12, "dec": 12}
WEEKDAYS = {"monday": 0, "tuesday": 1, "wednesday": 2, "thursday": 3, "friday": 4, "saturday": 5, "sunday": 6}
NUMBERS = {"a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
           "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "couple of": 2, "few": 3}
ORDINAL_WEEK = {"first": 1, "1st": 1, "second": 2, "2nd": 2, "third": 3, "3rd": 3, "fourth": 4, "4th": 4,
                "last": 5}
MAX_COUNT = 400  # "N days ago" beyond this is not read

M = r"(?P<m>january|february|march|april|may|june|july|august|september|october|november|december|" \
    r"jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
M2 = M.replace("?P<m>", "?P<m2>")
W = r"(?P<w>monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
N = r"(?P<n>\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple of|few)"
Y = r"(?P<y>20\d\d)"
D = r"(?P<d>\d{1,2})(?:st|nd|rd|th)?"
D2 = D.replace("?P<d>", "?P<d2>")
TIME_WORD = r"(?:in|during|since|of|last|this|early|mid|late|through|throughout|from|until|before|after)"

# In order: the first pattern that matches at the earliest position wins (rule 9).
PATTERNS = [
    ("week_of", r"\b(?P<o>first|1st|second|2nd|third|3rd|fourth|4th|last) week (?:of|in) " + M + r"(?: " + Y + r")?\b"),
    ("part_of", r"\b(?P<p>early|beginning of|start of|mid|middle of|late|end of) " + M + r"(?: " + Y + r")?\b"),
    ("day_month", r"\b" + D + r"(?: of)? " + M + r"(?:,? " + Y + r")?\b"),
    ("month_day", r"\b" + M2 + r" " + D2 + r"(?:,? " + Y.replace("?P<y>", "?P<y2>") + r")?\b"),
    ("since_month", r"\bsince " + M + r"(?: " + Y + r")?\b"),
    ("last_month_name", r"\blast " + M + r"\b"),
    ("month", r"\b" + TIME_WORD + r" (?:the month of )?" + M + r"(?: " + Y + r")?\b"),
    ("month_year", r"\b" + M + r" " + Y + r"\b"),
    ("today", r"\b(?:earlier today|today|this morning|this afternoon|this evening|tonight)\b"),
    ("yesterday", r"\b(?:yesterday|last night)\b"),
    ("tomorrow", r"\btomorrow\b"),
    ("days_ago", r"\b" + N + r" days? ago\b"),
    ("weeks_ago", r"\b" + N + r" weeks? ago\b"),
    ("months_ago", r"\b" + N + r" months? ago\b"),
    ("past_days", r"\b(?:the )?(?:past|last) " + N + r" days\b"),
    ("past_weeks", r"\b(?:the )?(?:past|last) " + N + r" weeks\b"),
    ("this_week", r"\bthis week\b"),
    ("last_week", r"\b(?:last week|the week before)\b"),
    ("this_month", r"\bthis month\b"),
    ("last_month", r"\blast month\b"),
    ("this_year", r"\bthis year\b"),
    ("last_year", r"\blast year\b"),
    ("year", r"\b(?:in|during|since) " + Y + r"\b"),
    ("weekday", r"\b(?:(?:last|on|this past) " + W + r"|" + W.replace("?P<w>", "?P<w2>") + r"'s)\b"),
]
COMPILED = [(name, re.compile(rx)) for name, rx in PATTERNS]

PROMISE_RE = re.compile(r"\b(?:promise|promised|promises|commit|committed|commitment)\b")
OUTCOMES = [
    ("not_done", re.compile(r"\b(?:break|broke|broken|miss|missed|fail|failed|skip|skipped)\b")),
    ("kept", re.compile(r"\b(?:keep|kept|managed)\b")),
    ("moved", re.compile(r"\b(?:moved|postponed|pushed)\b")),
    ("let_go", re.compile(r"\b(?:let go|dropped|gave up)\b")),
]


def norm(text):
    t = text.lower().replace("’", "'").replace("‘", "'")
    t = re.sub(r"[^a-z0-9' ]+", " ", t)
    return " ".join(t.split())


def _number(word):
    if word is None:
        return None
    if word.isdigit():
        n = int(word)
        return n if 0 < n <= MAX_COUNT else None
    return NUMBERS.get(word)


def _month_end(y, m):
    return date(y, m, calendar.monthrange(y, m)[1])


def _recent_month(m, today, year=None, last=False):
    """The most recent month m that has begun (rule 2), or the one in the given year."""
    if year is not None:
        return year, m
    y = today.year if m <= today.month else today.year - 1
    if last and m == today.month:
        y -= 1
    return y, m


def _shift_month(y, m, back):
    k = y * 12 + (m - 1) - back
    return k // 12, k % 12 + 1


def _period(name, g, today):
    """(start, end) dates for one match, or None."""
    gd = g.groupdict()
    year = int(gd["y"]) if gd.get("y") else None
    if name in ("week_of", "part_of", "since_month", "last_month_name", "month", "month_year"):
        y, m = _recent_month(MONTHS[gd["m"]], today, year, last=(name == "last_month_name"))
        end = _month_end(y, m)
        if name == "week_of":
            k = ORDINAL_WEEK[gd["o"]]
            if k == 5:
                return end - timedelta(days=6), end
            return date(y, m, 7 * k - 6), date(y, m, 7 * k)
        if name == "part_of":
            p = gd["p"]
            if p in ("early", "beginning of", "start of"):
                return date(y, m, 1), date(y, m, 10)
            if p in ("mid", "middle of"):
                return date(y, m, 11), date(y, m, 20)
            return date(y, m, 21), end
        if name == "since_month":
            return date(y, m, 1), today
        return date(y, m, 1), end
    if name in ("day_month", "month_day"):
        mword = gd["m"] if name == "day_month" else gd["m2"]
        dnum = int(gd["d"] if name == "day_month" else gd["d2"])
        yword = gd.get("y") if name == "day_month" else gd.get("y2")
        if name == "month_day" and mword == "may" and not yword:
            return None  # "I may 2 hours" is not 2 May (DayCounts)
        m = MONTHS[mword]
        try:
            if yword:
                d = date(int(yword), m, dnum)
            else:
                d = date(today.year, m, dnum)
                if d > today:
                    d = date(today.year - 1, m, dnum)
        except ValueError:
            return None
        return d, d
    if name == "today":
        return today, today
    if name == "yesterday":
        d = today - timedelta(days=1)
        return d, d
    if name == "tomorrow":
        d = today + timedelta(days=1)
        return d, d
    if name in ("days_ago", "weeks_ago", "months_ago", "past_days", "past_weeks"):
        n = _number(gd["n"])
        if n is None:
            return None
        if name == "days_ago":
            d = today - timedelta(days=n)
            return d, d
        if name == "weeks_ago":
            d = today - timedelta(days=7 * n)
            monday = d - timedelta(days=d.weekday())
            return monday, monday + timedelta(days=6)
        if name == "months_ago":
            y, m = _shift_month(today.year, today.month, n)
            return date(y, m, 1), _month_end(y, m)
        if name == "past_days":
            return today - timedelta(days=n - 1), today
        return today - timedelta(days=7 * n - 1), today
    if name == "this_week":
        return today - timedelta(days=today.weekday()), today
    if name == "last_week":
        monday = today - timedelta(days=today.weekday() + 7)
        return monday, monday + timedelta(days=6)
    if name == "this_month":
        return date(today.year, today.month, 1), today
    if name == "last_month":
        y, m = _shift_month(today.year, today.month, 1)
        return date(y, m, 1), _month_end(y, m)
    if name == "this_year":
        return date(today.year, 1, 1), today
    if name == "last_year":
        return date(today.year - 1, 1, 1), date(today.year - 1, 12, 31)
    if name == "year":
        return date(year, 1, 1), min(date(year, 12, 31), today)
    if name == "weekday":
        wd = WEEKDAYS[gd["w"] or gd["w2"]]
        back = (today.weekday() - wd) % 7 or 7
        d = today - timedelta(days=back)
        return d, d
    raise ValueError(name)


def parse(text, today):
    """{"start", "end", "promises", "outcome", "rest"} for a message; start and end are None without a period."""
    t = norm(text)
    today_d = date.fromisoformat(today)
    found = None
    for name, rx in COMPILED:
        for g in rx.finditer(t):
            if found is not None and g.start() >= found[1].start():
                break
            p = _period(name, g, today_d)
            if p is None:
                continue
            found = (name, g, p)
            break
    start = end = None
    rest = t
    if found is not None:
        s, e = found[2]
        if s <= e and s <= today_d + timedelta(days=1):
            start, end = s.isoformat(), e.isoformat()
            g = found[1]
            rest = " ".join((t[:g.start()] + " " + t[g.end():]).split())
    promises = bool(PROMISE_RE.search(t))
    outcome = None
    if promises:
        for o, rx in OUTCOMES:
            if rx.search(t):
                outcome = o
                break
    return {"start": start, "end": end, "promises": promises, "outcome": outcome, "rest": rest}
