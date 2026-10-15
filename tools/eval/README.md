# Memory exams (retrieval section)

Two exams. Exam 1 is the old app's (43 questions), kept for history. Exam 2 (`memory_exam2.py`, below) is the
harder one the Max plan is measured with (`docs/MAX-PLAN.md`).

## Exam 1

`memory_exam.py` runs the old app's memory exam (`fixtures/`, copied unchanged from the old repo's `tools/eval`:
an invented user, "Tester Zed", 14 conversations, 43 questions) against a Python mirror of the new app's
retrieval. No model answers anything: for each answerable question it measures how much of the gold
evidence the coach could see (the Part 2 memory blocks plus RELEVANT_PAST for that question).

```
python3 tools/eval/memory_exam.py            # keyword only, standard library
python3 -m venv /tmp/venv && /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy
/tmp/venv/bin/python tools/eval/memory_exam.py --hybrid   # keyword + the bundled model, as the app runs it (bge-small-en-v1.5 until Phase 4)
```

Results (`results/2026-10-10-stage-b.json`, `results/2026-10-10-stage-c.json`), share of gold evidence within reach:

| Retrieval | All questions | Without promises |
|---|---|---|
| Old app, keyword | 70.8% | 63.9% |
| Old app, keyword + MiniLM (0.30) | 80.6% | 76.7% |
| Stage B, keyword only | 47.1% | 60.4% |
| Stage B, keyword + bge | 62.5% | 81.7% |
| Stage C, keyword only | 62.9% | 60.4% |
| **Stage C, keyword + bge (what the app runs)** | **80.6%** | 80.0% |

Stage C adds OPEN_PROMISES (open promises, and the ones answered in the last 14 days) and puts every promise,
with its outcome, in the search index. The promise questions stop scoring zero: "time" questions go from 0% to
83%. One question lost one item: "Am I still sending out job applications?" now brings back the old promise
"Send two job applications by Sunday (not done)", a fair match, which pushes the summary of the job offer out of
the five RELEVANT_PAST slots. Still missing: "Which promises did I break?" (two broken promises from August,
outside the 14-day window, that share no words with the question).

Before stage C every promise question scored zero, which was the whole gap in the first column. Stage B also
found two real gaps that were fixed: keyword candidates were read oldest first, and quotes, events and retired
notes were not searchable.

The mirror must follow the Kotlin (`core/Retrieval.kt`, `memory/SearchIndex.kt`, `core/MemoryBlocks.kt`); when
those change, change `memory_exam.py` and run it again. The answers section of the old harness (a model
answers, a judge grades) is not ported: it needs an API key and belongs with the test bench in stage E.

### Exam 1 again, with the 0.1.12 search (14 Oct 2026)

The mirror had fallen behind the app: 0.1.12 searches three-letter words, meets word forms by a stem and reads
candidates one word at a time. With the mirror brought up to date (`results/2026-10-14-exam1-0.1.12-*.json`):
keyword only 69.8% (was 62.9%), keyword + bge 79.2% (was 80.6%).

## Exam 2 (Max plan, Phase 0)

`exam2/build_history.py` makes one invented year of "Rafi", a CAF student: 30 conversations that carry the
story, 108 ordinary ones that compete with them in search, a profile with replaced values, people, 69 notes
(one retired, one he deleted), goals with a revision, targets with 78 check-ins and their reasons, 13
promises with every outcome, weekly letters, reviews, chapters, and one off-the-record conversation.
`exam2/questions.json` asks 157 questions of 11 kinds (recall, superseded, synthesis, time, vague
follow-ups, patterns months apart, plan, check-ins, people, letters and reviews, promises) and 8 traps.

