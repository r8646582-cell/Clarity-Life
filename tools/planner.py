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


def version_on(versions, day):
    """The version in force on day, or None before the first one."""
    day = d(day)
    out = None
    for v in versions:
        if d(v["from"]) <= day:
            out = v
        else:
            break
    return out


def _window(checkins, schedule, first, last, rest_dates, versions=None):
    rest = {d(x) for x in rest_dates}
    if versions:
        sched = set()
        has_count = False
        cur = first
        while cur <= last:
            if cur not in rest:
                v = version_on(versions, cur)
                s = v["schedule"] if v else schedule
                status = v["status"] if v else "active"
                if status == "active":
                    kind, val = parse_schedule(s)
                    if kind == "days":
                        if cur.weekday() in val:
                            sched.add(cur)
                    else:
                        has_count = True
            cur += timedelta(days=1)
        if has_count:
            sched = None
    else:
        kind, _ = parse_schedule(schedule)
        sched = set(scheduled_dates(schedule, first, last, rest)) if kind == "days" else None
    answered = [c for c in checkins
                if first <= d(c["day"]) <= last and c["level"] != "rest"
                and (sched is None or d(c["day"]) in sched)]
    scheduled = len(sched) if sched is not None else None
    amount = sum(c.get("amount", 0) for c in answered)
    done = sum(1 for c in answered if c["level"] in DONE_LEVELS)
    return {"answered": len(answered), "scheduled": scheduled, "amount": amount, "done": done}


def typical_pace(checkins, schedule, today, rest_dates=(), start=None, versions=None):
    """Pace over the 14 days before today. None when fewer than MIN_ANSWERED answered scheduled days.
    With start (the first day it was tracked), the window never reaches before it: days before a plan existed
    are not unanswered days. A plan that starts today or later has no window, so no answer rate yet.
    With versions (target_version), the schedule in force on each day is used (Q3)."""
    today = d(today)
    first = today - timedelta(days=WINDOW)
    if start is not None and d(start) > first:
        first = d(start)
    last = today - timedelta(days=1)
    if first > last:
        return {"answered": 0, "scheduled": 0, "amount": 0, "done": 0, "pace": None, "answer_rate": None}
    w = _window(checkins, schedule, first, last, rest_dates, versions)
    w["pace"] = w["amount"] / w["answered"] if w["answered"] >= MIN_ANSWERED else None
    w["answer_rate"] = (round_half_up(100 * w["answered"] / w["scheduled"]) if w["scheduled"] else None)
    return w


def best_pace(checkins, schedule, today, rest_dates=(), start=None, versions=None):
    """Best pace over any 14-day window that ends before today and holds at least MIN_ANSWERED answered days.
    The typical window (ending yesterday) is always one of the candidates, so best >= typical whenever both exist.
    With start, check-ins before it are not counted, the same as in typical_pace."""
    today = d(today)
    if start is not None:
        checkins = [c for c in checkins if d(c["day"]) >= d(start)]
    days = [d(c["day"]) for c in checkins if d(c["day"]) < today]
    if not days:
        return None
    last_end = today - timedelta(days=1)
    end = min(min(days) + timedelta(days=WINDOW - 1), last_end)
    best = None
    while end <= last_end:
        w = _window(checkins, schedule, end - timedelta(days=WINDOW - 1), end, rest_dates, versions)
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


def project(remaining, left, typical, best, step=1.0, deadline_passed=None):
    """Where the backlog ends up at the typical and the best pace, in the unit, never rounded in its favour.
    With no scheduled day left: past_deadline, or no_days_left when deadline_passed is False (the deadline is
    today and today is answered, or no scheduled day falls before it), so the deadline day never reads as passed.
    With no typical pace (fewer than MIN_ANSWERED answers in the window, after a gap) the best fortnight from
    his own history is still given; the status stays no_data, since a verdict needs the typical pace."""
    out = {"remaining": remaining, "days_left": left}
    if remaining <= 0:
        out["status"] = "done"
        return out
    if left <= 0:
        out["status"] = "no_days_left" if deadline_passed is False else "past_deadline"
        return out
    out["required_per_day"] = remaining / left
    if typical is None:
        if best is not None:
            reach_b = _reach(best, left, remaining, step)
            out["best_per_day"], out["reaches_best"] = best, reach_b
            out["short_best"] = remaining - reach_b
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


