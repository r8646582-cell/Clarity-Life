# Clarity: the life system (final v1 spec)

Written 9 Oct 2026 for the owner. This replaces LITE-PLAN.md where they differ. It is the one spec to build from. Scope is locked: anything not in this file goes on the "Later" list (section 16) and waits for two weeks of real use.

Status: nothing in this spec has been built or run on a device. The old app's CI reportedly builds green; the new one does not exist yet.

The repo seed `Clarity-Life/` (next to this file) already contains the finished prompt set (`prompts/`), the machine-checked contracts (`contracts/`: prompt rules, context packet, card test vectors) and the checkers (`tools/check_prompts.py`, `tools/test_cards.py`, `tools/cards.py`, the reference card parser). Also in the seed: `tools/planner.py` and `tools/textcheck.py` (reference math and QuoteCheck/NumberCheck), `tools/context.py` (the context packet), `tools/appliers.py` (the background-job validators), `tools/bench.py` (the test bench checks), all with hand-checked vectors and a mutation check, `db/schema.sql`, `contracts/states.json`, `CLAUDE.md` (instructions for building with Claude Code) and a GitHub Actions gate. Section 18 explains how the whole design keeps bugs low.

---

## 0. What this is, and the rules that never break

A private Android app that holds your life over years (goals, plans, daily results, patterns, conversations) and lets you talk with a coach that has all of it in front of it. The chat is the interface. The system is the loop in section 1.

