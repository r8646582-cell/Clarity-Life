# CLAUDE.md: Clarity (Android life coach and life system)

You are building a private Android app for one person, its owner, who is a beginner. Explain what you change in plain language, give step-by-step instructions for anything they must do themselves (GitHub secrets, installing the APK, phone settings), and never assume they know Android or Git terms.

Read this file, then `docs/LIFE-SYSTEM.md` (the spec: scope is locked), then the files named below. If this file and the spec disagree, the spec wins; if the spec and a file in `contracts/` disagree, stop and ask the owner.

## What the app is
A lifetime life system: goals, plans, daily check-ins, patterns, conversations, held in an encrypted on-device database, with a coach (an AI) that talks. **The AI only talks. The app changes state only when the owner taps a button.** Three tabs (Talk, Plan, What I know) plus Settings. The UI follows the Bold mockups in `docs/mockups/` (start with its README; tokens also in spec section 2). Rebuild them in Compose; do not redesign them.

## Rules that never break (details in spec section 0 and 18)
1. No hidden actions. AI output reaches the database only through (a) the owner tapping a card button, which runs a repository command, or (b) a validated background applier (reflection, gardening). Background jobs never create cards and never change goals, plans, targets or promises.
2. Code does all dates, day counts, pace and every number that is saved or shown as a fact. The AI receives finished facts and never replaces them. In conversation the coach may do simple rough sums on numbers it was given, shown and called an estimate (the owner's decision, 11 Oct 2026); it never counts days on a calendar or works out a date (the app gives day counts in NOW), and no number the model worked out is saved as an app fact.
3. Fail closed at every AI boundary: bad cards, bad JSON, unverified quotes and numbers are dropped and logged without content; the prose still shows.
4. Only their own words and actions are evidence. A coach claim never raises a note's confidence.
5. Their deletions are final. A deleted note is never recreated.
6. Nothing leaves the phone except calls to the configured AI provider. No accounts, analytics, crash-reporting SDKs, or logging of message content or keys. The manifest sets `android:allowBackup="false"` (Android's own cloud backup would copy the database off the phone); their backups are only the encrypted ones they choose.
7. No streaks, badges or nagging. The only notifications are reminders they set by tapping a card, and the optional silent Sunday letter alert.
8. Never `fallbackToDestructiveMigration`. Every schema change = a migration + a MigrationTest. A failed migration stops with an error and never wipes data.
9. Nothing model-specific is hard-coded: provider, base URL, model names, embedding model and prompts are settings. Defaults live only in `DefaultSettings.kt`.
10. English only. Real names stay. No crisis or help-line screens (the owner removed them on purpose); the coach persona has its own short safety rule.

## The contracts: the source of truth you must implement and keep passing
Everything below already exists and passes (`bash tools/check_all.sh`). The Python files are executable specs; **port them to Kotlin 1:1 and make the Kotlin tests read the same JSON vectors**, so the app and the spec cannot drift.

| Contract | Spec file | Kotlin to write | Kotlin test (reads the same file) |
|---|---|---|---|
| Card parser and validation | `tools/cards.py`, `contracts/card_vectors.json` | `CardParser.kt` (pure) | `CardParserTest` runs every vector |
| Planner math, day keys, review options, weekly facts, PLAN_STATE text | `tools/planner.py`, `contracts/planner_vectors.json` | `Planner.kt`, `DayKey.kt`, `PlanStateText.kt` (pure) | `PlannerTest` runs every vector; also a test that the PLAN_STATE text equals the sample in `prompts/examples.md` |
| QuoteCheck and NumberCheck | `tools/textcheck.py`, `contracts/textcheck_vectors.json` | `QuoteCheck.kt` (extend the old one), `NumberCheck.kt` | `TextCheckTest` |
| Promise times ("when" on a promise card) | `tools/promise_times.py`, `contracts/promise_time_vectors.json` | `PromiseTimes.kt` (pure) | `PromiseTimesTest` runs every vector |
| Prompt rules and placeholders | `contracts/prompts.json`, `tools/check_prompts.py` | `PromptContract.kt`, used by the prompt editor and `PromptContractTest` | `PromptContractTest` |
| Context packet | `contracts/context.md`, `tools/context.py`, `contracts/context_vectors.json` | `ContextBuilder.kt` (pure) | `ContextBuilderTest` runs every vector (ordering, escaping, trimming, history window) |
| Background appliers | `tools/appliers.py`, `contracts/applier_vectors.json` | `ReflectionApplier.kt`, `GardeningApplier.kt`, `TextJobCheck.kt` (pure) | `ApplierTest` runs every vector |
| Test bench checks | `tools/bench.py`, `contracts/bench_vectors.json` | `TestBench.kt` (pure checks) | `BenchCheckTest` runs every vector and parses `prompts/testbench.md` |
| Database | `db/schema.sql`, `contracts/states.json` | Room entities and DAOs identical to the schema | `SchemaTest` (entities match), `StateMachineTest` (illegal transitions throw), `MigrationTest` |

If you must change a contract, change the spec file, the vectors and the Kotlin together, run all checks, and tell the owner what changed and why. If a vector looks wrong, say so; do not edit it just to make a test pass. `tools/mutation_check.py` plants known bugs in the Python references and fails if the vectors miss any; when you fix a new class of bug, add a vector and a mutation for it.

## Prompts
`prompts/*.md` are bundled into the APK as assets and filled by code (`{{PLACEHOLDERS}}`). They are the coach's mind. You may change them only when the owner asks or approves, then run `python3 tools/check_prompts.py`, then the in-app test bench on the real model. Never inline prompt text in Kotlin. The in-app prompt editor must validate any override against `contracts/prompts.json` before saving and offer Reset to built-in.

## Stack
Kotlin, Jetpack Compose, Material 3 (Bold theme, Space Grotesk bundled), single activity, Navigation Compose, Hilt, Room + SQLCipher (key = 32 random bytes in the Android Keystore), OkHttp SSE streaming, WorkManager, kotlinx.serialization, ONNX Runtime for the embedder (gte-base-en-v1.5 with CLS pooling, 768 dimensions, since Max plan Phase 4; bge-small-en-v1.5 before; verify against reference vectors). minSdk 26. Ask before adding any other dependency. Pin versions.

## Reuse from the old repo (read-only reference: `r8646582-cell/clarity-`)
Copy, adapt and test: `ai/OpenAiWire.kt`, `OpenAiCompatibleClient.kt`, `AiClient.kt`, `AnthropicClient.kt`, `AnthropicWire.kt`, `CancellableCall.kt`; `security/KeystoreCipher.kt`, `SecretStore.kt`; the SQLCipher open code from `PurposeDatabase.kt`; `promise/Reminders.kt`; `chat/ReplyService.kt`; `time/TimeFacts.kt`, `CalendarDays.kt`; `memory/QuoteCheck.kt`; `SearchDoc`/`SearchIndex`; `HybridRetrieval.kt` and embedder files (change pooling); `backup/`; `tools/eval/`. **Do not copy** ActionExecutor, ToolCalls, ReplyMarkers, ToolSchemas, growth tree, journeys, ledgers, screen time, cost and off-peak scheduling, onboarding, per-job models. Start clean: no data importer.

## AI client rules (learned the hard way)
- Streaming: buffered UTF-8 line reader; never decode raw chunks. Reasoning chunks count as heartbeats and reset the 120 s gap timer. Handle `finish_reason: length` and error objects. Connect timeout 30 s; background calls read timeout 300 s.
- Thinking tokens may count toward max tokens: set chat output max high (at least 16,000 or the provider ceiling). No reply length cap in the app.
- Check current DeepSeek model IDs and the base URL (`https://api.deepseek.com`) from its official docs at build time; put them only in `DefaultSettings.kt`.
- Cache: follow `contracts/context.md` exactly. The system message (persona, examples, cards, chat-type file, then the frozen context blocks) is built when a conversation starts, stored in `conversation.prefix_json`, and rebuilt only when they switch chat type or resumes on a later day. Volatile blocks (NOW, PLAN_STATE, OPEN_PROMISES, CHECKIN_FACTS, CARD_RESULTS, EARLIER, RELEVANT_PAST) go in the final user turn, so a card saved mid-conversation is seen by the next reply. Record cached-token counts in `usage_month.tokens_cached` to confirm the cache works.
- Replies survive leaving the app: foreground `ReplyService`, partial text saved about once a second. Do not use the `shortService` type for it: Android stops a short service after about 3 minutes, and a reply with thinking and no length cap can run longer. Use `dataSync` (with `FOREGROUND_SERVICE_DATA_SYNC`), implement `onTimeout` by saving the partial reply as interrupted, and stop the service as soon as the reply ends. If the process dies anyway, `streaming` becomes `interrupted` on next launch (startup repair sweep), with a Try again button. Every request ends in complete, interrupted or failed; no stuck spinner.
- No backup provider (the owner's decision, 11 Oct 2026). Only the provider they chose is called; when it is down the reply fails with a quiet line and Try again. Never add automatic switching to another model.

## Data and time
- Store UTC milliseconds and the local `day_key` at write time (day boundary default 04:00, a setting). Changing time zone never moves old results.
- A missing check-in is "no answer", never a miss. Rest days are neither.
- Derived data (search index, embeddings, review facts) must be rebuildable from raw rows; provide Rebuild buttons in Settings, Advanced.
- Background jobs use the `job` table: deterministic key, unique index, state machine in `contracts/states.json`. Re-running must be a no-op.

## Phone reliability (Xiaomi / HyperOS)
Settings, Reminders: battery unrestricted, autostart, exact alarm status with buttons that open the right system pages, plus plain instructions. Reschedule reminders after reboot. Test reminders on a real device before calling stage C done.

## Build order (finish and verify each stage before the next; spec section 14)
A. **Skeleton:** project, Bold theme, encrypted DB from `db/schema.sql`, Settings (provider, key, model names, Test connection), Talk with streaming, persona + cards prompt assembled by `ContextBuilder`, conversations list. *Done when:* a real chat works and survives restart and an app switch; CI green.
B. **Memory:** reflection job, notes with evidence, What I know, FTS search, then embeddings. *Done when:* a fact from chat 1 appears in chat 2 and in What I know.
C. **Promises and cards:** `CardParser`, promise cards, reminders, the due card on Talk. *Done when:* a reminder fires on their phone and nothing saves without a tap.
D. **Planner:** goals, targets, backlogs, check-ins, `Planner`, plan cards, Reviews (week and month), letters. *Done when:* the syllabus scenario in the test bench passes on the real model and all planner vectors pass.
E. **Focuses, chapters, backup, export, Health, test bench.** *Done when:* an encrypted backup restores on a second device or emulator.
F. **Freeze:** the owner uses it for two weeks; the bench passes; the restore test is done. Then v1 is final. Anything else goes on the Later list.

## Before every commit
1. `bash tools/check_all.sh` and `bash tools/forbidden_patterns.sh`.
2. In the Android project: `./gradlew lintDebug testDebugUnitTest` (and the migration tests when the schema changed).
3. Small commits, one feature at a time, each with a plain-language summary. Never commit keys, keystores, `local.properties` or `.env`.
4. If you cannot run something (no emulator or device), say so plainly and list what the owner must check by hand. Do not claim it works.

## Git and release
Repo is private. A version tag (`v1.0.0`) builds the signed release APK in GitHub Actions and attaches it to a release. Signing material lives only in GitHub Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); give the owner step-by-step instructions to create the keystore and add the secrets on the GitHub website, and remind them to store the keystore and passwords safely outside the repo. They update by installing over the old app, never by uninstalling. Bump `versionCode` and `versionName` each release and show the version in Settings, Advanced.

## What you must not do
Do not add features that are not in the spec. Do not write AI-side date logic, or arithmetic beyond the rough conversational sums rule 2 allows. Do not make prompts or contracts "more flexible" without being asked. Do not hide failures: show a quiet, honest line and log a content-free error. Do not weaken a check to make a build pass.
