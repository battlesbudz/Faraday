#!/usr/bin/env python3
"""Extract EmbeddingGemma-2 embeddings for the front-door classifier examples.

Pinned model: onnx-community/embeddinggemma-2-ONNX (quantized text tower),
the ONNX export of google/embeddinggemma-2 — the same weights the app ships
as embeddinggemma-2-text-270m.litertlm (litert-community pinned revision
9be6e8b90982095dc05c2bd162e4b954ee4dbac7).

Each example produces:
  - emb_a: embedding of the current message alone
  - emb_b: embedding of the window input (previous + "\\n" + current), when a
    previous user message exists
Both use the model's published classification prompt format
("task: classification | query: <text>") and the app's exact storage math:
768-dim sentence embedding truncated to the first 256 components and
L2-renormalized (MemoryVectorMath.truncateAndNormalize).

Usage:
  python3 extract_embeddings.py --model-dir <dir with onnx/ + tokenizer.json> \
      --examples examples_v1.jsonl --out embeddings_v1.jsonl
"""
import argparse
import json
import time

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

CLASSIFICATION_PREFIX = "task: classification | query: "
STORED_DIM = 256


def build_input(previous, current):
    """Single construction site mirrored by the app's FrontDoorInput.build."""
    if previous is None:
        return current
    return previous + "\n" + current


def truncate_normalize(vec):
    out = np.asarray(vec, dtype=np.float64)[:STORED_DIM]
    norm = np.linalg.norm(out)
    if norm > 0:
        out = out / norm
    return out.astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--examples", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    tok = Tokenizer.from_file(f"{args.model_dir}/tokenizer.json")
    tok.enable_padding()
    sess = ort.InferenceSession(
        f"{args.model_dir}/onnx/model_quantized.onnx",
        providers=["CPUExecutionProvider"],
    )
    input_names = [i.name for i in sess.get_inputs()]
    print("model inputs:", input_names)
    out_names = [o.name for o in sess.get_outputs()]
    print("model outputs:", out_names)

    def embed(text):
        enc = tok.encode(CLASSIFICATION_PREFIX + text)
        ids = np.array([enc.ids], dtype=np.int64)
        attn = np.array([enc.attention_mask], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": attn}
        if "token_type_ids" in input_names:
            feed["token_type_ids"] = np.zeros_like(ids)
        # The pinned ONNX export is the full multimodal graph; text-only
        # inference feeds zero-token media features (dynamic first axis).
        for media in ("image_features", "video_features", "audio_features"):
            if media in input_names:
                feed[media] = np.zeros((0, 512), dtype=np.float32)
        t0 = time.perf_counter()
        outs = sess.run(None, feed)
        ms = (time.perf_counter() - t0) * 1000.0
        by_name = dict(zip(out_names, outs))
        emb = by_name["sentence_embedding"][0]
        return truncate_normalize(emb), ms

    rows = [json.loads(l) for l in open(args.examples) if l.strip()]
    times = []
    with open(args.out, "w") as fh:
        for i, row in enumerate(rows):
            emb_a, ms = embed(row["current"])
            times.append(ms)
            rec = dict(row)
            rec["emb_a"] = [float(x) for x in emb_a]
            if row.get("previous") is not None:
                emb_b, ms_b = embed(build_input(row["previous"], row["current"]))
                times.append(ms_b)
                rec["emb_b"] = [float(x) for x in emb_b]
            fh.write(json.dumps(rec) + "\n")
            if (i + 1) % 40 == 0:
                print(f"{i + 1}/{len(rows)} embedded")
    times = np.array(times)
    print(f"embedded {len(rows)} examples, {len(times)} inputs")
    print(f"embed latency on this VM: mean {times.mean():.0f} ms, "
          f"p50 {np.percentile(times, 50):.0f} ms, p95 {np.percentile(times, 95):.0f} ms")


if __name__ == "__main__":
    main()