FINISH_HORIZON = 366      # a plan that has not finished a year after its deadline is reported as not finishing


def plan_finish(steps, remaining, today, deadline, today_answered=False, rest_dates=()):
    """Where the plan's own steps finish a backlog, counted day by day: each step adds its target on every
    scheduled day from its start to its end (None = open). Counting starts today, or tomorrow when today is
    already answered (today's work is then in remaining). Steps that overlap add up. Rest dates add nothing.
    A step with an Nx/week schedule has no fixed days, so the plan cannot be dated: status "count_schedule".
    Statuses: done, finishes (on or before the deadline), late (after it), never (no planned work left)."""
    today, deadline = d(today), d(deadline)
    out = {"remaining": remaining}
    if remaining <= EPS:
        out["status"] = "done"
        return out
    if not steps:
        out["status"] = "never"
        return out
    parsed = []
    for st in steps:
        kind, val = parse_schedule(st["schedule"])
        if kind != "days":
            out["status"] = "count_schedule"
            return out
        parsed.append((d(st["start"]), d(st["end"]) if st.get("end") else None, val, st["target"]))
    rest = {d(x) for x in rest_dates}
    cur = today + timedelta(days=1) if today_answered else today
    last = deadline + timedelta(days=FINISH_HORIZON)
    total, by_deadline, finish = 0.0, None, None
    while cur <= last:
        if cur not in rest:
            for start, end, days, target in parsed:
                if start <= cur and (end is None or cur <= end) and cur.weekday() in days:
                    total += target
        if cur == deadline:
            by_deadline = total
        if total >= remaining - EPS:
            finish = cur
            break
        if all(end is not None and end < cur for _, end, _, _ in parsed):
            break   # every step has ended and the total is not reached
        cur += timedelta(days=1)
    if by_deadline is None:     # never reached the deadline day in the loop
        # counting began after the deadline (its day is answered): nothing more lands by it; else it stopped
        # before the deadline, finished early or with every step ended
        by_deadline = 0.0 if (today + timedelta(days=1) if today_answered else today) > deadline else total
    out["reaches_by_deadline"] = min(remaining, round_down(by_deadline)) if by_deadline < remaining - EPS else remaining
    if finish is None:
        out["status"] = "never"
        return out
    out["finish"] = finish.isoformat()
    out["days_spare"] = (deadline - finish).days
    out["status"] = "finishes" if finish <= deadline else "late"
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


# ---------- a backlog's facts, from the targets linked to it (PlanBlocks.backlogFacts) ----------

def backlog_schedule(schedules):
    """The days a backlog is worked on, from the schedules of its live linked targets.
    One distinct schedule: that one, as written. Several, all with fixed days: the days any of them is on,
    as a day list (daily when that is every day), so a target on other days than the first one's is never
    left out of the days left or the pace. Any Nx/week among them: a count, min(7, the sessions of all of them
    per week). None: daily."""
    distinct = []
    for s in schedules:
        if s not in distinct:
            distinct.append(s)
    if not distinct:
        return "daily"
    if len(distinct) == 1:
        return distinct[0]
    parsed = [parse_schedule(s) for s in distinct]
    if all(k == "days" for k, _ in parsed):
        days = sorted(set().union(*[v for _, v in parsed]))
        return "daily" if len(days) == 7 else ",".join(DAYS[i] for i in days)
    n = sum(v if k == "count" else len(v) for k, v in parsed)
    return "%dx/week" % min(7, n)


LEVEL_ORDER = ["rest", "not", "minimum", "target", "beyond"]


def merge_by_day(rows):
    """One row per day: amounts added (rest rows add nothing), done when any was done (the highest done level),
    rest only when every answer was rest, else not. The reason is the first one given."""
    by = {}
    for r in rows:
        by.setdefault(d(r["day"]), []).append(r)
    out = []
    for day in sorted(by):
        lst = by[day]
        if all(r["level"] == "rest" for r in lst):
            level = "rest"
        elif any(r["level"] in DONE_LEVELS for r in lst):
            level = max((r["level"] for r in lst if r["level"] in DONE_LEVELS), key=LEVEL_ORDER.index)
        else:
            level = "not"
        reason = next((r.get("reason") for r in lst if r.get("reason")), None)
        row = {"day": day.isoformat(), "level": level, "amount": sum(r.get("amount", 0) for r in lst if r["level"] != "rest")}
        if reason:
            row["reason"] = reason
        out.append(row)
    return out


