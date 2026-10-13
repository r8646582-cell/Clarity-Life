---
description: Scan the whole app for every kind of bug, write a dated report, then ask which to fix
argument-hint: "[optional: an area to focus on, e.g. reminders, Talk home, backup]"
---

# Bug scan

Scan this repo for every kind of bug you can find, and write a report. **Do not change any code in this run** until I say which findings to fix.

Focus: $ARGUMENTS (if empty, scan everything).

I am a beginner. Explain each finding in plain language: what I would see on my phone, and why.

## 0. Read first

1. `CLAUDE.md`, then `docs/LIFE-SYSTEM.md` (the spec; it wins over CLAUDE.md), then `contracts/` and `tools/`.
2. The latest file in `docs/audits/` if there is one, so you do not report what was already fixed or already decided.
3. `git log --oneline -40`, so you know what changed recently. New code is where most bugs are.

## 1. Run the checks

- `bash tools/check_all.sh`, `bash tools/forbidden_patterns.sh`, `python3 tools/mutation_check.py`.
- `./gradlew lintDebug testDebugUnitTest` if the Android SDK is available. If it is not, say so plainly, and read the latest CI run's results instead if you can.
- Every failure or lint warning is a finding.

## 2. Look for every kind of bug

Go through each area below. Read the code, not just the names. For each screen, read the Compose code **and** the view model and repository behind it.

**Screens:** Talk home, a conversation, the conversations drawer, the chat-type switch, cards (promise, plan, target change, backlog, focus, note, mode), Plan (Today, Goals, Promises, Reviews, letters), What I know, Your story, a Focus, Settings (every group), Keep reminders reliable, the prompt editor, the test bench, Health, the guide, the lock screen.

### A. Wrong results (logic)
- Off-by-one errors in counts, ranges, loops, "N more", "Step 1 of 3".
- Wrong condition: `<` vs `<=`, `&&` vs `||`, a missing `else`, an `if` that can never be true.
- Rounding the flattering way (needed pace must round **up**, projections **down**).
- Integer division, division by zero, NaN or Infinity reaching the screen.
- A rule implemented differently in two places (two parsers, two copies of the same check).
- Kotlin and the Python reference in `tools/` disagreeing on any input the vectors do not cover. If you find one, propose a new vector.

### B. Dates and times
- Midnight, the day boundary setting (midnight by default, 1 to 6 am possible) and the hours just around it: what do "today", "tomorrow", "in 7 hours", "yesterday" and greetings say at 12:30 am?
- Time zone changes and travel, daylight saving jumps, month end, year end, 29 Feb, a 7-day week that crosses a month.
- Anything that uses the phone's clock where it should use the stored `day_key`, or the other way round.
- Labels that age ("in 2 hours") but are never recomputed.
- A date worked out by the AI instead of by code (forbidden, rule 2).

