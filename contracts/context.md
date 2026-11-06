# Context packet contract (v2)

What the app sends with every chat request. The reference implementation is `tools/context.py` and its behaviour is pinned by `contracts/context_vectors.json`; `ContextBuilder.kt` must pass the same vectors. v2 follows `docs/CLARITY-V2-BUILD-SPEC.md` section 4: one plain chat, no cards, no chat types.

The layout puts the longest stable text first, so the provider's prefix cache (DeepSeek and Anthropic both cache by exact prefix) is hit on almost every request. This is also the main way the app keeps token use low:

```
system message   = Part 1 (persona, examples)  +  Part 2 (frozen for this conversation)
messages         = the history window             (grows; moves rarely, in jumps)
final user turn  = Part 3 (volatile blocks)  +  "## MESSAGE"  +  their new text
```

## Part 1: the fixed prompt
`persona.md` then `examples.md`, joined with a blank line, `{{USER_NAME}}` filled. Identical for every conversation, so that part of the prefix is shared across all chats. `tools/check_prompts.py` fails if it grows past the budget in `contracts/prompts.json`.

## Part 2: frozen context blocks
Built when the conversation starts (the first message of a new day starts a new conversation), stored with Part 1 in `conversation.prefix_json`, and reused byte for byte for every request in the conversation. Rebuilt only when:
- the conversation resumes on a later day (`prefix_day` differs from today's day key);
- they change what the coach knows (delete, edit or confirm a note; change a profile entry or a person) or forget a conversation: every open conversation builds its prefix again, so nothing they deleted or corrected is sent again in its old words (rule 5);
- a prompt is edited or reset.

Each block starts with `## NAME` on its own line, in this fixed order. A block with nothing to say is omitted, never sent empty.

| Block | Content | Trim |
|---|---|---|
| PROFILE | up to 30 entries, one line each, with grade (confirmed/likely/guess) and the day the value was first said and the newest value it replaced, both written by code | lowest grade first |
| PEOPLE | name, relation, one line | least recently mentioned first |
| NOTES | active notes: id, kind, grade, times seen, first/last seen as a period, text, up to 2 evidence lines | guess before likely before confirmed; within a grade, notes sharing no word with the message the prefix was built for go first, then fewer times seen first. A note left out can still come in RELEVANT_PAST when a message matches it |
| RECENT_SESSIONS | the last 5 conversation summaries other than this one, newest by their last message, each with its day, when it started and ended, and how long ago that day was, all written by code: `Fri 6 Nov, 9:05 pm to 9:40 pm (yesterday) "Title": summary`. Restored in 1.0.3: with one chat a day, this is how yesterday's plan reaches today's first reply | oldest first, before every note |

Budget for Part 2: 9,000 estimated tokens. Each item carries a drop rank computed by the app from the Trim column (0 = never drop); `trim_blocks` removes the highest rank first until the blocks fit. If the never-trim blocks alone exceed the budget, nothing is cut and a content-free `context_over_budget` error is logged.

## History window
Every stored message of this conversation is sent again in order: their messages as `user`, escaped, prefixed with the stamp the app wrote when they sent it and how long ago that was, counted in code from day keys (`[Fri 9 Oct, 21:40 (today)]`, `(yesterday)`, `(3 days ago)`); the coach's as `assistant`, exactly as shown, escaped. There are no card lines in v2. Messages still `pending` (waiting to send) are not part of the history until they are sent.

The window has its own budget of 12,000 estimated tokens (half of v1: with one chat a day, older detail is carried by EARLIER and RELEVANT_PAST instead of being paid for on every message). The index of the first message sent is stored with the conversation and only moves forward. When the window is over budget it jumps forward until the history is at most 75% of the budget (never past the newest 6 messages), so it moves rarely and the cached prefix survives many turns. When messages have left the window, the conversation's running summary is sent in the EARLIER block.

## Part 3: volatile blocks (the final user turn)
Rebuilt for every request and placed in the final user message, before `## MESSAGE` and their text. Fixed order:

| Block | Content | Trim |
|---|---|---|
| NOW | one code-written sentence, see below; then, for up to 4 dates they mentioned in their recent messages, one line each with the weekday and the number of days after today, counted in code | never |
| WORKING_ON | one line per working-on item (spec section 4): name, status (`active`, `paused`, `done`), `confirmed` or `unconfirmed`, the deadline as a date plus "N days left" only if the deadline is confirmed (otherwise `deadline: unknown`), the owner's quote that created it with its date, and the day it was last mentioned. Written by code (`WorkingOn`). Omitted when there are no items | never |
| LAST_CHAT | only when the previous conversation's last message was less than 36 hours ago (`LastChat.DAY_CHAT_HOURS`; one chat a day means the one before is usually yesterday's): one code-written line naming it, then its last 8 messages, oldest first, each cut to 500 characters, theirs stamped with the time they wrote it. It covers the gap before that conversation's summary is written | oldest message first, after every RELEVANT_PAST match |
| RELEVANT_PAST | up to 12 matches from their conversations and the records they keep (see below), each with its day, time, how long ago (counted in code from day keys) and conversation title; omitted when nothing is strong | lowest score first |
| NEW_NOTES | active notes added since this conversation's prefix was frozen (by a reflection); omitted when empty | oldest first |
| EARLIER | the running summary of the part of this conversation that is no longer in the history window | never |
| LETTER | only when the conversation was opened with "Talk about this letter": the letter's title and body | never |

