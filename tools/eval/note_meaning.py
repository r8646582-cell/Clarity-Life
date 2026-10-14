"""How the note checks of tools/appliers.py sort reworded notes from different ones (Max plan Phase 3).

For each pair in note_pairs.json it scores the two texts with the app's own model (bge-small-en-v1.5, CLS, as
memory_exam.py runs it) and with the word check, then reports, for the lines in tools/appliers.py:
  - deleted-note check: share of rewordings caught (good) and of different notes blocked (the cost);
  - duplicate check, every pair taken as the same kind (the strictest case): rewordings dropped as duplicates,
    and different notes wrongly dropped (that hides a new or changed note: only an opposite with no "not" in it).

Run: /tmp/venv/bin/python tools/eval/note_meaning.py   (needs onnxruntime, tokenizers, numpy; see README.md)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
import memory_exam as mx  # noqa: E402  (the embedder)
import appliers as A  # noqa: E402


def main():
    pairs = json.load(open(os.path.join(HERE, "note_pairs.json"), encoding="utf-8"))["pairs"]
    emb = mx.Embedder()
    rows = []
    for p in pairs:
        ctx = {"vectors": {p["a"]: list(map(float, emb.embed(p["a"]))), p["b"]: list(map(float, emb.embed(p["b"])))}}
        rows.append({
            "same": p["same"],
            "meaning": round(A.meaning(ctx, p["a"], p["b"]), 3),
            "words": round(A.similarity(p["a"], p["b"]), 2),
            "deleted_check": A.close_to_deleted(p["b"], [p["a"]], ctx),
            "words_only_deleted_check": A.close_to_deleted(p["b"], [p["a"]], {}),
            "duplicate_check": A.duplicate_of_active(p["b"], "pattern", [("pattern", p["a"])], ctx),
            "a": p["a"], "b": p["b"],
        })
    same = [r for r in rows if r["same"]]
    diff = [r for r in rows if not r["same"]]
    share = lambda rs, k: "%d of %d" % (sum(r[k] for r in rs), len(rs))
    result = {
        "lines": {"DELETED_MEANING": A.DELETED_MEANING, "DUPLICATE_MEANING": A.DUPLICATE_MEANING,
                  "DELETED_SIMILARITY": A.DELETED_SIMILARITY, "DUPLICATE_SIMILARITY": A.DUPLICATE_SIMILARITY},
        "rewordings caught as a deleted note, words only (before Phase 3)": share(same, "words_only_deleted_check"),
        "rewordings caught as a deleted note, words and meaning": share(same, "deleted_check"),
        "different notes blocked near a deleted one (the cost)": share(diff, "deleted_check"),
        "rewordings dropped as duplicates": share(same, "duplicate_check"),
        "different notes dropped as duplicates (an opposite with no \"not\" in it)": share(diff, "duplicate_check"),
        "meaning, rewordings": [min(r["meaning"] for r in same), max(r["meaning"] for r in same)],
        "meaning, different notes": [min(r["meaning"] for r in diff), max(r["meaning"] for r in diff)],
        "pairs": rows,
    }
    print(json.dumps({k: v for k, v in result.items() if k != "pairs"}, indent=1))
    return result


if __name__ == "__main__":
    r = main()
    if "--save" in sys.argv:
        with open(sys.argv[sys.argv.index("--save") + 1], "w", encoding="utf-8") as f:
            json.dump(r, f, indent=1, ensure_ascii=False)
            f.write("\n")
