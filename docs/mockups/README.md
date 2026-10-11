# UI mockups (Bold design, final for v1)

These are the screens to build, exported from the design canvas "Clarity Talk Mockups". Each file is one phone screen (390 x 844 px; Settings is the full scroll). They are HTML design files: open them for layout, spacing, colors, copy and states. Do not ship them; rebuild each screen in Jetpack Compose.

All names and numbers are sample data. The numbers follow the planner contract (`tools/planner.py`): required pace rounded up, "reaches" rounded down, and a Status line.

| File | Screen |
|---|---|
| Talk.dc.html | 1. Talk: due card, conversation, suggested promise card and its Saved receipt |
| ChatType.dc.html | 2. Chat type picker (bottom sheet), focuses |
| PlanChat.dc.html | 5. Plan chat with a plan card (what will be saved, shown in full) |
| Drawer.dc.html | 12. Conversations drawer: new, off the record, search, rename, delete, forget |
| TalkStates.dc.html | 13. Saved card, interrupted reply with Try again, backup provider line (not built: there is no backup provider, the owner's decision of 11 Oct 2026), unsent message with Retry |
| TalkLetter.dc.html | 9. Talk with the new-letter card, unread dot on Plan |
| Today.dc.html | 3. Plan, Today: Did it / Part / Not, amount stepper, reason chips, rest day |
| Goal.dc.html | 4. Goal with pace: need vs usual, where each pace lands, Status line |
| Promises.dc.html | 14. Plan, Promises: needs an answer, coming up, past outcomes |
| Review.dc.html | 6. Weekly review: day-by-day grid, reasons, coach note, re-plan flag |
| Reviews.dc.html | 10. Plan, Reviews: weeks (letter + numbers), delayed letter, months |
| Letter.dc.html | 11. The weekly letter (reading view) |
| Know.dc.html | 7. What I know: evidence, grades (guess, likely, confirmed), confirm, delete |
| Focus.dc.html | 8. Focus: rolling summary, graded steps with anxiety before and after |
| Settings.dc.html | 15. Settings, full scroll (coach, letters, reminders, data, backup, advanced). The "Backup provider" row here and in SettingsCoach is not built (no backup provider, the owner's decision of 11 Oct 2026) |
| SettingsCoach.dc.html | 16. Settings, Coach: provider, base URL, key, model names, test connection |
| Reliable.dc.html | 17. Keep reminders reliable on Xiaomi / HyperOS |

Tokens: ground #14121A, surface #2A2535, raised #352F43, text #FFFFFF, muted #B8B3C4, yellow #FFD23F, coral #FF6B5B (cards and warnings), danger text #FF8A7C, hairline #3D3749, control outline #6E6782, tab bar #0D0B12. Font: Space Grotesk. Touch targets at least 44 px. Suggestion cards are coral; after a tap they become a dark Saved receipt built from the real database result.
