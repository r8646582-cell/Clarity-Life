"""Reference implementation of the planner math: the executable spec for the Kotlin planner.

Everything the coach is told about pace, days left, projections, review dates and weekly facts is computed
here (and in the Kotlin port), never by the AI. The Kotlin port must pass contracts/planner_vectors.json.

Conventions
- A "day" is a local calendar date. day_key() turns a UTC time into the date it belongs to, with a day
  boundary hour (default 4) so a 12:30 am check-in counts for the day before.
- Weekdays are Mon=0 ... Sun=6 (Python's date.weekday()).
- Levels: rest, not, minimum, target, beyond. "Done" = minimum, target or beyond. Rest is neither done nor missed.
  A missing row is "no answer" and is never counted as a miss. In the UI: Did it = target (beyond when the
  amount is above the target), Part = minimum, Not = not. level_for() is the one mapping from an amount.
- Schedules have ONE grammar (SCHEDULE_RE), shared with the card parser: daily | weekdays | Nx/week (N 1..7) |
  a day list such as Mon,Wed,Fri | a range such as Sat-Thu. Anything else is rejected, never guessed.
- Honest display numbers. The coach copies these, so they must never flatter the plan:
    required per day   rounded UP to 0.1   (1.11 shows as 1.2: the pace that really reaches the total)
    reaches            rounded DOWN to the unit step, unless the raw projection reaches the total
    typical/best pace  rounded half up to 0.1 (a description of the past, not a promise)
  Status (ok / needs_best / short) is decided from the same numbers that are shown, so text and verdict agree.
"""
import math
import re
from collections import Counter
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
DONE_LEVELS = ("minimum", "target", "beyond")
SHORT_LEVELS = ("not", "minimum")   # days that may carry a reason chip: Not and Part
MIN_ANSWERED = 7          # answered scheduled days needed in a 14-day window
WINDOW = 14
REPLAN_RATIO = 0.85
REASON_MIN_N = 3          # a reason line is only shown when at least this many items are behind it
EPS = 1e-9

_DAY = r"(?:mon|tue|wed|thu|fri|sat|sun)"
SCHEDULE_RE = re.compile(r"^(?:daily|weekdays|[1-7]x/week|%s(?:,%s)*|%s-%s)$" % (_DAY, _DAY, _DAY, _DAY),
                         re.IGNORECASE)


class PlannerError(ValueError):
    pass


def round_half_up(x, step=1.0):
    return math.floor(x / step + 0.5 + EPS) * step


def round_up(x, step=1.0):
    return math.ceil(x / step - EPS) * step


def round_down(x, step=1.0):
    return math.floor(x / step + EPS) * step


def d(s):
    return date.fromisoformat(s) if isinstance(s, str) else s


def day_key(utc_iso, tz, boundary_hour=4):
    """Local date a UTC instant belongs to, shifting by the day boundary. utc_iso like 2026-10-10T19:30:00Z."""
    if not 0 <= boundary_hour <= 23:
        raise PlannerError("boundary hour must be 0 to 23")
    t = datetime.fromisoformat(utc_iso.replace("Z", "+00:00")).astimezone(ZoneInfo(tz))
    return (t - timedelta(hours=boundary_hour)).date()


def level_for(amount, minimum, target):
    """The check-in level for an amount. Rest is chosen by the user, never derived."""
    if amount < 0 or target <= 0 or minimum < 0 or minimum > target:
        raise PlannerError("bad amount or levels")
    if amount > target + EPS:
        return "beyond"
    if amount >= target - EPS:
        return "target"
    if amount > EPS and amount >= minimum - EPS:
        return "minimum"
    return "not"


def is_schedule(s):
    return isinstance(s, str) and bool(SCHEDULE_RE.match(s.strip()))