def target_versions(t):
    """A target's versions; one with none recorded (made before schema 8, or restored from an older backup) is
    read as its current values, active, from its start day."""
    if t.get("versions"):
        return t["versions"]
    return [{"from": t.get("start") or "0001-01-01", "minimum": t.get("minimum", 0), "target": t["target"],
             "schedule": t["schedule"], "status": "active"}]


def backlog_facts(total, start_done, created_day, deadline, targets, today, done_elsewhere=(), first_start=None,
                  rest_dates=(), off_targets=()):
    """Everything PLAN_STATE says about one backlog. targets: its live linked targets, each
    {schedule, target, start, end, checkins}; done_elsewhere: check-ins of its paused or ended targets.
    - done = start_done + every amount entered from created_day on (earlier work is already in start_done).
    - the pace reads every answer toward it, ended ramp steps included, from the first start of any target
      ever linked to it (first_start; else the earliest live start), so it does not restart at every step.
    - days left are counted on backlog_schedule's days, from today (tomorrow when today is answered), and
      never before the first live target starts: days before the plan begins have no work planned.
    - the plan's own steps are the live targets with a start, in start order.
    - with no target ever linked there is no pace window and no answer rate.
    - progress: how far the plan has got (plan_progress) over every target ever linked to it with a start day,
      live ones and off_targets (its paused and ended ones, {schedule, target, start, end, versions}) together."""
    today, deadline = d(today), d(deadline)
    rest = {d(x) for x in rest_dates}
    schedule = backlog_schedule([t["schedule"] for t in targets])
    merged = merge_by_day([c for t in targets for c in t.get("checkins", [])] + list(done_elsewhere))
    done = start_done + sum(c["amount"] for c in merged if d(c["day"]) >= d(created_day))
    remaining = total - done
    today_answered = any(d(c["day"]) == today for c in merged)
    count_from = today + timedelta(days=1) if today_answered else today
    starts = [d(t["start"]) for t in targets if t.get("start")]
    if starts and min(starts) > count_from:
        count_from = min(starts)
    left = scheduled_count(schedule, count_from, deadline, rest)
    # Nothing ever linked: nothing was asked, so there is no window (no answer rate of 0%).
    start = d(first_start) if first_start else (min(starts) if starts else (today if not targets and not done_elsewhere else None))
    target_vers = target_versions(targets[0]) if len(targets) == 1 else None
    tp = typical_pace(merged, schedule, today, rest, start, versions=target_vers)
    best = best_pace(merged, schedule, today, rest, start, versions=target_vers)
    steps = sorted([{"target": t["target"], "schedule": t["schedule"], "start": t["start"], "end": t.get("end")}
                    for t in targets if t.get("start")], key=lambda x: d(x["start"]))
    return {
        "done": done, "schedule": schedule, "days_left": left,
        "projection": project(remaining, left, tp["pace"], best, deadline_passed=deadline < today),
        "window_answered": tp["answered"], "answer_rate": tp["answer_rate"],
        "finish": plan_finish(steps, remaining, today, deadline, today_answered, rest) if steps else None,
        "progress": plan_progress([{"start": t["start"], "end": t.get("end"), "versions": target_versions(t)}
                                   for t in list(targets) + list(off_targets) if t.get("start")],
                                  merged, today, today_answered, rest) if steps else None,
    }


# ---------- target history (schema 8): what a plan asked for, and before/after a change ----------
#
# A target's values over time are versions: {from, minimum, target, schedule, status}, one per day a change took
# effect (the day he tapped it, or the target's start when that is later), oldest first. Before schema 8 nothing
# was recorded, so the migration gives every old target one version, its values then, from its start day.


