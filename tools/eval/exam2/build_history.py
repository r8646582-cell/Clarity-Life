"""Builds fixtures for memory exam 2 (docs/MAX-PLAN.md, Phase 0 step 3): one invented year of "Rafi", a CAF
student, with everything the app keeps, so the exam can ask about plans, check-ins, promises by outcome and
month, people, letters, reviews, replaced facts, patterns months apart, and vague follow-ups.

Everything is invented. Deterministic: run it again and history.json is byte-identical.

    python3 tools/eval/exam2/build_history.py      (writes tools/eval/exam2/history.json)

Ids used by questions.json:
  s:C       conversation C          m:C.K   his K-th message (1-based) in conversation C
  p:KEY     current profile entry   pold:KEY  a replaced profile value (its source conversations are in the row)
  who:NAME  a person                n:N     a note (active or retired)
  pr:N      a promise               g:N     a goal          rev:N  a goal revision
  t:N       a target (PLAN_STATE)   ckf:N   the counted check-in facts of target N (CHECKIN_FACTS)
  ck:N      one check-in            l:N     a weekly letter  rv:N   a review     ch:N  a chapter
  xn:N, xs:C  must never be seen: a note he deleted, an off-the-record conversation
"""
import json
import os
import random
from datetime import date, datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
TZ = timezone(timedelta(hours=5))  # Asia/Karachi, no daylight saving
TODAY = "2026-10-14"
NOW = "2026-10-14 10:00"


def ms(local):
    return int(datetime.strptime(local, "%Y-%m-%d %H:%M").replace(tzinfo=TZ).timestamp() * 1000)


def day_of(local):
    return local[:10]


conversations, messages = [], []
_mid = [0]


def conv(cid, start, title, lines, summary="", coach=None, off=False, gap=4, chat_type="free"):
    """lines: his messages, gap minutes apart. coach: the coach's replies, interleaved after each line."""
    t0 = datetime.strptime(start, "%Y-%m-%d %H:%M")
    k = 0
    for i, text in enumerate(lines):
        at = (t0 + timedelta(minutes=gap * 2 * i)).strftime("%Y-%m-%d %H:%M")
        _mid[0] += 1
        k += 1
        messages.append({"id": _mid[0], "conversation": cid, "k": k, "role": "user", "text": text, "at": ms(at)})
        if coach and i < len(coach) and coach[i]:
            _mid[0] += 1
            cat = (t0 + timedelta(minutes=gap * 2 * i + gap)).strftime("%Y-%m-%d %H:%M")
            messages.append({"id": _mid[0], "conversation": cid, "k": None, "role": "coach", "text": coach[i], "at": ms(cat)})
    conversations.append({"id": cid, "title": title, "start": ms(start), "summary": summary, "off_the_record": off,
                          "chat_type": chat_type})


# ---------------------------------------------------------------- the story, in order

conv(1, "2025-11-03 23:40", "Can't sleep again", [
    "It's almost midnight and I'm still scrolling reels in bed. Tomorrow I have to be at Pellow Freight by nine.",
    "This happens every night. I tell myself ten minutes and it's suddenly 2 am.",
    "Mrs. Danvers already noticed I came in late twice last week.",
], "He scrolls his phone in bed past midnight most nights, then is late and foggy for work at Pellow Freight; "
   "his manager Mrs. Danvers has noticed him arriving late.")

conv(2, "2025-11-09 19:15", "Why CAF", [
    "I want to finish CAF so I'm not stuck as an accounts assistant forever.",
    "My first paper is CAF 1 Financial Accounting, the exam is on 8 December.",
    "Studying after work is hard, I only manage maybe an hour on good days.",
], "He is studying for the CAF qualification to move beyond his accounts assistant job; first paper CAF 1 "
   "Financial Accounting on 8 December; manages about an hour of study after work.")

