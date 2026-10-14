# Bug scan, 14 Oct 2026 (full scan, Max plan Phase 0)

Scanned: branch `ccr-d44251b7-varbwn` at `1c3c8c3`. That is `main` (Clarity 0.1.12, `ef4b35f`) plus `docs/MAX-PLAN.md`.
This is step 2 of Phase 0 in `docs/MAX-PLAN.md`. It's a whole-app scan. The earlier report from today (`bug-scan-2026-10-14.md`) covered only the coach's memory, and all of its findings are fixed. The 13 Oct scan's nine findings are fixed too, so they aren't repeated here.
No code was changed in the scan itself. **All four findings were fixed afterwards on 14 Oct 2026 at the owner's request**; see "Fixed" below.

## 1. Checks run

| Check | Result |
|---|---|
| `bash tools/check_all.sh` | Passed: every vector, prompt check and schema check |
| `bash tools/forbidden_patterns.sh` | Passed |
| `python3 tools/mutation_check.py` | Passed: 58 planted bugs, all 58 caught |
| `./gradlew lintDebug testDebugUnitTest` | **Not run here, on purpose.** You asked to use GitHub instead of installing the Android SDK in this cloud session. The latest CI run on `main` (run #120, commit `ef4b35f`, the same app code) **passed** every job: contracts, lint and unit tests, minified release, and the emulator. |
| Real screens (step 2b) | **Could not run.** No emulator or phone is connected here. Nothing below comes from screenshots. |
| Design check against the mockups (C2) | **Not done in this pass.** Phase 7 of the plan adds screenshot tests, which do this properly. |

What I read closely: start-up (`MainActivity`, `StartupRepair`), the reply service, the card step, LAST_CHAT and search (new in 0.1.12), backup, restore and export, migrations, reminders and the restart receiver, Erase everything, settings and the live day, the prompt store, What I know, Your story (chart and tiles), and the Plan forms with the planner writer. I also searched the whole app for phone-clock dates, forced `!!` values and `.first()` on lists.

## 2. Findings

| # | Type | What I would see | When it happens | Where (file:line) | Why | Smallest fix | Severity |
|---|---|---|---|---|---|---|---|
| 1 | bug (no stuck spinner rule) | The coach's bubble keeps showing "thinking" forever. Send in that chat does nothing, until the app is fully closed and reopened. | Reading your API key fails at the moment you send. The key is sealed by the Android Keystore, and on some phones the Keystore throws an error now and then (more often after a system update or a change to the screen lock). | `chat/ReplyService.kt:103` (`endpointForChat()` is called before the `try`); `ui/talk/TalkViewModel.kt:325` | The error escapes the reply's own error handling. The service's last-resort handler ends the live stream, but it never marks the message failed, so the row stays `streaming`. Talk treats a `streaming` row as "a reply is being written" and holds Send. Only the start-up repair, on the next fresh launch, settles it. | Move the key read inside the `try`, and make the last-resort handler also mark the row failed (with Try again). Add a test with a key store that throws. | medium |
| 2 | bug | Rarely, after a phone restart or a forced stop, a reminder isn't set again, a chat's summary isn't retried, or a missed Sunday letter or review isn't caught up until the app is next closed and reopened. | You turn the phone sideways, or Android switches dark or light mode, during the first moments after you open the app. | `MainActivity.kt:68-84` | The start-up work runs in the screen's own lifetime (`lifecycleScope`). Turning the phone rebuilds the screen and cancels that work partway. The "once per app run" flag is already set, so it doesn't start again. | Run the start-up work in an app-wide scope that outlives the screen, not in `lifecycleScope`. Add a test. | medium |
| 3 | bug | After you travel to another time zone, Talk home and Plan keep showing the old zone's date and "today". The coach (which reads the zone fresh on every message) uses the new one. So a "Did it" tap can save to a different day than the one the coach is talking about. | You change time zone with the app open in the background (a flight, or setting the zone by hand), then come back to the app. | `settings/SettingsRepository.kt:74-78, 133-136` | `watchDay()` only gives a new value when a settings row changes. The time zone isn't a stored row (it comes from the phone), so a change of zone never reaches the screens. This is the same kind of bug as the 13 Oct finding 1, for the zone instead of the day boundary. | Also give a new value when Android announces a zone change (`ACTION_TIMEZONE_CHANGED`), or read the zone again whenever the app comes back to the front. Add a test. | low |
| 4 | bug | A Focus page says "Focus · week 3" when by your own day it's still week 2, or the other way round. | Only right around midnight, if "day ends" is set later than midnight, or after travel. | `ui/know/KnowScreen.kt:582-586` | It counts weeks with the phone's calendar date, not your day key (the rest of the app uses the day key). | Count from the day key (`Planner.dayKey`), like Plan does. | low |

That's all I could prove. The app is in good shape: four findings, none of them data loss or privacy, against nine and twelve in the two earlier scans.

### Fixed (14 Oct 2026, branch `ccr-d44251b7-varbwn`, CI run #124 green)

| # | What changed | Test |
|---|---|---|
| 1 | The key is read inside the reply's error handling (failed, with Try again). The service's last resort settles any row still streaming: interrupted if words arrived, failed if not. A reply already complete is never turned into a failure. | `ReplySettleTest` (emulator) |
| 2 | The start-up sweep runs in the app's own scope (`StartupOnce`), which a turned screen does not cancel. | `StartupOnceTest` |
| 3 | The live day also follows the phone's time zone, read again each time the app comes to the front. | `SettingsDayTest` |
| 4 | The Focus week counts from the day key it was made on to today's day key (`FocusBlock.week`). | `FocusBlockTest` |

## 3. Unsure (could not prove; how to check)

- **Export on years of data.** `backup/Exporter.kt:55` builds the whole Markdown file (every conversation, every message) as one block of text in memory before it writes anything. The JSON half is streamed. After years of use, that block could be large enough to run the phone out of memory, and the export would fail with a quiet line. *Check:* the 10-year synthetic database from Phase 0 step 4, exported on the emulator. The plan's Phase 1 step 7 ("readable forever" export) is the natural place to stream it.
- **Export also runs one database query per goal, per note and per conversation.** On years of data that may be slow. The same timing harness will show it.
- **`PromptStore.fill` replaces placeholders one after another.** If a value it fills in itself contains `{{SOMETHING}}` (for example, you typed those exact characters in a chat that goes into the reflection prompt), a later placeholder could be filled inside your words. It's very unlikely and I couldn't make it matter, so it isn't listed as a bug.

## 4. Needs my phone

1. **Finding 2:** close Clarity fully (swipe it away in Recent apps). Set a promise reminder 10 minutes ahead first. Restart the phone. Open Clarity and turn the phone sideways at once, then back. Wait for the reminder. If it doesn't ring, finding 2 is the likely cause.
2. **Finding 3:** with Clarity open in the background, go to the phone's Settings, Date and time, switch off the automatic time zone, and pick a zone where it's already tomorrow. Go back to Clarity: if Talk home still shows today's date, finding 3 is confirmed. Put the automatic zone back afterwards.
3. **Reminders in general** (as in earlier scans): a reminder 3 minutes ahead with the phone locked, and once more with battery saver on.

## 5. Not bugs but ideas

These are already in `docs/MAX-PLAN.md`, so nothing new is added here: a streamed export (Phase 1.7), a self-check that every future reminder has an alarm (Phase 1.8), screenshot tests against the mockups (Phase 7, needs a new dependency and your OK).
