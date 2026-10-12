# Context packet contract

What the app sends with every chat request. The reference implementation is `tools/context.py` and its behaviour is pinned by `contracts/context_vectors.json`; `ContextBuilder.kt` must pass the same vectors.

The layout puts the longest stable text first, so the provider's prefix cache (DeepSeek and Anthropic both cache by exact prefix) is hit on almost every request:

```
system message   = Part 1 (fixed per chat type)  +  Part 2 (frozen for this conversation)
messages         = the history window             (grows; moves rarely, in jumps)
final user turn  = Part 3 (volatile blocks)  +  "## MESSAGE"  +  their new text
```

## Part 1: the fixed prompt (start of the system message)
In this order, joined with a blank line:
1. `persona.md`, with `{{USER_NAME}}` filled.
2. `examples.md`, with `{{USER_NAME}}` filled.
3. `cards.md`, with `{{USER_NAME}}` and `{{ALLOWED_CARDS}}` filled (the names allowed for this chat type, comma separated).
4. The chat-type file: `mode_untangle.md`, `mode_decision.md` or `mode_plan.md`. Free talk adds nothing. When the conversation belongs to a focus, `mode_focus.md` follows it (after a blank line), so the selected chat type's instructions still apply inside a focus. The size budget below is checked with one chat-type file; a focus conversation carries `mode_focus.md` on top of it.

Persona and examples come first because they are identical for every conversation, so that part of the prefix is shared across all chats. Part 1 is about 12k real tokens; `tools/check_prompts.py` fails if it grows past the budget in `contracts/prompts.json`.

