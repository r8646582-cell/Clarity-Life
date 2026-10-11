# Review of 10 October 2026: what changed and why

Everything below was found by reading every file in the seed, then fixed, with a test added for each fix. `bash tools/check_all.sh` passes. Each fix that guards against a bug also has a planted-bug test in `tools/mutation_check.py`, which proves the tests would catch that bug if it ever came back.

Nothing here touches the Android app, because it does not exist yet. It changes the specifications the app will be built from, so these bugs never reach your phone.

## 1. Places where the safety checks let bad things through
The design rule is "fail closed": when in doubt, throw it away. These checks did the opposite.

| What was wrong | What could have happened | Fix |
|---|---|---|
| A plan card was accepted with **any** review date when the app had not offered review options. | The coach could invent a review date and it would be saved. | No offered options means the plan card is dropped. |
| The card parser accepted `NaN`, `Infinity`, huge numbers and JSON with the same key twice. | A target of "Infinity lectures"; Python and Kotlin disagreeing about which duplicate key wins. | Strict JSON, finite numbers up to 100,000. |
| Cards allowed `0x/week` and `99x/week`, which the planner then crashed on. Spaces and one-day ranges ("Sat-Sat") were handled differently in two places. | A saved target that breaks the Plan screen. | One schedule grammar, shared by cards and planner. |
| A backlog card could "adjust" a backlog while changing nothing; a target change could set the minimum above the target, or the target to zero. | Pointless or impossible saved changes. | Rejected. |
| QuoteCheck skipped any quotation that ran over a line break, and ignored unbalanced curly quotes. | A letter could put invented words in your mouth, unchecked. | Every quotation is checked; anything unreadable fails. |
| QuoteCheck matched inside words: "tired" was found in "retired". | A false quote passes as yours. | Quotes must start and end on word boundaries. |
| Single curly quotes ('like this') were never checked. | Same as above. | Checked. |
| NumberCheck only looked at digits. "About thirty-seven lectures" passed. | Invented figures in letters and reviews. | Numbers written in words are checked too; the prompts now say so. |
| Weekly facts counted reasons from "Part" days but reported them as "of N misses". | "tired 3 of 2 misses": a number that cannot be true, handed to the coach. | Reported as "tired on 3 of 3 days marked Not or Part", and never above the real count. |
| "Best fortnight" was missing whenever your tracked history was younger than 14 days, even when the typical pace existed. | The coach told only half the picture. | The recent window always counts, so best is never below typical. |

## 2. Numbers that flattered the plan
The coach copies the app's numbers, so rounding matters. At 0.6 lectures a day for 36 days you reach 21.6 lectures; the old code said "reaches 22". A required pace of 1.11 a day was shown as "1.1", but 1.1 a day does not reach the total. In the worst case, 0.99 a day for 40 days was reported as reaching 40 of 40 and "ok".

Now: the required pace is rounded **up**, "reaches" is rounded **down**, and a new code-written **Status** line says plainly whether the total is reached at your usual pace, only at your best fortnight, or not at all. The coach is told its words must agree with that line. The syllabus example now says 21 (which, interestingly, is what the spec itself always said).

## 3. Missing specifications for the riskiest code
The context packet, the reflection and gardening appliers (the only way AI output reaches your database without a tap) and the test bench checks had descriptions but no executable reference and no test vectors. They now have them:
- `tools/context.py` + `contracts/context_vectors.json`: order, escaping, trimming, the history window.
- `tools/appliers.py` + `contracts/applier_vectors.json`: new notes always start as a guess; the model can never mark anything "confirmed" (only your tap can); confidence rises one step at most and only with new evidence in your own words; notes you edited or confirmed are untouchable; a note close to one you deleted is never re-created; your confirmed profile facts change only with your exact words.
- `tools/bench.py` + `contracts/bench_vectors.json`: exactly how "one question at most", "no card", "numbers OK" and the rest are counted, so the phone and the spec agree.
- `tools/mutation_check.py`: 20 planted bugs; the build fails if any one is not caught.