def planned_amount(targets, first, last, rest_dates=()):
    """What the plan asked for from first to last: on every day that is not a planned rest day, each target adds
    the target amount in force that day when it is within its start and end, active then, and scheduled that day
    under the schedule in force then. None when any version in force on one of those days is an Nx/week
    schedule (it has no fixed days, so code cannot say what a given day asked for).
    targets: [{start, end, versions}]."""
    first, last = d(first), d(last)
    rest = {d(x) for x in rest_dates}
    asked, days = 0.0, set()
    cur = first
    while cur <= last:
        if cur not in rest:
            for t in targets:
                if d(t["start"]) > cur or (t.get("end") and d(t["end"]) < cur):
                    continue
                v = version_on(t["versions"], cur)
                if v is None or v["status"] != "active":
                    continue
                kind, val = parse_schedule(v["schedule"])
                if kind != "days":
                    return None
                if cur.weekday() in val:
                    asked += v["target"]
                    days.add(cur)
        cur += timedelta(days=1)
    return {"asked": asked, "days": len(days)}


def plan_progress(targets, checkins, today, today_answered=False, rest_dates=()):
    """How far the plan's own steps have got so far: from the first step's start to yesterday (to today when
    today is answered), what the steps asked for (planned_amount) against what was done (every amount entered
    on those days, merged per day). None before the first scheduled day has passed, or when it cannot be counted.
    targets: every target ever linked to the backlog, ended steps included, as in planned_amount."""
    if not targets:
        return None
    today = d(today)
    first = min(d(t["start"]) for t in targets)
    last = today if today_answered else today - timedelta(days=1)
    if last < first:
        return None
    p = planned_amount(targets, first, last, rest_dates)
    if p is None or p["days"] == 0:
        return None
    done = sum(c.get("amount", 0) for c in checkins if first <= d(c["day"]) <= last and c["level"] != "rest")
    return {"from": first.isoformat(), "to": last.isoformat(), "asked": p["asked"], "done": done,
            "days": p["days"]}


CHANGE_LOOKBACK = 90      # a change older than this is no longer news


def _changed_fields(a, b):
    out = []
    for key, label in (("target", "target"), ("minimum", "minimum")):
        if abs(a[key] - b[key]) > EPS:
            out.append("%s %g \u2192 %g" % (label, a[key], b[key]))
    if a["schedule"] != b["schedule"]:
        out.append("%s \u2192 %s" % (a["schedule"], b["schedule"]))
    return out


def _side(checkins, schedule, first, last, rest):
    if first > last:
        return {"answered": 0, "done": 0, "amount": 0}
    w = _window(checkins, schedule, first, last, rest)
    return {"answered": w["answered"], "done": w["done"], "amount": w["amount"]}


def change_compare(checkins, versions, start, today, rest_dates=()):
    """The latest change to a target's numbers or schedule in the last CHANGE_LOOKBACK days, with how it went in
    the WINDOW days before it (under the old schedule) and since (under the new one, up to yesterday). None when
    there is no such change, or fewer than REASON_MIN_N answered days on either side. A pause or a resume is not a
    change of the plan (status is left out)."""
    today = d(today)
    rest = {d(x) for x in rest_dates}
    for i in range(len(versions) - 1, 0, -1):
        before, after = versions[i - 1], versions[i]
        fields = _changed_fields(before, after)
        if not fields:
            continue
        day = d(after["from"])
        if day >= today or day < today - timedelta(days=CHANGE_LOOKBACK):
            return None
        b_first = max(day - timedelta(days=WINDOW), d(start))
        b = _side(checkins, before["schedule"], b_first, day - timedelta(days=1), rest)
        a = _side(checkins, after["schedule"], day, today - timedelta(days=1), rest)
        if b["answered"] < REASON_MIN_N or a["answered"] < REASON_MIN_N:
            return None
        return {"day": day.isoformat(), "fields": fields, "before_days": (day - b_first).days,
                "before": b, "after": a}
    return None


# ---------- a day's load across every goal, against his hours (Max plan Phase 6, step 3) ----------
#
# Several goals compete for the same hours. Code adds up, for each of the next LOAD_DAYS days, the minutes the
# live targets scheduled that day take, from his own estimates, and sets them against the minutes he says he has
# that weekday. The coach is told the sums; it never adds them up itself.

LOAD_DAYS = 7
MINUTE_UNITS = {"min", "mins", "minute", "minutes"}
HOUR_UNITS = {"h", "hr", "hrs", "hour", "hours"}


