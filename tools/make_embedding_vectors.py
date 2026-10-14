"""Writes contracts/embedding_vectors.json: the reference the Kotlin tokenizer and embedder must match.

NOT part of check_all.sh: it needs packages the contract checks never install. Run it only when the bundled
model changes:
    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install onnxruntime==1.20.1 tokenizers numpy
    /tmp/venv/bin/python tools/make_embedding_vectors.py app/src/main/assets/models/gte-base-en-v1.5

Reference implementation: the Hugging Face tokenizer of the model (its tokenizer.json at the revision
tools/bundle_embedding_model.py pins, BERT uncased WordPiece) and ONNX Runtime on the very model file bundled in
the app (its two parts joined). The vector (Max plan Phase 4, gte-base-en-v1.5): the first token's ([CLS]) last
hidden state, 768 numbers, L2-normalised, as the model card does it. The model takes no token_type_ids.
"""
import hashlib, json, os, sys
import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from tokenizers import Tokenizer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bundle_embedding_model import REPO, REVISION, model_bytes  # noqa: E402

DIM = 768
src = sys.argv[1]
model = model_bytes(src)
tok = Tokenizer.from_file(hf_hub_download(REPO, "tokenizer.json", revision=REVISION))
tok.no_padding()
tok.enable_truncation(512)
session = ort.InferenceSession(model)

TOKENIZER_CASES = [
    "hello",
    "Hello, World!",
    "i only study well in the morning, evenings are dead for me",
    "I'll properly start from the 1st... maybe.",
    "don't can't won't",
    "Café naïve résumé Ångström",
    "İstanbul ΣΊΣΥΦΟΣ straße",
    "my sister Hina helps me revise on sundays 😊",
    "price: $40 + 5% = <ok> ^_^ `x` | ~",
    "email me at test@example.com or visit https://example.com/a?b=c",
    "中文字符 mixed with English",
    "tabs\tand\nnew lines\r\nand  double  spaces",
    "zero​width and soft­hyphen and nbsp here",
    "x" * 120,
    "supercalifragilisticexpialidocious antidisestablishmentarianism",
    "1234567 3.14159 2026-10-10 21:40",
    "Mr. O'Neil's “quoted” ‘words’ — and – dashes",
    "",
    "   ",
    "word " * 600,
]

EMBED_CASES = [
    "i only study well in the morning, evenings are dead for me",
    "my sister Hina helps me revise on sundays",
    "I feel anxious before driving on the motorway",
    "should I take the job offer?",
]


def embed(text):
    ids = np.array([tok.encode(text).ids], dtype=np.int64)
    out = session.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": np.ones_like(ids)})[0]
    v = out[0, 0].astype(np.float64)
    return v / np.linalg.norm(v)


vectors = [embed(t) for t in EMBED_CASES]
data = {
    "_comment": "Made by tools/make_embedding_vectors.py from the bundled model. The Kotlin WordPiece tokenizer must give exactly these ids (TokenizerTest, JVM); the on-device embedder must give vectors with cosine >= 0.99 to these (EmbedderTest, emulator).",
    "model": "gte-base-en-v1.5 (Alibaba-NLP, its int8 ONNX export onnx/model_quantized.onnx at revision %s, bundled in two parts), CLS pooling" % REVISION[:12],
    "model_sha256": hashlib.sha256(model).hexdigest(),
    "dim": DIM,
    "max_tokens": 512,
    "tokenizer": [{"text": t, "ids": tok.encode(t).ids} for t in TOKENIZER_CASES],
    "embeddings": [{"text": t, "vector": [round(float(x), 6) for x in v]} for t, v in zip(EMBED_CASES, vectors)],
    "similar": [
        {"a": 2, "b": 3, "cosine": round(float(vectors[2] @ vectors[3]), 4)},
        {"a": 0, "b": 1, "cosine": round(float(vectors[0] @ vectors[1]), 4)},
    ],
}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "contracts", "embedding_vectors.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("wrote", out)