conv(3, "2025-11-16 11:05", "Hina's call", [
    "Hina called from Islamabad, she always calls on Sundays. She said she believes I'll pass.",
    "She's the only one in the family who gets why I'm doing this.",
], "His sister Hina in Islamabad calls every Sunday and backs his CAF plan; he feels she is the one in the "
   "family who understands it.")

conv(4, "2025-11-23 21:30", "Money worries", [
    "Ammi keeps asking how much the exams cost. She worries about money all the time.",
    "Each paper's fee is around 15,000 and I pay it from my salary.",
], "His mother worries about money and the cost of the exams; he pays each paper's fee (about 15,000) "
   "from his salary.")

conv(5, "2025-12-09 20:00", "After the exam", [
    "The CAF 1 exam was yesterday. The consolidation question destroyed me.",
    "I think I ran out of time on the last two questions.",
], "The day after sitting CAF 1 he felt the consolidation question went badly and he ran out of time on "
   "the last two questions.")

conv(6, "2026-01-12 18:45", "Result day", [
    "Result came. 46 in CAF 1. Pass is 50. I missed by four marks.",
    "I want to resit in June and this time actually do past papers under time.",
], "He failed CAF 1 with 46 (pass 50) and decided to resit in June, practising past papers under time.")

conv(7, "2026-01-18 07:30", "Starting the gym", [
    "I joined Ironline gym near the office, going before work at 6:30.",
    "The plan is three mornings a week, Monday Wednesday Friday.",
], "He joined Ironline gym near the office and plans to go before work on Monday, Wednesday and Friday "
   "at 6:30.")

conv(8, "2026-02-02 22:10", "Gym is working", [
    "Three weeks of gym now. On gym days I actually sleep before midnight because I'm tired.",
    "Funny how the gym fixed my phone-in-bed problem more than any app blocker.",
], "Three weeks into the gym; on gym days he sleeps before midnight, and the gym has helped his "
   "phone-in-bed habit more than app blockers did.")

conv(9, "2026-02-14 23:50", "Sana", [
    "Sana and I ended it tonight. I don't want this remembered.",
    "We'd been together two years.",
], "", off=True)

conv(10, "2026-03-02 19:00", "Quitting Pellow", [
    "I handed in my notice at Pellow Freight today. Last day is 31 March.",
    "Mrs. Danvers was kind about it, she said come back if it doesn't work out.",
    "I'll study full time until the resit and live on my savings, about 120,000.",
], "He resigned from Pellow Freight (last day 31 March) to study full time for the June resit on about "
   "120,000 of savings; Mrs. Danvers was kind and left the door open.")

conv(11, "2026-03-20 16:20", "Knee pain", [
    "My left knee hurts after squats. It's been swelling since Tuesday.",
    "I'll see a physio, Dr. Saleh, on Monday.",
], "His left knee hurts and swells after squats; he booked the physio Dr. Saleh.")

conv(12, "2026-03-24 13:00", "Physio verdict", [
    "Dr. Saleh says no squats or running for two months. Patellar tendon thing.",
    "So the gym is basically over for now. I'll walk instead, twenty minutes a day.",
], "Dr. Saleh ruled out squats and running for two months (patellar tendon), so he stopped the gym and "
   "switched to a 20-minute daily walk.")

conv(13, "2026-04-06 09:10", "Library routine", [
    "Full-time study, day 5. I go to the Model Town library at 9 and stay till 4.",
    "I focus way better at the library than at home, home has too many interruptions.",
], "Studying full time from 9 to 4 at the Model Town library, where he focuses far better than at home.")

conv(14, "2026-04-19 21:00", "Omar study buddy", [
    "Omar and I are doing past papers together on Thursdays at the library.",
    "He's sitting CAF 1 in June too. Having him there keeps me honest.",
], "His friend Omar, also resitting CAF 1 in June, does past papers with him on Thursdays at the library; "
   "it keeps him accountable.")

