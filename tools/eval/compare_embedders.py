"""Max plan Phase 4 step 1: newer small on-device meaning models against bge-small-en-v1.5, the app's model until then.

Every candidate (embedders.py) runs through the same measures the app is judged by, with the app's search and
note-check code unchanged; only the model and its similarity lines change:

  exam 1 and exam 2   share of the evidence within the coach's reach (memory_exam.py, memory_exam2.py), leaks
  meaning only        exam 2 with keyword search switched off: what the model alone brings (most sensitive)
  reflection          earlier conversations of a pattern in front of reflection (reflection_exam.py)
  notes               note_pairs.json: rewordings of a deleted note caught, at no more different notes blocked
                      than bge blocked at its line, 0.75 (tools/appliers.py check, words check included), and the AUC
  size and speed      int8 file size (what the APK would carry) and median ms per message on this computer

Fair lines. A cosine line means something different in every model (bge puts unrelated texts near 0.5, others
near 0.1), so bge's lines cannot be reused. The search line (Retrieval.SEMANTIC_MIN, 0.62 while bge was the model) is moved to
the cosine that the same share of random pairs of exam 2's texts reach in the candidate (quantile matching: it
uses no answers, so it is not tuned on the exam). The note lines are set from the note pairs at equal cost, as
above. "best line" columns are a sweep tuned on exam 2 itself, shown only to see how much a line can matter.

Run (about 10 minutes, downloads about 600 MB once into ~/.cache/huggingface):
    /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy huggingface_hub
    /tmp/venv/bin/python tools/eval/compare_embedders.py [names...] [--save=results/x.json]
"""
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
import memory_exam as mx  # noqa: E402
import memory_exam2 as m2  # noqa: E402
import reflection_exam as rx  # noqa: E402
import appliers as A  # noqa: E402
import embedders as E  # noqa: E402

REFERENCE = "bge-small"
REFERENCE_SEARCH_LINE = 0.62    # Retrieval.SEMANTIC_MIN while bge was the app's model
REFERENCE_DELETED_LINE = 0.75   # appliers.DELETED_MEANING while bge was the app's model
SPEED_TEXTS = [
    "i only study well in the morning, evenings are dead for me",
    "Talked to Hina again about revising together on Sundays, she said yes but only after lunch because of her shift",
    "I keep saying I'll start the audit chapter properly from Monday and then Monday comes and I scroll until two "
    "in the morning and hate myself a bit, it's the same thing as last term honestly",
]


class Cached:
    """One model for every run, each text embedded once."""

    def __init__(self, inner):
        self.inner, self.memo = inner, {}

    def embed(self, text):
        if text not in self.memo:
            self.memo[text] = self.inner.embed(text)
        return self.memo[text]


def load(name):
    return E.Combined(name.split("+")) if "+" in name else E.Candidate(name)


def exam2_texts():
    h = json.load(open(os.path.join(HERE, "exam2", "history.json"), encoding="utf-8"))
    return [d["text"] for d in m2.Index(h, False).docs.values()]


def pair_sample(n_texts, n=40000, seed=7):
    rnd = random.Random(seed)
    out = []
    while len(out) < n:
        a, b = rnd.randrange(n_texts), rnd.randrange(n_texts)
        if a != b:
            out.append((a, b))
    return out


def random_cosines(emb, texts, pairs):
    import numpy as np
    v = np.array([emb.embed(t) for t in texts])
    return sorted(float(v[a] @ v[b]) for a, b in pairs)


def matched_line(ref_sorted, cand_sorted, line):
    """The candidate's cosine reached by the same share of random pairs as `line` is in the reference."""
    above = sum(1 for c in ref_sorted if c >= line) / len(ref_sorted)
    k = min(len(cand_sorted) - 1, max(0, int(round((1 - above) * len(cand_sorted)))))
    return round(cand_sorted[k], 4), round(above, 5)


def with_model(emb, line, fn, keyword=True):
    saved = (mx.Embedder, mx.SEMANTIC_MIN, m2.Index.keyword)
    mx.Embedder = lambda: emb
    mx.SEMANTIC_MIN = line
    if not keyword:
        m2.Index.keyword = lambda self, ts: []
    try:
        return fn()
    finally:
        mx.Embedder, mx.SEMANTIC_MIN, m2.Index.keyword = saved


