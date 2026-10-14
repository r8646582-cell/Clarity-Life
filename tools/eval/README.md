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
/tmp/venv/bin/python tools/eval/memory_exam.py --hybrid   # keyword + bge-small-en-v1.5, as the app runs it
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
