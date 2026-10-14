"""Makes the meaning model files bundled in the app (Max plan Phase 4), so the bytes in the APK can be made again.

The model is Alibaba-NLP/gte-base-en-v1.5 (Apache-2.0), its own int8 ONNX export, at a pinned revision, unchanged.
GitHub refuses files over 100 MB, so the 147 MB file is kept as two parts (model_quantized.onnx.part1, .part2);
the app joins them into one file on the phone the first time it loads the model (memory/Embedder.kt), and the
Python mirrors join them in memory (memory_exam.Embedder).

NOT part of check_all.sh (it downloads). Run it only when the bundled model changes, then make the reference:
    /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy huggingface_hub
    /tmp/venv/bin/python tools/bundle_embedding_model.py
    /tmp/venv/bin/python tools/make_embedding_vectors.py app/src/main/assets/models/gte-base-en-v1.5
"""
import hashlib
import json
import os

from huggingface_hub import hf_hub_download

REPO = "Alibaba-NLP/gte-base-en-v1.5"
REVISION = "a829fd0e060bb84554da0dfd354d0de0f7712b7f"
PART_BYTES = 80_000_000  # under GitHub's 100 MB limit
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models", "gte-base-en-v1.5")


def parts(directory):
    """The parts of the bundled model, in order."""
    names = sorted(n for n in os.listdir(directory) if n.startswith("model_quantized.onnx.part"))
    return [os.path.join(directory, n) for n in names]


def model_bytes(directory):
    """The bundled model, joined."""
    return b"".join(open(p, "rb").read() for p in parts(directory))


def main():
    data = open(hf_hub_download(REPO, "onnx/model_quantized.onnx", revision=REVISION), "rb").read()
    tok = json.load(open(hf_hub_download(REPO, "tokenizer.json", revision=REVISION), encoding="utf-8"))
    os.makedirs(OUT, exist_ok=True)
    for old in parts(OUT):
        os.remove(old)
    for i in range(0, len(data), PART_BYTES):
        with open(os.path.join(OUT, "model_quantized.onnx.part%d" % (i // PART_BYTES + 1)), "wb") as f:
            f.write(data[i:i + PART_BYTES])
    # vocab.txt for the app's WordPiece, in id order (the same 30,522 words as BERT uncased)
    vocab = tok["model"]["vocab"]
    words = sorted(vocab, key=vocab.get)
    assert [vocab[w] for w in words] == list(range(len(words)))
    with open(os.path.join(OUT, "vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(words) + "\n")
    assert model_bytes(OUT) == data
    print("wrote", len(parts(OUT)), "parts,", len(data), "bytes, sha256", hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
