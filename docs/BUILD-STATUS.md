# Where the build is, and what comes next

Written 9 Oct 2026 at the end of the first Claude Code session, for the owner and for whichever AI picks this
up next. The spec is `docs/LIFE-SYSTEM.md` (scope locked) and the working rules are `CLAUDE.md`. This file
only says what exists, what does not, and in what order to do the rest.

Branches: everything below is merged into `main`, and the old working branches were deleted on 11 Oct 2026.
The branch names in the sections below are kept as history only; start new work from `main`.

---

## Stage A (skeleton): finished 10 Oct 2026, checked on a Redmi Note 13 Pro (HyperOS)

| Piece | State |
|---|---|
| Gradle project, Kotlin, Compose, Material 3, Hilt, Room + SQLCipher, OkHttp, WorkManager, pinned versions | done |
| Theme and tokens (0.1.6 redesign), Instrument Sans and Serif bundled (SIL OFL in `app/licenses/`) | done |
| Three tabs plus Settings, app icon, `allowBackup="false"`, no cloud backup or device transfer | done |
| `DefaultSettings.kt` as the only place a provider, base URL or model name is written | done |
| `prompts/*.md` and `contracts/prompts.json` packed into the APK as assets at build time | done |
| Contract ports with tests that read the same `contracts/*.json` files: `CardParser`, `StrictJson`, `Planner`, `PlanStateText`, `TextCheck`, `ContextBuilder`, `PromptContract` | done |
| Database: all 28 tables of `db/schema.sql`, schema exported to `app/schemas` and committed, `SchemaTest`, `StateMachines` + `StateMachineTest`, `StartupRepair`, `MigrationTest` (emulator) | done |
| Secrets: database key and API keys sealed with an Android Keystore key (`SecretStore`, `KeystoreCipher`) | done |
| AI clients: OpenAI-compatible streaming (DeepSeek), Anthropic as backup, `Failover`, 18 tests on the awkward parts | done |
| `ChatRepository`: the one writer, named commands, one transaction each | done |
| Context packet per `contracts/context.md`: frozen system message in `conversation.prefix_json`, volatile blocks in the final user turn, cached-token counts recorded | done |
| `TimeFacts`: NOW sentence, due phrases, message stamps, tested against the samples in `prompts/testbench.md` | done |
| `ReplyService`: `dataSync` foreground service, partial reply saved about once a second, `onTimeout` marks it interrupted | done |
| Talk screen: bubbles, chat type dropdown, conversations list with Forget, Try again on an interrupted or failed reply | done |
| Settings, Coach: provider presets, base URL, API key, chat and background model names, Test connection | done |

**43 JVM tests pass**, plus `bash tools/check_all.sh` (21 mutations, 0 survive) and
`bash tools/forbidden_patterns.sh`. `./gradlew assembleDebug assembleDebugAndroidTest lintDebug
testDebugUnitTest` all pass on the build machine.

### What stage A deliberately leaves unfinished

- A suggestion card shows what it would save and **Not now** works, but **Set it** waits for stage C, where
  the commands that write a promise, a plan or a note are built. Nothing can reach the database without it.
- The card parser is given an empty set of known ids, so plan, target change, backlog and focus-mode cards
  are dropped on purpose until the data behind them exists (stages D and E). This is fail-closed behaviour,
  not a bug.
- Context blocks that exist so far: FLAGS, NOW, CARD_RESULTS, EARLIER. PROFILE, PEOPLE, NOTES, FOCUS,
  CHAPTERS, RECENT_SESSIONS, IDEAS_RECENT, LETTER, REVIEW, PLAN_STATE, OPEN_PROMISES, CHECKIN_FACTS and
  RELEVANT_PAST arrive with the stages that create their data.
- Off-the-record conversations, the due card and the letter card on Talk are not built yet.
- Settings shows only what works; reminders, data, backup, the prompt editor, the test bench and the error
  log say they come later rather than showing dead switches.

### One divergence worth knowing about

Room cannot create SQLite `CHECK` constraints. The allowed-value lists of `db/schema.sql` (statuses,
levels, kinds) are therefore enforced in Kotlin, in `data/Values.kt`, and `SchemaTest` fails if those lists
and the schema's `CHECK` lists ever differ. Everything else (foreign keys, unique keys, NOT NULL, defaults,
indexes) is still enforced by the database itself.

### One contract change made this session

`contracts/textcheck_vectors.json` gained a vector for the six-character minimum of a stored quote (the old
vectors only tested a two-character quote, so an off-by-one would have slipped through both the Python and
the Kotlin side), and `tools/mutation_check.py` gained the mutation that proves it is caught.