def parse_schedule(s):
    """Return ("days", frozenset(weekdays)) or ("count", n). Raises PlannerError on anything else."""
    if not is_schedule(s):
        raise PlannerError("bad schedule")
    low = s.strip().lower()
    names = {n.lower(): i for i, n in enumerate(DAYS)}
    if low == "daily":
        return ("days", frozenset(range(7)))
    if low == "weekdays":
        return ("days", frozenset(range(5)))
    if low.endswith("x/week"):
        return ("count", int(low[:-6]))
    if "-" in low:
        a, b = low.split("-")
        i, j = names[a], names[b]
        if i == j:
            raise PlannerError("a range needs two different days")
        out, k = [], i
        while True:
            out.append(k)
            if k == j:
                break
            k = (k + 1) % 7
        return ("days", frozenset(out))
    return ("days", frozenset(names[x] for x in low.split(",")))


def scheduled_dates(schedule, start, end, rest_dates=()):
    """Scheduled dates in [start, end] for a day-based schedule, minus planned rest dates."""
    kind, val = parse_schedule(schedule)
    if kind != "days":
        raise PlannerError("count schedules have no fixed dates")
    start, end, rest = d(start), d(end), {d(x) for x in rest_dates}
    out, cur = [], start
    while cur <= end:
        if cur.weekday() in val and cur not in rest:
            out.append(cur)
        cur += timedelta(days=1)
    return out


def scheduled_count(schedule, start, end, rest_dates=()):
    """Number of scheduled sessions in [start, end]. For Nx/week: per Monday-to-Sunday week, min(N, days of that week in range)."""
    kind, val = parse_schedule(schedule)
    start, end = d(start), d(end)
    if end < start:
        return 0
    if kind == "days":
        return len(scheduled_dates(schedule, start, end, rest_dates))
    rest = {d(x) for x in rest_dates}
    weeks = {}
    cur = start
    while cur <= end:
        if cur not in rest:
            monday = cur - timedelta(days=cur.weekday())
            weeks[monday] = weeks.get(monday, 0) + 1
        cur += timedelta(days=1)
    return sum(min(val, n) for n in weeks.values())


def days_left(schedule, today, deadline, today_answered=False, rest_dates=()):
    """Scheduled sessions from today (only if today is not answered yet) through the deadline, inclusive."""
    start = d(today) + (timedelta(days=1) if today_answered else timedelta(0))
    return scheduled_count(schedule, start, deadline, rest_dates)


def _window(checkins, schedule, first, last, rest_dates):
    kind, _ = parse_schedule(schedule)
    rest = {d(x) for x in rest_dates}
    sched = set(scheduled_dates(schedule, first, last, rest)) if kind == "days" else None
    answered = [c for c in checkins
                if first <= d(c["day"]) <= last and c["level"] != "rest"
                and (sched is None or d(c["day"]) in sched)]
    scheduled = len(sched) if sched is not None else None
    amount = sum(c.get("amount", 0) for c in answered)
    done = sum(1 for c in answered if c["level"] in DONE_LEVELS)
    return {"answered": len(answered), "scheduled": scheduled, "amount": amount, "done": done}


def typical_pace(checkins, schedule, today, rest_dates=()):
    """Pace over the 14 days before today. None when fewer than MIN_ANSWERED answered scheduled days."""
    today = d(today)
    w = _window(checkins, schedule, today - timedelta(days=WINDOW), today - timedelta(days=1), rest_dates)
    w["pace"] = w["amount"] / w["answered"] if w["answered"] >= MIN_ANSWERED else None
    w["answer_rate"] = (round_half_up(100 * w["answered"] / w["scheduled"]) if w["scheduled"] else None)
    return w


def best_pace(checkins, schedule, today, rest_dates=()):
    """Best pace over any 14-day window that ends before today and holds at least MIN_ANSWERED answered days.
    The typical window (ending yesterday) is always one of the candidates, so best >= typical whenever both exist."""
    today = d(today)
    days = [d(c["day"]) for c in checkins if d(c["day"]) < today]
    if not days:
        return None
    last_end = today - timedelta(days=1)
    end = min(min(days) + timedelta(days=WINDOW - 1), last_end)
    best = None
    while end <= last_end:
        w = _window(checkins, schedule, end - timedelta(days=WINDOW - 1), end, rest_dates)
        if w["answered"] >= MIN_ANSWERED:
            p = w["amount"] / w["answered"]
            best = p if best is None or p > best else best
        end += timedelta(days=1)
    return best