**Rules (never break these):**
1. **The AI only talks. The app changes things only when you tap.** The AI may show a card (a suggested promise, plan, target or focus). Nothing is saved until you press its button. No hidden actions that change state.
2. **Code does the math and the bookkeeping.** Dates, pace, rates, counts, deadlines and streak-free statistics are computed in code and handed to the AI as finished facts. The AI never works out dates or counts days, and never replaces an app number. In conversation it may do simple rough sums on numbers it was given, shown and called an estimate (owner's decision, 11 Oct 2026).
3. **Only your own words and actions are evidence about you.** A coach claim never raises a note's confidence or seen-count. A missing check-in is "no answer", never "not done".
4. **Quotes shown as your words are verified word for word in code** (port of `QuoteCheck.kt`).
5. **Your deletions are final.** A deleted note is never recreated.
6. **Nothing leaves the phone except calls to your chosen AI provider.** No accounts, no analytics, no cloud sync, no logging of message content or keys.
7. **No crisis or help-line features** (removed on purpose by the owner). English only. Real names stay.
8. **No streak counters, badges or nagging.** The only notifications are reminders you set by tapping "Set it", and the optional silent Sunday letter alert.
9. **No destructive database migrations, ever.** A failed migration stops with an error and never wipes.

---

## 1. The loop

1. **Capture:** goals, daily check-ins, conversations. All saved forever, encrypted.
2. **Understand:** after each chat and review, the AI proposes patterns and notes from your own words and numbers. You keep or delete them.
3. **Plan:** the coach builds short and long plans from your real pace, deadlines and available time. You tap to save them as targets.
4. **Act and track:** a daily check-in with real numbers and reasons for misses.
5. **Review:** weekly (computed by code, discussed in chat), monthly (plan versus actual), quarterly (a written chapter of your life).
6. **Adapt:** reviews produce proposal cards ("the plan looks off", "this pattern repeats", "revisit this goal"). Nothing changes until you tap.

---

## 2. Screens (3 tabs plus Settings)

Design (the 0.1.6 redesign, approved 13 Oct 2026; see section 17): the canvas "Clarity Goal screen redesign", page "Redesign 0.1.6", exported to `docs/mockups/redesign/`. Tokens: ground #0F0E13, surface #1A1820 with a #2D2A36 hairline border, raised #24212C, text #F5F3F8, muted #A9A4B6 (not lower contrast), accent yellow #FFD23F (the one main action and key numbers), coral #FF7A66 (suggestion cards), calm green #8FD6A0 (saved, kept, healthy), danger text #FF8A7C, tab bar #0B0A0E. Fonts: Instrument Sans for everything you read and tap, Instrument Serif for headlines, big numbers and the coach's longer writing (both bundled, SIL OFL). Touch targets at least 44 dp. Real contrast: text at least 4.5:1. Your messages are white bubbles; coach replies are plain text beside a small yellow dot (the coach's presence mark, which glows while it is writing). No disclaimer lines on screens ("Counted by the app" and the like): the rules behind them stay in code.

### 2.1 Talk
- **Talk home** (added 13 Oct 2026): when no conversation is open, Talk is never blank. It shows, top to bottom: the date, a greeting with their name from Settings, Coach ("Hello, Sam.", or "Hello." until a name is set), the same at every hour (the owner's decision, 14 Oct 2026: a time-of-day greeting said "Good evening" at 12:30 am, and the date is already shown) with one code-written line under it (the day of the plan, or nothing), the unread letter card if any, and two or three **ways to start a chat** chosen by code (Plan and track when a target is scheduled today, Untangle, and the most recent active Focus; each opens a new conversation of that type with nothing sent). The input bar is always there; typing starts a free-talk conversation. The conversations drawer and Settings are in the top bar. **Today's steps and promises are not on Talk home** (the owner's decision, 14 Oct 2026: a promise and the plan step for the same lectures looked like two cards for one task). They are answered on Plan, Today and Plan, Promises, and while one waits for an answer a coral dot shows on the Plan tab and on that section's chip (section 2.2); it goes as soon as it is answered. A rest day asks nothing.
- Header "Talk." with a **chat type dropdown**: Free talk, Untangle, Decide, Plan and track, plus one entry per active Focus (for example "Social anxiety"). Switching mid-conversation is allowed: the history stays and the next reply follows the new type. Conversations drawer (list, search, rename, delete). One chat component serves every type; the type only changes the mode instructions, the extra data loaded, and which cards are allowed (see section 5).
- **Letter card** (only when a new weekly, monthly or yearly letter is unread): a yellow-outlined card above the chat with the letter's title and a Read button. It disappears once the letter is opened (mockup 9).
- **Due card:** removed from Talk on 14 Oct 2026 (the owner's decision, see Talk home). Due promises are answered on Plan, Promises (Needs an answer: Done / Not done with a reason chip row / Moved), and the Plan tab's dot says one is waiting.
- **Cards in chat** under a coach reply (see section 5): promise, plan, target change, backlog, focus, note, chat-type switch. Each has its own buttons; nothing happens until tapped.
- Input bar: text field, send. Keyboard rules: input above keyboard, your message scrolls near the top, reply streams below, a centered "jump to latest" button when needed.
- Reply survives leaving the app: keep `ReplyService` (short foreground service), write the partial reply to the database about once a second, mark `interrupted` on next launch if the process died, with a Try again button.
- Every coach reply has long-press "Details" (model, tokens, time to first token) in Advanced only.

### 2.2 Plan (one tab, sections)
- **Today:** the check-in. One row per active target scheduled today. For each: Did it / Part / Not, plus an amount stepper when the target is a quantity. "Not" or "Part" shows reason chips: tired, no time, forgot, avoided it, felt too big, sick, plans changed, low mood, other (+ optional one line note). A planned rest day shows as Rest and counts as neither done nor missed.
- **Goals:** paste or write a goal (6 months, 1 year, any horizon). Each goal shows its targets, its current plan version, and its status (on pace / behind / no data yet). A goal can be archived, never silently changed.
- **Backlogs:** a bucket with a total, done count, deadline and unit ("40 lectures by 15 March"). Shows required per day versus actual.
- **Promises:** one-off commitments with time and optional reminder. Outcomes: Done, Not done, Moved, Let go. "Not done" asks for the reason chips. Past promises are kept with their outcomes.
- **Reviews (where letters live):** one list, newest first, with three groups: **Weeks** (each row is one week: date range, the letter's title, chips Letter and Review, a yellow dot while the letter is unread), **Months** (the monthly letter and the monthly review) and **Years** (yearly letters, once there is one; 15 Oct 2026). A coral dot on the Reviews chip and on the Plan tab shows something unread. The same dot shows on the Today chip while a step scheduled today has no answer (not on a rest day), on the Promises chip while a promise is due and not answered, and on the Plan tab while any of these waits; each goes as soon as it is answered or read (14 Oct 2026). A dot is the only sign: no notification, no count, no streak. Tapping a week opens the **letter** (large readable text, buttons **Talk about this letter** and **See the numbers**; the numbers page is the weekly review with its Talk about it button). Letters can be deleted by you. Quarterly chapters live in What I know, Chapters. Mockups 9 to 11.
- **Plan with coach** button (on Plan, and on each goal, target and backlog): opens a chat in Plan mode already loaded with the full planner facts for what you were looking at. Same engine and same shared memory as Talk, just a different doorway. Plan mode stays human: it can talk about feelings about missed days and can suggest switching to Untangle.

### 2.3 What I know
- Search field. Sections collapsed by default: Profile (values, future self, life facts), Patterns, What helps, What doesn't, Open threads, People, Focuses, Chapters.
- Each item shows its evidence (dates, your quotes, seen count) when expanded. Edit or delete anything. "Confirmed" tag for things you edited or confirmed.
- Archive: retired notes and old versions, searchable.
- At the top, a **Your story** card opens the story page (2.5).

### 2.5 Your story (added 13 Oct 2026, the owner's approved mockup)
One page, opened from the top of What I know: what he did, what he overcame and how he changed, over his whole life with the app. It fills in over time and is honest while it is short ("The first pages." for the first four weeks).
- **Header:** a small "Your story" label, the headline, and one line with how long the story is and when it began ("Day 1 · since 13 Oct 2026", "Five months · since ...", counted by code). Then a switch: This month, This year, All of it, which sets the date range of every count and chart below it. The switch is hidden while the story is still in its first calendar month, because all three would show the same days.
- **The short version:** a few sentences written once a month by the monthly story job (section 8), in the serif with a thin yellow edge. Until the first one exists, one small card says when it arrives.
- **Counts** (code): the amount done of the first backlog (or days answered when there is none), promises kept, things overcome (focuses he marked done), in the chosen range; one card with three columns. Hidden while all three are zero.
- **Week by week** (code, `tools/story.py`): one chart per backlog, chips to switch between them. Bars are what he did in each story week (seven days from the backlog's start; calendar months past 26 weeks); a dashed line is what his plan's steps asked for in the same stretch, so a ramp draws as stairs. A finished backlog shows the day it was done.
- **What you overcame:** written by the monthly story job, at most three items, each with Then (his own words, a saved quote), what he did, and Now. Before the first story, the focuses he marked done are listed with their steps.
- **What helps you / What you're good at:** active what-helps and strength notes as a list, one row each, with its seen count written out under it ("Seen 2 times in your own words"). Tapping one opens its proof: its evidence rows with dates, and That's right, Not quite, Delete (the same rules as What I know).
- **How you've changed, in your words:** two of his saved quotes, then and now, chosen by the story job by id (so they are always word for word); hidden until there is a story.
- **Your years:** the chapters, grouped by year, newest first, and the chapter being written.
- **Talk about how far you've come:** opens a free-talk conversation.
- No scores, levels, streaks or badges. Every number is counted by code; the AI writes only the short version and the overcame lines, and both pass the text checks.

### 2.4 Settings (the simplified Bold mockup, plus the model fields)
Main level: **Coach** (provider preset, base URL, API key, chat model name typed freely, background model name defaulting to the same, Test connection), **Reminders** (weekly letter on/off, "Sunday alert" on/off (one quiet notification when the letter is ready, no sound), day boundary (default midnight, like a calendar, the owner's choice of 13 Oct 2026; a night owl can pick 1 to 6 am so a 12:30 am check-in counts for the day before); Keep reliable: battery, autostart, exact alarms), **Your data** (app lock, hide in recents, backup folder and automatic weekly backup, Export everything, Erase everything with type-ERASE). **Advanced** (collapsed): embedding model and Re-index, prompt editor with Reset to built-in, test bench, error log (no content), version. **Help for a new person** (added 13 Oct 2026 at the owner's request): a read-only guide, "How Clarity works", opens from a ? on Talk home and from the bottom of Settings; it asks nothing and saves nothing. Until a key is saved, Talk home shows a card with the three steps to get one ("Get a key" opens the provider's key page in the browser; the app sends nothing there), and Settings opens on Coach.

---

## 3. Conversations, Focuses, and what the AI sees

**Decision: many conversations, one shared memory.** Every conversation sees the same memory and the same plan data. A conversation is a thread; the knowledge is shared.

- **Conversations drawer:** grouped by date, searchable, rename, delete ("Forget this conversation" removes the thread and anything learned only from it, with confirmation). Off-the-record conversations: never saved or reflected.
- **Focus:** a long-running subject you choose to follow ("social anxiety", "the final exam", "the job decision"). The coach can suggest one as a card; you tap to create it. A focus has a name, why, status (active, paused, done), a **rolling summary** the AI rewrites after each chat inside it, linked goals, targets, plans and a timeline of its conversations. Starting a chat inside a focus loads that summary, so the thread of a months-long problem never resets. Focus summaries are shown and editable in What I know.
- **Without a focus,** a conversation is ordinary and its learnings still flow to shared memory.

**What the AI receives each time** (a fixed-size packet; never "all the data"). The exact contract is `contracts/context.md`, implemented by `tools/context.py`:
1. Fixed prompt (cached across all conversations of a chat type): persona, examples, card rules, chat-type instructions.
2. Frozen context (cached for the whole conversation): flags, profile, people, active notes (capped), focus summary, latest chapters, recent session summaries (each with its day and clock times), recent ideas, and the letter or review it was opened from. It is built again when they change something in What I know or forget a conversation, and when another conversation's summary is written, so a deleted note is never sent again and a new conversation catches up with the one before it (added 14 Oct 2026, after the coach missed a nap plan made two hours earlier).
3. The messages of this conversation (a window of about 24k tokens that moves forward in jumps, plus a running summary of anything that left the window).
4. Volatile facts with their new message: the current time sentence (code-written, with the day boundary), the **planner facts** (section 6) and today's check-in state, open promises with code-written due phrases, what they did with the last reply's cards, the last messages of the conversation they were in less than 6 hours ago (word for word, with times), and "possibly relevant from the past" (top matches across all conversations, labeled with date, time, how long ago and conversation title).
Budgets: frozen context about 9k tokens, volatile facts about 4k, history about 24k. Order of trimming: oldest summaries, then lowest-evidence notes. The planner facts, promises, time and flags are never trimmed. Nothing stored is ever truncated; only what is sent.

---

## 4. Models and replies

- One strong model for all chat, **thinking on**, streaming. The model name is a typed setting. Default DeepSeek (OpenAI-compatible, base URL `https://api.deepseek.com`); check current model IDs and prices at build time; never hard-code names at call sites.
- **No reply length cap.** Chat max output set high (the thinking tokens count against it; use the provider's documented ceiling, at least 16,000). Depth is governed by the persona (section 12), not by limits.
- Background jobs (reflection, review narrative, chapter, letter) use the background model setting; default same as chat; temperature 0.3 for JSON jobs.
- Streaming client: reuse `OpenAiWire` and `OpenAiCompatibleClient` (buffered UTF-8 line reader; thinking chunks are heartbeats; handle finish_reason `length` and error objects). Connect timeout 30 s, chat gap timeout 120 s, background read timeout 300 s.
- No backup provider (the owner's decision, 11 Oct 2026): only the provider they chose is ever called. When it is down, the reply fails with the usual quiet line and Try again; no other model answers instead.
- Cost: keep a tiny local usage counter (counts only) shown in Settings. Not a budget system.

---

## 5. The AI contract: talk plus cards

The AI's reply is text. It may end with at most three **card lines**, each a single line `[[suggest: {json}]]`. The app parses them and renders cards. The visible text and stored message never contain the marker.

Card types and fields (the exact schema and limits are enforced by `tools/cards.py`, the reference parser; the Kotlin parser must pass every test vector in `contracts/card_vectors.json`):
- `promise`: text, when (local human wording, e.g. "tomorrow 07:00"), remind (true/false), why.
- `plan`: goal id (or omitted for a new goal), title, rationale (short), targets (up to 4: name, target, min, unit, schedule, start, end, weeks; a ramp repeats one name as steps, each but the last with `weeks`, and code chains their dates: the owner's decision, 13 Oct 2026), backlog (up to 2: name, total, unit, deadline), review_date (must be one of the **review options** the app puts in PLAN_STATE, so the AI never calculates a date).
- `target_change`: target id, new target_value / min / schedule / end, reason.
- `backlog`: new (name, total, unit, deadline) or an adjustment of an existing id, with a reason.
- `focus`: name, why.
- `note`: kind (pattern, what_helps, what_doesnt, value, thread), text. An offer only; still needs your tap.
- `mode`: switch chat type (free, untangle, decide, plan, or focus + id). Tap to switch; the coach never switches by itself.
Schedules use a small grammar the code can parse: daily, weekdays, a day list (Mon,Wed,Fri), a range (Sat-Thu) or a count (3x/week). Ids (G, T, B, F numbers) are copied from the context; an unknown id drops the card.

**Which cards each chat type may show:** Free talk: promise, focus, mode, note. Untangle: mode, note only (no commitment cards). Decide: promise (only if they commit), mode, note. Plan and track: plan, target_change, backlog, promise, mode, note. Inside a Focus: the same as the type currently selected. The app drops any card a type does not allow. **Journeys** (such as overcoming social anxiety or building a habit) are not a separate feature: they are a Focus plus a plan with targets; the Focus chat holds the talking and the plan lives in Plan.

Rules:
- A card with bad JSON is dropped and logged (no content). It never changes state.
- **Card step (the owner's decision, 13 Oct 2026):** the AI decides what to suggest; writing the card down does not depend on it remembering a card line. After every reply with no valid card, the app asks for that reply's cards as JSON (`{"cards": [...]}`, the provider's JSON mode), read by the same parser and rules (`contracts/context.md`, Card step). An empty list is normal; nothing saves until a tap.
- **Plans are agreed before their card (the owner's decision, 13 Oct 2026):** a new plan is laid out in words first and the coach asks if this is the plan; the plan card comes only after their yes, as the final version with their changes. No draft plan cards. A change they ask for themselves counts as the yes.
- "when" strings are resolved by code (`TimeFacts`/`PromiseTimes`); the AI never computes timestamps. If code cannot resolve a phrase, the card shows a time picker instead of guessing. Late at night (from midnight until the day boundary or 4 am, whichever is later; 14 Oct 2026, so it still holds with a midnight day end) "today", "tonight" and "tomorrow" always open the picker with the likeliest day preselected, and the NOW sentence calls those hours "late night", never "morning".
- Tapping "Set it"/"Save plan" writes to the database; the card shows "Saved" from the real result. "Not now" dismisses it.
- The persona forbids claiming anything is saved. The only receipt is the card showing Saved.
- Duplicate cards in one reply are rejected (two promises are allowed, nothing else twice). Only trailing lines of the exact form can become cards; any stray `[[` in the prose is removed and logged, so raw markers are never shown.
- Background jobs (reflection, reviews, letter, chapter, gardening) never produce cards and never change goals, plans, targets or promises. They write text, notes and summaries only, under the rules in section 7.

---

## 6. The planner: tracker design and the math (all in code)

### 6.1 Entities
- **Goal:** text you pasted or wrote, horizon (3 months, 6 months, 1 year, custom), area (health, studies and career, relationships, money, habits, mind, meaning, rest and joy), deadline if any, status, revision history.
- **Plan:** versions of how a goal will be pursued; each version has its rationale and when it was created and from which conversation.
- **Target:** a recurring item (daily, certain weekdays, N times a week). Has a unit if quantitative, a **minimum** (bad-day level) and a **target** (normal level), a start and end date, status.
- **Backlog:** a finite amount to clear by a deadline.
- **Check-in:** one row per target per scheduled day: level (Rest, Not, Minimum, Target, Beyond), actual amount, reason chip, optional note, time entered. A missing row means "no answer".
- **Availability:** your usual hours available per weekday for planning (set once in profile, editable). The coach asks if it is missing. Since Max plan Phase 6 you set them in Plan, Goals, "Your time, next 7 days" (seven fields, Monday to Sunday), and a target that is not counted in minutes or hours can carry your estimate of the minutes one session takes. Code then adds up the time every goal's targets take on each of the next 7 days and sets it against your hours (`day_load`, `load_lines`), so "several goals competing for the same hours" is a fact the coach reads, not a sum it does.

### 6.2 Computed facts (what the AI is given)
Computed per goal, target and backlog, only when enough data exists (at least 7 answered scheduled days; otherwise the line says "not enough data yet"):
- remaining = total minus done.
- daysLeft = scheduled days until the deadline, minus planned rest days.
- requiredPerDay = remaining / daysLeft, shown rounded **up** to 0.1 so the shown pace really reaches the total.
- actualPerDay = amount done over the last 14 days / answered scheduled non-rest days in that window. Answer rate is reported separately.
- projectedDone = done + actualPerDay × daysLeft, shown rounded **down** ("reaches"), never in the plan's favour. A Status line written by code (reaches at typical pace / only at best fortnight / does not reach) is given to the coach so its assessment cannot drift from the numbers.
- gap = remaining − (actualPerDay × daysLeft), shown in the unit.
- pace range from **your own history** (no fixed padding): typical = the last 14 days; best = your best 14-day window so far; projected finish is computed for both and the coach is told both ("at your usual pace about 21 of 40; at your best fortnight 36 of 40"). People underestimate time, so the plan is judged against what you have actually done, not what you hope to do.
- weekly review stats: done rate overall and per target, by weekday, by time of day if recorded, top three reasons with counts, minimum-versus-target split, answer rate.
- 28-day trend of done rate per target, rest/sick days excluded.
- Backlog status: on pace / behind / not enough data. **Re-plan trigger:** projectedDone below 85% of remaining on two consecutive weekly reviews with enough data produces a "The plan may be off" card.
All of this is unit-tested with fixed fixtures (including leap days, rest days, month ends, time zones).

**Reviewed against real use (Max plan Phase 6, 14 Oct 2026).** A backlog's facts are computed by `backlog_facts` in `tools/planner.py` with vectors, not only in Kotlin. A backlog fed by several targets counts the days of all of them (it used to count only the first target's days, so a weekend target listed first made the weekday answers invisible). Days left never start before the plan's first step does. After a gap (fewer than 7 answers in 14 days) his best fortnight is still given under "Not enough data yet". On the deadline day, once answered, the line says "no scheduled days left before the deadline", never "the deadline has passed", and the plan line no longer counts work after the deadline as reaching it.

**Plan changes mid-way (schema 8).** Every change he taps to a target (number, minimum, schedule, pause, resume, end) is kept with the day it took effect (`target_version`). From it code says how far a plan's steps have got ("So far on the plan for B1: the steps asked for 20, done 18, behind by 2"), so a ramp is judged by what its steps asked for on each day rather than by a flat pace, and how his latest change went ("changed on 12 Oct (target 1 → 2): in the 14 days before done on 14 of 14 answered days, since on 5 of 6"). Targets from before schema 8 are read as their current values from their start day; nothing about earlier changes is guessed.

### 6.3 The planning rules the coach follows (the syllabus case)
Written into `mode_plan.md` and the persona:
- Start from the deadline and work backward. Use your **actual** pace, not a hoped-for pace.
- A "tiny start" is a way to begin, never a substitute for a plan. A plan must reach the goal, or the coach must say plainly that the numbers do not reach it and lay out the trade-offs: raise the pace (what would that cost?), cut scope by priority or exam weight, move the deadline, drop something, or accept a lower outcome. Ask for the missing fact (weights, hours available, what is mandatory) rather than guessing.
- Check total daily load against your availability before suggesting anything.
- Use two levels (minimum and target) and planned rest days.
- For studying: spread practice over days and use self-testing; for habits: if-then plans for the likeliest obstacle; for fear or avoidance (such as social anxiety): graded steps from easy to hard, with the next step chosen by how the last one went. These are well-established ideas; the coach must not invent studies or statistics and must say "research suggests" rather than "science proves".
- Every plan ends with a review date. The coach reviews against the computed facts, not against its memory of what was said.

---

### 6.4 Research behind the tracker, and its limits
- **Monitoring progress helps.** A meta-analysis of 138 randomized studies (about 20,000 people) found that getting people to monitor goal progress improved goal attainment by a small to moderate amount (d about 0.40), more when progress was physically recorded and when it was reported to someone. So: check-ins are written with real numbers, and the weekly review is the "report". It is an average effect, not a promise for you.
- **Habits take weeks, and one missed day does not break them.** In a small study of 96 people, habits took 66 days on average to become automatic (range 18 to 254), and missing a single day did not reduce the chance of forming the habit. So: no streaks, habit targets are judged over weeks, and the coach knows a slow start is normal.
- **Be kind after a miss.** Two small experiments with students found that self-compassion after a failure led to more studying on a retest than self-esteem boosting or nothing. Small and not conclusive; it supports the persona rule that a miss is information, not a verdict.
- **Planning fallacy.** Forecasts based on the specifics of a project run optimistic; calibrating against past similar results (the "outside view") helps, though the authors of a large project dataset caution it is not only a bias. So: pace comes from your own history, shown as typical and best.
- Claims from habit-app vendors about streak anxiety and abandonment are plausible but not independent evidence; the design avoids streaks anyway.
Sources: Harkin et al. meta-analysis (eprints.whiterose.ac.uk/id/eprint/91437), Lally et al. via spring.org.uk/2023/01/form-a-habit.php, Breines and Chen via livescience.com/57358, PMI "Planning for the planning fallacy".

---

## 7. Memory (built for a lifetime)

Layers:
1. **Archive** (forever): every conversation, message, check-in, promise, review, chapter, letter, retired note. Encrypted, searchable, never sent in full.
2. **Notes:** types pattern, what helps, what doesn't, strength, value, open thread. Each has status (active, retired, deleted by you), confidence (guess, likely, confirmed), seen count, first and last seen, and **evidence rows** (date, source, your exact words or the numbers behind it). Caps on active notes (about 15 per type; gardening merges, never touches notes you edited or confirmed).
3. **Profile:** at most about 30 durable entries (values, future self, life facts, availability). Corrections by you win and are marked confirmed.
4. **Chapters:** every 3 months a written chapter replaces older session summaries in the context.
5. **Focus summaries:** rolling, one per focus.

After each conversation (when you end it, after 30 minutes idle, or on a new day) a reflection job returns strict JSON: summary, title, proposed notes (at most 3 new, update by id), your exact words (verified), and the focus summary if the chat was in one. Rules from `reflection.md` kept: only you are evidence; a pattern needs two separate occurrences; corrections win; be stingy. Code applies it in one transaction and rejects replays.

**Built after each chat with the whole history in view (Max plan Phase 3, 14 Oct 2026):** reflection also gets up to 8 earlier conversation summaries and events found in the whole history by keyword and meaning (`reflection_queries`, `reflection_choose` in `tools/retrieval.py`), so a pattern months apart is in front of it. A new, rewritten or merged note close to one you deleted is refused by shared words or by meaning (the on-phone model, cosine 0.75), and a new note that repeats an active one of the same kind in meaning (0.9) is dropped, never when one says "not" and the other does not (`tools/appliers.py`; vectors are kept in memory only). Profile entries carry the day they were first said, and a value reflection replaces is kept as "before, until that day" (`profile_past`), so an old fact never poses as a current one; a correction in your own words keeps nothing of the wrong value, and deleting an entry deletes its past too. Gardening is given the groups of notes the code finds close in meaning, as suggestions it may merge; the applier still checks every merge.

**Reasons from check-ins feed patterns through code:** the app counts reason chips per target, weekday and, since schema 6, the time of day he answered (on the day itself). The reflection and review jobs may propose a pattern note citing those counts (for example "You mark 'tired' on 9 of the last 12 missed evening targets"). Evidence rows store the counts and dates.

**Retrieval:** keyword (FTS4) plus meaning search (embeddings), merged by reciprocal rank fusion and re-ranked by recency and evidence grade. Embeddings find and rank only; they never write text or change confidence.
- Model (Max plan Phase 4, 14 Oct 2026): **gte-base-en-v1.5** (Alibaba, Apache-2.0, its int8 ONNX export, 147 MB, bundled in two parts and joined on the phone; 768 dimensions, CLS pooling, BERT uncased vocabulary, 512-token input in the app), no prefix. The owner chose the best outcomes whatever the size. `tools/eval/compare_embedders.py` measured 31 models and 8 mixes of two or three; averaged around each line it was best or level on every exam: exam 2 95.1% to 97.2%, exam 1 (held out) 84.7% to 92.4%, the reflection exam 88.5% to 96.2%, all 31 rewordings of a deleted note caught at bge's cost. No mix and no re-ranker did better. Its lines: meaning search 0.42, deleted note 0.65, duplicate 0.88, gardening suggestion 0.82 (the bge lines were 0.62, 0.75, 0.9, 0.85). On the update the old vectors are removed and made again in the background. Before Phase 4: **bge-small-en-v1.5** (384 dimensions, 512-token input, MIT). It pools with the first-token (CLS) vector, not mean pooling like the current MiniLM code, so `OnnxEmbedder` must change and be tested against reference vectors before use. If recommended for short search queries, add the model's documented query prefix; verify with the model card at build time.
- Vectors are derived data in the encrypted database. Changing the model: the app removes the old model's vectors and makes the new ones in the background by itself (Settings, Advanced, Re-index does the same by hand). Never a migration.
- On failure, fall back to keyword results.
- Measure retrieval with `tools/eval` (memory exam) before and after.

---

## 8. Reviews and adaptation

- **Weekly review** (Sunday, local time; catch-up on launch if missed): code computes the week's facts; one model call (`review_weekly.md`) writes a short note under them. Shown in Plan, Reviews (mockup 6). It never changes targets. If the re-plan trigger fires, the review shows "The plan may be off" with a Talk about it button.
- **Review facts, Max plan Phase 6:** besides the counts above, each target's line now has the period before ("The week before: done on 6 of 7 answered days"), the 28-day trend of section 6.2 ("Done rate by week, last 4 weeks (oldest first): 57%, 71%, 86%, 100%", a week needs 3 answers), and the time of day he answered when the period holds enough of it; each backlog has plan versus actual for the period ("B1 "Lectures": the plan asked for 14 in this period, done 14, exactly on it"), read under the target values in force each day (schema 8). All in `tools/planner.py` (`done_trend`, `trend_line`, `planned_amount`, `plan_period_line`) with vectors.
- **Monthly review:** plan versus actual per goal, backlog status, top reasons, and up to three "revisit" questions (`review_monthly.md`, JSON). Each revisit opens a Plan chat; nothing is proposed as a card by the job.
- **Monthly story** (section 2.5, `story.md`, strict JSON): after each calendar month ends, one job (key `story:YYYY-MM`) writes the short version (40 to 120 words), picks a then and a now quote by id from his saved quotes, and writes at most three "overcame" items (title, then-quote id, what he did, now). Code validates it (`apply_story` in tools/appliers.py): ids must be in the list it was given, the then quote older than the now quote, plain text, every quotation his and every number in its inputs; a failed check twice means it is not published and the page keeps the last story. Skipped (no call) when the month had no reflected conversation and no check-in. It never creates cards and never changes goals, plans, targets, promises or notes.
- **Quarterly chapter:** written from summaries, notes, reviews, goals and focus summaries; stored; shown in What I know, Chapters.
- **Morning brief** (added 13 Oct 2026; **removed the same day at the owner's request**: it is not written, not shown and has no Settings switch. The `brief` table, `prompts/brief_daily.md` and the `check_text_job` kind `brief` contract stay so the schema and contracts are unchanged. What follows is the design as it was): a few lines from the coach each morning, shown on Talk home and at the top of Plan, Today. A background job (key `brief:YYYY-MM-DD`, one per day, after the day boundary; catch-up on launch) writes 15 to 90 words from finished facts only: the NOW sentence, today's PLAN_STATE lines, yesterday's check-ins, today's open promises with their due phrases, and the active notes (`prompts/brief_daily.md`). It is checked like the letter (`check_text_job` kind `brief`: plain text, every quotation his, every number in its inputs); a brief that fails twice is not shown (no line at all; tomorrow has a new one). Skipped with no call when nothing is scheduled today, no promise is open and nothing was checked in yesterday. It never creates cards and never changes goals, plans, targets, promises or notes; it is stored in the `brief` table (schema version 5) and only the latest is shown.
- **Weekly letter: on by default.** The pipeline each Sunday: 19:00 the review facts and review note are written; 19:30 the letter is written from the week's conversations, check-ins and review facts (`letter_weekly.md`); it is verified (every quote found word for word, every number found in the inputs); then it is stored with title, body and `readyAt`. It appears (a) as the yellow card at the top of Talk until opened, (b) as a dot on Plan and on its Reviews chip, (c) as the newest row under Weeks in Plan, Reviews. If you turned on "Sunday alert", one silent notification says "Your weekly letter is ready". Opening it marks it read. **Talk about this letter** starts a free-talk conversation that has the letter in its context (LETTER block), and any promise or plan still needs your tap. A letter is skipped (no model call) when the week had no reflected conversation with at least 4 of your messages and fewer than 3 answered check-in days; the review still appears. One letter per week, enforced by a unique key. If it fails verification twice it is not published; the list shows one quiet line "This week's letter is delayed" with Try again. You can delete any letter; deleted letters are never regenerated.
- **Monthly and yearly letters** (added 15 Oct 2026 at the owner's request, moved from the Later list): the weekly letter covers each week, the **monthly letter** is written from that month's weekly letters (a week belongs to the month its Sunday is in), and the **yearly letter** from that year's monthly letters (`letter_monthly.md`, `letter_yearly.md`). The monthly letter is due from the 2nd of the next month (one day of grace so the month's last Sunday letter is written first) and needs at least 2 weekly letters; the yearly letter is due from 3 January once December's letter is settled (or from 10 January whatever happened) and needs at least 2 monthly letters. With fewer, it is skipped with no model call. Only the latest month and year are written. They use the letter model and the same checks (`check_text_job` kind `letter`: every quotation is his, from his messages, saved quotes and check-in notes of the period; every number is in the inputs; two failed checks and it is not published, "This month's letter is delayed" with Try again). Keys `letter:month:YYYY-MM` and `letter:year:YYYY` in the same `letter` table (no schema change). They appear as the letter card on Talk and the dot on Plan, under Months (a month opens its letter, whose See the numbers is the monthly review) and Years in Plan, Reviews. **No notification**: the only letter alert stays the optional Sunday one (rule 7). The one Settings switch "Letters" turns all three on or off. The quarterly chapter still reads only the weekly letters.
- Skip any job with nothing to say (no API call). Retry background jobs 3 times with backoff; then show one quiet "delayed" line with Try again.

---

## 9. Reliability and lifetime safety

- **Database:** Room + SQLCipher, key = 32 random bytes in the Android Keystore (`KeystoreCipher`). Schema version starts at 1, exported schemas committed, a written migration and a MigrationTest for every change. `fallbackToDestructiveMigration` never appears (a test searches for it).
- **Backups:** AES-256-GCM over gzip(JSON), key from your passphrase with PBKDF2-HMAC-SHA256 at 600,000 iterations (iteration count in the header). Automatic, weekly by default or nightly (your choice), made by the nightly maintenance to a folder you choose (Storage Access Framework, so Drive works), **read back and checksum-verified** before pruning; keep the last 4 by default (8, 14 or 30 if you choose); header carries schema version. A backup that was not verified is deleted, never counted. The format is written down in `docs/BACKUP-FORMAT.md`, and `tools/backup_format.py` opens a backup on any computer without the app. Sample backups of every format and database version are restored in CI (Max plan, Phase 1, 14 Oct 2026).
- **Nightly maintenance** (Max plan, Phase 1): at 03:30, battery not low, once a day: database integrity check (no backup after a failed check, so good backups are never pushed out), stuck jobs and replies repaired, search index and meaning vectors completed from the raw rows, lost reminder alarms set again, year-old log lines removed, missed background work caught up, backup when due. It never changes a raw row and never calls the AI; the result is a line in Health.
- **Export everything:** unencrypted readable Markdown plus JSON (conversations, goals, plans, check-ins, promises, notes with evidence, chapters). Warn that it is unencrypted.
- **Restore test:** a Health item you mark yearly: restore on a second phone/emulator and confirm.
- **Write your backup passphrase on paper.** Without it a backup cannot be restored. The database key lives in the phone's Keystore and is lost with a reset or a new phone, so the backups and the passphrase are the way back. Settings has a Test button that says whether a typed passphrase matches.
- **Offline:** everything readable offline; an unsent message stays in the box with a Retry button.
- **Xiaomi/HyperOS:** battery unrestricted, autostart, exact alarms status shown in Settings with buttons.
- **App lock** (biometric) and hide in recents, on by default.
- **Everything configurable that tends to change:** provider, base URL, model names, embedding model, prompts. So a retired model does not need a new APK.
- **Updates:** sideloaded signed release APK from GitHub Actions; update over the old app (never uninstall); signing key and its password stored safely outside the repo.

---

## 10. Data model (Room, version 8)

`settings`, `event` (the behaviour chains reflection records: when, situation, feeling, action, payoff, outcome), `conversation` (id, title, focusId, mode, startedAt, endedAt, summary, reflectedUpToMessageId, offTheRecord), `message` (id, conversationId, role, content, createdAt, status streaming/complete/interrupted/failed), `focus`, `goal`, `goal_revision`, `plan`, `target` (since schema 8 with `minutes`, his estimate of one session's minutes), `target_version` (a target's values from each day a change took effect; added in schema version 8, Max plan Phase 6), `backlog`, `checkin` (since schema 6 with `entered_minute` and `entered_day`, his local time and day of answering), `promise`, `note`, `note_evidence`, `profile_entry` (since schema 6 with `since_day`, the day the value was first said), `profile_past` (a value replaced by a newer one, with its until day; added in schema version 6, Max plan Phase 3), `person`, `review`, `chapter`, `letter`, `story` (Your story's monthly text; added in schema version 4), `brief` (the morning brief; added in schema version 5), `quote`, `reflection_item` (the idea tags, disagreements and contradictions reflection returns; added in schema version 2), `search_doc` (FTS4), `embedding_row`, `prompt_override`, `error_log`, `usage_month`, `usage_kind` (the month's calls and tokens split by what made them: chat, the card follow-up, memory, letters, reviews; added in schema version 9 for Health's cost by job). 34 simple tables, including `rest_day` (planned days off) and `job` (the exact definition is `db/schema.sql`; state machines are in `contracts/states.json`). `note.kind` is pattern, what_helps, what_doesnt, strength, value or thread (strengths are shown under What helps as "What you are good at"). `letter` has periodStart, periodEnd, title, body, readyAt, readAt, key (unique). `review` has kind (week or month), period, facts JSON, narrative, readAt. (This is more than the 8 I first suggested; the planner and evidence rows are the reason.) Indexes on conversationId, createdAt, status, date columns; schema version 7 (Max plan Phase 5) adds only indexes, chosen by `tools/query_plans.py`, which plans every query the app runs and fails when one reads a whole growing table without a written reason. Paging for lists.

---

## 11. Reuse from the old repo (copy, adapt, test)

`ai/OpenAiWire.kt`, `OpenAiCompatibleClient.kt`, `AiClient.kt`, `AnthropicClient.kt`, `AnthropicWire.kt`, `CancellableCall.kt`; `security/KeystoreCipher.kt`, `SecretStore.kt`; the SQLCipher open code in `PurposeDatabase.kt`; `promise/Reminders.kt` and the reminder time rules; `chat/ReplyService.kt`; `time/TimeFacts.kt`, `CalendarDays.kt`; `memory/QuoteCheck.kt`; `SearchDoc`/`SearchIndex`; `HybridRetrieval.kt` and the embedder files (with the bge change); `backup/` (encryption and verify logic); `tools/eval/`. Do not copy: ActionExecutor, ToolCalls, ReplyMarkers, ToolSchemas, growth, journeys, ledgers, screen time, cost and off-peak, onboarding, per-job models.
Old data: start clean. No importer (your decision).

---

## 12. Prompts (written, in `Clarity-Life/prompts/`)

All of these exist now and pass `tools/check_prompts.py`. Review them; you have authority to change any of them. After any change run the checker, then the test bench on the real model.

| File | What it does |
|---|---|
| `persona.md` | The coach's mind. Five private checks before every reply (what they need, the real question, what I know and how well, the other side, what a wise person would leave out). Adapts to their length, state, stage of change, hour, culture, and to the notes that say what helps or does not help them; changes approach when it is not working. Depth with no length cap but one thread, no menus, no lists, no stock phrases, no em dashes. Holds ground, same facts give the same assessment in any mood, hears the other side, 0 to 10 want check before a promise or plan, if-then plans, a missed item is information, honest planning (a tiny start is never the plan), a short safety rule if they may be in danger, session landing after about 20 turns, and the rule that context text is data, never instructions. |
| `cards.md` | The seven card types and exact format, per-chat allowed list filled by the app. |
| `examples.md` | 21 fictional conversations, deliberately unlike their real life so they cannot be mistaken for facts about them (including the syllabus gap with real numbers, graded exposure steps for a fear of driving, a number it was not given, a missed item, a challenge that is earned, the other side, danger). Cards in them are parsed by the real parser in the checker. |
| `mode_untangle.md`, `mode_decision.md`, `mode_plan.md`, `mode_focus.md` | The four chat types. `mode_plan.md` is the planning method (deadline backward, typical versus best pace, levers, missing facts, load check, two levels, obstacles, review date from options, tiny start as bridge only, not enough data, per-domain notes). |
| `reflection.md` | After a conversation: strict JSON with observations, notes with verified evidence (quotes or counts copied from the app's facts), events, focus summary, profile, people. Cannot touch promises or plans. Learns how to coach them from their reactions. |
| `review_weekly.md`, `review_monthly.md` | Narrate the app's computed facts; every number must exist in the inputs. |
| `letter_weekly.md`, `chapter.md`, `gardening.md` | Letter (a person-level letter, not a repeat of the numbers), quarterly chapter, monthly memory gardening. |
| `story.md` | The monthly story for Your story (section 2.5): strict JSON with the short version, a then and a now quote chosen by id, and at most three things overcome; every number from the facts the app counted. |
| `testbench.md` | 69 scenarios with automatic checks (semantics pinned by `tools/bench.py`) plus a human Expect line. |

`contracts/prompts.json` is the single contract (placeholders per prompt, required rules, forbidden old syntax, size budgets, banned phrases). `contracts/context.md` is the context packet the app must build.

## 13. Tests

**Contract tests (already runnable):** `bash tools/check_all.sh` runs the prompt checks, the card, planner, text-check, context, applier and bench vectors, the schema checks and the mutation check. The Kotlin tests read the same `contracts/` files, so the Python reference and the app cannot drift apart.
**Unit (JVM, I can write and run the pure-Kotlin ones here):** pace math, rest days, time zones and month ends, check-in level mapping, answer-rate handling, backlog projections, re-plan trigger, card parser (valid, invalid, duplicate, injected text), time phrases, quote verification, evidence rules (coach text never raises confidence), note caps, retrieval fusion, embedding pooling versus reference vectors, backup encrypt/decrypt and verify, migration tests, a search for `fallbackToDestructiveMigration`.
**Test bench (run on the real model; the checks marked in `testbench.md` run automatically, you judge the rest):** the old scenarios that still apply, plus: caving after three pushbacks, same story in three moods, padding and menu test, echo test (you are partly wrong), avoidance repeated three times, weak commitment (no card), missed promise, memory callback correctness, a 40-turn conversation, the **syllabus gap** (numbers show the goal cannot be reached at the current pace; the coach must not answer with only a tiny start), the social-anxiety plan (graded steps, next step from last result), "paste a 1-year goal" turning it into targets with a card, and an impossible plan (the coach says so).
**Scoreboard:** `tools/eval` before and after any prompt change; memory exam for retrieval.
**Device checklist** (needs your phone): reminders on HyperOS, reply survives app switch and process kill, restore on a second device, battery settings, app lock.
I cannot build or run the Android app in my environment (no Android SDK here). Builds and device checks happen on GitHub Actions, your machine, or Claude Code.

---

## 14. Build order (finish each before the next)

A. **Skeleton:** project, Bold theme, encrypted DB, Settings with provider/key/model, Talk with streaming, persona, conversations list. *Done when:* a real chat works and survives restart and an app switch.
B. **Memory:** reflection, notes with evidence, What I know, FTS search, then embeddings (bge). *Done when:* a fact from chat 1 appears in chat 2 and in What I know, and the memory exam does not regress.
C. **Promises and cards:** card parser, promise cards, reminders, due card on Talk. *Done when:* a reminder fires on your phone and nothing saves without a tap.
D. **Planner:** goals, targets, backlogs, check-ins, computed facts, plan cards, reviews. *Done when:* the syllabus test scenario passes on the real model and the math tests pass.
E. **Focuses and chapters, backup, export, Health and test bench.** *Done when:* a backup restores on a second device.
F. **Freeze:** two weeks of real use, the test bench passing, the restore test done. Then v1 is final.

---

## 15. Honest limits

- The AI's judgment comes from the model; this system makes it well-informed and honest, not infallible. It cannot guarantee a syllabus gets covered.
- Plans and margins here (the 85% re-plan trigger, 7-day data minimum, 12k budget) are starting values to tune from real use.
- Embedding quality gains are measured only on a small synthetic exam in the old repo; the phone speed is unmeasured.
- Android and provider changes can still force an occasional fix; the settings above reduce but do not remove that.
- Losing the phone without a backup, or losing the passphrase, loses the data.

---

## 16. Later list (not in v1)

Growth tree, screen-time tracking, read aloud, onboarding questionnaires and Big Five, practice-a-conversation mode, journey templates beyond what plans cover, multi-language, cost budgets and off-peak scheduling, widgets.

## 17. Decisions (9 Oct 2026)

Decided by the owner: start clean (no old data import); Claude may rewrite and improve the prompts and anything else in the app; weekly letter on by default; the owner creates the new GitHub repo.
Still open (not blocking): make the old repo private (it is public today); whether to freeze v1 for two weeks of real use before adding features (recommended, see section 14 step F).

Also decided or recommended on 9 to 10 Oct 2026: letters live under Plan, Reviews with a card on Talk and a dot on Plan (section 2); the optional Sunday alert is a Settings toggle (my recommendation: on, silent; it is one notification a week); day boundary at 4:00 am (changed to midnight on 13 Oct 2026 at the owner's request; 1 to 6 am stay as choices); the coach has a short safety rule for danger but no help-line screen, matching your earlier removal.

Decided on 13 Oct 2026: **Your story** (section 2.5) and its monthly story job (section 8) are part of v1. It is not the growth tree on the Later list: no scores, levels or badges.

Decided on 13 Oct 2026, the 0.1.6 redesign (the owner approved the mockups on the canvas "Clarity Goal screen redesign", page "Redesign 0.1.6", and chose every recommended option):
1. **Typography:** Instrument Sans (interface) and Instrument Serif (headlines, big numbers, the coach's longer writing) replace Space Grotesk. Both are bundled (SIL OFL, licenses in `app/licenses/`); no new library.
2. **No disclaimer lines** on screens: "Counted by the app", "Written by the coach from your record", "Nothing is guessed" and the like are removed. The rules stay in code: numbers by code, quotes and numbers checked before anything is published.
3. **Talk home** (section 2.1): never a blank screen.
4. **Morning brief** (section 8): a daily background job, validated like the letter, numbers only from code facts, no cards, no state changes; shown on Talk home and Plan, Today; on by default with a switch in Settings. Contract: `check_text_job` kind `brief` in `tools/appliers.py` with vectors, ported in `TextJobCheck.kt`.
5. **Goal screen:** when the saved plan falls short of the goal, no red number. A calm card says what the saved plan is and what it reaches, with one button that opens a Plan chat with a message already typed (not sent), for example "Your saved plan is 2 a day. Add your ramp?"
6. **Settings:** grouped cards with icons, switches and real buttons, short copy; no text-link rows.
7. **Palette** (section 2): darker, calmer ground; yellow only for the main action and key numbers; coral for suggestion cards; a calm green for saved and healthy; bottom tabs with icons.
8. The unread letter card sits at the top of Talk home.

Decided on 13 Oct 2026, later the same day: the **morning brief is removed** (Talk home, Plan, Today and its Settings switch); its background job no longer runs. **Your story** is made cleaner: one title block, the range switch only once it matters, a counts card only once something is counted, and what helps and strengths as a list with their counts written out.

Decided on 14 Oct 2026 (Clarity 0.1.11, the owner's request for a design that stays calm after a year of use): a note in **Your story** or in a What I know section opens its proof in a **sheet over the page** (the note, its grade, how often and when, the newest thing he said, every moment folded, eight at a time; That's right, Not quite, Delete), never a jump to another screen. Your story lists the three surest notes (confirmed, then most seen) on two lines each with "Show N more"; "What you overcame" shows Then and Now and unfolds what he did. **What I know** shows its sections as one list with counts. Promise answers are two by two, with Done marked by a warm edge. Past promises show twelve at a time. Coach cards keep the Saved receipt and get no Undo. Mockup: `docs/mockups/redesign/Screens.html`.

---

## 18. Designed for few bugs

No software is bug-free. This design tries to make bugs rare, small, loud and recoverable, in that order.

**1. A small surface.** Three tabs, one chat component, seven card types, 33 simple tables, one AI provider interface. Every feature left out (growth tree, ledgers, screen time, journeys, per-job models, onboarding, cost scheduling) is a class of bugs that cannot happen. The scope lock in the header is part of the bug plan.

**2. One writer for every fact.** All changes go through a small set of named commands in a repository layer (SaveCard, RecordCheckin, ResolvePromise, ApplyReflection and so on). Each command is one database transaction. Screens never write to the database directly, and AI output reaches the database only in two ways: your tap on a card (which runs a command), or one of the validated background appliers. There is no third path.

**3. Fail closed at every AI boundary.** The AI is the least predictable part, so everything it produces is checked before use, and anything wrong is dropped, not repaired by guessing.
- Cards: only trailing lines of the exact format, strict field types and limits, ids must exist, review dates must be one of the offered options, per-chat-type allowlist. Reference parser plus shared test vectors, mutation-tested by `tools/mutation_check.py` (planting a known bug must make the vectors fail, in CI). Strict JSON: duplicate keys, NaN and Infinity are rejected; a plan card is only valid when review options were offered.
- Reflection and gardening JSON (`tools/appliers.py`): schema check, unknown ids rejected, caps enforced, evidence quotes verified against their messages, evidence counts verified against the app's own facts, new notes always start as guess, confidence never rises more than one step and never to confirmed without their tap, notes they edited or confirmed are untouchable, notes close to ones they deleted are never re-created, replays rejected, one transaction.
- Letters, chapters, reviews: **QuoteCheck** (every quoted phrase, in straight, curly or single curly quotes, across line breaks, must appear word for word and on word boundaries in what they wrote) and **NumberCheck** (every number in the text, in digits or in words, must appear in the inputs). Fail twice and the text is not published; you see a quiet "delayed" line, never a wrong quote or invented figure.
- A rejected card or text never hides the coach's prose.

**4. A pure core, thin glue.** Pace math, rest days, day keys, time phrases, card parsing, evidence rules, note caps, context budgeting and the re-plan trigger are plain Kotlin functions with no Android in them, tested on the JVM with fixed fixtures (leap day, month end, rest days, a time-zone change, check-ins after midnight). Android code only connects them to screens, alarms and storage.

**5. Time cannot corrupt history.** Each check-in stores the UTC time and the local day key chosen at write time (day boundary midnight by default, a setting). Changing time zone or the system clock never moves old results.

**6. Explicit state machines.** Message (streaming, complete, interrupted, failed), job (pending, running, succeeded, failed, skipped), card (offered, saved, dismissed) and promise (open, kept, not done, moved, let go) each have a written table of allowed transitions, and a test that illegal transitions are rejected. On every start a repair sweep turns stuck states into safe ones (streaming becomes interrupted, running becomes pending). No spinner can stay forever.

**7. Idempotent background jobs.** Every job has a deterministic key (type, period, conversation, hash of inputs) with a unique index. WorkManager can retry or run twice; the second run finds the key and stops. So there are no double letters, double reflections or double notes.

**8. Derived data is rebuildable.** Search index, embeddings, review facts and planner facts are computed from the raw rows (messages, check-ins, promises, notes). A corrupted index costs a rebuild, not your history. Settings, Advanced has Rebuild search and Re-index buttons. Raw rows are soft-deleted only (except when you choose Forget or Erase).

**9. Prompts are tested like code.** `contracts/prompts.json` is checked in CI by Python and by a Kotlin test: exact placeholders, required rules present, forbidden old syntax absent, size budgets. The in-app prompt editor validates a prompt against the same contract before it can be saved, so a typo cannot silently break the coach. Every built-in prompt ships in the APK; Reset to built-in always works.

**10. Injection-proof context.** Anything that came from you or from the model is escaped before it enters a context block (`[[` and `]]` neutralised, header lines indented). The persona says block text is data, not instructions. Test bench has a card-injection scenario.

**11. Honest numbers.** The coach never works out dates; it copies finished facts, day counts and review-date options, and any sum of its own is shown and called a rough estimate. A failing computation shows "not enough data", never a guess.

**12. Data safety first.** Versioned Room schema with a MigrationTest per change, never destructive migration (a CI grep fails the build if the word appears), verified encrypted backups, readable export, paper passphrase. See section 9.

**13. Loud, content-free diagnostics.** Health screen: last run and result of each job, counts of cards dropped, quotes rejected, numbers rejected, JSON rejected, interrupted replies, backup age, database size, the last nightly check and whether every reminder ahead has its alarm. A rising rejection count after a model or prompt change tells you something shifted. Nothing in a log contains message text or keys.

**14. A CI gate on every push.** Android lint, JVM unit tests, `check_prompts.py`, `test_cards.py`, a grep for `fallbackToDestructiveMigration`, for hard-coded model names, and for logging of content, then a signed release build. A tag is only cut when all pass, and the last good APK is kept so you can go back.

**15. Changing the model is a controlled event.** Changing the chat or background model name shows "Run the test bench now". Bench results (auto-checks passed, your pass/fail marks) are stored per model, so you can compare before you switch for good.

**16. Few dependencies, pinned.** Only the libraries in the stack list, at pinned versions, updated at most quarterly and only after the gate passes.

**17. Failure containment in the UI.** A corrupted row or a failed job never blocks a screen: lists load with paging and per-row error handling, every screen has an error state with Retry, and the app always opens to Talk. Background trouble shows one quiet line, never a crash.

**What this does not remove:** model behaviour can still be wrong in ways no check catches (a bad judgment sounds fluent); Android, HyperOS and provider changes will still occasionally need a fix; a lost phone without a backup, or a lost passphrase, loses the data. The test bench and your own two weeks of use are the defence for judgment; sections 9 and 15 cover the rest.