---

## What the owner has to do themselves

Nothing on a phone has been tested, because the build machine has no phone or emulator.

1. **Get the app onto the phone.** The CI workflow builds and tests but does not yet upload an installable
   debug APK. First task of the next session: add that upload step, then download the APK from the run's
   artifacts on github.com and install it (Android will ask you to allow installing from this source).
2. **Paste your API key** in Settings, Coach, then press **Test connection**. Expect
   "Connected. The model answered and streamed its thinking."
3. **Check the three stage A promises**: a real chat works; the reply keeps going when you switch to
   another app; after a restart a half-written reply shows "That reply was cut off" with Try again.
4. **Xiaomi / HyperOS**: battery unrestricted and autostart for Clarity, or the phone will kill the reply
   service. The Settings page with the buttons for this arrives in stage C.
5. **Release signing**, when you want a signed APK from a version tag: create a keystore, then add the four
   secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) on github.com under
   Settings, Secrets and variables, Actions. Keep the keystore file and its passwords somewhere safe
   outside the repository: without them you cannot update the app, only reinstall it from scratch.

---

## The order of the rest

Each stage finishes with the spec's own "done when" (section 14). Do not start the next one before it.

### 0. Close out stage A
Done on 10 Oct 2026 (branch `ccr-38e49fc8-tb86x8`):
- CI runs on `ccr-*` branches, uploads the debug APK (`clarity-debug-apk`), builds the minified release
  APK, and runs the emulator tests on Android 14: database, Keystore, a full chat round trip
  (`ChatRoundTripTest`) and a scripted walk through the real app (`tools/emulator_smoke.sh`).
- Bugs found on a Redmi Note 13 Pro and fixed: Test connection ran the network call on the main
  thread (crash); the card parser's regex had a bare `}` that Android's ICU engine rejects, so the first
  chat crashed with ExceptionInInitializerError; a trailing space in a model name broke the connection.
- Every chat error is now caught (Throwable, not just Exception), shown as a plain line under the chat,
  and written to the last-crash box at the bottom of Settings (error kinds and code lines only).
- Talk: coach replies use the full width, Send closes the keyboard, the list follows new text.
- On the phone: a real chat with DeepSeek works (10 Oct 2026).

Confirmed on the phone (10 Oct 2026): the reply keeps going during an app switch, and it even
completes after the app is swiped away from recents (the foreground ReplyService outlives the screen).
The "That reply was cut off" path is for a real process kill and is covered by StartupRepair.

**Stage A is finished.** Stage B, memory, is in progress (see below).

### B. Memory (in progress, branch `ccr-3507555e-xov626`, started 10 Oct 2026)

Built and checked on CI (no phone or emulator on the build machine; CI runs the emulator tests):

| Piece | State |
|---|---|
| `ReflectionApplier`, `GardeningApplier`, `TextJobCheck` (+ `JobFields`), `ApplierTest` over all 41 cases | done |
| Reflection job: `ReflectionInput` (pure), `ReflectionJob`, `ReflectionWorker`, `ReflectionScheduler`; job row with a deterministic key, no call when nothing is new, 3 attempts, stale `running` rows reclaimed | done |
| `MemoryRepository.applyReflection`: one transaction, re-checks every note and profile entry at save time, caps of 15 active notes per kind and 30 profile entries | done |
| Database version 2: `reflection_item` (idea tags, disagreements, contradictions), migration + `MigrationTest` | done |
| What I know (`docs/mockups/Know.dc.html`): sections, evidence, grades, Edit, Delete (asks, final), That's right, search, archive | done |
| PROFILE, PEOPLE, NOTES, RECENT_SESSIONS, IDEAS_RECENT blocks (`MemoryBlocks`), trim order tested | done |
| EARLIER: the conversation is reflected right after a reply that moved the history window | done |
| FTS search (`SearchIndex`, `Retrieval`): their messages and summaries, RELEVANT_PAST, Rebuild search in Settings, a one-time build after the update | done |
| Forget this conversation: asks first; removes index rows and notes learned only from it | done |
| Meaning search: gte-base-en-v1.5 since Max plan Phase 4 (int8 ONNX, 147 MB in two parts; bge-small-en-v1.5, 34 MB, before), bundled in the APK as the owner chose, ONNX Runtime 1.20.0, `WordPiece` tokenizer matching the reference on all 20 cases (`TokenizerTest`), vectors checked against `contracts/embedding_vectors.json` on first load and in `EmbedderTest` | done |
| Hybrid retrieval: keyword and meaning matches merged by reciprocal rank fusion; keyword only when the model is off; Re-index meaning in Settings | done |
| The memory exam: ported to `tools/eval/memory_exam.py` (fixtures from the old repo, unchanged). 81.7% of the evidence within reach without promises, against the old app's 76.7%: no regression. Promise questions wait for stage C. | done |
| Search also covers their verified quotes, the events reflection recorded and retired notes (the exam showed they were missing); keyword candidates are read newest first | done |

