"""Candidate on-device meaning models for Max plan Phase 4 (compare_embedders.py runs them).

Each candidate is run the way the phone would run it: its int8 ONNX file (the size that would ship in the
APK), its own tokenizer, its own pooling, one text at a time, no padding, at most 512 tokens, L2-normalised.

Downloads go to the Hugging Face cache (~/.cache/huggingface), never into the repository.
Needs: /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy huggingface_hub

"bge-small" is the file the app bundled until Max plan Phase 4 (the same bytes); "leaf-mt-384" is what it bundles
now (tools/bundle_embedding_model.py joins the same export into one file; memory_exam.Embedder runs that file).
"""
import os
import time

# name: (repo, onnx file in the repo, tokenizer repo, pooling, prefix, query prefix, licence, released, tokenizer kind)
#   prefix:       put before every text, as the app would (it compares his messages with his messages and notes,
#                 so every text is embedded the same way)
#   query prefix: the model's prefix for search queries, for the record only. Tried on the first run: the line
#                 for a query against a text cannot be set from text-to-text pairs, so those runs were not fair
#                 and are not reported; a query prefix would also need a second vector per message.
ARCTIC_Q = "Represent this sentence for searching relevant passages: "
CANDIDATES = {
    "bge-small": ("Xenova/bge-small-en-v1.5", "onnx/model_quantized.onnx", "BAAI/bge-small-en-v1.5", "cls", "", None,
                  "MIT", "2023-09", "WordPiece"),
    "bge-small-fp32": ("Xenova/bge-small-en-v1.5", "onnx/model.onnx", "BAAI/bge-small-en-v1.5", "cls", "", None,
                       "MIT", "2023-09", "WordPiece"),
    "arctic-xs": ("Snowflake/snowflake-arctic-embed-xs", "onnx/model_quantized.onnx", None, "cls", "", ARCTIC_Q,
                  "Apache-2.0", "2024-04", "WordPiece"),
    "arctic-s": ("Snowflake/snowflake-arctic-embed-s", "onnx/model_quantized.onnx", None, "cls", "", ARCTIC_Q,
                 "Apache-2.0", "2024-04", "WordPiece"),
    "arctic-m-v1.5": ("Snowflake/snowflake-arctic-embed-m-v1.5", "onnx/model_quantized.onnx", None, "cls", "", ARCTIC_Q,
                      "Apache-2.0", "2024-07", "WordPiece"),
    "mxbai-xsmall": ("mixedbread-ai/mxbai-embed-xsmall-v1", "onnx/model_quantized.onnx", None, "mean", "", None,
                     "Apache-2.0", "2024-09", "WordPiece"),
    "leaf-mt": ("MongoDB/mdbr-leaf-mt", "onnx/model_quantized.onnx", None, "mean", "", ARCTIC_Q,
                "Apache-2.0", "2025-08", "WordPiece"),
    "leaf-ir": ("MongoDB/mdbr-leaf-ir", "onnx/model_quantized.onnx", None, "mean", "", ARCTIC_Q,
                "Apache-2.0", "2025-08", "WordPiece"),
    "minilm-l6": ("Xenova/all-MiniLM-L6-v2", "onnx/model_quantized.onnx", "sentence-transformers/all-MiniLM-L6-v2",
                  "mean", "", None, "Apache-2.0", "2021-08", "WordPiece"),
    "e5-small-v2": ("Xenova/e5-small-v2", "onnx/model_quantized.onnx", "intfloat/e5-small-v2", "mean", "query: ", None,
                    "MIT", "2023-05", "WordPiece"),
    "gte-small": ("Xenova/gte-small", "onnx/model_quantized.onnx", "thenlper/gte-small", "mean", "", None,
                  "MIT", "2023-07", "WordPiece"),
    "nomic-v1.5": ("nomic-ai/nomic-embed-text-v1.5", "onnx/model_quantized.onnx", None, "mean", "clustering: ",
                   "search_query: ", "Apache-2.0", "2024-02", "WordPiece"),
    # Larger models (the owner, 14 Oct 2026: "choose the model with the best outcomes, don't care about size").
    "leaf-mt-fp32": ("MongoDB/mdbr-leaf-mt", "onnx/model.onnx", None, "mean", "", ARCTIC_Q, "Apache-2.0", "2025-08", "WordPiece"),
    "mxbai-large": ("mixedbread-ai/mxbai-embed-large-v1", "onnx/model_quantized.onnx", None, "cls", "", ARCTIC_Q,
                    "Apache-2.0", "2024-03", "WordPiece"),
    "bge-base": ("Xenova/bge-base-en-v1.5", "onnx/model_quantized.onnx", None, "cls", "", None, "MIT", "2023-09", "WordPiece"),
    "bge-large": ("Xenova/bge-large-en-v1.5", "onnx/model_quantized.onnx", None, "cls", "", None, "MIT", "2023-09", "WordPiece"),
    "arctic-l": ("Snowflake/snowflake-arctic-embed-l", "onnx/model_quantized.onnx", None, "cls", "", ARCTIC_Q,
                 "Apache-2.0", "2024-04", "WordPiece"),
    "arctic-m-v2.0": ("Snowflake/snowflake-arctic-embed-m-v2.0", "onnx/model_quantized.onnx", None, "cls", "", "query: ",
                      "Apache-2.0", "2024-11", "SentencePiece (XLM-R)"),
    "arctic-l-v2.0": ("Snowflake/snowflake-arctic-embed-l-v2.0", "onnx/model_quantized.onnx", None, "cls", "", "query: ",
                      "Apache-2.0", "2024-11", "SentencePiece (XLM-R)"),
    "gte-base": ("Xenova/gte-base", "onnx/model_quantized.onnx", None, "mean", "", None, "MIT", "2023-08", "WordPiece"),
    "gte-large": ("Xenova/gte-large", "onnx/model_quantized.onnx", None, "mean", "", None, "MIT", "2023-08", "WordPiece"),
    "gte-base-v1.5": ("Alibaba-NLP/gte-base-en-v1.5", "onnx/model_quantized.onnx", None, "cls", "", None,
                      "Apache-2.0", "2024-04", "WordPiece"),
    "gte-large-v1.5": ("Alibaba-NLP/gte-large-en-v1.5", "onnx/model_quantized.onnx", None, "cls", "", None,
                       "Apache-2.0", "2024-04", "WordPiece"),
    "e5-large-v2": ("Xenova/e5-large-v2", "onnx/model_quantized.onnx", None, "mean", "query: ", None, "MIT", "2023-06", "WordPiece"),
    "uae-large": ("WhereIsAI/UAE-Large-V1", "onnx/model_quantized.onnx", None, "cls", "", None, "MIT", "2023-12", "WordPiece"),
    "mpnet-base": ("Xenova/all-mpnet-base-v2", "onnx/model_quantized.onnx", None, "mean", "", None, "Apache-2.0", "2021-08",
                   "WordPiece (MPNet)"),
    "gte-modernbert": ("Alibaba-NLP/gte-modernbert-base", "onnx/model_quantized.onnx", None, "cls", "", None,
                       "Apache-2.0", "2025-01", "BPE (ModernBERT)"),
    "modernbert-embed": ("nomic-ai/modernbert-embed-base", "onnx/model_quantized.onnx", None, "mean", "clustering: ",
                         "search_query: ", "Apache-2.0", "2024-12", "BPE (ModernBERT)"),
    "granite-r2": ("onnx-community/granite-embedding-english-r2-ONNX", "onnx/model_quantized.onnx", None, "cls", "", None,
                   "Apache-2.0", "2025-08", "BPE (ModernBERT)"),
    "embeddinggemma": ("onnx-community/embeddinggemma-300m-ONNX", "onnx/model_quantized.onnx", None, "mean",
                       "task: sentence similarity | query: ", "task: search result | query: ", "Gemma terms", "2025-08",
                       "SentencePiece (Gemma)"),
    "qwen3-0.6b": ("onnx-community/Qwen3-Embedding-0.6B-ONNX", "onnx/model_quantized.onnx", None, "last",
                   "Instruct: Retrieve semantically similar text\nQuery: ", None, "Apache-2.0", "2025-06", "BPE (Qwen)"),
    "granite-small-r2": ("onnx-community/granite-embedding-small-english-r2-ONNX", "onnx/model_quantized.onnx", None,
                         "cls", "", None, "Apache-2.0", "2025-07", "BPE (ModernBERT)"),
}