def session_minutes(unit, target, minutes=None):
    """Minutes one session at the target level takes: the target itself when its unit is minutes or hours,
    else his own estimate (minutes), else None (not known, never guessed)."""
    u = (unit or "").strip().lower()
    if u in MINUTE_UNITS:
        return float(target)
    if u in HOUR_UNITS:
        return float(target) * 60
    return float(minutes) if minutes else None


def parse_hours(text):
    """His hours, as the app stores them: seven numbers of minutes, Monday to Sunday, comma separated, each
    0 to 1440. Anything else is None (not set); it is never read loosely."""
    if not isinstance(text, str):
        return None
    parts = [x.strip() for x in text.split(",")]
    if len(parts) != 7:
        return None
    out = []
    for x in parts:
        if not re.fullmatch(r"\d{1,4}(?:\.\d+)?", x):
            return None
        v = float(x)
        if v > 1440:
            return None
        out.append(v)
    return out


def _min_text(m):
    return "none" if m <= EPS else "%g min" % round_half_up(m)


def hours_text(hours):
    """'Mon to Fri 120 min, Sat 240 min, Sun none': runs of days with the same minutes, Monday first."""
    out, i = [], 0
    while i < 7:
        j = i
        while j + 1 < 7 and abs(hours[j + 1] - hours[i]) < EPS:
            j += 1
        days = DAYS[i] if i == j else "%s to %s" % (DAYS[i], DAYS[j])
        out.append("%s %s" % (days, _min_text(hours[i])))
        i = j + 1
    return ", ".join(out)


def day_load(targets, today, rest_dates=(), days=LOAD_DAYS):
    """For each of the days from today: whether it is a planned rest day, the minutes of the targets scheduled
    that day that have a session time (timed, by id), and the ids of those that have none (untimed). A target
    counts on a day within its start and end when the version in force then (the latest for days to come) is
    active and scheduled that day. Nx/week targets have no fixed days: they are listed apart, with their
    sessions a week and minutes a week (None without a session time).
    targets: [{id, schedule, target, start, end, versions, unit, minutes}]."""
    today = d(today)
    rest = {d(x) for x in rest_dates}
    out, weekly = [], []
    for t in targets:
        v = version_on(target_versions(t), today) or target_versions(t)[0]
        kind, val = parse_schedule(v["schedule"])
        runs = ((not t.get("end") or d(t["end"]) >= today)
                and (not t.get("start") or d(t["start"]) <= today + timedelta(days=days - 1)))
        if kind == "count" and v["status"] == "active" and runs:
            m = session_minutes(t.get("unit"), v["target"], t.get("minutes"))
            weekly.append({"id": t["id"], "per_week": val, "minutes": None if m is None else m * val})
    for i in range(days):
        day = today + timedelta(days=i)
        row = {"day": day.isoformat(), "rest": day in rest, "minutes": 0.0, "timed": [], "untimed": []}
        if not row["rest"]:
            for t in targets:
                if t.get("start") and d(t["start"]) > day or (t.get("end") and d(t["end"]) < day):
                    continue
                v = version_on(target_versions(t), day)
                if v is None or v["status"] != "active":
                    continue
                kind, val = parse_schedule(v["schedule"])
                if kind != "days" or day.weekday() not in val:
                    continue
                m = session_minutes(t.get("unit"), v["target"], t.get("minutes"))
                if m is None:
                    row["untimed"].append(t["id"])
                else:
                    row["minutes"] += m
                    row["timed"].append(t["id"])
        out.append(row)
    return {"days": out, "weekly": weekly}


