# Clarity v2: build spec for Claude Code

Owner: Umair (beginner, one user). Read `CLAUDE.md` first, then this file. **Where this file and `CLAUDE.md` or `docs/LIFE-SYSTEM.md` disagree, this file wins, but only for the items listed in section 2.** Everything else in `CLAUDE.md` still holds. Screens to copy are in `docs/mockups-v2/`. The coach's character is `persona-v2.md`. The human-readable blueprint is the Claude Doc "Clarity blueprint" (v2).

How to work: explain every change in plain words, work one stage at a time (section 12), stop at the end of each stage with a "what to check by hand" list, ask before adding a dependency, never claim something works that you could not run. If something here is unclear or conflicts with a contract vector, stop and ask the owner. Do not "improve" the design.

## 1. What we are building
A private Android app that is one honest friend you talk to every day.
- One plain chat. No cards, no buttons in the chat, no chat types, no forms.
- The app quietly remembers (facts, behaviour patterns, what the owner is working on) and the owner can check it with one tap.
- The app writes mirror-style letters (weekly, monthly, yearly, milestone).
- It is fully local and encrypted. The only network call is to the AI provider the owner chose, with their own key.
Non-goals: no Plans tab, no plan notes page, no goals or targets screens, no check-in form, no streaks, no accounts, no analytics, no crash SDKs.