def _reach(pace, left, remaining, step):
    raw = pace * left
    if raw >= remaining - EPS:
        return remaining
    return min(remaining, round_down(raw, step))


def project(remaining, left, typical, best, step=1.0):
    """Where the backlog ends up at the typical and the best pace, in the unit, never rounded in its favour."""
    out = {"remaining": remaining, "days_left": left}
    if remaining <= 0:
        out["status"] = "done"
        return out
    if left <= 0:
        out["status"] = "past_deadline"
        return out
    out["required_per_day"] = remaining / left
    if typical is None:
        out["status"] = "no_data"
        return out
    reach_t = _reach(typical, left, remaining, step)
    out["typical_per_day"], out["reaches_typical"] = typical, reach_t
    out["short_typical"] = remaining - reach_t
    if best is not None:
        reach_b = _reach(best, left, remaining, step)
        out["best_per_day"], out["reaches_best"] = best, reach_b
        out["short_best"] = remaining - reach_b
    if reach_t >= remaining:
        out["status"] = "ok"
    elif best is not None and out["reaches_best"] >= remaining:
        out["status"] = "needs_best"
    else:
        out["status"] = "short"
    return out


def replan_flag(reviews):
    """True when the last two weekly reviews both had enough data and both projected below 85% of what remained."""
    if len(reviews) < 2:
        return False
    return all(r["answered_days"] >= MIN_ANSWERED and r["projected_done"] < REPLAN_RATIO * r["remaining"]
               for r in reviews[-2:])


_MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
_WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def review_options(schedule, today, rest_dates=()):
    """The first scheduled day on or after today+7 and today+14, as the coach may offer them.
    English names come from fixed tables, never from the phone's locale."""
    kind, val = parse_schedule(schedule)
    today = d(today)
    rest = {d(x) for x in rest_dates}
    out = []
    for weeks in (1, 2):
        cur = today + timedelta(days=7 * weeks)
        if kind == "days":
            for _ in range(366):          # bounded: rest dates can never make this loop forever
                if cur.weekday() in val and cur not in rest:
                    break
                cur += timedelta(days=1)
            else:
                raise PlannerError("no scheduled day within a year")
        out.append({"date": "%d %s" % (cur.day, _MONTHS[cur.month - 1]), "weekday": _WEEKDAYS[cur.weekday()],
                    "after": "after one week" if weeks == 1 else "after two weeks"})
    return out


def weekly_facts(checkins, schedule, week_start, rest_dates=()):
    """Facts for the Monday-to-Sunday week starting week_start (a Monday)."""
    ws = d(week_start)
    if ws.weekday() != 0:
        raise PlannerError("week must start on a Monday")
    we = ws + timedelta(days=6)
    kind, _ = parse_schedule(schedule)
    w = _window(checkins, schedule, ws, we, rest_dates)
    rows = [c for c in checkins if ws <= d(c["day"]) <= we and c["level"] != "rest"]
    if kind == "days":
        sched = set(scheduled_dates(schedule, ws, we, rest_dates))
        rows = [c for c in rows if d(c["day"]) in sched]
    missed = [c for c in rows if c["level"] == "not"]
    short = [c for c in rows if c["level"] in SHORT_LEVELS]
    reasons = Counter(c["reason"] for c in short if c.get("reason"))
    by_weekday = Counter(DAYS[d(c["day"]).weekday()] for c in missed)
    levels = Counter(c["level"] for c in rows)
    return {
        "scheduled": w["scheduled"], "answered": w["answered"], "done": w["done"],
        "missed": len(missed), "part": levels["minimum"],
        "done_rate": round_half_up(100 * w["done"] / w["answered"]) if w["answered"] else None,
        "answer_rate": round_half_up(100 * w["answered"] / w["scheduled"]) if w["scheduled"] else None,
        "minimum": levels["minimum"], "target": levels["target"] + levels["beyond"],
        # ties broken by name so the Kotlin port gives the same order
        "top_reasons": [(r, n) for r, n in sorted(reasons.items(), key=lambda x: (-x[1], x[0]))[:3]],
        "missed_by_weekday": dict(by_weekday),
    }