`memory_exam2.py` builds, for each question, what the app sends today: Part 2 and Part 3 with their real
budgets and drop ranks (`tools/context.py`), LAST_CHAT, OPEN_PROMISES, PLAN_STATE, CHECKIN_FACTS and the 0.1.12
search. It reports the share of each question's evidence in the request, and **leaks**: anything he deleted
or said off the record that reaches the coach. `tools/check_all.sh` runs `test_memory_exam2.py`: the history
must be reproducible and leaks must be 0, and `mutation_check.py` plants two leaks to prove that check fails.

```
python3 tools/eval/memory_exam2.py                     # keyword only
/tmp/venv/bin/python tools/eval/memory_exam2.py --hybrid  # as the app runs it
```

Baseline, Clarity 0.1.12 (`results/2026-10-14-exam2-baseline-*.json`):

| Kind | Keyword only | Keyword + bge (the app) |
|---|---|---|
| **All 157 questions** | **62.6%** | **71.5%** |
| recall | 86.7% | 95.0% |
| superseded | 69.4% | 88.9% |
| synthesis | 61.5% | 70.8% |
| time ("in August", "last week") | 42.1% | 52.6% |
| vague follow-ups ("same again") | 59.2% | 66.7% |
| patterns months apart | 32.5% | 44.2% |
| plan | 66.7% | 70.8% |
| check-ins | 60.0% | 60.0% |
| people | 93.8% | 93.8% |
| letters, reviews, chapters | 20.0% | 20.0% |
| promises | 75.0% | 100% |
| Leaks | 0 | 0 |

Where it is lost, in the Max plan's words: only the new message is searched (follow-ups), months and outcomes
are not understood (time), old check-ins, letters, reviews, goal revisions, finished goals and ended targets
are not sent or searchable at all, and five results is often too few for a pattern spread over a year.
Phase 2 targets 95%.

## Phase 2: what the AI receives (14 Oct 2026)

`memory_exam2.py` now uses `tools/retrieval.py` and `tools/recall_time.py` as they are (the same code the app's
`Retrieval.kt` and `RecallTime.kt` are tested against), searches the question together with its "context" lines,
and indexes letters, reviews, chapters, goals, goal changes, targets and check-ins as `SearchIndex` does.
`memory_exam.py` (exam 1) uses the same search. Results: `results/2026-10-14-exam2-phase2-*.json`.

| Kind | Keyword, before | Keyword, Phase 2 | App (keyword + bge), before | **App, Phase 2** |
|---|---|---|---|---|
| **All 157 questions** | 62.6% | 92.5% | 71.5% | **95.1%** |
| check-ins | 60.0% | 100% | 60.0% | 100% |
| vague follow-ups | 59.2% | 95.8% | 66.7% | 94.2% |
| patterns months apart | 32.5% | 60.0% | 44.2% | 86.7% |
| people | 93.8% | 100% | 93.8% | 100% |
| plan | 66.7% | 100% | 70.8% | 100% |
| promises | 75.0% | 100% | 100% | 100% |
| recall | 86.7% | 98.3% | 95.0% | 98.3% |
| superseded | 69.4% | 88.9% | 88.9% | 97.2% |
| synthesis | 61.5% | 77.1% | 70.8% | 82.3% |
| time | 42.1% | 92.1% | 52.6% | 92.1% |
| letters, reviews, chapters | 20.0% | 100% | 20.0% | 100% |
| Leaks | 0 | 0 | 0 | 0 |

Exam 1 (the old fixture, not looked at while designing Phase 2): keyword 69.8% to 77.8%, keyword + bge 79.2% to
84.7%.

Read these numbers with care. The exam measures whether the evidence is **within reach** of the coach, not
whether the coach answers well, and it rewards sending more: the app now sends up to 12 results instead of 5,
which is part of the gain. Exam 2 was written before Phase 2, but Phase 2 was built while looking at it; a few
choices (which question words say nothing, the 5% rarity line) were checked on it. Exam 1 rising too is the
sign that this is not only fitting exam 2. What is still missed: summaries a pattern question shares no word
or meaning with ("have I taken on too much before?"), and broad periods ("what happened in March?") where the
period holds more conversations than are sent.

