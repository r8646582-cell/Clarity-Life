# Memory exam (retrieval section)

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