conv(15, "2026-05-03 18:30", "Moving to Millbrook", [
    "We're moving to Khala Rukhsana's house in Millbrook. The Kestrel Road flat rent went up again.",
    "I'll get the small room on the roof. It's quiet up there.",
], "The family is moving from the Kestrel Road flat to his aunt Khala Rukhsana's house in Millbrook after "
   "another rent rise; he will have the quiet rooftop room.")

conv(16, "2026-05-24 06:20", "Rooftop mornings", [
    "Since the move I study on the roof from 5:30 to 8 before it gets hot. Best focus I've ever had.",
    "The library is too far from Millbrook now, ninety minutes by bus.",
], "After the move he studies in the rooftop room from 5:30 to 8 am before the heat, with his best focus "
   "ever; the library is now 90 minutes away by bus.")

conv(17, "2026-06-10 22:00", "Resit nerves", [
    "Resit is in four days. I've done eleven past papers under time.",
    "My scores went from 44 to 58 across them.",
], "Four days before the resit he had done eleven timed past papers, scores rising from 44 to 58.")

conv(18, "2026-07-08 17:40", "Passed", [
    "I PASSED. 61 in CAF 1.",
    "Omar passed too, 55. He's moving to Dubai next week for a job though.",
], "He passed the CAF 1 resit with 61; Omar passed with 55 and is moving to Dubai for a job.")

conv(19, "2026-07-20 20:15", "Next papers", [
    "Next I'm doing CAF 5 Tax and CAF 3 Law together in December.",
    "Sir Waqas teaches Tax online, his course has 60 recorded lectures.",
], "He planned to sit CAF 5 Tax and CAF 3 Law together in December; Sir Waqas's online Tax course has 60 "
   "recorded lectures.", chat_type="plan")

conv(20, "2026-08-04 23:55", "Phone again", [
    "I'm back to scrolling in bed till 2. Without the gym I'm never tired at night.",
    "Mornings on the roof are ruined, I wake up at 9 and it's already too hot.",
], "The phone-in-bed habit is back since the gym stopped; he scrolls till 2 am and loses his rooftop "
   "mornings to the heat.")

conv(21, "2026-08-12 19:30", "Dropping Law", [
    "Two papers at once is too much. I'm dropping Law, just Tax in December.",
    "Sir Waqas agreed, he said Tax alone is plenty for one sitting.",
], "He dropped CAF 3 Law to sit only CAF 5 Tax in December, with Sir Waqas's agreement.", chat_type="plan")

conv(22, "2026-08-21 21:00", "Missed the form", [
    "I missed the exam form deadline on the 20th. There was a power cut all evening and the bank site was down.",
    "Late fee is 3,000 extra. So annoying.",
], "He missed the 20 August exam form deadline because of an evening power cut and a bank website "
   "outage; the late fee is 3,000.")

conv(23, "2026-08-30 18:00", "Focus: phone in bed", [
    "I want to properly fix the phone thing this time, not just try for a few days.",
    "Idea: phone charges in the kitchen from 11 pm.",
], "He started a focus on stopping phone use in bed; first idea is charging the phone in the kitchen "
   "from 11 pm.", chat_type="focus")

conv(24, "2026-09-06 08:00", "Kitchen charger works", [
    "Week one of the kitchen charger. Slept by 12 four nights out of seven.",
    "The nights it failed were when I took the phone 'just for an alarm'.",
], "First week of charging the phone in the kitchen: asleep by midnight four nights of seven; failures "
   "came from taking the phone back 'for the alarm'.", chat_type="focus")

conv(25, "2026-09-13 19:45", "Power cuts", [
    "Load shedding is back, two hours every evening, sometimes in the morning too.",
    "When the power goes, the lectures stop because the router dies.",
], "Load shedding returned (two hours most evenings, sometimes mornings), which stops his lectures "
   "because the router goes off.")

conv(26, "2026-09-20 17:00", "Paid the fee", [
    "Paid the Tax exam fee today, 18,000 with the late fee. Ammi was upset about the extra 3,000.",
    "I'm going to buy a small UPS for the router so power cuts don't stop lectures.",
], "He paid the Tax exam fee, 18,000 including the 3,000 late fee, which upset his mother; plans a small "
   "UPS for the router.")

