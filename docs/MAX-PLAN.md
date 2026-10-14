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