## Phase 3: how memory is built after each chat (14 Oct 2026)

### Reflection sees the earlier conversations a pattern is made of (`reflection_exam.py`)

Exam 2's questions name the conversations their answer is spread over. Each question naming two or more (time
questions aside) gives pairs: when the latest conversation is reflected, is each earlier one in front of the job
that writes notes? Before Phase 3 reflection saw only the 12 most recent events. Now it also gets EARLIER
CONVERSATIONS AND EVENTS LIKE THIS ONE: every new message of his joined, searched by keyword, and his newest six
messages one by one by keyword and meaning (`tools/retrieval.py` `reflection_queries`, `reflection_choose`; the app
runs the same in `SearchIndex.earlierForReflection`), at most 8 summaries and events, one per conversation.

```
python3 tools/eval/reflection_exam.py                  # keyword only
/tmp/venv/bin/python tools/eval/reflection_exam.py --hybrid
```

| Earlier conversation of a pattern within reach of reflection (26 pairs) | Keyword only | Keyword + bge (the app) |
|---|---|---|
| Before Phase 3 (12 most recent events) | 11.5% | 11.5% |
| **Phase 3** | 88.5% | **88.5%** |

Searching all his new messages joined by meaning as well was tried and was worse (70 to 85%: a long text's vector
is a blur), so the joined text is searched by keyword only. Still missed: three pairs whose conversations share
neither a word nor a meaning with each other (lecture count against load shedding, the knee against the phone
habit, quitting work against the library). Results: `results/2026-10-14-phase3-reflection-*.json`.

### Note checks by meaning (`note_meaning.py`)

`note_pairs.json`: 68 pairs of notes, 31 of them one note reworded, 37 different notes (many on the same topic,
some the opposite claim), scored with the app's model:

| | Words only (before) | Words and meaning (Phase 3) |
|---|---|---|
| Rewordings of a deleted note caught | 0 of 31 | **30 of 31** |
| Different notes refused near a deleted one (the cost) | 0 of 37 | 12 of 37 |
| Rewordings dropped as duplicates of an active note | 0 of 31 | 9 of 31 |
| Different notes dropped as duplicates (every pair taken as one kind) | 1 of 37 | 1 of 37 |

Rewordings score 0.735 to 0.955; different notes 0.547 to 0.947, because this model scores opposites like
rewordings ("helps you sleep" and "keeps you awake": 0.947). So the deleted-note line is low (0.75: refusing a note
near something he deleted is the safe side), and a duplicate by meaning needs the same kind, the same "not" in
both, and 0.9. The word check now has the "not" guard too: before, "You never keep promises to your sister" was
dropped as a repeat of "You keep promises to your sister". What stays: an opposite with no "not" in it, of the same
kind, is still taken for a repeat. Results: `results/2026-10-14-phase3-note-meaning.json`.

### Gardening's suggestions (`gardening_clusters` in `tools/appliers.py`)