When the reflection runs: 30 minutes after the last reply in a conversation (each reply moves it back), at
once when they start a new conversation, and on app start for anything left over. On the first start after
this update, every stage A conversation they already had is reflected once (one background call each).

Decisions taken in this stage, for the owner to know:
- `reflection_item` is a new table (schema version 2). The schema had nowhere to keep the idea tags
  (IDEAS_RECENT needs them), the disagreements and the contradictions that reflection returns. The migration
  only adds a table; nothing existing is touched.
- `tools/appliers.py` crashed on some malformed gardening output (an object as an id, a list as a key) and
  accepted `10.0` as person id 10. Fixed, with a new vector and a planted-bug test.
- Room was not configured to export its schema; it now writes `app/schemas`, and CI fails (printing the
  file) when a build exports a schema that is not committed.
- A reflection's title replaces only the 40-character placeholder Talk writes from their first message; a
  title they typed stays. Significance and tone are checked but not stored (the schema has no column).
- A profile entry becomes confirmed only by their tap or edit, never by a job, even when the job quoted them.
- Meaning search: the model is bundled (the owner's choice, 10 Oct 2026), so the APK is about 45 MB bigger and
  works offline. Only arm64 (phones) and x86_64 (the CI emulator) native code is packed. A past message
  counts as a meaning match at cosine 0.62 or more; very short messages are left out of meaning search,
  because small talk all looks alike. No query prefix: the model card says v1.5 does not need one when
  messages are compared with messages. `tools/make_embedding_vectors.py` remakes the reference vectors if
  the model ever changes (it needs onnxruntime and tokenizers, so it is not part of check_all.sh).

What the owner checks on the phone for "done when": have a conversation that mentions a concrete fact (a
person, a time that works for them), wait 30 minutes or start a new conversation, then (1) open What I know
and find it under the right section with "You said" evidence, and (2) in a new conversation, ask something
that needs it and see the coach use it.

### C. Promises and cards (built, branch `ccr-f202bb50-51yo10`, 10 Oct 2026; waiting for the phone check)

Built and checked on CI (no phone or emulator on the build machine; CI runs the emulator tests):

| Piece | State |
|---|---|
| `PromiseTimes`: a card's "when" read in code. Python reference `tools/promise_times.py`, 54 shared cases in `contracts/promise_time_vectors.json`, `PromiseTimesTest`, 4 planted bugs in `tools/mutation_check.py` | done |
| The save path: `chat/CardSaver.kt`, one transaction per card (promise, note, focus, chat-type switch). The card is re-validated with the real parser, and "Saved" is built from the row written. `CardSaveTest` (emulator): nothing saves without a tap | done |
| Promise card: shows the resolved day and time; Set it, Change (picker), Not now. Picks are asked for after midnight, for a bare hour, for a past time and for wording it cannot read | done |
| Reminders: `promise/Reminders.kt`, exact alarms, set again after a restart, an update, a clock change and on app start; notification hidden on the lock screen. `ReminderTest` (emulator) | done |
| Answering a promise: `promise/PromiseRepository.kt` (Done, Not done with reason chips, Moved, Let go, Undo), state machine checked. `PromiseRepositoryTest` (emulator) | done |
| Due card at the top of Talk, and Plan, Promises (needs an answer, coming up, past) | done |
| OPEN_PROMISES in every message (`core/PromiseText.kt`, `PromiseTextTest`): open ones, then the last 14 days' answers. Every promise is in the search index with its outcome | done |
| Settings, Reminders: "My day ends at", and Keep reminders reliable (`docs/mockups/Reliable.dc.html`) with a test reminder | done |
| Memory exam re-run: all questions 62.5% -> 80.6% with promises in (`tools/eval/README.md`) | done |

Decisions taken in this stage, for the owner to know:
- `PromiseTimes` is a new contract (Python reference, shared cases, Kotlin port), the same as the others. It never
  guesses: anything it is not sure of opens the picker. "tomorrow" is read against the moment the coach offered the
  card, so tapping it the next day still means the day they meant.
- OPEN_PROMISES also carries the promises answered in the last 14 days, as the test bench scenarios already assumed
  ("marked not done, reason: tired"). `contracts/context.md` now describes the lines.
- Reminders use `USE_EXACT_ALARM` (Android 13+, granted on install for reminder apps), so no permission screen is
  needed for exact alarms. Notifications are asked for the first time a promise with a reminder is set.
- A note card saved by their tap is confirmed at once and has no evidence row, so Forget leaves it alone.
- A promise is never saved in the past; Moved keeps the old one as "moved" and starts it again. A move has no Undo.
- Not built here, on purpose: plan, target and backlog cards (stage D, they need the planner) and mode cards into a
  focus (stage E).

What the owner checks on the phone for "done when" (spec section 14: a reminder fires on their phone, and nothing saves
without a tap):
1. Install the new debug APK over the old app (never uninstall: that wipes the data).
2. Settings, Keep reminders reliable: make every row OK (Autostart by hand), then tap "Send a test reminder in 1
   minute" and lock the phone. It must ring.
3. In Talk, ask the coach to help you commit to something small "in 5 minutes, remind me" (or a time 5 minutes
   ahead). A coral card appears. Before tapping, open Plan, Promises: it must be empty. Tap Set it: the card turns
   into a dark "Saved" receipt, and the promise is in Plan, Promises. Lock the phone and wait: the reminder rings.
4. Restart the phone with another promise set a few minutes ahead: it must still ring.
5. When it is due, the yellow card at the top of Talk shows it. Try Not done (pick a reason), then Undo in Plan.

### D. Planner (built, branch `ccr-29480420-vlt574`, 10 Oct 2026; waiting for the phone check)

Started at the owner's request ("Start stage d") on top of stage C. Built and checked on CI (no phone or emulator on
the build machine; CI runs the emulator tests and the scripted walk, which now also opens Plan and writes a goal):