conv(27, "2026-09-27 21:20", "Bought the UPS", [
    "Got the UPS for 9,500. Router stays on for about three hours now.",
    "Did two lectures during the evening cut for the first time.",
], "He bought a router UPS for 9,500, giving about three hours of internet; did two lectures during an "
   "evening power cut for the first time.")

conv(28, "2026-10-04 20:30", "Omar in Dubai", [
    "Omar video called from Dubai. He says the job is long hours but he's saving well.",
    "I miss our Thursday sessions, studying alone is lonelier.",
], "Omar called from Dubai (long hours, saving well); he misses their Thursday study sessions and finds "
   "studying alone lonelier.")

conv(29, "2026-10-10 22:40", "Behind on lectures", [
    "I'm at lecture 31 of 60 and the exam is 1 December. Sir Waqas said finish lectures by end of November.",
    "Tuesdays are always bad because Khala's family visits.",
], "He is at Tax lecture 31 of 60 with the exam on 1 December and Sir Waqas's advice to finish lectures by "
   "end of November; Tuesdays go badly because of family visits.", chat_type="plan")

conv(30, "2026-10-14 08:10", "Morning plan", [
    "Plan for today: two lectures before noon if the power holds, then nap after Zuhr.",
    "If the power goes I'll do the practice questions on paper instead.",
    "Lectures 32 and 33 are both on sales tax.",
], "", coach=[
    "Good plan. Two lectures before noon, nap after Zuhr, practice questions on paper if the power goes.",
    "That's a solid fallback.",
    "Sales tax it is. Tell me how it goes.",
])

# ---------------------------------------------------------------- filler: ordinary days, deterministic
FILLER_LINES = [
    "Long day. I'm tired but I got one study block done.",
    "Woke up late again, the morning felt wasted.",
    "Ate too much at dinner and now I feel sluggish.",
    "Watched a cricket match instead of studying, not proud of it.",
    "The weather is crazy hot today, hard to concentrate.",
    "Did some revision of old notes, felt okay.",
    "Had chai with the neighbours, nice evening.",
    "My head hurts a bit, probably not enough water.",
    "I kept checking my phone during the study session.",
    "Cleaned my room finally, feels better.",
    "Family dinner tonight, cousins came over.",
    "Bit anxious about the exam today for no clear reason.",
    "Went to the market for groceries with Ammi.",
    "Slept early for once, felt great this morning.",
    "Study session was slow, kept rereading the same page.",
    "Rain today, the roads flooded near the market.",
    "Helped my cousin with his maths homework.",
    "Feeling low energy, not sure why.",
    "Prayed Fajr on time and studied after, good start.",
    "Spent too long on YouTube tonight.",
]
FILLER_TITLES = ["Quick check-in", "Ordinary day", "Evening thoughts", "Tired", "Small win", "Rough morning"]

rng = random.Random(7)
key_days = {datetime.fromtimestamp(c["start"] / 1000, TZ).date() for c in conversations}
d = date(2025, 11, 5)
cid = 100
while d <= date(2026, 10, 11):  # never in the last two days, so LAST_CHAT stays the morning plan
    if d not in key_days and rng.random() < 0.30:
        cid += 1
        hour = rng.choice([8, 13, 18, 21, 22])
        lines = rng.sample(FILLER_LINES, rng.randint(2, 3))
        conv(cid, "%s %02d:%02d" % (d.isoformat(), hour, rng.randint(0, 50)), rng.choice(FILLER_TITLES), lines,
             "An ordinary day: " + lines[0][0].lower() + lines[0][1:].rstrip("."))
    d += timedelta(days=1)