def load_lines(load, names, hours=None):
    """The load as the coach reads it. names: [[id, label, schedule]], label like 'T4 "Reading"'.
    Nothing (an empty list) when no target has a session time and his hours are not set."""
    names = {n[0]: (n[1], n[2]) for n in names}
    days = load["days"]
    timed_any = any(r["timed"] for r in days) or any(w["minutes"] is not None for w in load["weekly"])
    if not timed_any and hours is None:
        return []
    parts = []
    for r in days:
        day = d(r["day"])
        label = "%s %d %s" % (DAYS[day.weekday()], day.day, _MONTHS[day.month - 1])
        if r["rest"]:
            parts.append("%s planned rest day" % label)
            continue
        m = round_half_up(r["minutes"])
        if hours is None:
            parts.append("%s %g min" % (label, m))
            continue
        have = round_half_up(hours[day.weekday()])
        over = m - have
        parts.append("%s %g of %g min%s" % (label, m, have, (", %g over" % over) if over > EPS else ""))
    head = ("Time planned, next %d days (minutes from his targets and his own time estimates; his hours from Plan): " % len(days)
            if hours is not None else
            "Time planned, next %d days (minutes from his targets and his own time estimates; his hours are not set): " % len(days))
    lines = [head + "; ".join(parts) + "."]
    untimed = []
    for r in days:
        for i in r["untimed"]:
            if i not in untimed:
                untimed.append(i)
    if untimed:
        lines.append("Not in these minutes (no time estimate): %s." % ", ".join(names[i][0] for i in untimed))
    for w in load["weekly"]:
        lab, sched = names[w["id"]]
        if w["minutes"] is None:
            lines.append("%s is %s, not on fixed days, and has no time estimate." % (lab, sched))
        else:
            lines.append("%s is %s, not on fixed days: %g min a week, not in the days above."
                         % (lab, sched, round_half_up(w["minutes"])))
    if hours is not None:
        planned = sum(r["minutes"] for r in days if not r["rest"]) + sum(
            w["minutes"] * len(days) / 7.0 for w in load["weekly"] if w["minutes"] is not None)
        have = sum(hours[d(r["day"]).weekday()] for r in days if not r["rest"])
        lines.append("Next %d days in all: %g of %g min available%s." % (
            len(days), round_half_up(planned), round_half_up(have),
            ", Nx/week targets included" if any(w["minutes"] is not None for w in load["weekly"]) else ""))
    return lines


# ---------- review facts (Max plan Phase 6, step 4) ----------

TREND_WEEKS = 4


def done_trend(checkins, schedule, last, rest_dates=(), weeks=TREND_WEEKS, start=None):
    """The done rate of each of the last `weeks` 7-day stretches ending on `last`, oldest first (spec 6.2: the
    28-day trend per target, rest days left out). A stretch's rate needs at least REASON_MIN_N answered scheduled
    days, else None; days before start (the target's first day) are not part of it."""
    last = d(last)
    out = []
    for k in range(weeks - 1, -1, -1):
        b = last - timedelta(days=7 * k)
        a = b - timedelta(days=6)
        if start is not None and d(start) > a:
            a = d(start)
        if a > b:
            out.append({"first": (b - timedelta(days=6)).isoformat(), "last": b.isoformat(), "answered": 0,
                        "done": 0, "rate": None})
            continue
        w = _window(checkins, schedule, a, b, rest_dates)
        rate = round_half_up(100 * w["done"] / w["answered"]) if w["answered"] >= REASON_MIN_N else None
        out.append({"first": a.isoformat(), "last": b.isoformat(), "answered": w["answered"], "done": w["done"],
                    "rate": rate})
    return out


def trend_line(trend):
    """'Done rate by week, last 4 weeks (oldest first): 50%, 60%, too few answers, 67%.' None when fewer than two
    weeks have a rate (a trend needs two points)."""
    if sum(1 for t in trend if t["rate"] is not None) < 2:
        return None
    return "Done rate by week, last %d weeks (oldest first): %s." % (
        len(trend), ", ".join("%d%%" % t["rate"] if t["rate"] is not None else "too few answers" for t in trend))


def plan_period_line(bid, name, asked, done):
    """'B1 "Lectures": the plan asked for 14 in this period, done 12, behind by 2.' (plan versus actual)."""
    gap = done - asked
    tail = ("behind by %g" % round_half_up(-gap, 0.1) if gap < -EPS else
            "ahead by %g" % round_half_up(gap, 0.1) if gap > EPS else "exactly on it")
    return '%s "%s": the plan asked for %g in this period, done %g, %s.' % (
        bid, name, round_half_up(asked, 0.1), round_half_up(done, 0.1), tail)


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
    if st in ("done", "past_deadline", "no_days_left"):
        return [head + {"done": ", complete.", "past_deadline": ", the deadline has passed.",
                        "no_days_left": ", no scheduled days left before the deadline."}[st]]
    head += ", scheduled days left %d%s, required %s per day." % (p["days_left"], off_days_phrase(schedule),
                                                                    _num_up(p["required_per_day"]))
    lines = [head]
    if st == "no_data":
        lines.append("  Not enough data yet: fewer than %d answered days in the last %d days." % (MIN_ANSWERED, WINDOW))
        if "best_per_day" in p:     # after a gap his own best stretch is still known
            tail_b = (", short by %g." % p["short_best"]) if p["short_best"] > 0 else "."
            lines.append("  Best fortnight so far: %s per day, reaches %g of %g%s"
                         % (_num(p["best_per_day"]), p["reaches_best"], remaining, tail_b))
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


