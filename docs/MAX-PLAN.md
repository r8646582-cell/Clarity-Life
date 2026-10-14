# Max plan: making the whole app (everything except the AI model) as good as it can be

Written 14 Oct 2026 at the owner's request. The owner unlocks the spec's scope for this upgrade: the work below is approved in direction, and each phase still ends with the owner's OK before the next. The AI model (DeepSeek) is upgraded later and is not part of this plan.

The goal in the owner's words: the app on the phone should be "maxed out, dynamic, intelligent, autonomous, 100 percent reliable and lifetime", so that everything the AI receives is already correct, complete and relevant, and the AI never sounds dumb because it was missing something.

## What "max" means here (so it can be measured, not just claimed)

| Area | Measure | Today | Target |
|---|---|---|---|
| What the AI sees | Memory exam: share of needed evidence in the request (`tools/eval/memory_exam.py`) | 80.6% on 43 questions | 95%+ on a harder exam of 120+ questions |
| Reliability | Every request ends complete, interrupted or failed; no data loss in any test | rules exist, partly tested | proven by fault-injection tests (kill mid-reply, full disk, failed migration, bad backup) |
| Lifetime | App stays fast with 10 years of data | never measured | synthetic 10-year database: search < 150 ms, packet build < 300 ms, startup < 1.5 s on a mid phone |
| Data safety | Restore works from any old backup version | manual test planned | automatic backups + a restore test in CI for every backup format version |
| Code quality | Every contract has vectors, a mutation check and a Kotlin test | yes for most | yes for every new piece, plus UI and emulator tests in CI |

"100 percent reliable" is not something any software can promise; the honest version is: every failure we can think of has a test, fails safely, never loses data, and tells the owner in a quiet line.

## Rules that do not change
- **The AI only talks; nothing saves without a tap.** "Autonomous" in this plan means the app's own code looks after itself (maintenance, repair, backups, indexing, counting) without being asked. It never means the AI changing goals, plans or promises by itself. Changing that rule would be a separate, explicit owner decision; the recommendation is to keep it.
- Code does all dates and numbers. Fail closed. Only their words are evidence. Deletions are final. Nothing leaves the phone except calls to the chosen AI provider. No streaks, badges or nagging. No destructive migrations.
- Every change: spec + Python reference + vectors + mutation + Kotlin + test, together. Small commits with plain-language summaries. Ask before any new dependency.

## Phase 0: Tools and a baseline (do first)
1. Set up the Android build in the cloud session (SDK via a SessionStart hook) so `./gradlew lintDebug testDebugUnitTest` runs here, not only in GitHub Actions. If not possible, say so and rely on CI.
2. Run the full bug scan (`bug-scan` skill) and fix what it finds.
3. Make the memory exam harder: vague follow-ups ("same thing again"), time questions ("which promises did I break in August?"), patterns months apart, reworded copies of deleted notes, questions about plans, check-ins, people and letters. Target 120+ questions.
4. Build a synthetic 10-year database generator and a timing harness.
5. Record the baseline numbers in this file.

## Phase 1: Lifetime reliability and data safety
1. **Automatic encrypted backups**: on a schedule the owner picks, to a folder they pick (on-device or their own storage), keeping the last N; each backup verified right after writing (decrypt + integrity check).
2. **Key loss protection**: the database key lives in the Android Keystore, which a phone reset destroys. Backups are encrypted with the owner's own passphrase so they restore on any phone. Clear warning and a "test your passphrase" button.
3. **Restore tests for every backup format version** in CI, including restoring a backup made by an older app version.
4. **Database health**: integrity check on a nightly maintenance job, startup repair sweep extended (stuck jobs, half-written rows, orphaned evidence, missing index or embedding rows).
5. **Migration tests from every past schema version** to the newest, with real-looking data.
6. **Fault injection tests**: process killed mid-reply, mid-reflection, mid-backup; disk full; network drop; provider error objects; clock and time-zone changes; day-boundary changes.
7. **Readable forever**: export everything to plain JSON and Markdown in a documented format, so their life record outlives the app.
8. **Reminders that never silently fail**: reschedule after reboot, app update, time change and time-zone change; a self-check that every future reminder has an alarm; Health screen shows any that are missing.
9. **Nightly maintenance job** (the "autonomous" part): integrity check, search and embedding backfill, stuck-job repair, old-log cleanup, backup, gardening when due. Idempotent, logged without content, visible in Settings, Health.