WORKING_ON and NEW_NOTES are volatile on purpose: if a working-on item is created or confirmed, or another reflection adds notes, the very next reply already sees it, without a full prefix rebuild. Budget for Part 3: 4,000 estimated tokens.

### RELEVANT_PAST (Max plan Phase 2)
What is searched (`SearchIndex`, kept in step with the raw rows and rebuildable): his messages, conversation summaries, his verified quotes, events, retired notes (labelled with the days they were first and last seen), every promise with its outcome ("Promise: Call Mom (done)"), and every other record he keeps: weekly letters, week and month reviews, chapters, goals (finished and set aside too), goal changes with their reason, targets (ended too) and the check-ins that carry a reason or a note. Each record reads as written by `SearchText` (`tools/retrieval.py`), starting with what it is ("Letter", "Goal", "Check-in") and with dates written out. Never: the coach's replies, an off-the-record conversation, a note he deleted, a deleted letter.

How one request searches (the pure part is `tools/retrieval.py` and `tools/recall_time.py`, pinned by `contracts/retrieval_vectors.json` and `contracts/recall_time_vectors.json`; `Retrieval.kt` and `RecallTime.kt` pass the same vectors):
1. **The period he names**, read in code (`RecallTime`): "in August", "last week", "yesterday", "on 12 October", "late September", "since March" become exact days. What falls in it is a list of its own: promises due in it, conversations held in it, letters, reviews and chapters about it, goal changes made in it, and every check-in of a period of at most a week. With the words promise, promised or committed, only promises come (unless none falls in the period), and the outcome he names (break, kept, moved, let go) comes first; with no period, every promise is the pool. The coach never works out a date: this only chooses what is looked up.
2. **The new message**, by keyword (at least two shared words, or one rare word: found in at most 5% of the index) and by meaning (the on-phone model, gte-base-en-v1.5 since Max plan Phase 4: cosine at least 0.42, the line bge-small had at 0.62; each text once).
3. **The conversation**: when this conversation has earlier messages (or a title), the same two searches again for the title, his last 3 messages and the new one, so "yeah, same again" finds what it points at.
4. **Notes left out**: active notes the frozen NOTES block had no room for, by keyword against the new message (weight 1), labelled as not in the list above. Never a retired or deleted note this way (retired notes are in the index; deleted ones are nowhere).
The lists are merged by weighted reciprocal rank fusion (period 2, new message 1, conversation 0.5); a near-duplicate of a chosen result is left out, at most one result comes from each conversation, and at most 12 are sent. The 4,000-token budget drops the weakest first. Measured with `tools/eval/memory_exam2.py`.

### NOW
One sentence, written by code, that names the day the moment counts as (the day boundary: midnight by default, 1 to 6 am if they choose; the example uses 4 am):
- `It's Friday 9 October 2026, 9:40 pm (night).`
- `It's Sunday 4 October 2026, 12:40 am (late night; their day still counts as Saturday until 4 am).`
The word in brackets is the part of the day: late night from midnight until the day boundary or 4 am, whichever is later (so 12:40 am is late night with a midnight boundary too, never "morning"), then morning, afternoon (from noon), evening (from 5 pm) and night (from 9 pm). The "still counts as" note is added only before the day boundary. No greeting band is given; the coach decides.
