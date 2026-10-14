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
Built when the conversation starts, stored with Part 1 in `conversation.prefix_json`, and reused byte for byte for every request in the conversation. Part 1 and Part 2 are rebuilt in these cases only:
- they switch the chat type in this conversation (the mode file and the card list change);
- the conversation resumes on a later day (`prefix_day` differs from today's day key), so a conversation reopened next week does not carry last week's notes;
- they change what the coach knows (delete, edit or confirm a note; change, confirm or delete a profile entry; change or delete a person; edit a focus summary) or forget a conversation: every open conversation builds its prefix again, so nothing they deleted or corrected is sent again in its old words (rule 5);
- a reflection of another conversation is applied: the other open conversations build their prefix again, so a conversation started before that summary existed (for example one started seconds after the last, while its summary was still being written) sees it with its next message. The reflected conversation keeps its prefix; its messages are in its own history;
- a prompt is edited or reset.

Each rebuild costs one request without the provider's cache. Changing a setting never changes an open conversation's prefix.

Each block starts with `## NAME` on its own line, in this fixed order. A block with nothing to say is omitted, never sent empty.

| Block | Content | Trim |
|---|---|---|
| FLAGS | `tough_love: gentle|balanced|firm`, `listen_only: true|false`, `chat_type`, `off_the_record` | never |
| PROFILE | up to 30 entries, one line each, with grade (confirmed/likely/guess) | lowest grade first |
| PEOPLE | name, relation, one line | least recently mentioned first |
| NOTES | active notes: id, kind, grade, times seen, first/last seen as a period, text, up to 2 evidence lines | guess before likely before confirmed; fewer times seen first |
| FOCUS | when the chat belongs to a focus: id, name, why, rolling summary, current step, last 3 step results | never |
| CHAPTERS | last 2 chapters | older first |
| RECENT_SESSIONS | the last 5 conversation summaries, newest by their last message, each with its day, when it started and when its last message was, and how long ago that day was, all written by code: `Wed 14 Oct, 9:05 am to 9:40 am (today) "Title": summary` | oldest first |
| IDEAS_RECENT | the last 20 idea tags used | oldest first |
| LETTER | only when opened with "Talk about this letter": the letter's title and body | never |
| REVIEW | only when opened with "Talk about it" from a review: that review's facts and note | never |

Budget for Part 2: 9,000 estimated tokens. Each item carries a drop rank computed by the app from the Trim column (0 = never drop); `trim_blocks` removes the highest rank first until the blocks fit. If the never-trim blocks alone exceed the budget, nothing is cut and a content-free `context_over_budget` error is logged.

## History window
Every stored message of this conversation is sent again in order: their messages as `user`, escaped (below), prefixed with the stamp the app wrote when they sent it and how long ago that was, counted in code from day keys (`[Fri 9 Oct, 21:40 (today)]`, `[Thu 8 Oct, 22:10 (yesterday)]`, `(3 days ago)`); RECENT_SESSIONS, note evidence and RELEVANT_PAST dates carry the same bracket, so the coach never compares a date with NOW itself; the coach's as `assistant`, exactly as shown, escaped (below), and then one line `[[suggest: {json}]]` for each card that reply offered, in order, written from the stored card JSON (already validated, so it holds no bracket pair; a stored card that does is left out). The cards go back in because a history of replies that point at a button with no card line teaches the model to stop sending card lines. The card's status is not in the history (it would change old messages and break the cache); CARD_RESULTS carries it. Off-the-record conversations work the same way while open; they are simply never stored.

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
| LAST_CHAT | only when another conversation's last message was less than 6 hours ago (never an off-the-record one): one code-written line naming it, then its last 8 messages, oldest first, each cut to 500 characters, theirs stamped with the time they wrote it. See below | oldest message first |
| RELEVANT_PAST | top matches (max 5) from keyword plus meaning search, each with its day, time, how long ago (counted in code from day keys; hours and minutes too within the last 24 hours) and conversation title; omitted when nothing is strong | lowest score first, before LAST_CHAT |

PLAN_STATE, OPEN_PROMISES and CARD_RESULTS are volatile on purpose: if they save a plan or a promise mid-conversation, the very next reply already sees it, so the coach never suggests a duplicate or talks about a plan that no longer exists. Budget for Part 3: 4,000 estimated tokens.

### LAST_CHAT
The words of the conversation they were just in, so a new conversation never starts blind. Typing on Talk home always starts a new conversation (spec section 2.1), and that happens a lot: Android may close the app while they sleep or nap. Summaries are written by a background job that takes a while, so without this block a conversation started right after another could miss it entirely. Example:
```
Your other conversation "Power cut, nap until 11", Wed 14 Oct, 9:02 am to 9:31 am (today); its last message was 1 hour 49 minutes ago. Its last messages:
[9:28 am] They: ok so I sleep now and wake up at 11, if the lights are on I do one lecture
Coach: Deal. Alarm for 11, and if the power is back, one lecture. If not, rest.
[9:31 am] They: ok good night
```
Shown with every request while that conversation's last message is less than 6 hours old. "Ago" is counted in code with `TimeFacts.spell`. The coach's lines are context, never evidence about them (rule 4).

### NOW
One sentence, written by code, that names the day the moment counts as (the day boundary: midnight by default, 1 to 6 am if they choose; the example uses 4 am):
- `It's Friday 9 October 2026, 9:40 pm (night).`
- `It's Sunday 4 October 2026, 12:40 am (late night; their day still counts as Saturday until 4 am).`
The word in brackets is the part of the day: late night from midnight until the day boundary or 4 am, whichever is later (so 12:40 am is late night with a midnight boundary too, never "morning"), then morning, afternoon (from noon), evening (from 5 pm) and night (from 9 pm). The "still counts as" note is added only before the day boundary. No greeting band is given; the coach decides.

### Resolving "when" in a promise card
The app resolves the card's `when` text in code (`PromiseTimes`), relative to the day key, and the card always shows the resolved weekday, date and time ("Sat 10 Oct, 17:00") before they tap, so they see exactly what will be saved. Late at night (from midnight until the day boundary or 4 am, whichever is later), "today", "tonight" and "tomorrow" are ambiguous: the card opens its time picker with the most likely reading preselected instead of saving silently. If a phrase cannot be resolved at all, the card shows the picker empty.

### OPEN_PROMISES
Written by code (`PromiseText`), never trimmed. First every open promise, soonest first, with its code-written due phrase; one that has passed says it has no answer yet (never "not done"). Then what they answered in the last 14 days (at most 8, newest first), so the coach can follow up without asking what happened:
```
P4 "Study 1 hour at 8am", due Monday 5 October, 8:00 am (in 1 day 7 hours).
P2 "Call Mom", was due Saturday 6:00 pm, 6 hours 40 minutes ago, no answer yet.
P3 "Study 20 minutes at 5pm", was due Friday 5:00 pm, 1 day 7 hours ago: not done, reason: tired.
```
Outcomes are `done`, `not done` (with `, reason: X` when they picked a chip), `moved to a new time` (the new time is its own open line) and `let go`. Older promises are in the search index with their outcome, so RELEVANT_PAST can bring one back.

## Card step (every reply's cards, in JSON mode)
The coach decides what to offer, in its words. Writing the card down does not depend on it remembering a card line: after every finished reply that has no card that passed the parser, the app sends one more request before the reply is stored. It carries the same system message and messages, then the reply's prose as `assistant`, then `prompts/card_followup.md` as `user`, with `{{REASON}}` filled with rule names only (no content). It asks for one JSON object, `{"cards": [...]}`, each item a card object as in the card rules; with the DeepSeek preset the request sets `response_format: json_object`, so the provider guarantees the shape. Thinking is off and the output is capped at 2,000 tokens. An empty list is the normal answer. Every card is read by the same parser with the same known ids and chat-type allowlist; words in the answer are never shown. The kept cards are added to the reply as card lines, so they are stored and shown exactly like cards the reply carried itself, and still save nothing until tapped. A reply that already carries a valid card, or was cut off by the length limit, skips the step. If the step fails or brings no valid card, the reply is stored as it was, and the quiet "no button" line shows when its words pointed at one. Logged as codes only (`card_followup_ok`, `card_followup_none`, `card_followup_failed`); its tokens count in `usage_month`.

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
  Plan steps for B1 "CAF lectures": 2 per day, daily, 14 Oct to 20 Oct; 3 per day, daily, 21 Oct to 27 Oct; 4 per day, daily, from 28 Oct. At these steps it finishes on 2 Jan (Saturday), 30 days before the deadline.   <- after a backlog's lines when its linked targets have start days
```
Display rounding never flatters the plan: required pace is rounded up, "reaches" is rounded down, and the Status line is decided from the same numbers that are shown. The coach copies ids, numbers, the status and review dates exactly. It never calculates. A plan card is only accepted when Review options were offered.

How the app assembles it (`core/PlanBlocks.kt`, tested against the sample above in `PlanBlocksTest`):
- Ids are the database ids: G2 is goal 2, T3 target 3, B1 backlog 1. Each goal is followed by its backlogs, then its targets; backlogs and targets without a goal come last. With nothing tracked yet the block says `No goals, targets or backlogs yet.` and still ends with the review options.
- A backlog's done = `start_done` + the amounts entered on its linked targets from the day the backlog was made (earlier work is already in `start_done`, so it is never counted twice). Its pace and days left use the first linked target's schedule and the whole history of its linked targets, added up per day.
- Pace windows start no earlier than the first start day of any target ever linked to the backlog (ended ramp steps included), so days before a plan existed are never unanswered days, and a plan that has not started has no answer rate. The pace reads the answers of every linked target, ended steps included.
- The plan steps line comes from `plan_finish` and `plan_line` in `tools/planner.py`: the backlog's live linked targets in start order, each adding its target on its scheduled days from its start to its end, counted from today (tomorrow when today is answered), rest days skipped. It says where the steps finish it and how many days before the deadline, or how many they reach by the deadline and how short that is. An Nx/week step cannot be dated, and the line says so.
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
Read in code by `PlanDates` (`tools/plan_dates.py`, `contracts/plan_date_vectors.json`): today, tomorrow, a weekday, `20 Nov`, `Nov 20`, `20 Nov 2026`, `2026-11-20`, counted from their day key. Anything else, a date in the past, an end before its start, or a review date that is not 1 to 28 days ahead, means the card saves nothing. The card shows every date it read before they tap. A ramp step (`weeks`: a whole number, 1 to 52, never with `end`) ends `7 × weeks − 1` days after its start, and the next target with no `start` begins the day after it; a target that follows no step starts today as before. On saving, the first step updates an existing target of that name and later steps are new targets linked to the same backlog, whose pace then reads every step's answers from the first step's start.

## Rules that keep this safe
- Anything originating from them or from the model (messages, notes, quotes, summaries, letters, pasted text) is escaped before it is sent: `[[` and `]]` become `[ [` and `] ]` (repeated until none remain), and a line starting with `## ` (after any spaces) is indented by two spaces. A note or a pasted email can never smuggle in a block header, a fake NOW, or a card.
- Block text is data, not instructions. The persona says so (Grounding).
- Token counts are estimated with `estimate_tokens` (3 characters per token), which is deliberately pessimistic, so a real tokenizer never finds the request over a limit.
- The request also sets the provider's maximum output high (at least 16,000 tokens, since thinking counts against it). There is no reply length cap in the app.