## Phase 2: What the AI receives on every message (the biggest win)
1. **Search with the conversation, not just the last message**: the query is built in code from the new message plus the last few turns and the conversation's topic, so "yeah, same again" still finds the right past.
2. **Time and outcome understanding in code**: "last month", "in August", "yesterday", "since I started", "broken", "kept", "missed" become exact date ranges and filters over promises, check-ins, events and conversations. Deterministic, with vectors.
3. **Better ranking**: fuse keyword and meaning scores with recency and record type; remove near-duplicates (diversity); send as many results as are strong and fit the budget, not a fixed 5.
4. **Everything searchable**: notes trimmed for space, every promise, check-in history with reasons, plan and goal revisions, letters, reviews, chapters, people.
5. **Relevant notes first**: when memory is large, the frozen NOTES block favours notes related to this conversation's topic and chat type, and a small volatile block brings in any note relevant to the latest message that did not fit.
6. **Code-written insights**: more facts counted by code for the AI to read (weekday and time-of-day patterns, reason trends, before/after a plan change, how promises usually go by type), each only with enough data behind it.
7. **Accurate size counting**: a closer token estimate so less room is wasted and more useful facts fit, still never over the provider's limit.
8. Update `contracts/context.md`, `tools/context.py`, the vectors and `memory_exam.py` together; show the exam score after each change.

## Phase 3: How memory is built after each chat
1. Reflection receives past events similar in meaning from the whole history, not only the last 12, so a pattern months apart is noticed.
2. Duplicate and deleted-note checks use meaning (embeddings) as well as shared words, so a reworded deleted note can never return.
3. Facts with time: profile entries and notes can carry "since" and "until" (written by code from the day they were said), so old facts never pose as current ones. Schema change with migration and MigrationTest.
4. Check-ins record the time of day (schema change), enabling time-band facts.
5. Gardening gets meaning clusters computed by code to suggest merges, still validated by the applier.

## Phase 4: The on-phone meaning model (needs owner OK: APK size)
1. Evaluate newer small on-device embedding models against bge-small-en-v1.5 on the memory exam; switch only if clearly better, with new reference vectors and a Rebuild path for old embeddings.
2. Optional small on-device re-ranker for the final ordering of search results, only if the exam shows a real gain and the size and speed are acceptable.

## Phase 5: Speed and battery
Baseline profiles, R8 checks, database indexes reviewed against real queries, vector search memory use, background job scheduling that respects battery, timing tests against the Phase 0 harness on the 10-year database.

## Phase 6: Planner, check-ins and reviews
Review every planner rule against real use: ramps, rest days, missed weeks, plan changes mid-way, several goals competing for the same hours (code-computed load per day), better review facts. Every number stays in `tools/planner.py` with vectors first.

## Phase 7: Quality gates
Emulator smoke tests and Compose UI tests in CI; screenshot tests against the mockups; accessibility checks (contrast, touch targets, font scaling); a Kotlin mutation check if a tool is approved; CI fails on any drop in the memory exam score.

## Phase 8: New features (a menu; the owner picks, each one approved separately)
Candidates that keep the rules: home-screen widget for today's check-in; Health Connect import (sleep, steps; on-device only) as code-counted facts; a global search screen over their whole history; journal entries that are not chats; voice typing using the phone's on-device keyboard; a yearly chapter; attachments (photos of notes) stored encrypted. Not candidates (they break owner decisions): streaks, nagging, cloud sync, crisis screens, automatic model switching.

## How each phase ends
All checks pass (`bash tools/check_all.sh`, `bash tools/forbidden_patterns.sh`, Gradle lint and tests), numbers recorded here, a plain-language summary for the owner, and a short list of what the owner must check on the phone. Then the owner says go for the next phase.

## Results log
(Each phase adds its before and after numbers here.)

