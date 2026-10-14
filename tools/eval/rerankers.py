"""Max plan Phase 4 step 2: would a small on-device re-ranker improve what RELEVANT_PAST sends?

A re-ranker (a "cross-encoder") reads the new message and one candidate together and scores how well they go
together. It is slower than meaning search (one run per candidate, not one per message), so it could only order
the few dozen candidates search already found. This measures whether it helps, with the app's search unchanged
around it: for each exam question, the fused candidates (keyword, meaning, the period list, the conversation
lines; tools/retrieval.py fuse) are cut to the best POOL, re-ordered, and handed to choose_final as before.

  replace  the pool in the re-ranker's order
  blend    the pool ordered by weighted reciprocal rank of its fused place and its re-ranker place (equal weight)

Measured: exam 2 and exam 1 (share of evidence within reach, leaks), and milliseconds per candidate on this
computer. Every model runs as its int8 ONNX file, as it would on the phone.

Run (needs the venv of compare_embedders.py; downloads about 400 MB once into ~/.cache/huggingface):
    /tmp/venv/bin/python tools/eval/rerankers.py [names...] [--save=results/x.json]
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
import memory_exam as mx  # noqa: E402
import memory_exam2 as m2  # noqa: E402
import retrieval as rv  # noqa: E402

POOL = 30
# name: (repo, onnx file, tokenizer repo, licence, released, size note)
CANDIDATES = {
    "ms-marco-tinybert-l2": ("Xenova/ms-marco-TinyBERT-L-2-v2", "onnx/model_quantized.onnx", None, "Apache-2.0", "2021"),
    "ms-marco-minilm-l6": ("Xenova/ms-marco-MiniLM-L-6-v2", "onnx/model_quantized.onnx", None, "Apache-2.0", "2021"),
    "ms-marco-minilm-l12": ("Xenova/ms-marco-MiniLM-L-12-v2", "onnx/model_quantized.onnx", None, "Apache-2.0", "2021"),
    "jina-tiny": ("jinaai/jina-reranker-v1-tiny-en", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-04"),
    "jina-turbo": ("jinaai/jina-reranker-v1-turbo-en", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-04"),
    "mxbai-xsmall": ("mixedbread-ai/mxbai-rerank-xsmall-v1", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-02"),
    # Larger (the owner, 14 Oct 2026: best outcomes, size does not matter)
    "mxbai-base": ("mixedbread-ai/mxbai-rerank-base-v1", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-02"),
    "mxbai-large": ("mixedbread-ai/mxbai-rerank-large-v1", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-02"),
    "bge-reranker-large": ("Xenova/bge-reranker-large", "onnx/model_quantized.onnx", None, "MIT", "2023-09"),
    "bge-reranker-v2-m3": ("onnx-community/bge-reranker-v2-m3-ONNX", "onnx/model_quantized.onnx", None, "Apache-2.0", "2024-03"),
    "gte-modernbert": ("Alibaba-NLP/gte-reranker-modernbert-base", "onnx/model_quantized.onnx", None, "Apache-2.0", "2025-01"),
}


class CrossEncoder:
    def __init__(self, name, threads=2):
        import numpy as np
        import onnxruntime as ort
        from huggingface_hub import hf_hub_download
        from tokenizers import Tokenizer
        repo, onnx, tok_repo, *_ = CANDIDATES[name]
        path = hf_hub_download(repo, onnx)
        self.size_mb = round(os.path.getsize(path) / 1e6, 1)
        self.np = np
        self.tok = Tokenizer.from_file(hf_hub_download(tok_repo or repo, "tokenizer.json"))
        self.tok.no_padding()
        self.tok.enable_truncation(512)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        self.s = ort.InferenceSession(path, opts)
        self.inputs = {i.name for i in self.s.get_inputs()}
        self.memo, self.ms = {}, []

    def score(self, query, text):
        key = (query, text)
        if key not in self.memo:
            np = self.np
            enc = self.tok.encode(query, text)
            ids = np.array([enc.ids], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
            if "token_type_ids" in self.inputs:
                feed["token_type_ids"] = np.array([enc.type_ids], dtype=np.int64)
            a = time.perf_counter()
            out = self.s.run(None, feed)[0]
            self.ms.append((time.perf_counter() - a) * 1000)
            self.memo[key] = float(out.reshape(-1)[0])
        return self.memo[key]


def run(ce, mode):
    """Exam 2 and exam 1 with the re-ranker between fusion and choose_final."""
    saved = (rv.choose_final, m2.Index.relevant_past, mx.Index.relevant_past)
    original_choose = rv.choose_final
    state = {}

    def choose(keys, docs, max_results=None):
        q = state.get("q")
        if ce is None or q is None:
            return original_choose(keys, docs, max_results)
        pool = [k for k in keys if k in docs][:POOL]
        rest = [k for k in keys if k not in pool]
        scores = {k: ce.score(q, docs[k]["text"]) for k in pool}
        by_ce = sorted(pool, key=lambda k: -scores[k])
        if mode == "replace":
            order = by_ce
        else:
            order = rv.fuse([(1.0, pool), (1.0, by_ce)])
        return original_choose(order + rest, docs, max_results)

    def wrap(fn):
        def inner(self, text, *a, **k):
            state["q"] = text
            try:
                return fn(self, text, *a, **k)
            finally:
                state["q"] = None
        return inner

    rv.choose_final = choose
    m2.Index.relevant_past = wrap(saved[1])
    mx.Index.relevant_past = wrap(saved[2])
    try:
        e2 = m2.run(True)
        e1 = mx.run(True)
    finally:
        rv.choose_final, m2.Index.relevant_past, mx.Index.relevant_past = saved
    return e2, e1


def main(names, save=None):
    emb = mx.Embedder()
    memo = {}

    class Cached:
        def embed(self, t):
            if t not in memo:
                memo[t] = emb.embed(t)
            return memo[t]

    mx.Embedder = Cached  # one model for every run, each text embedded once
    out = {"pool": POOL, "embedder": mx.MODEL_NAME, "runs": {}}
    e2, e1 = run(None, None)
    out["runs"]["none (the app today)"] = {"exam2": e2["mean_coverage"], "exam1": e1["mean_coverage"], "leaks": len(e2["leaks"]),
                                         "exam2_by_kind": e2["by_kind"]}
    print("%-30s exam2 %.1f%%  exam1 %.1f%%" % ("none (the app today)", 100 * e2["mean_coverage"], 100 * e1["mean_coverage"]), flush=True)
    for name in names:
        ce = CrossEncoder(name)
        for mode in ("replace", "blend"):
            e2, e1 = run(ce, mode)
            ms = sorted(ce.ms)[len(ce.ms) // 2]
            key = "%s, %s" % (name, mode)
            out["runs"][key] = {"repo": CANDIDATES[name][0], "licence": CANDIDATES[name][3], "size_mb": ce.size_mb,
                                "ms_per_candidate": round(ms, 2), "exam2": e2["mean_coverage"], "exam1": e1["mean_coverage"],
                                "leaks": len(e2["leaks"]), "exam2_by_kind": e2["by_kind"]}
            print("%-30s %6.1f MB %5.1f ms/candidate  exam2 %.1f%%  exam1 %.1f%%  leaks %d" % (
                key, ce.size_mb, ms, 100 * e2["mean_coverage"], 100 * e1["mean_coverage"], len(e2["leaks"])), flush=True)
    if save:
        with open(save, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=1)
            f.write("\n")
    return out


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    save = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--save=")), None)
    main(args or list(CANDIDATES), save)
