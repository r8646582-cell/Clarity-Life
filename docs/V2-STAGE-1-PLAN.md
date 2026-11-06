# Clarity v2: Stage 1 plan ("a chat that remembers")

Source: `docs/CLARITY-V2-BUILD-SPEC.md` section 12. Baseline before starting: `bash tools/check_all.sh` passes (checked 6 Nov 2026).
Each slice is a small commit, with its tests, and ends with a "what to check by hand" list.

| Slice | What | Needs owner approval first? |
|---|---|---|
| 1A | Prompts: install v2 persona, examples, reflection, gardening, letters, summary roll-up; merge `contract-entries.json` into `contracts/prompts.json`; run `check_prompts.py` | YES: owner approves the diff of every prompt file |
| 1B | Context packet: update `contracts/context.md`, `tools/context.py`, vectors, then `ContextBuilder.kt` (frozen: persona, examples, PROFILE, PEOPLE, NOTES; volatile: NOW, WORKING_ON, RELEVANT_PAST, EARLIER, LETTER, MESSAGE) | no |
| 1C | One chat per day (new `day_key` starts a new conversation, `chat_type = 'free'` only) and the quiet top line | no |
| 1D | New nav (Talk, Know, Letters); hide Plans, Today, Reviews, Story and card UI (hidden, not deleted) | no |
| 1E | Migration: `message.status` gets `pending`, daily usage counters, settings `daily_chat_limit`, `background_model`, `daily_nudge`; MigrationTest | no |
| 1F | Offline queue (WorkManager, unique work per conversation, max 2 retries, "Waiting to send") | no |
| 1G | Limits: 60 chats/day default, 12 background calls/day hard cap, nightly skip on empty day, rollover at 04:00 | no |
| 1H | Memory keeper: behaviour patterns (need a second day to rise above `guess`), venting rule (status change needs 2 days or a tap), applier vectors | no |
| 1I | Know: "Is this right?" card and groups (Patterns, About you, People) | no |
| 1J | Chat command "forget that" with confirm sheet | no |
| 1K | Update `CLAUDE.md` and `docs/BUILD-STATUS.md` (tabs, rules 1 and 7, prompt list, build order) | no |

Order: 1A and 1B first (they define what the coach sees), then 1E (schema), then 1C, 1D, 1F, 1G, 1H, 1I, 1J, 1K.

Stage 0 (the owner's 14-day test of `persona-v2.md` in a plain chat) comes first in the spec. If it has not been done, tell me and we adjust persona wording before 1A.