| Piece | State |
|---|---|
| `PlanDates`: the dates on a plan, backlog or target change card read in code. Python reference `tools/plan_dates.py`, 44 shared cases in `contracts/plan_date_vectors.json`, `PlanDatesTest`, 5 planted bugs in `tools/mutation_check.py` (31 mutations, 0 survive) | done |
| `PlanBlocks`: PLAN_STATE and CHECKIN_FACTS from the rows; the syllabus case comes out as the sample in `contracts/context.md` word for word (`PlanBlocksTest`) | done |
| `PlannerRepository` + `PlannerDao`: goals (every change kept as a revision), targets, backlogs, rest days, check-ins (one per target per day, a reason only with Not or Part, never for tomorrow), all one transaction each. `PlannerRepositoryTest` (emulator) | done |
| `PlanApply`: exactly what a plan, target change or backlog card would write, shown on the card before the tap and run by the save. A plan for an existing goal reuses its target and backlog of the same name, so history and pace survive. `PlanApplyTest` | done |
| Plan, Today: Did it / Part / Not, amount stepper, reason chips, a line, Clear, rest day. Today's unanswered check-ins on the due card on Talk | done |
| Plan, Goals: goal list with on pace / behind / no data yet, the goal page with need vs usual pace and where each pace lands, Write a goal, Add a target, Add a backlog, Pause, End, Mark done, Archive, Plan with coach | done |
| Every message carries PLAN_STATE (always in Plan and track, elsewhere once something is tracked) and CHECKIN_FACTS; the card parser gets the real ids and review options; reflection gets CHECKIN_FACTS | done |
| Plan, target change and backlog cards: "What will be saved" line by line, Save plan / Change it / Save, a Saved receipt from the rows. `CardSaveTest` (emulator) covers the whole path | done |
| Weekly and monthly reviews: `ReviewFacts`, `ReviewTimes`, `ReviewWriter`, re-plan flag, `TextJobCheck` before a note is published, Try again when delayed | done |
| Weekly letter: `LetterInput`, `LetterWriter` (two checks, then "delayed"), the letter card on Talk, the letter page, Talk about this letter (LETTER block), Delete is final, the silent Sunday alert. `WritingTest` (emulator) | done |
| Plan, Reviews: Weeks and Months, the review page with "The plan may be off", revisit questions, Talk about it (REVIEW block), coral dots on the Reviews chip and the Plan tab | done |
| Settings, Reminders: Weekly letter (on) and Sunday alert (off) | done |

