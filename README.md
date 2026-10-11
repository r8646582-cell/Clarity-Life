# Clarity

**A private life coach and life system for Android.**

Clarity keeps your goals, plans, daily check-ins and conversations in one place on your phone, and gives you an AI coach to talk them through with. Over weeks and months the coach comes to know you: what you are working towards, what helps you, what gets in your way. It knows this only from what you tell it and what you do.

Two promises sit underneath everything:

- **The coach only talks. Nothing changes until you tap a button.** When the coach suggests something (a promise, a plan, a note about you), it appears as a card. Nothing is saved until you tap **Set it**.
- **Your life stays on your phone.** Everything is kept in an encrypted database on the phone. There is no account, no sign-up, no ads, no analytics and no tracking. The only thing that leaves the phone is the conversation sent to the AI provider you choose, using your own key.

---

## Contents

1. [What you need](#1-what-you-need)
2. [Install the app](#2-install-the-app)
3. [First-time setup (5 minutes)](#3-first-time-setup-5-minutes)
4. [The three tabs](#4-the-three-tabs)
5. [Cards: how anything gets saved](#5-cards-how-anything-gets-saved)
6. [A day with Clarity](#6-a-day-with-clarity)
7. [Settings](#7-settings)
8. [Backups and your data](#8-backups-and-your-data)
9. [Updating the app](#9-updating-the-app)
10. [Troubleshooting](#10-troubleshooting)
11. [Privacy in plain words](#11-privacy-in-plain-words)
12. [For developers](#12-for-developers)

---

## 1. What you need

- **An Android phone** running Android 8.0 or newer, with a 64-bit processor (almost every phone from the last several years).
- **An AI API key.** The coach is a large language model that runs on a provider's servers. By default Clarity uses **DeepSeek**. You pay that provider directly for what you use; the cost per conversation is usually small. Clarity itself is free.
- **About 200 MB** of free space.

### Getting a DeepSeek API key

1. Open **platform.deepseek.com** in your browser and create an account.
2. Add a small amount of credit (the **Top up** page).
3. Open **API keys**, tap **Create new API key**, and give it any name.
4. Copy the key (it starts with `sk-`). You will paste it into Clarity in a moment.

Keep the key private, like a password. Anyone with it can spend your credit.

Prefer another provider? Clarity also works with **Anthropic** and with any **OpenAI-style** provider. You choose this in Settings (see [section 7](#7-settings)).

---

## 2. Install the app

1. On your phone, open this project's **Releases** page (the **Releases** link on the right of the main page, or under the code on a phone).
2. Under the newest version, tap **app-release.apk** to download it.
3. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or file manager. (This is normal for apps that do not come from the Play Store.)
4. Tap **Install**, then **Open**.

Want to be sure the download was not changed? Each release also has a `SHA256SUMS.txt` file. Its code should match the SHA-256 of the APK you downloaded.

---

## 3. First-time setup (5 minutes)

1. **Open Settings** (the gear at the top of the screen).
2. Under **Coach**:
   - **Main provider:** leave it on **DeepSeek** (or pick yours).
   - **API key:** paste the key you copied.
   - Tap **Test connection**. You should see a line saying it connected and the model answered. If not, see [Troubleshooting](#10-troubleshooting).
   - **Your name** (optional): type the name you want the coach to use. Leave it empty and the coach will simply learn about you as you talk.
3. **My day ends at:** if you often stay up past midnight, this decides when "today" becomes "tomorrow". The default is 4 am, so a check-in at 1 am still counts for the day before.
4. **Reminders:** open **Keep reminders reliable** and follow the steps there (see [section 7](#reminders) for why).
5. **Your data:** set a **backup passphrase** and choose a **backup folder**. Write the passphrase down on paper. More in [section 8](#8-backups-and-your-data).

That is it. Go to **Talk** and say hello.

---

## 4. The three tabs

### Talk: conversations with the coach

This is where you think out loud. Type anything: how your day went, a decision you are stuck on, a goal you want to reach.

- **Chat type** (the menu at the top). Each type changes how the coach approaches the conversation:
  - **Free talk**: anything at all. The coach listens, asks, and helps you see clearly.
  - **Untangle**: when something feels knotted or heavy. The coach helps you make sense of it and asks you to commit to nothing.
  - **Decide**: for a choice between options. The coach helps you weigh them and hears every side, but never picks for you.
  - **Plan and track**: for goals and plans. The coach sees your real numbers (what you have done, your pace, days left) and helps you make a plan that can actually work.
  - **A Focus**: each long-running topic you follow (see below) gets its own entry, so the coach picks up where you left off.
- **Replies keep coming when you leave the app.** You can switch apps while the coach writes. If a reply gets cut off (no signal, the phone closed the app), it is marked and a **Try again** button appears.
- **Conversations** (the list button): every conversation, grouped by day, with search. Each conversation can be **Renamed** or **Forgotten**. Forget removes it, and the notes that came only from it, for good.
- **Press and hold a message** to **Copy** it or see its **Details**.
- **The due card** at the top of Talk shows promises that are due and check-ins waiting for an answer, so you can answer them in one tap.
- **The letter card** appears when your weekly letter is ready.

### Plan: goals, check-ins and reviews

- **Today**: your check-ins for the day. For each target, tap **Did it**, **Part** or **Not**. Add an amount if the target counts something (pages, lectures, minutes). After **Part** or **Not** you can tap a reason ("What got in the way?") and add an optional line. Not answering is fine: a missing check-in is "no answer", never a failure. Mark a **Rest day** and nothing counts against you.
- **Goals**: everything you are working towards, at any size, from "this month" to "five years". Each goal shows its plan, its targets and an honest pace line. A **target** repeats (for example "study 1 to 2 hours, Saturday to Thursday", with a minimum for bad days and a target for normal days). A **backlog** is a fixed amount by a date (for example "40 lectures by 20 November"), with the pace you need next to the pace you actually keep.
- **Plan with coach** opens a Plan and track chat with all those numbers in front of the coach.
- **Promises**: one-off commitments ("call the bank tomorrow at 11"). When one is due, answer **Done**, **Not done**, **Moved** or **Let go**.
- **Reviews**: a weekly review and a monthly review. The app counts the numbers itself and the coach adds a short, honest note about what they mean. If two reviews in a row show a plan cannot finish on time, you will see **The plan may be off.**
- **Letters**: every Sunday evening the coach writes you a short letter about your week: one true thing you might not have seen from inside it, and one small thing to try. Every quote in it is checked word for word against what you actually wrote.

**The numbers never flatter you.** The app, not the AI, does all the arithmetic. It judges pace by what you have actually done recently, not by what you hope to do, so a plan that cannot work is called out early instead of at the deadline.

### What I know: everything the coach has learned

This tab is the coach's notebook about you, fully open to you.

- **Profile**: the lasting facts about you.
- **Patterns**, **What helps**, **What does not help**: what the coach has noticed over time.
- **Open threads**: things that are still unresolved for you.
- **People**: the people who matter in your life.
- **Focuses**: long-running topics you follow (for example "public speaking", "the final exam", "the job decision"). Each has a summary that grows after every conversation in it, so months of work never reset.
- **Chapters**: every three months, a short chapter of your life with the coach.

Each note shows **where it came from**: your own words, quoted with the date, or a count the app made. The coach's opinion never counts as evidence. A note marked as a guess only becomes "confirmed" when you tap **That's right**.

You can **edit** any note, or **delete** it. **Deletions are final:** a deleted note is never recreated.

---

## 5. Cards: how anything gets saved

At the end of a reply the coach may suggest something as a **card**:

| Card | What it does |
|---|---|
| **Promise** | A one-off commitment, with a time and an optional reminder |
| **Plan** | A plan for a goal: its targets and a review date |
| **Target change** | Adjusts a target that is not working |
| **Backlog** | A fixed amount to clear by a date |
| **Focus** | Starts following a long-running topic |
| **Note** | Something worth remembering about you |
| **Mode** | Suggests switching chat type (for example to Decide) |

Each card shows exactly **what will be saved**: every date and number. Then you choose:

- **Set it**: saves it. The card turns into **Saved**.
- **Not now**: nothing is saved.

**Nothing is ever saved without your tap.** Reminders are only set when you asked for one.

---

## 6. A day with Clarity

- **Morning:** open Talk. The due card shows what is on today. Tell the coach how you feel about the day, or just get on with it.
- **During the day:** when you are stuck or overwhelmed, open an **Untangle** chat. When a choice comes up, open **Decide**.
- **Evening:** open **Plan, Today** and answer your check-ins: Did it, Part or Not. It takes a minute. Skip it on days you cannot; nothing breaks.
- **When a plan slips:** open **Plan with coach**. The coach sees the real numbers and helps you rework the plan honestly, without shame.
- **Sunday evening:** read your weekly letter.

There are **no streaks, no badges and no nagging**. The only notifications are reminders you set yourself, and an optional silent note when your Sunday letter is ready.

---

## 7. Settings

### Coach
- **Main provider**: DeepSeek, Anthropic, or OpenAI-style (any compatible service, with its own **Base URL**).
- **API key**: stored encrypted with the phone's secure hardware (the Android Keystore).
- **Chat model**, **Chat thinking**: which model answers in Talk, and how hard it thinks. More thinking gives deeper answers but is slower and costs a little more.
- **Background model**: the model used for quiet work in the background (updating notes, reviews, chapters).
- **Weekly letter model** and **Weekly letter thinking**: the letter uses a bigger model by default, since it is written once a week.
- **Test connection**: checks the key and model work.
- **Your name**: optional.

Model names are plain text, so when a provider renames a model you just type the new name; you never need a new version of the app for that.

### Reminders
Phones (especially Xiaomi, Redmi and POCO with HyperOS/MIUI, but also Samsung, Oppo, Vivo and others) often stop apps in the background to save battery, which makes reminders ring late or not at all. Open **Keep reminders reliable**. It shows the state of each setting, with a button that opens the right system page:

1. **Battery saver**: set Clarity to **No restrictions**.
2. **Autostart**: turn it on for Clarity (Xiaomi and some others).
3. **Alarms and reminders**: allow it, so reminders ring on time.
4. **Notifications**: allow them.

Reminders are set again automatically after the phone restarts.

The **Sunday alert** (on by default, silent) tells you when your weekly letter is ready. Turn it off here if you prefer.

### Privacy
- **App lock** (on by default): asks for your fingerprint or the phone's screen lock when you open Clarity, and again after a minute away. The phone needs a screen lock set for this to work.
- **Hide in recents** (on by default): hides Clarity's content in the recent-apps screen.

### Your data
Backups, restore, export and Erase everything. See the next section.

### Advanced
- **Edit the coach's prompts**: the instructions that shape the coach. Every edit is checked before it is saved, and **Reset to built-in** always brings back the original.
- **Run the test bench**: runs a set of test conversations against your chosen model and shows how many pass. Worth running after you change the model.
- **Health**: when each background job last ran, and counts of anything the app rejected (for example a quote the coach got wrong). No message content is ever shown or logged.
- **Re-index meaning** and other **Rebuild** buttons: rebuild the search indexes from your data if something looks off.
- **Version**: the version of the app you have.

---

## 8. Backups and your data

Android's own cloud backup is **turned off** for Clarity on purpose, so your life does not get copied to a cloud account. The only backups are the ones you make, and they are encrypted.

- **Set it up once:** in **Settings, Your data**, choose a **Backup passphrase** and a **Backup folder** (for example a folder synced to your cloud drive, or a USB stick). Write the passphrase down. **Without it, a backup cannot be opened by anyone, including you.**
- **Back up now** makes a backup at any time. Once the folder and passphrase are set, Clarity also makes one automatically every week.
- **Restore from a backup** brings everything back, for example on a new phone. Install Clarity, add your API key, then restore.
- **Export everything** writes a readable copy (Markdown and JSON) of your goals, notes and conversations. **This copy is not encrypted**, so keep it somewhere safe.
- **Erase everything** deletes all your data from the phone. You must type ERASE to confirm. It cannot be undone.

---

## 9. Updating the app

1. Download the new **app-release.apk** from Releases.
2. Open it and tap **Update** (or **Install**).

**Install the new version over the old one. Never uninstall first:** uninstalling deletes everything stored in the app. Your conversations, notes and plans carry over to the new version automatically.

---

## 10. Troubleshooting

| Problem | What to do |
|---|---|
| **Test connection fails** | Check the key was pasted completely, with no space before or after. Check you have credit with the provider. Check the model name is correct for your provider. |
| **"That reply was cut off"** | The connection dropped or the phone closed the app. Tap **Try again**. If it happens often, follow **Keep reminders reliable** (the same settings keep replies alive). |
| **The reply failed** | The provider may be down or busy. Wait a minute and tap **Try again**. |
| **Reminders ring late or never** | Open **Settings, Reminders, Keep reminders reliable** and make every line green. |
| **"App not installed" when updating** | The new APK must be signed by the same key as the one on your phone. Download it again from the official Releases page. Do not uninstall unless you have a backup and know its passphrase. |
| **The coach got a fact about me wrong** | Open **What I know**, find the note, and edit or delete it. Or simply tell the coach in Talk. |
| **The coach calls me "this person"** | Type your name in **Settings, Coach, Your name**, or tell the coach your name. |

---

## 11. Privacy in plain words

- Your data lives **only on your phone**, in an encrypted database. The key to it is held by the phone's secure hardware.
- When you talk to the coach, the conversation and the relevant context (your notes, plans and recent messages) are sent to **the AI provider you chose**, using your key, under that provider's terms. Nothing else leaves the phone.
- There are **no accounts, no analytics, no crash reporters and no ads**. The app's own error log never contains your messages or keys.
- Your backups are encrypted with your passphrase, and only go where you put them.
- **The AI only talks.** It cannot change anything on its own. Anything it writes about you is checked: a quote it attributes to you must match your actual words exactly, and any number must match the real data, or it is thrown away.

---

## 12. For developers

The Android app is in `app/` (Kotlin, Jetpack Compose, Material 3, Hilt, Room + SQLCipher, OkHttp streaming, WorkManager, ONNX Runtime for on-device embeddings). minSdk 26.

- `docs/PROJECT-BRIEF.md`: a self-contained summary for anyone (or any AI) joining the project. Start here.
- `docs/LIFE-SYSTEM.md`: the full spec (scope locked).
- `docs/BUILD-STATUS.md`: what is built, what is left, and what to check on a phone.
- `CLAUDE.md`: the rules for building it with Claude Code.
- `docs/mockups/`: the screen designs (open the README there).
- `prompts/`: the coach's mind (persona, examples, cards, chat types, reflection, reviews, letter, chapter, gardening, test bench). Bundled into the APK and filled by code.
- `contracts/`: machine-checked contracts and shared test vectors.
- `db/schema.sql`: the database design.
- `tools/`: executable reference implementations and checkers (Python 3.9+, standard library only).

| Reference | What it pins down | Vectors |
|---|---|---|
| `tools/cards.py` | the card parser | `contracts/card_vectors.json` |
| `tools/planner.py` | pace, days left, projections, review dates, weekly facts, PLAN_STATE text | `contracts/planner_vectors.json` |
| `tools/textcheck.py` | QuoteCheck and NumberCheck | `contracts/textcheck_vectors.json` |
| `tools/promise_times.py` | a promise card's "when" resolved to a day and time, or the time picker | `contracts/promise_time_vectors.json` |
| `tools/context.py` | the context packet: order, escaping, trimming, history window | `contracts/context_vectors.json` |
| `tools/appliers.py` | reflection, gardening, review and letter validation | `contracts/applier_vectors.json` |
| `tools/bench.py` | the test bench's automatic checks | `contracts/bench_vectors.json` |
| `tools/mutation_check.py` | proves the vectors catch planted bugs | |

The Kotlin tests read the same JSON vectors as the Python references, so the app and the spec cannot drift.

```bash
bash tools/check_all.sh            # every contract check
bash tools/forbidden_patterns.sh   # things the code must never do
./gradlew lintDebug testDebugUnitTest
```

**Releases:** pushing a version tag (for example `v0.1.0`) builds the signed release APK in GitHub Actions and attaches it to a GitHub release, with the notes from `docs/release-notes/<tag>.md`. Signing uses four repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