## 2. What changes in the existing rules
Keep all of `CLAUDE.md` rules 1 to 10 and the "AI client rules", "Data and time", "Phone reliability" sections, except:
1. **Rule 1 (no hidden actions) changes.** Cards are gone. The AI still never writes to the database in chat. Two things may change state:
   a. A tap by the owner (That's right, Not quite, Delete, Forget, settings).
   b. The **memory keeper**, a validated background applier, which may create or update notes, patterns and **working-on items**, always marked as the keeper's proposal (strength `guess` or item `unconfirmed`) until the owner confirms them. The keeper may never delete anything, never mark an item done or dropped from one angry or tired message (section 7), and never set a deadline the code could not confirm (section 6).
2. **Rule 7 (notifications):** the only notifications are the optional daily nudge (off by default) and the optional silent Sunday letter alert. No reminders created from cards.
3. **Tabs:** Chat, Know, Letters, plus Settings. No Plans tab.
4. **Per-job models were "do not copy".** Exception: add ONE optional setting `background_model` (default: same as the chat model) used for memory extraction and letters so the owner can pick a cheaper one. No other per-job models.
5. **Prompts:** `persona-v2.md` replaces `prompts/persona.md`; cards, chat-type and mode prompts are removed (section 9). The owner has asked for this change by approving this spec.
6. **Chat commands** (section 8) are detected by code, not by the AI.
Everything else stays: the AI only talks, code does all dates and numbers, only the owner's words and actions are evidence, fail closed, deletions are final, never destructive migrations, English only, no crisis screens.

## 3. Screens
Copy each `docs/mockups-v2/*.dc.html` in Compose. Bottom bar: Talk, Know, Letters. Settings opens from the icon in the chat header.

| Screen | File | Notes |
|---|---|---|
| Chat | `Main.dc.html` | **No greeting, no suggestion buttons.** One quiet top line built by code: `<weekday> <day> <month> · Working on N things` (N = active confirmed or unconfirmed items; hide "Working on" if 0). Tapping it opens Know. A new unread letter appears as a normal coach message with a plain "Read it" link. |
| Chat conversation | `TalkPlan`, `TalkTriage`, `TalkLimit` | Plain bubbles only. Plans, triage and the fourth-item question are ordinary text, produced by the persona. |
| No signal | `Offline.dc.html` | See section 5. |
| Conversations | `Drawer.dc.html` | List and search of past chats. Long press: rename or forget (existing ForgetConversation). |
| What I know | `Know.dc.html` | Top: "Is this right?" card. Then WORKING ON list. Then collapsed groups: Patterns, About you, People. |
| Note and proof | `Evidence.dc.html` | Existing proof sheet. Proof lines are quotes only (no check-ins now). That's right / Not quite / Delete for good. |
| Letters | `Letters.dc.html` | One list, newest first, kind label (WEEKLY, MONTHLY, YEARLY, MILESTONE). |
| Letter | `Letter.dc.html` | Text plus "Talk about this letter" (opens a chat with the letter loaded, existing `opened_from`). |
| Settings | `Settings.dc.html` | Coach (provider and model, key, test), Limits (daily chat limit), Reminders (daily nudge OFF by default, weekly letter, day ends at), Your data (app lock, back up and export, erase). Keep the existing Xiaomi reminder help under the nudge toggle. |

"What I know" mapping from existing note kinds: `pattern`, `what_helps`, `what_doesnt` all show under **Patterns**; `strength`, `value` and profile entries under **About you**; `person` under **People**; `thread` is hidden from the UI but still given to the coach. The "Is this right?" card is the existing `KnowScreen` card (first `canConfirm` item); extend it to show working-on items that are `unconfirmed` first.

## 4. The chat engine
- **One chat per day.** When the first message of a new `day_key` is sent (day starts 04:00, existing setting), start a new `conversation`. Memory is shared. No chat types: always `chat_type = 'free'` (keep the column; stop writing others).
- **Context packet** (follow `contracts/context.md` ordering and caching rules; update the contract and vectors together). Frozen prefix (built at conversation start): persona-v2, then PROFILE, PEOPLE, NOTES. Volatile, in the final user turn: NOW, WORKING_ON, RELEVANT_PAST, EARLIER, LETTER (when opened from a letter), MESSAGE. Remove FOCUS, CHAPTERS, PLAN_STATE, OPEN_PROMISES, CHECKIN_FACTS, CARD_RESULTS, IDEAS_RECENT, REVIEW, FLAGS blocks from the contract once their code is removed.
- **WORKING_ON block** (built by code, `ContextBuilder`): one line per item: name, status (`active`, `paused`, `done`), `confirmed` or `unconfirmed`, deadline as a date plus "N days left" **only if the deadline is confirmed**, the owner's quote that created it with its date, and last-mentioned date. Items with unclear deadlines say `deadline: unknown`.
- Replies stream as before (`ReplyService`). A reply is never saved with invented text.
- Quiet reply-checker: **not in stage 1.** Add only if the 14-day test shows false "you told me X" claims (stage 5).

## 5. Offline and limits
**Offline queue.** Add message status `pending` (migration: `message.status` CHECK needs it; use a safe table copy and a MigrationTest).
- Sending with no connection (or a failed connect) stores the user message as `pending`, shows "Waiting to send" under it, and never shows a red error banner.
- A `WorkManager` job (network-connected constraint, unique work name per conversation, backoff, max 2 retries per attempt cycle) sends pending messages in order and then asks for the reply once. If the owner is in the app, a normal streaming reply appears.
- Reading old chats, Know and Letters never needs the network.
- The draft is never wiped by a failure. Interrupted or failed replies keep the existing Try again behaviour.

**Limits (wallet safety).** Reuse `usage_month`; add a per-day counter table or key (UTC day by `day_key`).
- **Chat ceiling:** setting `daily_chat_limit` (default 60 user messages per day). At the limit the chat shows one plain line: "Daily limit reached. It resets at 04:00." The owner can raise it in Settings. Never silently drop a message: it stays as the draft.
- **Background cap:** a hard, non-configurable cap of 12 background AI calls per day (extraction, letters, gardening, retries all count). When reached, background work stops until the next day, and Settings shows a quiet "Background work paused until tomorrow".
- Retries: at most 2 per request, with backoff. No loops.
- The nightly job (reflection catch-up, gardening, summaries roll-up) does not run on a day with no chat messages.
- Memory extraction after a chat runs once per conversation per idle period (no extraction on every message), on `background_model`.
- Tests: counter rollover at 04:00, cap behaviour, nightly skipped on empty day, retry cap.

## 6. What you are working on
Store: **prefer reusing `goal` plus `goal_revision`** (read `db/schema.sql` lines for both first). If they cannot hold the fields below, add `working_item` and `working_item_event` with a migration. **Tell the owner which you chose before building.** Required fields per item: id, name (the owner's words), status (`active`, `paused`, `done`, `dropped`), `confirmed` (0/1, set by the owner's tap or by their own clear statement as below), deadline_date nullable, deadline_state (`none`, `unclear`, `confirmed`), size_text nullable (rough, e.g. "30 lectures"), origin_quote and day, last_mentioned_day, created_at, plus an event history (every change, with the owner's quote, so the old version is kept).
Rules (all enforced in code, covered by vectors):
1. **Macro only.** Items are big (an exam, a project, a habit). The keeper must not create items for single tasks or per-day work. No sub-tasks, no burn-down.
2. **Cap: 3 active.** The keeper may never make a 4th item active. It stores the proposal as `unconfirmed` and `paused`, and the persona asks the trade-off question (which of the three pauses). Only the owner's chat answer or a tap changes which are active. Paused items do not count toward the cap, and they appear under WORKING ON with "paused".
3. **Dates: ask, never guess.** The deadline becomes `confirmed` only if (a) the owner's own message contains a date that code parses unambiguously with the existing date logic (`PromiseTimes` / `DayKey`, port from `tools/promise_times.py`), or (b) the owner taps That's right on it. Otherwise `unclear`, no day count is shown or given to the AI, and the persona asks one plain question. The AI never supplies a date: the keeper returns the owner's quote, and code parses it.
4. **Size and progress** are optional and rough. Progress is recorded only when the owner volunteers it, as a dated event. The persona never asks for counts. Planner math (existing `Planner.kt`) is used only for items that have both a numeric size and a confirmed deadline, to give the coach finished pace facts.
5. **Stale item:** no mention for 21 days (setting constant) makes it eligible for one "still on this?" question from the persona, once (record that it was asked). No further nagging.
6. **Done or dropped:** requires the owner's own clear statement (and for dropped or paused-from-frustration, section 7). Done queues a milestone letter (stage 4).
7. **Visibility:** the WORKING ON list is on Know. Any item with `confirmed = 0` also appears first in "Is this right?" until the owner taps. That's right confirms; Not quite opens a plain editor (name, deadline, status); Delete is final (deletion rules unchanged).

## 7. Memory keeper
Extend the existing reflection job (do not rewrite what works; keep ReflectionApplier validation and the quote-verification check). The keeper reads the new chat and proposes: facts, behaviour patterns, people, working-on updates. New rules, each with applier vectors:
1. **Behaviour patterns** are notes with `kind = 'pattern'` written as what the owner tends to do and what helped ("Stalls on a hard topic after poor sleep and picks up the phone", "Recovered with two worked examples instead of reading theory"). Start at `guess`. Strength only rises when a **second, separate day** has the owner's own words or actions supporting it. The coach's wording never raises strength.
2. **Venting is mood, not decision.** Text said in frustration or exhaustion ("I hate this subject, never again", "I'll drop it and get a job") may be saved as a dated `event` of kind mood at most. It must never create or change a fact, working-on status, deadline, or anything `confirmed`. A pause or drop from the owner counts only if they state it calmly in a later message, or repeat it on a different day, or tap it. Implement this as an applier check: a status change needs `evidence_days >= 2` or an explicit confirm tap, otherwise reject it and log without content.
3. The keeper never deletes. Only the owner's tap (or a confirmed chat command, section 8) deletes. Deleted notes are never recreated (existing guard).
4. Everything has proof (owner quote plus day). Quotes pass `QuoteCheck`; numbers pass `NumberCheck`.
5. Skip a note when unsure. A guess is allowed, a fact is not.

## 8. Chat commands (code-detected)
No AI tool calls. A small fixed set of phrases (lowercased, trimmed) is matched by code before sending: "what do you remember about X" is NOT a command (the persona answers from NOTES; make sure retrieval runs on X). Only deletion needs code: if the message matches "forget that", "forget this", or "forget that note", code shows a plain confirm sheet naming the exact note (the one the coach just referred to; if unclear, the last "Is this right?" item) with Delete and Cancel. Nothing is deleted without the tap. "That is wrong, it is X" is ordinary chat; the keeper proposes the correction as a new guess and the old note is retired by the owner's confirm on Know. Add vectors for phrase matching.

## 9. Prompts and contracts
- Replace `prompts/persona.md` with `persona-v2.md` (keep the filename `persona.md`; keep `{{USER_NAME}}`). Update `contracts/prompts.json` `must_contain` for persona.md to phrases that exist in the new file (for example "think privately", "When a day falls apart", "What they are working on", "Same facts, same assessment", "never say or imply that you did", "NOW is the only truth"). The forbidden list (em dash and en dash) still applies; persona-v2 has none.
- Delete prompts: `cards.md`, `card_followup.md`, `mode_*.md`, `brief_daily.md`, `story.md`, `chapter.md`, `review_*.md` once their code is removed. Keep `reflection.md` (update for patterns and working-on rules in section 7), `gardening.md`, `letter_*.md`.
- Letters: update `letter_weekly.md`, `letter_monthly.md`, `letter_yearly.md` and add `letter_milestone.md` to the **mirror style**: what an honest friend would say after watching the period. Not a scorecard. Include where the period went well, where it fell apart and whether a known pattern repeated, the real pace if there is code-computed pace, and one thing to try. Every quote verified, every number from code, praise only if earned, short or none if little happened.
- **Letter facts from code** (no check-ins exist): days with a chat, number of chats, working-on events in the period, owner-volunteered progress events, notes first seen or strengthened, quotes retrieved by search for the period. Never invent a count.
- **The finished prompt drafts are in `docs/prompts-v2/`** (persona, examples, reflection, gardening, summary roll-up, four letters, optional reply-check) with `README.md` (install steps, placeholders, output changes) and `contract-entries.json` (ready to merge into `contracts/prompts.json`). Use them as written. Do not rewrite them. Run `python3 tools/check_prompts.py`, then show the owner the diff of every prompt file for approval before it ships. The reflection applier and letter fact packets must supply exactly the placeholders listed in the README. Ignore the older 'draft new prompt text yourself' wording elsewhere in this spec. The test bench (`prompts/testbench.md`, `tools/bench.py`) gets the cases in section 11.

## 10. Data and migrations
- Every schema change is a migration with a `MigrationTest`. Never destructive. Old tables (`target`, `backlog`, `checkin`, `promise`, `card`, `plan`, `focus`, `chapter`, `review`, `story`, `brief`) are **left in place and unused** in the first migrations. Dropping them is a separate, later migration that the owner approves, after a verified backup restore.
- New or changed: `message.status` gets `pending`; daily usage counters; working-on store (section 6); `note` unchanged (kinds already cover patterns); `letter` gets `kind` values `milestone` if missing (check its CHECK); settings gain `daily_chat_limit`, `background_model`, `daily_nudge` (default off).
- Derived data stays rebuildable (search index, embeddings). Keep the Rebuild buttons under Settings > Advanced.
- Backup/export: keep `backup/` and `docs/BACKUP-FORMAT.md`; add the new tables to the format and bump its version; restore must work from older backups.

## 11. Tests and test bench
Unit tests (pure, vector-driven where the repo already uses vectors): date confirm vs unclear, 3-active cap, venting rejection, pattern strength after one vs two days, working-on context lines, usage limits and rollover, nightly skip, offline queue state machine, chat command matching, migration tests for every schema change, letter fact packets.
Test bench (run on the real model, outside the app, nothing ships if one fails):
1. Pace: "exam 12 Feb, 30 lectures, 2 a day" gives a plain, correct statement using app-supplied day counts and ends with a real plan; says nothing is saved.
2. Derailment: "scrolled for 3 hours, will make it up tomorrow" runs the three steps and states the catch-up need before agreeing.
3. Rest after overwork is accepted without a lecture; the double-up is checked.
4. Venting: "I hate this subject, I'm done" gives company and does not change any item; a later calm "I'm pausing it" is asked about or confirmed, not assumed.
5. Fourth item: one trade-off question; no fifth question; a passing mention gets none.
6. Unclear date ("next Friday") gets one plain question; no day count.
7. Memory: a fact from chat 1 is used correctly in chat 2 days later; a wrong note is corrected through chat plus a tap.
8. Letter: every quote and number verifies; a quiet week gets a short or no letter.
9. No sycophancy: a flawed plan is not blessed.
Add checks to `tools/bench.py` and `contracts/bench_vectors.json` together.

## 12. Build stages (finish and verify each before the next; the owner uses each for 1 to 2 weeks)
- **Stage 0 (no code):** the owner runs the 14-day test with `persona-v2.md` in a plain chat. You do nothing here except help if asked.
- **Stage 1: a chat that remembers.** Hide Plans, Today, Reviews, Story and the card UI (do not delete yet). New nav (Talk, Know, Letters). One chat per day, top line (without the working-on part), persona-v2 and the new context packet, offline queue, limits, memory keeper with patterns and the venting rule, Know with "Is this right?", chat command for forget. *Done when:* a fact from today is used correctly days later, the owner can fix a wrong note with one tap, and a message sent with the network off arrives when it returns. CI green, all vectors pass.
- **Stage 2: what you are working on.** Section 6 in full. *Done when:* a deadline mentioned in passing shows correctly (or is asked about), a fourth item gets the trade-off question, venting changes nothing, and the top line shows the count.
- **Stage 3: weekly letter** (mirror style, Sunday alert). *Done when:* a letter arrives and every quote and number verifies.
- **Stage 4: monthly, yearly (31 December), milestone letters.** *Done when:* finishing an item produces a milestone letter the owner would keep.
- **Stage 5: safety and polish.** Encrypted backup restore on a second device or emulator, export, app lock, daily nudge (off by default), quiet reply-checker if needed, then, with the owner's approval, the cleanup migration and deletion of dead code (cards, parser, planner screens, old prompts, old contracts and vectors, with `tools/check_all.sh` updated). *Done when:* backup restores on a second phone.
Before every commit: `bash tools/check_all.sh`, `bash tools/forbidden_patterns.sh`, `./gradlew lintDebug testDebugUnitTest`; small commits with a plain summary. Update `CLAUDE.md` (tabs, rule 1, rule 7, the prompt list and build order) and `docs/BUILD-STATUS.md` when stage 1 starts, and say what you changed.

## 13. What to ask the owner (do not guess)
- Which store for working-on items (reuse `goal` or new table), before stage 2.
- Approval of each rewritten prompt file before it ships.
- Approval before the cleanup migration and any deletion of old tables or code.
- The yearly letter date is 31 December unless told otherwise.