### Phase 0 baseline (14 Oct 2026, Clarity 0.1.12 plus the Phase 0 fixes)

**Step 1, build tools:** not set up in the cloud session, at the owner's request (14 Oct 2026): Gradle lint, unit tests and emulator tests run in GitHub Actions on every push.

**Step 2, bug scan:** `docs/audits/bug-scan-2026-10-14-full.md`. Four findings, all fixed with a test each: a reply stuck on "thinking" when the key read failed, the start-up sweep cut short by a turn of the phone, screens missing a time zone change, the Focus week counted by the phone's date. CI run #124 green.

**Step 3, what the AI sees** (`tools/eval/README.md`):

| Exam | Keyword only | Keyword + meaning (the app) |
|---|---|---|
| Exam 1, 43 questions (old), mirror brought up to 0.1.12 | 69.8% | 79.2% |
| **Exam 2, 157 questions over one invented year (new)** | 62.6% | **71.5%** |
| Exam 2 leaks (deleted note or off-the-record chat reaching the coach) | 0 | 0 |

Exam 2 by kind (app): recall 95%, people 94%, superseded 89%, promises 100%, synthesis 71%, plan 71%, follow-ups 67%, check-ins 60%, time 53%, patterns months apart 44%, letters/reviews/chapters 20%. Target for Phase 2: 95%.