# ---------------------------------------------------------------- profile, people, notes
profile = [
    {"key": "name", "value": "Rafi", "confirmed": True},
    {"key": "studying", "value": "CAF; passed CAF 1, now CAF 5 Tax for the 1 December 2026 sitting", "confirmed": True},
    {"key": "work", "value": "studying full time since April 2026", "confirmed": True},
    {"key": "home", "value": "Khala Rukhsana's house in Millbrook, the rooftop room", "confirmed": True},
    {"key": "exercise", "value": "20-minute daily walk (no squats or running, knee)", "confirmed": False},
    {"key": "tutor", "value": "Sir Waqas, online Tax course", "confirmed": False},
]
old_profile = [
    {"key": "work", "value": "accounts assistant at Pellow Freight", "sources": [1, 2, 10]},
    {"key": "home", "value": "flat on Kestrel Road", "sources": [15]},
    {"key": "exercise", "value": "Ironline gym, Mon/Wed/Fri 6:30", "sources": [7, 8]},
    {"key": "studying", "value": "CAF 5 Tax and CAF 3 Law in December", "sources": [19]},
]
people = [
    {"name": "Hina", "relation": "sister, Islamabad", "notes": "calls every Sunday; backs his CAF plan", "updated": "2025-11-16 11:05"},
    {"name": "Ammi", "relation": "mother", "notes": "worries about money and exam costs", "updated": "2026-09-20 17:00"},
    {"name": "Omar", "relation": "friend, former study buddy", "notes": "passed CAF 1 with 55; moved to Dubai in July 2026", "updated": "2026-10-04 20:30"},
    {"name": "Khala Rukhsana", "relation": "aunt", "notes": "the family lives in her Millbrook house; her family visits on Tuesdays", "updated": "2026-10-10 22:40"},
    {"name": "Mrs. Danvers", "relation": "former manager at Pellow Freight", "notes": "kind when he resigned", "updated": "2026-03-02 19:00"},
    {"name": "Dr. Saleh", "relation": "physio", "notes": "patellar tendon; no squats or running", "updated": "2026-03-24 13:00"},
    {"name": "Sir Waqas", "relation": "Tax tutor", "notes": "60 recorded lectures; finish by end of November", "updated": "2026-10-10 22:40"},
]
notes = [
    {"id": 1, "kind": "pattern", "confidence": "confirmed", "times_seen": 6, "status": "active",
     "first": "2025-11-03", "last": "2026-09-06",
     "text": "Phone in bed after 11 pm costs him his next morning.",
     "evidence": [["quote", "I tell myself ten minutes and it's suddenly 2 am.", "2025-11-03"],
                  ["quote", "Mornings on the roof are ruined, I wake up at 9", "2026-08-04"]]},
    {"id": 2, "kind": "what_helps", "confidence": "likely", "times_seen": 3, "status": "active",
     "first": "2026-02-02", "last": "2026-09-06",
     "text": "Physical tiredness (gym days) gets him to sleep before midnight.",
     "evidence": [["quote", "On gym days I actually sleep before midnight because I'm tired.", "2026-02-02"]]},
    {"id": 3, "kind": "what_helps", "confidence": "confirmed", "times_seen": 4, "status": "active",
     "first": "2026-05-24", "last": "2026-09-27",
     "text": "Early mornings in the rooftop room give his best focus.",
     "evidence": [["quote", "Best focus I've ever had.", "2026-05-24"]]},
    {"id": 4, "kind": "what_helps", "confidence": "likely", "times_seen": 2, "status": "retired",
     "first": "2026-04-06", "last": "2026-05-03",
     "text": "He focuses far better at the Model Town library than at home.",
     "evidence": [["quote", "I focus way better at the library than at home", "2026-04-06"]]},
    {"id": 5, "kind": "what_doesnt", "confidence": "likely", "times_seen": 3, "status": "active",
     "first": "2026-07-20", "last": "2026-08-12",
     "text": "Taking on two papers at once overwhelms him.",
     "evidence": [["quote", "Two papers at once is too much.", "2026-08-12"]]},
    {"id": 6, "kind": "strength", "confidence": "likely", "times_seen": 2, "status": "active",
     "first": "2026-06-10", "last": "2026-07-08",
     "text": "Timed past papers steadily raise his scores.",
     "evidence": [["quote", "My scores went from 44 to 58 across them.", "2026-06-10"]]},
    {"id": 7, "kind": "thread", "confidence": "guess", "times_seen": 1, "status": "active",
     "first": "2026-10-04", "last": "2026-10-04",
     "text": "Studying alone since Omar left feels lonely.",
     "evidence": [["quote", "studying alone is lonelier.", "2026-10-04"]]},
    {"id": 8, "kind": "pattern", "confidence": "guess", "times_seen": 1, "status": "deleted_by_user",
     "first": "2026-09-20", "last": "2026-09-20",
     "text": "He resents his mother for worrying about money.",
     "evidence": []},
    {"id": 9, "kind": "value", "confidence": "confirmed", "times_seen": 3, "status": "active",
     "first": "2025-11-09", "last": "2026-07-20",
     "text": "Wants a qualified career, not to stay an assistant.",
     "evidence": [["quote", "I want to finish CAF so I'm not stuck as an accounts assistant forever.", "2025-11-09"]]},
]
# Ordinary notes a year of chats accumulates (they take room in NOTES, as real ones would).
for i in range(10, 70):
    notes.append({"id": i, "kind": rng.choice(["pattern", "what_helps", "what_doesnt", "thread"]),
                  "confidence": rng.choice(["guess", "guess", "likely"]), "times_seen": rng.randint(1, 3),
                  "status": "active", "first": "2026-0%d-1%d" % (rng.randint(1, 9), rng.randint(0, 9)),
                  "last": "2026-09-2%d" % rng.randint(0, 8),
                  "text": "Ordinary note %d: %s" % (i, rng.choice(FILLER_LINES)),
                  "evidence": [["quote", rng.choice(FILLER_LINES), "2026-09-1%d" % rng.randint(0, 9)]]})

