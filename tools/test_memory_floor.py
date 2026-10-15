"""Memory exam floor (Max plan Phase 7): CI fails when a change lowers either memory exam's score.

The exams measure how much of the evidence each question needs is in front of the coach (tools/eval/README.md).
The floor is in tools/eval/exam_floor.json; a change that raises a score on purpose raises the floor with it.
Keyword search only, so it runs with the standard library. tools/mutation_check.py plants a smaller packet to
prove a drop is caught."""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "eval"))
import memory_exam  # noqa: E402
import memory_exam2  # noqa: E402

if __name__ == "__main__":
    with open(os.path.join(HERE, "eval", "exam_floor.json"), encoding="utf-8") as f:
        floor = json.load(f)
    failed = []
    for key, exam in (("exam1_keyword", memory_exam), ("exam2_keyword", memory_exam2)):
        score = exam.run(False)["mean_coverage"]
        low = floor[key]
        print("memory exam floor: %s %.2f%% (floor %.2f%%)" % (key, 100 * score, 100 * low))
        if score < low - 1e-9:
            failed.append("%s fell from %.2f%% to %.2f%%" % (key, 100 * low, 100 * score))
        elif score > low + 1e-9:
            print("  it rose: raise the floor in tools/eval/exam_floor.json to %.4f" % score)
    if failed:
        sys.exit("memory exam floor: " + "; ".join(failed))