**Step 4, lifetime** (`LifetimeTimingTest` on the CI emulator, run #125: 7,306 conversations, 73,060 messages, 43,836 meaning vectors, 9,401 check-ins over ten years). The emulator is faster than a mid-range phone, so a number over target here is over target on the phone too.

| Measure | Ten years, emulator | Target (mid phone) |
|---|---|---|
| Open the encrypted database, first query | 483 ms | |
| Start-up repair sweep + search index check | 93 ms | |
| Drawer, every conversation's row | 249 ms | |
| Start-up database work, these three together | about 0.8 s | start-up < 1.5 s in all |
| Plan tracker (reads every check-in ever) | 1,180 ms (1,049 ms again) | |
| Search, first of the session (reads every vector) | 5,745 ms | < 150 ms |
| Search, later (median of 4) | 411 ms (worst 663 ms) | < 150 ms |
| Request, new conversation (frozen part built) | 5,866 ms | < 300 ms |
| Request, next message | 2,181 ms | < 300 ms |
| Settings, rebuild search index | 2,939 ms | |
| Memory in use after search | 60 MB of 201 MB allowed | |
| Database file | 123 MB (+ 9.5 MB log) | |

What the numbers say: after ten years, a message would wait two to six seconds before it is even sent, and the first search of a session takes almost six. Two causes are known: the plan tracker reads every check-in ever made, on every request (about a second), and the first meaning search reads all 44,000 vectors from the encrypted file. The rest of the new-conversation time (building the frozen part) is not broken down yet; Phase 5 times each part. A fix that is cheap and safe can come earlier, with the owner's OK.


### Phase 1: lifetime reliability and data safety (14 Oct 2026, built, CI run #131 green; waiting for the owner's OK)

| Step | What was built | How it is proven |
|---|---|---|
| 1. Automatic backups | Made by the nightly maintenance when due: weekly (default) or every night; keep the newest 4 (default), 8, 14 or 30; read back and verified before older ones go. A backup that was not verified is deleted at once, and one the phone killed half way is deleted before the next, so a broken file never counts as one of the newest. | `MaintenanceTest` (JVM: when a backup is due); writing to a real folder can only be checked on the phone (below) |
| 2. Key loss protection | Backups were already sealed with the owner's passphrase. New: a plain warning that a reset or lost phone leaves the backups and the passphrase as the only way back, and a Test button that says whether a typed passphrase matches (constant-time, nothing shown or kept). | by hand on the phone (below) |
| 3. Restore tests for every format | `contracts/backups/`: five sample backups (format 1 at database versions 1 and 3; format 2 at 3, 4 and 5, the last in three sealed pieces) and 14 damaged copies. Opened by `tools/backup_format.py`, by the app's code on the JVM (`BackupSamplesTest`) and restored into today's database on the emulator (`RestoreTest`). 7 planted bugs, all caught. | CI, every push |
| 4. Database health | Nightly SQLite integrity check and broken-link count; no backup after a failed check; a plain line in Health with the safe repair (restore the newest backup). Stuck jobs and hours-old "streaming" replies repaired; search index holes rebuilt from the raw rows. | `MaintenanceRunnerTest`, `StartupRepairTest` |
| 5. Migrations from every version | Real-looking rows (the sample backups' data) put into a database of versions 1, 2, 3 and 4, migrated all the way to 5, every row and word checked. A database with no migration path refuses to open and keeps every row. | `MigrationTest` |
| 6. Fault injection | Killed mid-reply and mid-reflection (`StartupRepairTest`); killed or disk full mid-backup (a backup cut at any byte never opens; a full disk is an error; unfinished files removed); a damaged backup half restored changes nothing (`RestoreTest`); provider error objects and cut-off replies (`WireTest`, existing); clock, time-zone and day-boundary changes (existing JVM tests, plus alarms re-set on a time-zone change). | CI |
| 7. Readable forever | `docs/BACKUP-FORMAT.md` writes the backup format down; `tools/backup_format.py open` opens a backup on any computer with Python and the passphrase, no app needed. Export now streams (no memory spike at ten years), adds reviews and letters, and carries a README. | `test_backup_format.py`, AES/GCM known-answer tests |
| 8. Reminders that never silently fail | Re-set after a time-zone change too (restart, update and clock change were already covered). A self-check finds any reminder ahead without an alarm and sets it again: nightly, and whenever Health is opened. Health shows "N ahead, each has its alarm". | `ReminderTest` |
| 9. Nightly maintenance | 03:30, battery not low, once a day (job key): integrity check, repairs, search and vector backfill, reminder self-check, year-old log cleanup, catch-up of reflections, reviews, letters and gardening, backup when due. Never changes a raw row, never calls the AI, counts only in the log. Health: "Nightly check: 7 hours ago: database ok, ...". | `MaintenanceRunnerTest` |

Not done in this phase, said plainly: "100 percent reliable" is not claimable; the integrity check finds damage but cannot repair a damaged database (the repair is restoring a backup); the Test button checks the passphrase against the one saved on this phone, not against each file in the folder; whether Drive and HyperOS let the 03:30 run happen on time can only be seen on the phone.

**What the owner checks on the phone** (after installing this build over the old one):
1. Settings, Your data: choose Every night, tap Back up now once, then the next morning look at Settings, Health: "Nightly check" should say it ran, "database ok", and "Last verified backup" should be that night.
2. Tap Test next to the passphrase, type it from the paper: it should say it matches.
3. Settings, Health: "Reminders ahead" should say each has its alarm.
4. Optional: copy one backup file to a computer and run `python3 tools/backup_format.py open <file> out.json`.

### Phase 2: what the AI receives on every message (14 Oct 2026, built; waiting for the owner's OK)

**Memory exam 2** (157 questions, `tools/eval/README.md`): **71.5% to 95.1%** of the evidence within the coach's
reach (keyword only: 62.6% to 92.5%), 0 leaks. Target was 95%. Exam 1, the old fixture not used to design this:
79.2% to 84.7%. By kind (app): letters/reviews/chapters 20% to 100%, check-ins 60% to 100%, plan 71% to 100%, time
53% to 92%, patterns months apart 44% to 87%, vague follow-ups 67% to 94%, synthesis 71% to 82%.

| Step | What was built | How it is proven |
|---|---|---|
| 1. Search with the conversation | Besides the new message, a second search of his last 3 messages in the conversation (and its title) with the new one; its results count half. | `contracts/retrieval_vectors.json`, emulator test "a vague follow-up" |
| 2. Time and outcome in code | `RecallTime` reads "in August", "last week", "yesterday", "on 12 October", "late September", "since March", "the first week of September", "3 days ago"... into exact days; "promise" with break/kept/moved/let go orders promises by outcome. What falls in the period is a result list of its own: promises due, conversations held, letters, reviews, chapters, goal changes, and the check-ins of a week or less. | `tools/recall_time.py`, 74 shared cases (both "may" traps, leap years, weekdays), 11 planted bugs |
| 3. Better ranking | Weighted fusion (period 2, message 1, conversation 0.5); one rare word counts as a match; repeated small talk counts once; near-duplicates out; one result per conversation; up to 12 results (was 5), trimmed by the budget. | `tools/retrieval.py`, 80 shared cases, 12 planted bugs |
| 4. Everything searchable | Letters, reviews, chapters, goals (finished too), goal changes with reasons, targets (ended too), check-ins with a reason or note; promises now read "Promise: ...". Kept in step with their tables (a deleted letter leaves search at once); the index rebuilds once after the update and the new vectors are made in the background. | emulator tests (letter found then gone; promises in August) |
| 5. Relevant notes first | Notes sharing a word with the conversation's message are the last of their grade to be trimmed; active notes left out of NOTES come back through RELEVANT_PAST when a message matches. | `MemoryBlocksTest` |
| 6. Code-written insights | CHECKIN_FACTS: the last 90 days (reasons, weekdays) and a reason that grew against the 14 days before; OPEN_PROMISES: how promises due in the last 90 days went. At least 3 items behind every number. | `PlanBlocksTest`, `PromiseTextTest` |
| 7. Size counting | Measured against DeepSeek's tokenizer: "3 characters a token" under-counted numbers, dates and emoji (0.56 to 0.71 of real). Digits now 1 token each, other non-English characters 2; English unchanged. | `contracts/context_vectors.json`, 1 planted bug |
| 8. Contracts together | `contracts/context.md` (RELEVANT_PAST section, NOTES, CHECKIN_FACTS, OPEN_PROMISES, the estimate), Python references, vectors, `mutation_check.py` (91 planted bugs, all caught), Kotlin ports and tests, both exam mirrors. | `bash tools/check_all.sh`, CI |

Not done, said plainly:
- Step 7 asked for a closer estimate so more fits. Measured, no simple rule both wastes less room on English and
  stays safe on everything else (a safe one would waste more: 1.7 times real instead of 1.4). The estimate was made
  safe where it was wrong instead. A real tokenizer in the app would do both; it is a new dependency, so it waits
  for the owner.
- Step 5 favours notes by the conversation's words only, not by chat type: there is no rule yet for which notes a
  chat type needs that would not be a guess.
- Step 6 has no time-of-day lines (check-ins do not record the time until Phase 3) and no before/after a plan change.
- The exam measures what is within reach, not how well the coach answers, and sending up to 12 results instead of
  5 is part of the gain. The test bench on the real model is the check of the answers.
- Broad periods ("what happened in March?") send at most 10 of what the period holds; patterns with no shared
  word or meaning are still missed.

**Speed at ten years** (`LifetimeTimingTest`, CI emulator; runners vary by about 20% from run to run):

| Measure | Phase 0 (run #125) | Phase 2, first build (run #133) | Phase 2, after the fix (run #136) |
|---|---|---|---|
| Search, later (median of 4) | 411 ms | 946 ms | 674 ms (worst 1,246) |
| Search, first of the session | 5,745 ms | 7,057 ms | 5,307 ms |
| Request, next message | 2,181 ms | 3,652 ms | 2,703 ms |
| Request, new conversation | 5,866 ms | 5,835 ms | 5,433 ms |
| Settings, rebuild search index | 2,939 ms | 3,313 ms | 2,378 ms |

Phase 2 makes a message's search about half a second slower at ten years (it now searches up to five lists,
not two, and brings the record index up to date after a check-in). The first build read up to 160 vectors from
the database per search; that was fixed. What is left belongs to Phase 5 (speed), which also has the older
costs: the plan tracker reading every check-in (about a second) and the first search reading every vector.

### Phase 3: how memory is built after each chat (14 Oct 2026, built; waiting for the owner's OK)

| Step | What was built | How it is proven |
|---|---|---|
| 1. Reflection sees the whole history | Besides the 12 most recent events, reflection gets up to 8 earlier conversation summaries and events like this one: everything he said in the new part, joined, searched by keyword, and his newest six messages one by one by keyword and meaning; one per conversation, oldest first, dated by code. | `tools/eval/reflection_exam.py`: for 26 pairs of conversations a pattern question links, the earlier one is in view of reflection **3 of 26 (11.5%) before, 23 of 26 (88.5%) now**. `retrieval_vectors.json` (5 cases), 4 planted bugs, `ReflectionInputTest` |
| 2. Note checks by meaning | A new, rewritten or merged note close in meaning (0.75) to one he deleted is refused; a new note repeating an active one of the same kind in meaning (0.9) is dropped, never when one says "not" and the other does not (that guard now covers the word check too). Checked again inside the database transaction. Vectors kept in memory only. | `tools/eval/note_meaning.py` on 68 written pairs: rewordings of a deleted note caught **0 of 31 before, 30 of 31 now**. 12 applier vectors, 8 planted bugs, an emulator test with the real model |
| 3. Facts with time | Database version 6. A profile value carries the day it was first said; a value reflection replaces is kept as "before, until that day" and sent with the entry. Notes and profile changes are dated by the day he said them, not the day the job ran. A retired note found by search says when it was first and last seen. | `MigrationTest` (5 to 6, and every old version with real rows to 6), a version 6 sample backup restored in CI, `MemoryBlocksTest`. Exam 2 keyword-only 92.5% to 93.0% (replaced facts 89% to 93%); with meaning search unchanged at 95.1%; 0 leaks |
| 4. Check-in time of day | A check-in stores his local time and day of answering. CHECKIN_FACTS gains "answered late at night (22:00 to 4:00) on 9 days, marked Not or Part on 6 of them" over 90 days. | `PlanBlocksTest` |
| 5. Gardening suggestions | Code groups notes that look like one (same kind, same "not", meaning 0.85 or half the words, at most 4) and lists them under NOTES as merges to consider; the applier checks every merge as before. | 3 applier vectors, 4 planted bugs; 17 of 31 rewordings suggested, 3 of 37 different notes (for the model to turn down) |

Contracts: `tools/appliers.py` (84 vectors), `tools/retrieval.py` (85), `mutation_check.py` 107 planted bugs, all caught; `contracts/context.md`, `db/schema.sql` and the spec (sections 7 and 10) updated together. No prompt was changed.

Not done, said plainly:
- The thresholds were set on 68 note pairs written for this phase, not on his real notes. This model scores
  opposites like rewordings, so an opposite with no "not" in it ("helps you sleep" and "keeps you awake"), of the
  same kind, is still taken for a repeat. Near something he deleted, a different note on the same topic is
  sometimes refused too (12 of 37 test pairs): the safe side of rule 5, but a cost.
- With meaning search off (the model failed its check), the note checks fall back to words, as before.
- Reflection still misses earlier conversations that share neither a word nor a meaning with the new one (3 of
  26). It gets about 1,600 more tokens per run.
- Only the newest replaced value of a profile entry is sent; older ones stay in the database and the export.
  Entries and check-ins from before version 6 have no day or time (nothing is guessed).
- Time-of-day lines need answers given on the day itself, so they appear only after some weeks of use.
- The exams measure what is within reach, not what the model writes; the test bench on the real model is that
  check.

**What the owner checks on the phone** (after installing this build over the old one):
1. Open the app once: it updates the database to version 6 (no data changes). Settings, Health should still say "database ok" after the next night.
2. Answer a check-in today as usual. Nothing looks different; after a few weeks, Talk's coach may mention when in the day you tend to answer "Not".
3. In What I know, open a profile entry the coach updates later: it shows "Since ..." and, when a value was replaced, "Before, until ...".
4. If you delete a note and later say the same thing in other words, it should not come back.

### Phase 4: the on-phone meaning model (14 Oct 2026, built; waiting for the owner's OK)

The owner's rule (14 Oct 2026): **choose the model with the best outcomes; size does not matter.**

**Step 1, a newer model: switched to gte-base-en-v1.5.** `tools/eval/compare_embedders.py` ran bge-small-en-v1.5
against 31 models (23 MB to 614 MB, including EmbeddingGemma and Qwen3-Embedding) and 8 mixes of two or three,
through exam 1, exam 2, the reflection exam and the note pairs, each with its lines set fairly (the search line
from random pairs, without answers; the note lines at the same cost as bge's). The top ones were measured again
with the line moved a little either way, so one lucky setting could not decide it. Full table in
`tools/eval/README.md`. **gte-base-en-v1.5** (Alibaba, 2024, Apache-2.0) was best or level on every exam, on its
own; mixes of two or three models were no better, and bigger models were worse on the exams.

| | bge-small-en-v1.5 (before) | gte-base-en-v1.5 (now) |
|---|---|---|
| Exam 2, 157 questions | 95.1% | **97.1%** |
| Patterns months apart (part of exam 2) | 86.7% | **90.0%** |
| Exam 1, the exam not used to design anything | 84.7% | **91.9%** |
| Reflection exam: earlier conversations of a pattern in view | 88.5% (23 of 26) | **96.2% (25 of 26)** |
| Rewordings of a deleted note caught (different notes blocked) | 30 of 31 (12 of 37) | **31 of 31** (12 of 37) |
| Leaks (deleted or off the record) | 0 | 0 |
| Size in the app | 34 MB | 147 MB |
| Time per text (cloud computer; the ratio is what counts) | 1x | about 3x |
| Numbers per vector (storage and memory) | 384 | 768 |

How: CLS pooling as its model card says, 768 numbers per vector, no prefix; its vocabulary is bge's word for word,
so the app's tokenizer did not change. GitHub refuses files over 100 MB, so the model is kept in two parts and the
app joins them on the phone the first time (`tools/bundle_embedding_model.py` makes them from a pinned revision;
`contracts/embedding_vectors.json` regenerated). Its lines: search 0.42 (was 0.62), deleted note 0.65 (0.75),
duplicate 0.88 (0.9), gardening suggestion 0.82 (0.85), changed in `tools/appliers.py`, the Kotlin and the test
vectors together (`mutation_check.py`: 107 planted bugs, all caught). After the update the old vectors are removed
once and every vector is made again in the background (the "Rebuild path for old embeddings"); keyword search
works meanwhile. (Earlier the same day this phase used mdbr-leaf-mt, the best small model, 23 MB; it was replaced
when the owner said size does not matter.)

**Step 2, a re-ranker: measured, not built.** `tools/eval/rerankers.py` tried 11 re-rankers (4.5 MB to 643 MB) on
the best 30 search candidates. On top of gte-base-en-v1.5 the best one changes nothing (1 question better, 1
worse); replacing the order never helped; the bigger ones were no better than the small one. Left out on
outcomes, not size.

Not done, said plainly:
- Speed on a phone is not measured, only guessed from the cloud computer: about 3 times the old model per text,
  so perhaps a tenth to a fifth of a second for each message, and remaking the vectors after the update takes
  minutes for a few months of history (it would be hours for ten years). Memory for vectors doubles (about 46 MB
  at ten years). Phase 5 (speed and battery) should time it on the 10-year emulator database.
- Fewer rewordings are dropped as duplicates (6 of 31, was 9) and gardening is shown slightly fewer merges to
  consider (14 of 31, was 17), each at the same number of wrong ones.
- The lines were set on the same exams and 68 note pairs as before, not on his real notes and messages.
- One emulator test sentence was changed: "chemistry is boring" now counts as close in meaning to "Hina helps me
  revise chemistry", which is fair; the test is about one shared word, so it now uses "the chemistry of this team
  is off".
- The Android build, lint and tests were not run in this session (the owner's choice in Phase 0: they run in
  GitHub Actions on every push).

**What the owner checks on the phone** (after installing this build over the old one):
1. The first open after the update may take a few seconds longer (the app unpacks the 147 MB model once).
2. Leave the app open (or come back) for a few minutes: it makes the new meaning vectors for your whole history
   in the background. Nothing to tap; your data does not change.
3. Settings, Health, the next day: the counts should not start rising. (If the new model failed its check on your
   phone, the app quietly falls back to keyword search and logs it there; tell Claude if you see that.)
4. In Talk, ask about something from an older conversation in different words than you used then: the coach
   should still bring it up. Replies should not feel slower to start; if they do, say so.