def _step_text(st):
    """One step of a plan: '2 per day, daily, 14 Oct to 20 Oct' or '4 per day, daily, from 28 Oct'."""
    a, b = d(st["start"]), d(st["end"]) if st.get("end") else None
    when = ("%d %s to %d %s" % (a.day, _MONTHS[a.month - 1], b.day, _MONTHS[b.month - 1]) if b
            else "from %d %s" % (a.day, _MONTHS[a.month - 1]))
    return "%g per day, %s, %s" % (st["target"], st["schedule"], when)


def plan_line(bid, name, steps, p):
    """Where the plan's own steps take a backlog, from plan_finish. None when there is nothing to say."""
    st = p["status"]
    if st == "done" or not steps:
        return None
    head = 'Plan steps for %s "%s": %s.' % (bid, name, "; ".join(_step_text(x) for x in steps))
    if st == "count_schedule":
        return head + " These steps have no fixed days, so the app cannot date where they finish."
    if st == "finishes":
        f = d(p["finish"])
        spare = p["days_spare"]
        when = ("on the deadline day" if spare == 0 else
                "1 day before the deadline" if spare == 1 else "%d days before the deadline" % spare)
        return head + " At these steps it finishes on %d %s (%s), %s." % (f.day, _MONTHS[f.month - 1],
                                                                          _WEEKDAYS[f.weekday()], when)
    short = p["remaining"] - p["reaches_by_deadline"]
    tail = " At these steps it reaches %g of %g by the deadline, short by %g" % (
        p["reaches_by_deadline"], p["remaining"], short)
    if st == "never":
        return head + tail + ", and the steps end before the total."
    return head + tail + "."


def goal_line(gid, title, deadline):
    return 'Goal %s "%s", deadline %s.' % (gid, title, deadline)


def target_line(tid, title, gid, minimum, target, schedule, done, answered):
    return ('Target %s "%s" (%s): minimum %g, target %g, %s. Last %d days: done %d of %d answered days.'
            % (tid, title, gid, minimum, target, schedule, WINDOW, done, answered))


def plan_progress_line(bid, name, p):
    """'So far on the plan for B1 "CAF lectures" (14 Oct to 23 Oct): the steps asked for 23, done 20, behind by 3.'"""
    a, b = d(p["from"]), d(p["to"])
    gap = p["done"] - p["asked"]
    tail = ("behind by %g" % round_half_up(-gap, 0.1) if gap < -EPS else
            "ahead by %g" % round_half_up(gap, 0.1) if gap > EPS else "exactly on it")
    when = ("%d %s" % (a.day, _MONTHS[a.month - 1]) if a == b else
            "%d %s to %d %s" % (a.day, _MONTHS[a.month - 1], b.day, _MONTHS[b.month - 1]))
    return 'So far on the plan for %s "%s" (%s): the steps asked for %g, done %g, %s.' % (
        bid, name, when, round_half_up(p["asked"], 0.1), round_half_up(p["done"], 0.1), tail)


def change_line(tid, name, c):
    """'T3 "Lectures": changed on 20 Oct (target 1 → 2). In the 14 days before: done on 9 of 12 answered days,
    1.0 per answered day. Since: done on 4 of 5, 1.4 per answered day.'"""
    day = d(c["day"])
    b, a = c["before"], c["after"]
    return ('%s "%s": changed on %d %s (%s). In the %d days before: done on %d of %d answered days, %s per answered '
            'day. Since: done on %d of %d, %s per answered day.' % (
                tid, name, day.day, _MONTHS[day.month - 1], ", ".join(c["fields"]), c["before_days"], b["done"],
                b["answered"], _num(b["amount"] / b["answered"]), a["done"], a["answered"],
                _num(a["amount"] / a["answered"])))