### C. What the screen shows (UI and wording)
- The same item shown twice on one screen, or two screens disagreeing about the same fact.
- Text built by code that reads badly: repeated words ("2 lectures · CAF lectures"), "1 days", "null", empty brackets, a stray "·", a sentence with no full stop.
- Empty states: what each screen shows with no data, no key, no goals, a first launch.
- Very long text: a long name, goal, promise or quote. Does it cut off, overlap or push buttons off screen?
- Big font size (Android's font scale at 1.3 to 2.0), small phones, landscape, the keyboard covering the input or a button.
- Something that looks tappable but is not, or a button that does nothing.
- Colours, fonts and spacing that differ from `docs/mockups/redesign/`.

### D. State and the app's lifecycle
- What is lost on screen rotation, dark/light change, leaving the app, the app lock, or Android killing the app in the background (half-typed message, open dialog, scroll position, selected tab).
- Settings that only take effect after a restart, or values read once and kept when they can change.
- A spinner that can stay forever. Every request must end complete, interrupted or failed.
- A reply that is streaming when the phone sleeps, loses network, or the app is swiped away (`ReplyService`, `onTimeout`, the startup repair sweep).

### E. Doing things twice, or at the same time (concurrency)
- A double tap that saves twice, or two buttons tapped quickly.
- Two coroutines writing the same row, a missing database transaction, a read-then-write that can race.
- Work done on the main thread (database, file or network on the UI thread: a freeze or crash).
- A background job and the screen changing the same data at the same moment.
- Re-running a job that is not a no-op (the `job` table keys must make it one).

### F. The database
- Room entities that differ from `db/schema.sql`, a missing index, a missing foreign key or cascade.
- A migration that could lose data, or a version without a `MigrationTest` (rule 8; never destructive).
- Deletes: forgetting a conversation, deleting a note (never recreated, rule 5), erasing everything. Anything left behind, or deleted too much.
- Derived data (search index, embeddings, review facts) that cannot be rebuilt from raw rows, or goes stale.
- Backup and restore: does every table and column go in and come back, including the newest ones?

### G. Background jobs, reminders and notifications
- WorkManager jobs: constraints, retries, what happens when one fails, the catch-up on launch.
- Reminders: rescheduled after a reboot, a time change and an app update; exact alarm permission missing; Android 13+ notification permission; Doze and Xiaomi/HyperOS battery rules.
- A notification that is not one I set by tapping a card or the Sunday letter alert (rule 7).

### H. The AI boundary
- Streaming: chunks split in the middle of a character, a stalled stream, `finish_reason: length`, error objects, timeouts (connect 30 s, gap 120 s, background 300 s).
- Each provider's own path (DeepSeek and Anthropic): settings that one provider rejects or ignores, thinking eating a small output limit, token usage and cached tokens recorded wrongly.
- Anything the AI wrote that reaches the database without my tap (rule 1), a bad card or JSON that is not dropped (rule 3), a quote or number that is not checked.
- The context packet: anything that changes the cached system message mid-conversation (it breaks the cache), user text that is not escaped, a block over its budget.
- Prompts: a placeholder that is never filled, a `{{...}}` left in the text the model sees.

### I. Security and privacy
- Anything that leaves the phone except calls to the chosen AI provider (rule 6).
- Message content, keys or personal data in logs, crash reports or error rows.
- The API key stored anywhere except the encrypted store; the database not encrypted.
- `android:allowBackup`, exported activities, services or receivers, intents another app could send, links opened without checking.
- The app lock and hiding content in the recent-apps screen: any way to see content without unlocking.
- The release build: R8/minify removing classes that serialization or Room need.

### J. Speed and memory
- Slow work on every recomposition, lists without keys, loading everything when only a page is shown.
- Leaks: a context or view kept in a long-lived object, a flow collected forever.
- Startup work that blocks the first screen.

### K. Accessibility
- Icon buttons without a content description, touch targets under 44 dp, text contrast under 4.5:1, information shown by colour alone, controls TalkBack cannot reach.

### L. The code base and its documents
- `CLAUDE.md`, the spec, code comments, `tools/*.py` docstrings, release notes and the guide screen disagreeing with what the code now does.
- Dead code from removed features, unused settings, TODOs, commented-out code.
- Tests that cannot fail, tests that test nothing, important paths with no test.
- Hard-coded model names, URLs or prompt text outside `DefaultSettings.kt` and `prompts/` (rule 9).
- Dependencies that are not pinned, or added without being in the CLAUDE.md stack.
- Build and release: `versionCode`/`versionName` bumped, the CI workflow, signing secrets never in the repo.

## 3. Prove each finding

Report a bug only if you can name the exact situation that triggers it (the input, the time, the tap) and trace it through the code to the wrong result. If you cannot, leave it out or put it under "Unsure" with what would confirm it. Do not invent problems or pad the list.

## 4. Write the report

Write it to `docs/audits/bug-scan-YYYY-MM-DD.md` (today's date) and show it to me.

1. **Checks run:** what passed, what failed, what could not run and why.
2. **Findings**, most serious first, as a table:

   | # | Type | What I would see | When it happens | Where (file:line) | Why | Smallest fix | Severity |

   Type is one of: bug, crash, data loss, privacy, wording, layout, accessibility, slow, docs out of date, dead code, missing test. Severity is high (data loss, a crash, privacy, or a broken rule from CLAUDE.md), medium (wrong on screen, works badly), or low (wording, tidying).
3. **Unsure:** suspicions you could not prove, and how to check them on the phone.
4. **Needs my phone:** what only a real device can show (reminders firing, HyperOS battery rules, the lock screen), as numbered steps I can follow.
5. **Not bugs but ideas:** a short list kept separate, because the spec's scope is locked.

## 5. Stop and ask

Ask me which numbers to fix. Then, for each one I choose:
- one small commit per fix, each with a test (and a contract vector if it touches a contract);
- run every check in CLAUDE.md's "Before every commit" before each commit;
- push to a new branch, never straight to `main`;
- never weaken a check or a test, and never edit a vector just to make it pass;
- update the report's table with what was fixed.