def note_checks(emb, cost_cap=None):
    """Rewordings of a deleted note caught by tools/appliers.py's check (words and meaning), with the meaning line
    set so that no more different notes are blocked than cost_cap (bge's own count at its line when None)."""
    pairs = json.load(open(os.path.join(HERE, "note_pairs.json"), encoding="utf-8"))["pairs"]
    ctxs = [{"vectors": {p["a"]: list(map(float, emb.embed(p["a"]))), p["b"]: list(map(float, emb.embed(p["b"])))}}
            for p in pairs]
    cos = [A.meaning(c, p["a"], p["b"]) for c, p in zip(ctxs, pairs)]
    same = [c for c, p in zip(cos, pairs) if p["same"]]
    diff = [c for c, p in zip(cos, pairs) if not p["same"]]
    auc = sum((s > d) + 0.5 * (s == d) for s in same for d in diff) / (len(same) * len(diff))

    def at(line):
        saved = A.DELETED_MEANING
        A.DELETED_MEANING = line
        try:
            hit = [A.close_to_deleted(p["b"], [p["a"]], c) for c, p in zip(ctxs, pairs)]
        finally:
            A.DELETED_MEANING = saved
        caught = sum(h for h, p in zip(hit, pairs) if p["same"])
        blocked = sum(h for h, p in zip(hit, pairs) if not p["same"])
        return caught, blocked

    if cost_cap is None:
        line = REFERENCE_DELETED_LINE
    else:
        line = 1.01
        for t in sorted(set(round(c, 4) for c in cos), reverse=True):
            if at(t)[1] > cost_cap:
                break
            line = t
    caught, blocked = at(line)
    return {"auc": round(auc, 3), "deleted_line": round(line, 4), "rewordings_caught": caught, "of": len(same),
            "different_blocked": blocked, "of_different": len(diff)}


def main(names, save=None):
    texts = exam2_texts()
    pairs = pair_sample(len(texts))
    ref = Cached(load(REFERENCE))
    ref_cos = random_cosines(ref, texts, pairs)
    ref_notes = note_checks(ref)
    out = {"random_pairs": len(pairs), "texts": len(texts), "models": {}}
    for name in [REFERENCE] + [n for n in names if n != REFERENCE]:
        print("...", name, file=sys.stderr, flush=True)
        inner = ref.inner if name == REFERENCE else load(name)
        emb = ref if name == REFERENCE else Cached(inner)
        cos = ref_cos if name == REFERENCE else random_cosines(emb, texts, pairs)
        line, share = (REFERENCE_SEARCH_LINE, None) if name == REFERENCE else matched_line(ref_cos, cos, REFERENCE_SEARCH_LINE)
        e2 = with_model(emb, line, lambda: m2.run(True))
        e2m = with_model(emb, line, lambda: m2.run(True), keyword=False)
        e1 = with_model(emb, line, lambda: mx.run(True))
        rf = with_model(emb, line, lambda: rx.run(True))
        sweep = {}
        for k in (-0.08, -0.04, 0.04, 0.08):
            t = round(line + k, 4)
            sweep[t] = with_model(emb, t, lambda: m2.run(True))["mean_coverage"]
        sweep[line] = e2["mean_coverage"]
        best = max(sweep.items(), key=lambda kv: (kv[1], -abs(kv[0] - line)))
        notes = ref_notes if name == REFERENCE else note_checks(emb, ref_notes["different_blocked"])
        base = lambda n: E.CANDIDATES[E.VARIANTS.get(n, (n,))[0]]
        parts = [base(n) for n in name.split("+")]
        meta = tuple(" + ".join(str(p[i]) for p in parts) for i in range(len(parts[0])))
        out["models"][name] = {
            "repo": meta[0], "licence": meta[6], "released": meta[7], "tokenizer": meta[8], "pooling": meta[3],
            "size_mb": round(inner.model_bytes / 1e6, 1),
            "ms_per_message": E.speed(inner, SPEED_TEXTS),
            "search_line": line, "random_pairs_above_line": share,
            "exam2": e2["mean_coverage"], "exam2_leaks": len(e2["leaks"]), "exam2_by_kind": e2["by_kind"],
            "exam2_meaning_only": e2m["mean_coverage"], "exam2_meaning_only_by_kind": e2m["by_kind"],
            "exam1": e1["mean_coverage"],
            "reflection": rf["within_reach_phase3"],
            "exam2_best_line": {"line": best[0], "exam2": best[1]},
            "notes": notes,
        }
        r = out["models"][name]
        print("%-18s %6.1f MB %6.1f ms  line %.3f  exam2 %.1f%%  meaning-only %.1f%%  exam1 %.1f%%  refl %.1f%%  notes %d/%d auc %.3f  leaks %d" % (
            name, r["size_mb"], r["ms_per_message"], line, 100 * r["exam2"], 100 * r["exam2_meaning_only"],
            100 * r["exam1"], 100 * r["reflection"], notes["rewordings_caught"], notes["of"], notes["auc"],
            r["exam2_leaks"]), flush=True)
    if save:
        with open(save, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=1)
            f.write("\n")
    return out


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    save = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--save=")), None)
    main(args or list(E.CANDIDATES) + list(E.VARIANTS), save)