# ---------------------------------------------------------------- plans, targets, check-ins
goals = [
    {"id": 1, "title": "Pass CAF 1 Financial Accounting", "status": "done", "deadline": "2026-06-14", "created": "2026-01-12"},
    {"id": 2, "title": "Pass CAF 5 Tax", "status": "active", "deadline": "2026-12-01", "created": "2026-07-20"},
    {"id": 3, "title": "Look after my knee", "status": "active", "deadline": None, "created": "2026-03-24"},
]
revisions = [
    {"id": 1, "goal": 2, "text": "Pass CAF 5 Tax and CAF 3 Law (deadline 2026-12-01)", "reason": "created", "at": "2026-07-20"},
    {"id": 2, "goal": 2, "text": "Pass CAF 5 Tax (deadline 2026-12-01)", "reason": "two papers at once is too much", "at": "2026-08-12"},
]
backlogs = [
    {"id": 1, "goal": 2, "name": "Tax lectures", "total": 60, "unit": "lectures", "start_done": 0, "deadline": "2026-11-30", "status": "active"},
    {"id": 2, "goal": 1, "name": "CAF 1 past papers", "total": 12, "unit": "papers", "start_done": 0, "deadline": "2026-06-13", "status": "done"},
]
targets = [
    {"id": 1, "goal": 2, "backlog": 1, "name": "Tax lectures", "unit": "lectures", "minimum": 1, "target": 2,
     "schedule": "mon,tue,wed,thu,fri,sat", "start": "2026-08-15", "end": None, "status": "active"},
    {"id": 2, "goal": 3, "backlog": None, "name": "Walk", "unit": "minutes", "minimum": 10, "target": 20,
     "schedule": "daily", "start": "2026-03-25", "end": None, "status": "active"},
    {"id": 3, "goal": 1, "backlog": 2, "name": "Timed past paper", "unit": "papers", "minimum": 1, "target": 1,
     "schedule": "3/week", "start": "2026-04-01", "end": "2026-06-13", "status": "ended"},
]
rest_days = ["2026-09-05", "2026-10-03"]
checkins = []
ck_id = [0]