Decisions taken in this stage, for the owner to know:
- **Ids:** G2 is goal 2, T3 target 3, B1 backlog 1, straight from the database, so an id the coach copies names
  exactly one row.
- **A backlog's done count** = what you typed as already done + the amounts entered on its linked targets from
  the day the backlog was made. Earlier check-ins still count toward your pace, but not twice toward done.
- **Review options** use the days on which at least one target is scheduled (every day when there are none).
- **A plan never ends or removes anything you have.** It reuses a target or backlog of the same name under the
  same goal (changing only what the card shows), and leaves the others alone; End a target on its goal page.
- **Dates on cards** are read against the day the coach offered the card (like promise cards). A review date
  must be 1 to 28 days ahead; a card tapped after its review day saves nothing and says why.
- **No time of day is recorded for a check-in**, so CHECKIN_FACTS has no time-band lines (only weekdays and
  reasons), and reflection can only cite those.
- **Reviews:** the facts are stored as soon as the period is due (Sunday 19:00), the note follows. Weeks are
  caught up 4 back, months 2 back, only weeks that overlap your tracking. The weekly review uses the thinking
  model; the monthly one is JSON at temperature 0.3 (as the spec says for JSON jobs).
- **The letter** is written only for the newest week (a missed letter is not written weeks late).
- **Not built yet:** the review page's day-by-day grid (Review.dc.html; the counted lines stand in for it),
  letters and reviews in search, and editing a goal's words from the goal page (the command exists; the screen
  only archives, marks done, or reactivates).

What the owner checks on the phone for "done when" (spec section 14: the syllabus scenario passes on the real model,
and every planner vector still passes; the vectors pass on CI):
1. Install the new debug APK over the old app (never uninstall).
2. Plan, Goals, Write a goal: "Clear the chemistry lectures", deadline "20 Nov". Open it, Add a backlog
   ("Chemistry lectures", total 40, unit lectures, deadline 20 Nov), then Add a target ("Chemistry lectures",
   unit lectures, target 2, minimum 1, schedule Sat-Thu, counts toward the backlog). Check that the goal page
   says "Not enough data yet" (there is no history yet) and shows "You need ... a day".
3. Plan, Today: answer the target (Did it, then try the stepper, then Not with a reason, then Clear). Talk's
   yellow card shows the check-in while it is unanswered.
4. The syllabus scenario on the real model (the in-app test bench arrives in stage E, so this is by hand): on the
   goal page tap Plan with coach and write "how do i cover the 40 that are left by 20 nov". The coach must say
   plainly whether the numbers reach it (with no history it should say there is not enough data and plan on an
   estimate with an early review), must not answer with only "just start with one", and asks one question. Then
   give it your hours and priorities; it should offer ONE plan card. Before tapping, open Plan, Goals: nothing has
   changed. Tap Save plan: the card shows Saved, and the goal page shows the plan version and its review day.
   Send one more message: the coach now talks about the saved plan, not a new one.
   Honest limit: the test bench's syllabus scenario gives the coach two weeks of history (typical 0.6 a day, best
   fortnight 1.0), which a fresh goal on the phone does not have. Running that exact scenario needs the in-app
   test bench, which is stage E. So stage D's "done when" is fully met only once the bench runs it; this step
   checks the same behaviour with the history you have.
5. After a week of check-ins: on Sunday after 19:00 open Plan, Reviews. The week's numbers appear, then the
   coach's note; after 19:30, if the week had a real conversation or 3+ check-in days, the letter appears with
   its card on Talk. Read it, tap Talk about this letter, and ask the coach what the letter said: it must know.
   Try Delete on a letter you do not need: it is gone and does not come back.

### Between D and E: smoother Talk and faster models (10 Oct 2026, branch `ccr-1c0a0374-t73ars`)