Before the monthly tidy, code groups notes that look like one (same kind, the same "not", meaning 0.85 or more or
half their words shared, at most 4 to a group) and lists them under NOTES as merges to consider. On the same 68
pairs (each taken as one kind): 17 of 31 rewordings are suggested, and 3 of 37 different notes are, which the model
has to turn down (the prompt's own rule: never merge two notes about different things). The applier checks every
merge as before; nothing is merged by code.

## Phase 4: the on-phone meaning model (14 Oct 2026)

The owner's rule for this phase: **the best outcomes, whatever the size.**

### Step 1: which meaning model (`compare_embedders.py`, `embedders.py`)

Every candidate ran as an ONNX file the phone could run, with the app's search and note checks unchanged; only the
model and its lines changed. The search line was moved to the cosine the same share of random pairs of exam 2's
texts reaches (about 18%, as 0.62 does for bge): no answers are used, so it is not tuned on the exam. The
deleted-note line was set so that no more different notes are blocked than bge blocks (12 of 37). "Meaning only"
is exam 2 with keyword search off. Speed is per message on the cloud computer: only the ratio means anything.
"A+B" is a mix: the two models' cosines averaged (their vectors side by side). Results:
`results/2026-10-14-phase4-embedders.json` (small models), `-embedders-larger.json`, `-embedders-mixes.json`.

| Model | Size | Time | Exam 2 | Meaning only | Exam 1 | Reflection | Deleted-note rewordings |
|---|---|---|---|---|---|---|---|
| bge-small-en-v1.5, the app before | 34 MB | 1x | 95.1% | 91.1% | 84.7% | 88.5% | 30 of 31 |
| mdbr-leaf-mt, first 384 numbers | 23 MB | 0.5x | 96.7% | 95.1% | 94.2% | 88.5% | 30 |
| gte-small | 34 MB | 1x | 97.2% | 96.2% | 90.3% | 88.5% | 28 |
| gte-base | 110 MB | 2x | 98.0% | 97.1% | 88.4% | 92.3% | 30 |
| gte-large | 337 MB | 8x | 97.8% | 96.2% | 90.7% | 88.5% | 30 |
| **gte-base-en-v1.5: the app now** | **147 MB** | **3x** | 97.1% | 94.3% | 90.5% | **96.2%** | 30 |
| gte-base-en-v1.5, full precision | 556 MB | 9x | 96.6% | 93.7% | 91.9% | 96.2% | 30 |
| gte-large-en-v1.5 | 446 MB | 9x | 96.3% | 94.8% | 86.1% | 92.3% | 28 |
| gte-modernbert-base | 150 MB | 4x | 97.5% | 95.9% | 86.1% | 92.3% | 31 |
| mxbai-embed-large-v1 | 337 MB | 7x | 96.5% | 93.8% | 87.5% | 80.8% | 31 |
| bge-base / bge-large | 110 / 337 MB | 2.5x / 7x | 94.9 / 96.0% | 92.4 / 92.6% | 85.2 / 88.0% | 88.5% | 29 / 31 |
| EmbeddingGemma-300m (int8) | 310 MB | 40x | 97.1% | 94.2% | 81.0% | 88.5% | 31 |
| Qwen3-Embedding-0.6B | 614 MB | 17x | 95.5% | 90.7% | 81.7% | 88.5% | 31 |
| UAE-Large, e5-large-v2, arctic-l, arctic-m/l-v2.0, all-mpnet, modernbert-embed, granite r2 | 110 to 570 MB | | 92.0 to 97.1% | 71 to 94% | 79 to 86% | 81 to 88.5% | 24 to 31 |
| Small ones (arctic-xs/s/m, mxbai-xsmall, MiniLM, e5-small, nomic, granite-small, leaf-ir) | 23 to 137 MB | | 92.7 to 96.6% | 76 to 93% | 79 to 88% | 77 to 88.5% | 24 to 31 |
| Mixes: leaf-mt + gte-base-v1.5 / gte-base + gte-base-v1.5 / all three | 170 to 280 MB | | 97.0 / 97.8 / 97.4% | | 96.1 / 91.2 / 95.4% | 92.3 / 96.2 / 92.3% | 31 |

Exam 1 has 36 questions, so one question is 2.8 points, and single lines flatter some models. So the top ones were
measured again with the line moved 0.03 either way and averaged (exam 2 / exam 1 / reflection): mdbr-leaf-mt
96.7 / 92.0 / 88.5, gte-base 97.2 / 89.7 / 91.2, **gte-base-en-v1.5 97.2 / 92.4 / 96.2**, gte-base + gte-base-v1.5
97.5 / 91.6 / 96.2, all three 97.5 / 91.2 / 92.3. gte-base-en-v1.5 (Alibaba, 2024, Apache-2.0) is best or level on
every exam, alone; a mix is no better and runs two or three models per text. Its full-precision file is no better
than its int8 one. Its vocabulary is bge's word for word, so the app's tokenizer did not change.

The app's lines for it (the same method, rounded): meaning search 0.42 (bge 0.62), deleted note 0.65 (0.75),
duplicate note 0.88 (0.9), gardening suggestion 0.82 (0.85). With them, as the app runs it:

| | bge-small (before) | gte-base-en-v1.5 (Phase 4) |
|---|---|---|
| Exam 2, all 157 questions | 95.1% | **97.1%** |
| recall / superseded / synthesis | 98.3 / 97.2 / 82.3% | 100 / 97.2 / 87.5% |
| time / vague follow-ups / patterns months apart | 92.1 / 94.2 / 86.7% | 97.4 / 96.7 / 90.0% |
| check-ins, plan, people, promises, letters | 100% | 100% |
| Exam 1, 36 answerable questions | 84.7% | **91.9%** |
| Reflection exam, 26 pairs | 88.5% | **96.2%** |
| Rewordings of a deleted note caught / different notes blocked | 30 of 31 / 12 of 37 | **31 of 31** / 12 of 37 |
| Rewordings dropped as duplicates / different notes dropped | 9 of 31 / 1 of 37 | 6 of 31 / 1 of 37 |
| Gardening: rewordings suggested / different notes suggested | 17 of 31 / 3 of 37 | 14 of 31 / 3 of 37 |
| Leaks | 0 | 0 |

Results: `results/2026-10-14-exam2-phase4-hybrid.json`, `results/2026-10-14-phase4-reflection-hybrid.json`,
`results/2026-10-14-phase4-note-meaning.json`. The bundled files are made by `tools/bundle_embedding_model.py`
(pinned revision, the 147 MB file in two parts because GitHub refuses one over 100 MB) and the reference by
`tools/make_embedding_vectors.py`. (An earlier build of this phase used mdbr-leaf-mt, the best small model; it
was replaced the same day when the owner said size does not matter.)

### Step 2: a re-ranker (`rerankers.py`)

A re-ranker reads the message and one candidate together, so it runs once per candidate. Tried on the best 30
fused candidates, replacing their order or blended with it (reciprocal rank, equal weight). Results:
`results/2026-10-14-phase4-rerankers.json` (on the mdbr-leaf-mt build).

| Re-ranker | Size | ms per candidate | Exam 2, replace | Exam 2, blend |
|---|---|---|---|---|
| none (mdbr-leaf-mt build) | | | 96.7% | 96.7% |
| ms-marco-TinyBERT-L2 / MiniLM-L6 / MiniLM-L12 | 4.5 / 23 / 34 MB | 0.4 / 3 / 6 | 96.4 / 96.8 / 96.6% | 96.8 / **97.8** / 97.5% |
| jina-reranker-v1-tiny / turbo | 33 / 38 MB | 3 / 4 | 96.3 / 95.5% | 97.2 / 97.3% |
| mxbai-rerank-xsmall / base / large | 87 / 244 / 643 MB | 14 / 32 / 83 | 95.9 / 95.9 / 95.4% | 97.2 / 96.5 / 96.8% |
| bge-reranker-large / v2-m3 | 563 / 571 MB | 49 / 53 | 95.3 / 96.7% | 97.2 / 97.6% |
| gte-reranker-modernbert-base | 151 MB | 25 | 96.5% | 97.1% |
| control: a random order, blended | | | | 96.4 to 97.2% |

Exam 1 did not move with any of them. Then the best one on top of the model the app now runs:

| On gte-base-en-v1.5 | Exam 2 | Exam 1 |
|---|---|---|
| no re-ranker | 97.1% | 90.5% |
| a random order, blended (3 runs) | 96.7 to 97.1% | |
| ms-marco-MiniLM-L6, blended | 97.1% (1 question better, 1 worse) | 90.5% |

Not built: on the better model it adds nothing. Replacing the order never helped, the larger re-rankers were no
better than the small one, and its earlier gain on mdbr-leaf-mt was inside what a random order does.
