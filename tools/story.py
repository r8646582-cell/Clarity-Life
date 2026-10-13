"""Reference implementation of the Your story facts: the executable spec for the Kotlin port (core/Story.kt).

Everything the story page shows as a number or a date is counted here, never by the AI (spec section 2.5):
the bars of a goal's chart (what he did in each week or month), the plan line under them (what his plan asked
for in the same stretch, step by step, so a ramp 2, 3, 4 a day draws as stairs), how long his story is
("Five months"), its headline, and the date range of each view (This month, This year, All of it).
The Kotlin port must pass contracts/story_vectors.json.

Conventions (same as planner.py)
- A "day" is a local calendar date; weekdays are Mon=0 ... Sun=6.
- Story weeks run seven days from the backlog's own start day, so week one is the plan's first week (a plan
  that starts on a Wednesday has Wednesday-to-Tuesday weeks). Months are calendar months.
- A check-in adds its amount on its day, whatever its level (a rest day has amount 0).
- A step adds its target on each of its scheduled days that is not a planned rest day. An Nx/week step adds
  its target min(N, its days in that stretch) times per seven days, so it has a plan line too.
"""
from datetime import date, timedelta

from planner import EPS, PlannerError, d, parse_schedule

MAX_WEEK_BARS = 26     # more weeks than this and the chart shows months
NUMBER_WORDS = ["Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
                "Eleven", "Twelve"]
FIRST_PAGES_DAYS = 28  # a story younger than four weeks is "The first pages."


def _num(x):
    """Amounts as JSON numbers: whole numbers as ints (the Kotlin test compares within 1e-9)."""
    return int(round(x)) if abs(x - round(x)) < EPS else x


def _month_start(day):
    return date(day.year, day.month, 1)


def _next_month(day):
    return date(day.year + (day.month == 12), 1 if day.month == 12 else day.month + 1, 1)


def buckets(first, last):
    """The chart's stretches from first to last (both included): story weeks when there are at most
    MAX_WEEK_BARS of them, otherwise calendar months. Each is (start, end) with end clipped to last."""
    first, last = d(first), d(last)
    if last < first:
        return "week", []
    weeks = (last - first).days // 7 + 1
    out = []
    if weeks <= MAX_WEEK_BARS:
        cur = first
        while cur <= last:
            out.append((cur, min(cur + timedelta(days=6), last)))
            cur += timedelta(days=7)
        return "week", out
    cur = first
    while cur <= last:
        nxt = _next_month(cur)
        out.append((cur, min(nxt - timedelta(days=1), last)))
        cur = nxt
    return "month", out


def _planned(steps, a, b, rest):
    """What the steps ask for from a to b (both included)."""
    total = 0.0
    for st in steps:
        kind, val = parse_schedule(st["schedule"])
        s = d(st["start"])
        e = d(st["end"]) if st.get("end") else None
        lo = max(a, s)
        hi = b if e is None else min(b, e)
        if hi < lo:
            continue
        if kind == "days":
            cur = lo
            while cur <= hi:
                if cur.weekday() in val and cur not in rest:
                    total += st["target"]
                cur += timedelta(days=1)
        else:
            # per seven days counted from lo; a planned rest day is a day it cannot be done
            cur = lo
            while cur <= hi:
                end = min(cur + timedelta(days=6), hi)
                free = sum(1 for i in range((end - cur).days + 1) if cur + timedelta(days=i) not in rest)
                total += st["target"] * min(val, free)
                cur += timedelta(days=7)
    return total


def series(first, last, steps, checkins, rest_dates=()):
    """The bars and the plan line for one backlog: one entry per stretch (see buckets), with what he did in it
    and what the plan asked for in it. first = the backlog's start day; last = today, or the day it was done."""
    rest = {d(x) for x in rest_dates}
    for st in steps:
        parse_schedule(st["schedule"])      # a bad schedule fails here, never guessed
    by, spans = buckets(first, last)
    out = []
    for a, b in spans:
        done = sum(c.get("amount", 0) for c in checkins if a <= d(c["day"]) <= b)
        out.append({"start": a.isoformat(), "end": b.isoformat(), "done": _num(done),
                    "planned": _num(_planned(steps, a, b, rest))})
    return {"by": by, "bars": out}


def finished_on(total, start_done, checkins):
    """The first day the running total reached the backlog's total, or None while it is not reached."""
    run = start_done
    if run >= total - EPS:
        return None
    for c in sorted(checkins, key=lambda c: c["day"]):
        run += c.get("amount", 0)
        if run >= total - EPS:
            return d(c["day"]).isoformat()
    return None


def _months_between(first, today):
    m = (today.year - first.year) * 12 + today.month - first.month
    if today.day < first.day:
        m -= 1
    return m


def length(first, today):
    """How long his story is, in the words the page shows: Day 3, Two weeks, Five months, Two years."""
    first, today = d(first), d(today)
    days = (today - first).days + 1
    if days < 1:
        raise PlannerError("the story cannot start after today")
    if days < 14:
        return "Day %d" % days
    if days < 63:
        return "%s weeks" % _words(days // 7)
    months = _months_between(first, today)
    if months < 24:
        return "One month" if months == 1 else "%s months" % _words(months)
    years = months // 12
    return "%s years" % _words(years)


def _words(n):
    return NUMBER_WORDS[n] if n < len(NUMBER_WORDS) else str(n)


def headline(first, today):
    """The page's headline: the first four weeks are its first pages."""
    days = (d(today) - d(first)).days + 1
    return "The first pages." if days < FIRST_PAGES_DAYS else "Your story so far."


def view_range(view, first, today):
    """The days a view covers, both included: month = this calendar month, year = this calendar year,
    all = since the story began. Never before the story began."""
    first, today = d(first), d(today)
    if view == "month":
        a = _month_start(today)
    elif view == "year":
        a = date(today.year, 1, 1)
    elif view == "all":
        a = first
    else:
        raise PlannerError("unknown view")
    return {"from": max(a, first).isoformat(), "to": today.isoformat()}