Asked for by the owner ("the answer is lagging", "copy a message and see its details", "DeepSeek V4.1 Flash,
V4 Pro only for weekly letters", "is the connection code perfect"):

| Change | Why |
|---|---|
| Replies type out at an even pace (`core/LiveText.kt`, the `ReplyText` bubble on Talk) | Every streamed piece used to rebuild the whole Talk state and restart a scroll animation, so text juddered. Now only the reply's own Text redraws, a few characters a frame, and the list follows with an instant scroll; scrolling up stops the follow |
| Card lines are held back while streaming | `[[suggest: ...]]` used to flash up as raw brackets until the reply finished |
| Long-press a message: Copy, Details | Details shows when it was written; for the coach also model, thinking level, tokens read and written, status. Replies from before this update say "Not recorded" |
| Chat model `deepseek-flash` (DeepSeek-V4.1-Flash), thinking **Low** by default; Settings, Coach, Chat thinking: Off, Low, High, Max | `deepseek-reasoner` thought at full strength before every reply, which was the main reason replies were slow. DeepSeek's levels are low, high and max; there is no medium |
| Weekly letter: `deepseek-v4-pro`, thinking High, its own two settings | The only job that uses the big model. Reflection and the monthly review are JSON with thinking off; the weekly review uses the fast model at High |
| `deepseek-reasoner` / `deepseek-chat` saved in Settings are moved to `deepseek-flash` once on start | So the update takes effect without retyping |
| Connection code: thinking and `reasoning_effort` sent explicitly (DeepSeek thinks by default), temperature only with thinking off (thinking mode ignores it), the reply collected in a StringBuilder | Reviewed for speed: one shared OkHttp pool, the prefix cached per day for DeepSeek's prompt cache, a buffered line reader. Nothing else in it was slowing replies |

### E. Focuses, chapters, backup, export, Health, test bench (built, branch `ccr-1c0a0374-t73ars`, 10 Oct 2026; waiting for the phone check)

| Piece | State |
|---|---|
| Focuses in conversations: FOCUS block (`core/FocusBlock.kt`, `FocusBlockTest`), focuses in Talk's dropdown, Start it puts the conversation in the new focus, mode cards into a focus, What I know: Edit summary, Pause, Done, Make active | done (part 1) |
| Quarterly chapters: `core/Chapters.kt` (`ChaptersTest`), `writing/ChapterWriter.kt` after each calendar quarter (only the latest finished one, only with 3+ reflected conversations), TextJobCheck before it is stored, CHAPTERS block (last two, older first) in every conversation, shown in What I know, Chapters | done (part 2) |
| Encrypted backups: `core/BackupFormat.kt` (AES-256-GCM over gzip JSON, PBKDF2-HMAC-SHA256 600,000 iterations in the header, header bound to the data, SHA-256 checked; `BackupFormatTest`), `backup/BackupRepository.kt` (write to their folder, read back and open before pruning to the last four, restore in one transaction, search and reminders rebuilt), weekly `BackupWorker`, Settings, Your data. `RestoreTest` (emulator): a backup from one database restores into another; a wrong passphrase changes nothing | done (part 3) |
| Export everything: one zip (Markdown plus JSON), not encrypted, with a warning first (`backup/Exporter.kt`) | done (part 4) |
| Health, in Settings: last run of each job, error codes counted over 30 days, interrupted replies, this month's calls and cache share, backup age, database size, the yearly restore test they mark (`ui/settings/HealthSection.kt`) | done (part 5) |
| Test bench checks: `core/TestBench.kt`, a 1:1 port of `tools/bench.py`; `BenchCheckTest` runs all 25 vectors of `contracts/bench_vectors.json` and parses every scenario of `prompts/testbench.md` | done (part 6) |
| The in-app test bench, Settings, Test bench (`bench/BenchRunner.kt`, `ui/settings/BenchSection.kt`): every scenario sent to the chat model the way a chat is built, off the record with only its Setup lines, scored with `TestBench`, failures first, tap to read the reply next to its Expect line; the score is kept per model and thinking level | done (part 7) |
| Prompt editor, Settings, Edit the coach's prompts (`ui/prompts/PromptEditorScreen.kt`): every prompt, saved only if it passes `PromptContract`, Reset to built-in, every conversation's context rebuilt after a change | done (part 8) |
| App lock and hide in recents (`security/AppLock.kt`, Settings, Privacy): Android's own fingerprint/face or screen-lock prompt, no extra library; asks on opening and after more than a minute away; a phone with no screen lock is never locked out; the recents card is blank (Android 13+) | done (part 9) |

Decisions in part 1: a focus "step" is a promise set from a conversation inside the focus (the schema has no
separate step table). Inside a focus the cards allowed stay those of the chat type that was selected (spec
section 5). Picking a plain chat type takes the conversation out of its focus. A focus is never deleted.

Decisions in part 3: the passphrase is kept sealed in the Keystore (like the API key) so the weekly backup can
run on its own; it is never shown again. The search index and the meaning vectors are not in a backup (they are
rebuilt after a restore). API keys are never in a backup: after restoring on a new phone, paste the key again.
A restore needs a backup of the same schema version (2 today). The restored settings keep this phone's folder.

What the owner checks on the phone for these changes:
1. Install the new debug APK over the old app (never uninstall).
2. Settings, Coach: the chat model reads `deepseek-flash` and Chat thinking shows Low. Press Test connection.
3. Ask the coach something: the reply should start sooner and type out evenly. Scroll up while it writes: the
   list stays where you are. Long-press the reply: Copy works; Details says "deepseek-flash", Thinking Low.
4. Focus: in Free talk, talk about something you want to work on over weeks; when the coach offers a focus card,
   tap Start it. The dropdown now shows the focus name. Set a small promise from that chat. Next day (or after
   a new conversation), open What I know, Focuses: the summary is written; Edit it, then try Pause and Make active.
5. Backups (stage E's "done when"): Settings, Your data. Choose a folder (Google Drive is fine; make a folder
   called "Clarity backups"), Set a passphrase and WRITE IT ON PAPER, then Back up now. The line must say
   "read back and verified". To test a restore without risking your data, install the APK on a second phone
   or an emulator, paste nothing, go to Settings, Your data, Restore from a backup, pick the file from Drive,
   type the passphrase, and check that your conversations, goals and What I know are there.
6. Export everything: save the zip, open clarity-export.md in any text app, and check it reads well.
7. Settings, Health: the jobs that ran show "done" with a time; "This month" shows the cache share (above 50%
   after a few messages in one conversation means DeepSeek's prompt cache works). After the restore test in
   step 5, tap "I did the restore test".
8. Settings, Test bench: Run it once (about 70 short calls). Look at the ones marked Fail first, then read a few
   marked Judge against their Expect line. The syllabus scenario ("how do i cover the 40...") is the one stage D's
   "done when" needs.
9. App lock: close Clarity, wait a minute, open it: it asks for your fingerprint. Open the app switcher: Clarity's
   card is blank. Choosing a backup folder (which leaves the app for a moment) must not ask again.
10. Prompt editor: Settings, Edit the coach's prompts. Open persona.md, delete {{USER_NAME}} and Save: it must refuse
    and say why. Press back without saving; nothing changes.

### E, the full list from the spec
- Focuses with rolling summaries, the FOCUS block, quarterly chapters.
- Backup: AES-256-GCM over gzip(JSON), PBKDF2-HMAC-SHA256 at 600,000 iterations, read back and
  checksum-verified before pruning, keep the last four; Export everything as Markdown plus JSON.
- The Health screen: last run of each job, counts of what was dropped or rejected, backup age, database size.
- Port `tools/bench.py` to `TestBench.kt` with `BenchCheckTest` over all 25 cases, and build the in-app test
  bench that runs `prompts/testbench.md` against the real model, storing results per model.
- The prompt editor (validating against `PromptContract`, with Reset to built-in), app lock, hide in recents.
- *Done when: an encrypted backup restores on a second device or emulator.*

### Quality audit after E (10 Oct 2026, branch `claude/app-quality-audit-okaiym`)

A full review of the app by six parallel reviewers, then fixes in small commits. What was found, what was fixed,
what still needs the owner's decision (the SQLCipher library, the Sunday alert default, gardening, Erase everything,
large backups, meaning-search cache) and what to check on the phone: `docs/AUDIT-2026-10-10.md`. CI now also runs
on `claude/*` branches. Contract change: `tools/appliers.py` count evidence must be copied from one CHECKIN_FACTS
line without cutting a number (one new vector, one new planted bug; 32 mutations, 0 survive).

### Audit follow-up (11 Oct 2026, branch `claude/audit-form-issues-u1v6hu`)

Every item the audit left open is fixed: the SQLCipher library (now `sqlcipher-android` 4.6.1), the Sunday
alert (on by default), monthly gardening, Erase everything, streaming backups (format 2; format 1 still
restores), the in-memory meaning-search cache, and the smaller items (the full drawer with search and rename,
the stored backlog creation day in schema version 3, the monthly review timing, Anthropic prompt caching).
Details and phone checks are in `docs/AUDIT-2026-10-10.md`.

### No backup provider (11 Oct 2026, the owner's decision)

The automatic switch to Anthropic after 3 failures in a row is gone: `Failover.kt`, its test, the backup
endpoint and its three settings were removed, and the spec and CLAUDE.md now say so. Only the provider chosen
in Settings, Coach is ever called. When DeepSeek is down, the reply fails with the usual quiet line and Try
again. Anthropic stays as a preset he can pick by hand in Settings, Coach; it is never chosen for him.
On the phone: nothing to set. With the internet off, send a message: the quiet error line and Try again appear,
and no other model answers.

### F. Freeze
Two weeks of real use, the test bench passing, the restore test done. Then tag `v1.0.0` and treat the Later
list (spec section 16) as closed until that is finished.

---

## How to start the next session

Stage E was started on 10 Oct 2026 at the owner's request, before the stage D phone check; that check (the list
under D) still has to be done, and anything it finds is fixed first.