# Variants of a candidate: (base, output, dims). output "hidden" pools the transformer's last layer itself (skipping a
# projection the export adds); dims keeps the first n numbers of the vector (for models trained to allow it).
VARIANTS = {
    "leaf-mt-384": ("leaf-mt", None, 384),        # what the app runs (Max plan Phase 4)
    "leaf-mt-hidden": ("leaf-mt", "hidden", None),  # 384 numbers too, but without the model's projection
    "leaf-mt-512": ("leaf-mt", None, 512),
    "leaf-mt-768": ("leaf-mt", None, 768),
    "leaf-mt-256": ("leaf-mt", None, 256),
}


def _download(repo, name):
    from huggingface_hub import hf_hub_download
    path = hf_hub_download(repo, name)
    try:  # an external-data model keeps its weights next to it
        hf_hub_download(repo, name + "_data")
    except Exception:
        pass
    return path


def files(name):
    """(onnx path, tokenizer.json path) for a candidate, downloading them once."""
    repo, onnx, tok_repo, *_ = CANDIDATES[VARIANTS.get(name, (name,))[0]]
    return _download(repo, onnx), _download(tok_repo or repo, "tokenizer.json")


class Candidate:
    """One candidate model. embed(text) is what the app would store; embed_query(text) adds the query prefix."""

    def __init__(self, name, threads=2):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer
        self.np, self.name = np, name
        base, output, self.dims = VARIANTS.get(name, (name, None, None))
        _, _, _, self.pooling, self.doc_prefix, self.query_prefix, *_ = CANDIDATES[base]
        model, tok = files(name)
        self.model_bytes = os.path.getsize(model) + (os.path.getsize(model + "_data") if os.path.exists(model + "_data") else 0)
        self.tok = Tokenizer.from_file(tok)
        self.tok.no_padding()
        self.tok.enable_truncation(512)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        self.s = ort.InferenceSession(model, opts)
        self.inputs = {i.name for i in self.s.get_inputs()}
        outs = [o.name for o in self.s.get_outputs()]
        self.out = "sentence_embedding" if "sentence_embedding" in outs and output != "hidden" else outs[0]

    def _run(self, text):
        np = self.np
        ids = np.array([self.tok.encode(text).ids], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
        if "token_type_ids" in self.inputs:
            feed["token_type_ids"] = np.zeros_like(ids)
        if "position_ids" in self.inputs:  # a decoder (Qwen3): positions, and an empty cache
            feed["position_ids"] = np.arange(ids.shape[1], dtype=np.int64)[None, :]
            for i in self.s.get_inputs():
                if i.name.startswith("past_key_values"):
                    shape = [1 if d == "batch_size" else 0 if isinstance(d, str) else d for d in i.shape]
                    feed[i.name] = np.zeros(shape, dtype=np.float16 if "float16" in i.type else np.float32)
        o = self.s.run([self.out], feed)[0]
        if o.ndim == 2:  # the export already pooled (and projected)
            v = o[0]
        elif self.pooling == "cls":
            v = o[0, 0]
        elif self.pooling == "last":
            v = o[0, -1]
        else:
            v = o[0].mean(axis=0)
        v = v.astype(np.float64)[:self.dims]
        return v / np.linalg.norm(v)

    def embed(self, text):
        return self._run(self.doc_prefix + text)


def speed(emb, texts, rounds=3):
    """Median milliseconds to embed one text, on this computer (only the ratio to bge-small means anything)."""
    times = []
    for _ in range(rounds):
        for t in texts:
            a = time.perf_counter()
            emb.embed(t)
            times.append((time.perf_counter() - a) * 1000)
    times.sort()
    return round(times[len(times) // 2], 2)