def checkin(target, day, level, amount, reason=None, note=""):
    ck_id[0] += 1
    checkins.append({"id": ck_id[0], "target": target, "day": day, "level": level, "amount": amount,
                     "reason": reason, "note": note})


# Tax lectures, 15 Aug to 13 Oct: Tuesdays usually short (family visits), power cuts in late September.
crng = random.Random(11)
dd = date(2026, 8, 15)
done_lectures = 0
while dd <= date(2026, 10, 13):
    iso = dd.isoformat()
    wd = dd.weekday()
    if wd != 6 and iso not in rest_days:
        if wd == 1:
            checkin(1, iso, "not", 0, "family", "Khala's family visited" if crng.random() < 0.5 else "")
        elif date(2026, 9, 13) <= dd <= date(2026, 9, 26) and crng.random() < 0.6:
            checkin(1, iso, "minimum", 1, "power cut", "router off")
            done_lectures += 1
        elif crng.random() < 0.12:
            pass  # no answer: never a miss
        else:
            checkin(1, iso, "target", 2)
            done_lectures += 2
    dd += timedelta(days=1)
SPECIAL_CK = {}
# One check-in per target and day, as in the app: this one replaces whatever the loop wrote for that Monday.
checkins[:] = [c for c in checkins if not (c["target"] == 1 and c["day"] == "2026-08-24")]
checkin(1, "2026-08-24", "not", 0, "tired", "stayed up scrolling till 3")
SPECIAL_CK["scrolling"] = ck_id[0]
# Walks, the last six weeks.
dd = date(2026, 9, 1)
while dd <= date(2026, 10, 13):
    if crng.random() < 0.8:
        checkin(2, dd.isoformat(), "target", 20)
    dd += timedelta(days=1)

# ---------------------------------------------------------------- promises
promises = [
    {"id": 1, "text": "Put the phone on the kitchen charger by 11 pm", "due": "2025-11-04 23:00", "status": "not_done", "reason": "forgot", "conversation": 1},
    {"id": 2, "text": "Do one timed past paper on Saturday", "due": "2026-04-25 10:00", "status": "kept", "reason": None, "conversation": 14},
    {"id": 3, "text": "Book the physio appointment", "due": "2026-03-21 12:00", "status": "kept", "reason": None, "conversation": 11},
    {"id": 4, "text": "Email Sir Waqas about the Law syllabus", "due": "2026-08-07 18:00", "status": "not_done", "reason": "forgot", "conversation": 20},
    {"id": 5, "text": "Submit the exam form", "due": "2026-08-20 17:00", "status": "not_done", "reason": "power cut", "conversation": 21},
    {"id": 6, "text": "Phone in the kitchen from 11 pm for a week", "due": "2026-09-05 23:00", "status": "kept", "reason": None, "conversation": 23},
    {"id": 7, "text": "Buy the router UPS", "due": "2026-09-24 18:00", "status": "moved", "reason": None, "conversation": 26},
    {"id": 8, "text": "Buy the router UPS", "due": "2026-09-27 18:00", "status": "kept", "reason": None, "conversation": 26},
    {"id": 9, "text": "Call Hina back about the wedding dates", "due": "2026-08-16 20:00", "status": "let_go", "reason": None, "conversation": 21},
    {"id": 10, "text": "Finish Tax lecture 31", "due": "2026-10-10 21:00", "status": "kept", "reason": None, "conversation": 29},
    {"id": 11, "text": "Two Tax lectures before noon", "due": "2026-10-14 12:00", "status": "open", "reason": None, "conversation": 30},
    {"id": 12, "text": "Walk 20 minutes after Asr", "due": "2026-10-15 17:00", "status": "open", "reason": None, "conversation": 29},
    {"id": 13, "text": "Do the sales tax practice set", "due": "2026-08-29 20:00", "status": "not_done", "reason": "tired", "conversation": 21},
]