Paste this into a new Claude Code session on this repository:

> Read CLAUDE.md, docs/LIFE-SYSTEM.md and docs/BUILD-STATUS.md (all of it). Everything up to the audit
> follow-up is on `main`; start from `main`. Fix whatever my phone checks of stages D and E found, in small
> parts: one feature per commit, each with a plain-language summary, pushed so CI checks it, and do not start the
> next part until CI is green. Rely on GitHub CI for the Android build and the emulator; do not install the Android
> SDK. Before you finish: update BUILD-STATUS and tell me exactly what I must check on my phone.

How the stage B, C and D sessions worked, so the next one does not relearn it:
- **No Android SDK on the build machine.** GitHub Actions builds, lints, runs the unit tests, builds the
  release APK with R8, and runs the emulator tests plus `tools/emulator_smoke.sh` on every push to a `ccr-*`
  branch (about 12 minutes). The run status is readable without a login:
  `curl -s https://api.github.com/repos/r8646582-cell/Clarity-Life/actions/runs?branch=<branch>&per_page=1`.
- **Pure Kotlin can be checked locally in seconds** with the plain Kotlin compiler (no SDK): download
  `kotlin-compiler-2.0.21.zip` from the JetBrains GitHub releases plus the kotlinx-serialization-json,
  junit and hamcrest jars from Maven Central, then compile `app/src/main/java/com/clarity/life/core/*.kt`
  with `app/src/test/java/com/clarity/life/core/*.kt` and run them with JUnit and
  `-Dclarity.repoRoot=<repo>`. Keep new logic in `core/` (no Android) so it can be tested this way.