def reason_lines(reasons, short_days):
    """Only reasons with at least REASON_MIN_N items behind them are ever shown to the coach.
    short_days = days marked Not or Part (the only days that can carry a reason), so a count never exceeds it."""
    return ["%s on %d of %d days marked Not or Part" % (r, n, short_days)
            for r, n in reasons if n >= REASON_MIN_N and n <= short_days]


# ---------- text for the context packet (code-written, so the coach only copies) ----------

def _fmt(x):
    """A per-day figure with one decimal. Below 0.1 two decimals, so a slow pace never shows as 0.0."""
    if 0 < x < 0.1 - EPS:
        return "%.2f" % x
    return "%.1f" % x


def _num(x):
    """Typical and best pace: a description of the past, rounded half up."""
    return _fmt(round_half_up(x, 0.01) if 0 < x < 0.1 - EPS else round_half_up(x, 0.1))


def _num_up(x):
    """Required pace: rounded up, so the shown pace really reaches the total."""
    return _fmt(round_up(x, 0.01) if 0 < x < 0.1 - EPS else round_up(x, 0.1))


def off_days_phrase(schedule):
    kind, val = parse_schedule(schedule)
    if kind != "days":
        return ""
    off = [i for i in range(7) if i not in val]
    if not off or len(off) > 3:
        return ""
    names = {0: "Mondays", 1: "Tuesdays", 2: "Wednesdays", 3: "Thursdays", 4: "Fridays", 5: "Saturdays", 6: "Sundays"}
    return " (%s off)" % " and ".join(names[i] for i in off)


STATUS_TEXT = {
    "ok": "Status: reaches the total at the typical pace.",
    "needs_best": "Status: reaches the total only at the best-fortnight pace, not at the typical pace.",
    "short": "Status: does not reach the total, not even at the best-fortnight pace.",
    "short_no_best": "Status: does not reach the total at the typical pace.",
}


def backlog_lines(bid, name, total, done, schedule, p, window_answered, answer_rate, availability="not set"):
    remaining = total - done
    head = 'Backlog %s "%s": total %g, done %g, remaining %g' % (bid, name, total, done, remaining)
    st = p["status"]
    if st in ("done", "past_deadline"):
        return [head + (", complete." if st == "done" else ", the deadline has passed.")]
    head += ", scheduled days left %d%s, required %s per day." % (p["days_left"], off_days_phrase(schedule),
                                                                    _num_up(p["required_per_day"]))
    lines = [head]
    if st == "no_data":
        lines.append("  Not enough data yet: fewer than %d answered days in the last %d days." % (MIN_ANSWERED, WINDOW))
    else:
        tail_t = (", short by %g." % p["short_typical"]) if p["short_typical"] > 0 else "."
        lines.append("  Typical pace (last %d days, %d answered days): %s per day, reaches %g of %g%s"
                     % (WINDOW, window_answered, _num(p["typical_per_day"]), p["reaches_typical"], remaining, tail_t))
        if "best_per_day" in p:
            tail_b = (", short by %g." % p["short_best"]) if p["short_best"] > 0 else "."
            lines.append("  Best fortnight so far: %s per day, reaches %g of %g%s"
                         % (_num(p["best_per_day"]), p["reaches_best"], remaining, tail_b))
        key = st if (st != "short" or "best_per_day" in p) else "short_no_best"
        lines.append("  " + STATUS_TEXT[key])
    ar = "Answer rate %d%%." % answer_rate if answer_rate is not None else "Answer rate not tracked."
    lines.append("  %s Availability: %s." % (ar, availability))
    return lines


def review_options_line(opts):
    return "Review options: " + ", ".join("%s (%s, %s)" % (o["date"], o["weekday"], o["after"]) for o in opts) + "."


def goal_line(gid, title, deadline):
    return 'Goal %s "%s", deadline %s.' % (gid, title, deadline)


def target_line(tid, title, gid, minimum, target, schedule, done, answered):
    return ('Target %s "%s" (%s): minimum %g, target %g, %s. Last %d days: done %d of %d answered days.'
            % (tid, title, gid, minimum, target, schedule, WINDOW, done, answered))