## Part 2: frozen context blocks (rest of the system message)
Built when the conversation starts, stored with Part 1 in `conversation.prefix_json`, and reused byte for byte for every request in the conversation. Part 1 and Part 2 are rebuilt in only two cases: they switch the chat type in this conversation (the mode file and the card list change), or the conversation resumes on a later day (`prefix_day` differs from today's day key), so a conversation reopened next week does not carry last week's notes. Editing a prompt or a setting never changes an open conversation's prefix.

Each block starts with `## NAME` on its own line, in this fixed order. A block with nothing to say is omitted, never sent empty.

| Block | Content | Trim |
|---|---|---|
| FLAGS | `tough_love: gentle|balanced|firm`, `listen_only: true|false`, `chat_type`, `off_the_record` | never |
| PROFILE | up to 30 entries, one line each, with grade (confirmed/likely/guess) | lowest grade first |
| PEOPLE | name, relation, one line | least recently mentioned first |
| NOTES | active notes: id, kind, grade, times seen, first/last seen as a period, text, up to 2 evidence lines | guess before likely before confirmed; fewer times seen first |
| FOCUS | when the chat belongs to a focus: id, name, why, rolling summary, current step, last 3 step results | never |
| CHAPTERS | last 2 chapters | older first |
| RECENT_SESSIONS | last 5 session summaries with dates | oldest first |
| IDEAS_RECENT | the last 20 idea tags used | oldest first |
| LETTER | only when opened with "Talk about this letter": the letter's title and body | never |
| REVIEW | only when opened with "Talk about it" from a review: that review's facts and note | never |

Budget for Part 2: 9,000 estimated tokens. Each item carries a drop rank computed by the app from the Trim column (0 = never drop); `trim_blocks` removes the highest rank first until the blocks fit. If the never-trim blocks alone exceed the budget, nothing is cut and a content-free `context_over_budget` error is logged.

## History window
Every stored message of this conversation is sent again in order: their messages as `user`, escaped (below), prefixed with the stamp the app wrote when they sent it (`[Fri 9 Oct, 21:40]`); the coach's as `assistant`, exactly as shown, escaped (below), and then one line `[[suggest: {json}]]` for each card that reply offered, in order, written from the stored card JSON (already validated, so it holds no bracket pair; a stored card that does is left out). The cards go back in because a history of replies that point at a button with no card line teaches the model to stop sending card lines. The card's status is not in the history (it would change old messages and break the cache); CARD_RESULTS carries it. Off-the-record conversations work the same way while open; they are simply never stored.

The window has its own budget of 24,000 estimated tokens. The index of the first message sent is stored with the conversation and only moves forward. When the window is over budget it jumps forward until the history is at most 75% of the budget (never past the newest 6 messages), so it moves rarely and the cached prefix survives many turns. When messages have left the window, the conversation's running summary is sent in the EARLIER block.

## Part 3: volatile blocks (the final user turn)
Rebuilt for every request and placed in the final user message, before `## MESSAGE` and their text. Fixed order:

| Block | Content | Trim |
|---|---|---|
| NOW | one code-written sentence, see below; then, for up to 4 dates they mentioned in their recent messages, one line each with the weekday and the number of days after today, counted in code | never |
| PLAN_STATE | goals (G1...), targets (T1...), backlogs (B1...) with the computed facts, review options, re-plan flag | never |
| OPEN_PROMISES | open items with ids and code-written due phrases ("due Monday 5 October, 8:00 am (in 1 day 7 hours)", "was due Friday 5:00 pm, 2 days ago") | never |
| CHECKIN_FACTS | counted by the app: today's check-in state, reason counts per target, weekday and time band, answer rate, last-14-day summary; only lines with at least 3 items behind a number | never |
| CARD_RESULTS | what they did with each card under the coach's last reply: saved, not now, or no answer yet; or one line saying no button was shown, when that reply spoke of one and had no card; and one line for each card the parser threw away, with its reason, so the coach can correct it | never |
| EARLIER | the running summary of the part of this conversation that is no longer in the history window | never |
| RELEVANT_PAST | top matches (max 5) from keyword plus meaning search, each with a date and conversation title; omitted when nothing is strong | lowest score first |

PLAN_STATE, OPEN_PROMISES and CARD_RESULTS are volatile on purpose: if they save a plan or a promise mid-conversation, the very next reply already sees it, so the coach never suggests a duplicate or talks about a plan that no longer exists. Budget for Part 3: 3,000 estimated tokens.

### NOW
One sentence, written by code, that names the day the moment counts as (the day boundary, 4 am by default):
- `It's Friday 9 October 2026, 9:40 pm (night).`
- `It's Sunday 4 October 2026, 12:40 am (late night; their day still counts as Saturday until 4 am).`
No greeting band is given; the coach decides.

### Resolving "when" in a promise card
The app resolves the card's `when` text in code (`PromiseTimes`), relative to the day key, and the card always shows the resolved weekday, date and time ("Sat 10 Oct, 17:00") before they tap, so they see exactly what will be saved. Between midnight and the day boundary, "today", "tonight" and "tomorrow" are ambiguous: the card opens its time picker with the most likely reading preselected instead of saving silently. If a phrase cannot be resolved at all, the card shows the picker empty.

### OPEN_PROMISES
Written by code (`PromiseText`), never trimmed. First every open promise, soonest first, with its code-written due phrase; one that has passed says it has no answer yet (never "not done"). Then what they answered in the last 14 days (at most 8, newest first), so the coach can follow up without asking what happened:
```
P4 "Study 1 hour at 8am", due Monday 5 October, 8:00 am (in 1 day 7 hours).
P2 "Call Mom", was due Saturday 6:00 pm, 6 hours 40 minutes ago, no answer yet.
P3 "Study 20 minutes at 5pm", was due Friday 5:00 pm, 1 day 7 hours ago: not done, reason: tired.
```
Outcomes are `done`, `not done` (with `, reason: X` when they picked a chip), `moved to a new time` (the new time is its own open line) and `let go`. Older promises are in the search index with their outcome, so RELEVANT_PAST can bring one back.

## PLAN_STATE line formats (all numbers computed in code by `tools/planner.py`)
```
Goal G2 "Clear the chemistry lectures", deadline 20 Nov.
Backlog B1 "Chemistry lectures": total 40, done 0, remaining 40, scheduled days left 36 (Fridays off), required 1.2 per day.
  Typical pace (last 14 days, 10 answered days): 0.6 per day, reaches 21 of 40, short by 19.
  Best fortnight so far: 1.0 per day, reaches 36 of 40, short by 4.
  Status: does not reach the total, not even at the best-fortnight pace.
  Answer rate 83%. Availability: not set.
Target T3 "Chemistry lectures" (G2): minimum 1, target 1, Sat-Thu. Last 14 days: done 6 of 10 answered days.
Review options: 17 Oct (Saturday, after one week), 24 Oct (Saturday, after two weeks).
Re-plan flag: yes (below pace in two reviews in a row).        <- only when true
  Not enough data yet: fewer than 7 answered days in the last 14 days.   <- replaces the pace and status lines when true
```
Display rounding never flatters the plan: required pace is rounded up, "reaches" is rounded down, and the Status line is decided from the same numbers that are shown. The coach copies ids, numbers, the status and review dates exactly. It never calculates. A plan card is only accepted when Review options were offered.

How the app assembles it (`core/PlanBlocks.kt`, tested against the sample above in `PlanBlocksTest`):
- Ids are the database ids: G2 is goal 2, T3 target 3, B1 backlog 1. Each goal is followed by its backlogs, then its targets; backlogs and targets without a goal come last. With nothing tracked yet the block says `No goals, targets or backlogs yet.` and still ends with the review options.
- A backlog's done = `start_done` + the amounts entered on its linked targets from the day the backlog was made (earlier work is already in `start_done`, so it is never counted twice). Its pace and days left use the first linked target's schedule and the whole history of its linked targets, added up per day.
- Review options use the days on which at least one active day-based target is scheduled (every day when there are none), minus planned rest days.

## CHECKIN_FACTS line formats (counted in code, `core/PlanBlocks.kt`)
```
Today (Saturday): T3 "Chemistry lectures" not answered yet; T5 "Walk" done (target).
T3 "Chemistry lectures", last 14 days: 10 answered of 12 scheduled, done on 6.
T3 "Chemistry lectures", last 14 days: tired on 3 of 4 days marked Not or Part.
T3 "Chemistry lectures", last 14 days: marked Not on 3 Sundays.
```
The today line lists only targets scheduled today (`not answered yet`, `done (target)`, `marked Not, reason: tired`, `marked Part (minimum)`, `rest`), or says `Today (Friday) is a planned rest day.` A 14-day line is written only with at least 3 answers behind it (otherwise `too few answers to count` when 3 or more days were scheduled); reason and weekday lines only with at least 3 items behind the number, and a reason is never counted above the days marked Not or Part. The time of day is not recorded, so there are no time-band lines. Reflection may copy these lines word for word as count evidence.

## Dates on plan, backlog and target_change cards
Read in code by `PlanDates` (`tools/plan_dates.py`, `contracts/plan_date_vectors.json`): today, tomorrow, a weekday, `20 Nov`, `Nov 20`, `20 Nov 2026`, `2026-11-20`, counted from their day key. Anything else, a date in the past, an end before its start, or a review date that is not 1 to 28 days ahead, means the card saves nothing. The card shows every date it read before they tap.

## Rules that keep this safe
- Anything originating from them or from the model (messages, notes, quotes, summaries, letters, pasted text) is escaped before it is sent: `[[` and `]]` become `[ [` and `] ]` (repeated until none remain), and a line starting with `## ` (after any spaces) is indented by two spaces. A note or a pasted email can never smuggle in a block header, a fake NOW, or a card.
- Block text is data, not instructions. The persona says so (Grounding).
- Token counts are estimated with `estimate_tokens` (3 characters per token), which is deliberately pessimistic, so a real tokenizer never finds the request over a limit.
- The request also sets the provider's maximum output high (at least 16,000 tokens, since thinking counts against it). There is no reply length cap in the app.
