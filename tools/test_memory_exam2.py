"""Memory exam 2's guard (tools/eval/memory_exam2.py --check): the committed history is reproducible and nothing
he deleted or said off the record reaches the coach. tools/mutation_check.py plants leaks to prove it fails."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval"))
import memory_exam2  # noqa: E402

if __name__ == "__main__":
    memory_exam2.check()