- **Schema changes:** bump the version in `ClarityDatabase`, write the migration in `data/Migrations.kt`
  (SQL exactly as Room exports it), add a MigrationTest case, update `db/schema.sql`, `Values.kt`,
  `tools/check_schema.py` and the table counts. The first CI run then fails on purpose at "The database
  schema Room exported must be committed" and prints the new `N.json`; commit it exactly as printed.
- **Android's regex engine (ICU) is stricter than the JVM's:** escape `}` and `)` inside patterns. Stage A
  crashed on a bare `}` that desktop Java accepted.
- **Contracts:** a change to a Python reference needs a vector and, for a new class of bug, a mutation in
  `tools/mutation_check.py`; `bash tools/check_all.sh` must show 0 mutations surviving.
- **Memory exam:** `tools/eval/memory_exam.py` mirrors `core/Retrieval.kt`, `memory/SearchIndex.kt` and
  `core/MemoryBlocks.kt`. Change it with them. `--hybrid` needs a venv with onnxruntime, tokenizers, numpy.
- **Stage C notes:** promise cards already parse (`CardParser`) and render with Not now; Set it is the gap.
  Saving must go through one repository command per card type, one transaction, the card showing Saved from
  the real result. Resolve "when" in code (`PromiseTimes`) and show the time picker when ambiguous (between
  midnight and the 4 am day boundary) or unresolvable, never a guess. A note card saves as a note confirmed
  by them (their tap), with no evidence row; Forget leaves such notes alone by design.
- **Stage C:** the reason chips (`PromiseText.REASONS`) are the spec's list, ready for the check-ins of stage D. The
  due card is meant to show unanswered check-ins for today too (spec section 2.1); add them there in stage D.
  `PromiseTimesTest`, `PromiseTextTest` and the other core tests run locally in seconds with the plain Kotlin
  compiler (see above); `tools/eval/memory_exam.py --hybrid` worked with `pip install onnxruntime==1.20.1 tokenizers numpy`.
- **Stage D notes:** new pure code lives in `core/` (`PlanDates`, `PlanBlocks`, `PlanApply`, `ReviewFacts`,
  `ReviewTimes`, `LetterInput`) and runs locally with the plain Kotlin compiler in seconds. `kotlinx-serialization`
  jars from Maven Central can hit a rate limit; `repo.maven.apache.org` serves the same files. Background writing
  is in `writing/` (`ReviewWriter`, `LetterWriter`, `WritingWorker`); every run is safe to repeat because the job
  keys settle it. Each Sunday run is scheduled under a name that includes its time, so a run that schedules the
  next one never replaces (and cancels) itself.

