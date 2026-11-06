# Prompts v2

The complete set of prompts for the chat-first Clarity, written to match `docs/CLARITY-V2-BUILD-SPEC.md`. They are drafts in `docs/prompts-v2/` so the current app and its contract checks keep working until stage 1 starts.

## Install (stage 1, with the owner's approval of each file)
1. Copy each file into `prompts/`, replacing the old one of the same name. `persona.md` here equals `persona-v2.md` in the repo root.
2. Merge `contract-entries.json` into `contracts/prompts.json` (`prompts` object): placeholders, `must_contain` phrases that exist in each file, and `max_words`. Add the new block names to `block_names`: `WORKING_ON`, `APP_FACTS`, `LETTER_FACTS`, `QUOTES`, `ITEM_NAME`, `ITEM_OUTCOME`, `ITEM_HISTORY`, `ITEM_QUOTES`, `ITEM_SUMMARIES`, `PERIOD_LABEL`, `REPLY`. Remove block names whose code is gone (`FLAGS`, `FOCUS`, `CHAPTERS`, `PLAN_STATE`, `OPEN_PROMISES`, `CHECKIN_FACTS`, `CARD_RESULTS`, `IDEAS_RECENT`, `REVIEW`, `RECENT_SESSIONS`, `LAST_CHAT`).
3. Delete `cards.md`, `card_followup.md`, `mode_*.md`, `brief_daily.md`, `story.md`, `chapter.md`, `review_weekly.md`, `review_monthly.md` once their code is removed. The forbidden-everywhere list (em and en dashes) still applies; none of these files contain one.
4. Update `contracts/context.md` and `tools/context.py` / vectors: Part 1 is `persona.md` then `examples.md` only. Part 2 (frozen): PROFILE, PEOPLE, NOTES. Part 3 (volatile): NOW, WORKING_ON, RELEVANT_PAST, EARLIER, LETTER, MESSAGE.
5. Run `python3 tools/check_prompts.py`, then the test bench on the real model.

## The prompts and who uses them
| File | Used by | Output | Checked by code |
|---|---|---|---|
| `persona.md` | every chat reply | plain text | Reply is plain text; no "saved" claims are possible because nothing is saved by the AI |
| `examples.md` | every chat reply (after persona) | none | none |
| `reflection.md` | memory keeper, after each conversation (on `background_model`) | JSON (see below) | applier: quotes verified, evidence required, cap of 3 active items, deadline dates parsed by code from `deadline_quote`, venting never changes state, status changes need 2 days or a tap, one-day patterns stay `guess` |
| `gardening.md` | monthly tidy | JSON | existing `GardeningApplier` rules |
| `summary_rollup.md` | monthly and yearly roll-up of conversation summaries | text | none beyond length; the app keeps the originals until the roll-up exists |
| `letter_weekly.md` / `letter_monthly.md` / `letter_yearly.md` / `letter_milestone.md` | letters | text; first line is the title; empty text means "no letter" | `QuoteCheck`, `NumberCheck`, length |
| `reply_check.md` | optional quiet reply-checker (stage 5) | JSON | fail open: if the checker fails, show the reply |

## Reflection output changes from the old prompt
- Removed: `focus_summary`, check-in facts, chat type, cards.
- Added: `venting` (list of exact quotes said in a low moment), `working_on` with `propose`, `update` and `mentioned`.
- `deadline_quote` is the owner's exact words about a date. Code parses it with the existing date logic. The model never writes a date.
- The applier must reject any `working_on.update` with change `paused` or `dropped` unless the owner's quote is on at least two separate days or the owner confirmed it by tap (venting overrides).

## Placeholders the app must supply
- `WORKING_ON`: one line per item: name, status, confirmed or unconfirmed, deadline and "N days left" only when the deadline is confirmed (otherwise `deadline: unknown`), the owner's quote and its day, last mentioned day.
- `LETTER_FACTS` / `APP_FACTS`: plain statements counted by code, for example "You talked to me on 5 of the 7 days.", "You paused: Clarity app.", "You said you finished 12 lectures (on 3 November)." Never anything the code did not count.
- `QUOTES`, `ITEM_QUOTES`: the owner's exact words with their dates, chosen by retrieval and verified to exist.
- `ITEM_HISTORY`: the dated events of one working-on item and the day counts from code.

## Principles these prompts follow (so later edits keep them)
1. The AI talks, code counts. No prompt asks the AI to calculate a date or a figure that is saved or shown as fact.
2. Only the owner's words and actions are evidence. A mood is not a decision.
3. Describe what happened and what it cost, never the owner's character.
4. Simple English for a tired mind.
5. Empty is better than invented: every prompt allows "nothing to say".