# ---------------------------------------------------------------- writing the app keeps
letters = [
    {"id": 1, "week_of": "2026-09-21", "title": "The week the lights went out",
     "body": "Three evenings lost to power cuts, and still eight lectures. The kitchen charger held five nights."},
    {"id": 2, "week_of": "2026-09-28", "title": "A box that keeps the router on",
     "body": "The UPS changed the evenings: two lectures during a cut for the first time."},
    {"id": 3, "week_of": "2026-10-05", "title": "Tuesdays",
     "body": "Every Tuesday this month ended at zero; the other days averaged close to two."},
]
reviews = [
    {"id": 1, "kind": "month", "period": "2026-09", "facts": "Tax lectures: 38 of 52 planned. Reasons when short: power cut 7, family 4.",
     "note": "Power cuts were the main thing this month."},
    {"id": 2, "kind": "week", "period": "2026-10-05", "facts": "Tax lectures: 9 of 12 planned. Tuesday: not done (family).",
     "note": "A steady week apart from Tuesday."},
]
chapters = [
    {"id": 1, "start": "2026-03-01", "end": "2026-06-30", "title": "The resit months",
     "body": "He left Pellow Freight, studied full time, moved to Millbrook and passed CAF 1 with 61."},
    {"id": 2, "start": "2026-07-01", "end": "2026-08-31", "title": "Settling in Millbrook",
     "body": "Omar left for Dubai, Law was dropped, and the phone habit came back without the gym."},
    {"id": 3, "start": "2026-09-01", "end": "2026-10-14", "title": "The Tax push",
     "body": "Power cuts, a UPS, and a steady push through Sir Waqas's lectures."},
]
quotes = [
    {"id": 1, "conversation": 6, "text": "I missed by four marks.", "at": "2026-01-12 18:45"},
    {"id": 2, "conversation": 18, "text": "I PASSED. 61 in CAF 1.", "at": "2026-07-08 17:40"},
]
events = [
    {"id": 1, "conversation": 22, "at": "2026-08-21 21:00", "whenText": "20 August evening",
     "situation": "exam form deadline during a power cut", "feeling": "annoyed", "action": "could not submit",
     "payoff": "", "outcome": "late fee of 3,000"},
    {"id": 2, "conversation": 27, "at": "2026-09-27 21:20", "whenText": "evening power cut",
     "situation": "router on the new UPS", "feeling": "", "action": "did two lectures",
     "payoff": "kept the evening", "outcome": "first lectures during a cut"},
]


def render():
    """history.json's exact text (memory_exam2.py --check compares it with the committed file)."""
    out = {
        "synthetic": "Invented person for Clarity's memory exam 2. Not a real person.",
        "today": TODAY, "now": ms(NOW), "tz": "Asia/Karachi", "boundary_hour": 0,
        "conversations": conversations, "messages": messages,
        "profile": profile, "old_profile": old_profile, "people": [
            dict(p, updated=ms(p["updated"])) for p in people],
        "notes": notes, "goals": goals, "revisions": revisions, "backlogs": backlogs, "targets": targets,
        "rest_days": rest_days, "checkins": checkins, "special_checkins": SPECIAL_CK,
        "promises": [dict(p, due=ms(p["due"])) for p in promises],
        "letters": letters, "reviews": reviews, "chapters": chapters,
        "quotes": [dict(q, at=ms(q["at"])) for q in quotes],
        "events": [dict(e, at=ms(e["at"])) for e in events],
    }
    return json.dumps(out, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def main():
    with open(os.path.join(HERE, "history.json"), "w", encoding="utf-8") as f:
        f.write(render())
    print("conversations %d (key 30, filler %d), messages %d, check-ins %d, notes %d" % (
        len(conversations), len([c for c in conversations if c["id"] > 100]), len(messages), len(checkins), len(notes)))


if __name__ == "__main__":
    main()
