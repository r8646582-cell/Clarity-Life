"""Makes the meaning model file bundled in the app (Max plan Phase 4), so the bytes in the APK can be made again.

The model is MongoDB/mdbr-leaf-mt (Apache-2.0), its int8 ONNX export, at a pinned revision. That export keeps its
weights in a second file (model_quantized.onnx_data); ONNX Runtime on the phone reads one file from storage, so
the two are joined into one. Nothing in the graph changes.

NOT part of check_all.sh (it downloads). Run it only when the bundled model changes, then make the reference:
    /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy huggingface_hub onnx
    /tmp/venv/bin/python tools/bundle_embedding_model.py
    /tmp/venv/bin/python tools/make_embedding_vectors.py app/src/main/assets/models/mdbr-leaf-mt
"""
import hashlib
import json
import os
import shutil
import tempfile

import onnx
from huggingface_hub import hf_hub_download

REPO = "MongoDB/mdbr-leaf-mt"
REVISION = "1ed41b22ce166d66c24f88ebfc340e1f03adb20f"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models", "mdbr-leaf-mt")


def main():
    tmp = tempfile.mkdtemp()  # the download cache holds symbolic links, which onnx refuses to follow
    model = os.path.join(tmp, "model_quantized.onnx")
    for name in ("model_quantized.onnx", "model_quantized.onnx_data"):
        shutil.copyfile(hf_hub_download(REPO, "onnx/" + name, revision=REVISION), os.path.join(tmp, name))
    tok = json.load(open(hf_hub_download(REPO, "tokenizer.json", revision=REVISION), encoding="utf-8"))
    os.makedirs(OUT, exist_ok=True)
    m = onnx.load(model, load_external_data=True)
    onnx.checker.check_model(m)
    out = os.path.join(OUT, "model_quantized.onnx")
    onnx.save_model(m, out, save_as_external_data=False)
    # vocab.txt for the app's WordPiece, in id order (the same 30,522 words as BERT uncased)
    vocab = tok["model"]["vocab"]
    words = sorted(vocab, key=vocab.get)
    assert [vocab[w] for w in words] == list(range(len(words)))
    with open(os.path.join(OUT, "vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(words) + "\n")
    shutil.rmtree(tmp)
    data = open(out, "rb").read()
    print("wrote", out, len(data), "bytes, sha256", hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