## 4. The context packet: cheaper, and never out of date
- **Out-of-date plan numbers.** The plan numbers and open promises were frozen at the start of a conversation. If you saved a plan halfway through, the coach kept seeing the old one and could suggest it again. They are now refreshed with every message, along with a new CARD_RESULTS line (what you did with the last cards: saved, not now, or no answer yet).
- **Changing chat type.** Switching chat type mid-conversation contradicted the rule that the prompt never changes. Now the prompt is rebuilt when you switch type, and when a conversation is reopened on a later day.
- **Cache-friendly order.** The persona and the examples now come first, so the provider can reuse about 12k tokens across all your conversations. That makes replies faster and cheaper.
- **Size limits that add up.** The old "12k tokens for everything" could not hold, because the prompt alone is about 12k. Each part now has its own limit. History is a window that moves forward in jumps, so the cache keeps working for long conversations.
- **Spoofed headers.** A pasted text with a line like "## NOW" is neutralised, inside your messages too.

## 5. Prompts and examples
- **The examples copied your real life.** They used details from the owner's real life (family words, exam and focus), while telling the model "everything here is invented". That is how a coach ends up "remembering" things that never happened to you, the exact failure you remember. The examples now use an uncle, chemistry lectures and a fear of driving. They teach the same skills and cannot be mistaken for you. The test bench still uses your real situation, and now it checks whether the coach can transfer those skills, not copy them.
- Example 2 set a reminder you never asked for, against its own rule. Fixed, and the checker now catches that mistake in any example.
- Example 10 said "so the chain never breaks", which is streak thinking in an app with no streaks. Fixed.
- **The 0 to 10 question.** "Always ask 0 to 10 before any promise" would turn into a ritual. Now it is asked when the idea came from the coach or your wanting is unclear, never twice in one conversation, and not when you volunteered the commitment yourself.
- **After midnight.** At 12:40 am, "tomorrow" is ambiguous, because your day counts as yesterday until 4 am. The coach now writes the weekday name in cards, and the app shows the exact day and time on the card before you tap.
- Letters may not put a thinker's words in quotation marks (only your words get quotation marks), and every background prompt says that numbers in words are checked too.
- Gardening was asked to work out "60 days" itself. The app now marks notes that have gone quiet, so the AI does no date maths.
- Reflection can no longer "confirm" anything, and fixing a confirmed fact now requires your exact words.
- A new example (21) and 6 new test scenarios: being asked to calculate, a plan that works only at your best pace, cards you saved or declined, a promise after midnight, and "Talk about this letter".

## 6. Database
- New `rest_day` table. Planned rest days were part of the maths but had nowhere to be stored.
- `backlog.start_done`: for work you finished before you started tracking (for example, 6 of 50 lectures already done).
- Deleting a target can no longer wipe its check-in history. Before, the database deleted the history along with the target.
- "Forget this conversation" now also removes the evidence quotes taken from it. Before, your words from a forgotten chat survived inside notes.
- A deleted letter keeps only its key, so it is never regenerated; its text is cleared.
- Settings never hold API keys. They stay in the Keystore.

## 7. Build and release safety
- The forbidden-pattern check now also scans the Gradle build files, where an analytics SDK would actually be added. It also rejects `allowBackup="true"`: Android's own cloud backup would quietly copy your database off the phone. The manifest must say `allowBackup="false"`.
- CI now has least-privilege permissions, time limits, KVM for the emulator, saved test reports on failure, a check that the signing secrets exist, deletion of the signing key after use, and a SHA-256 checksum next to every released APK.
- `CLAUDE.md`: the reply service must not use Android's "short service" type, because Android stops it after about 3 minutes and a long thinking reply would be cut off.

## What I could not do or verify
- No Android app exists yet, so nothing was built or run on a phone. All checks here are the Python references and their vectors.
- No prompt was run on the real model. Run the test bench after the app exists, as the spec says.
- The 3-minute short-service limit and the cache behaviour of DeepSeek and Anthropic should be confirmed against their current documentation when the app is built.
