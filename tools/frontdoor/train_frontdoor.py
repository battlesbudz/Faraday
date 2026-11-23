#!/usr/bin/env python3
"""Train the front-door classifier head and emit the app asset + CI fixture.

Head: multinomial logistic regression (softmax) over the app's stored
embedding form (EmbeddingGemma-2 sentence embedding truncated to 256 dims and
L2-renormalized). Trained off-device with numpy; the shipped asset is plain
JSON the app scores with identical math.

Decision function (implemented identically in the app's FrontDoorClassifier):
  pA = probs(emb of current message); pB = probs(emb of window input) if any
  discussionConfident = chat is pA's top class, pA[CHAT] >= thresholds.discussion,
                        and pA[ACT] + pA[READ] < thresholds.offer
  offered(p)          = p[ACT] + p[READ] >= thresholds.offer
  toolsOffered        = offered(pA) or (not discussionConfident and offered(pB))
  actIntended         = pA[ACT] >= thresholds.act or
                        (not discussionConfident and pB[ACT] >= thresholds.act)
A confidently-chat current message (a cancellation, a topic change, talk
about actions) stands on its own: the window may resolve fragments and
continuations, but it can never override the message in front of it.
The veto signal is separate and more sensitive: chat is the current
message's top class, clears thresholds.veto, and the message does not lean
action at the act threshold — and it is only ever computed for a turn the
window did not carry, so it can never eat a window-carried request.

Thresholds are tuned on the TRAIN split only and frozen into the asset.
The held-out split is evaluated once, here, and again in CI by the app's JVM
test against the shipped weights and the fixture embeddings emitted here.

Usage:
  python3 train_frontdoor.py --embeddings embeddings_v1.jsonl \
      --head-out head_v1.json --fixture-out holdout_v1.json \
      --metrics-out metrics_v1.json
"""
import argparse
import json

import numpy as np

CLASSES = ["CHAT", "READ", "ACT"]
IDX = {c: i for i, c in enumerate(CLASSES)}
DIM = 256


def f32(x):
    return np.asarray(x, dtype=np.float64).astype(np.float32).astype(np.float64)


def softmax(logits):
    z = logits - logits.max(axis=-1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=-1, keepdims=True)


def train_head(X, y, sample_weight, epochs=3000, lr=0.05, l2=1e-3, seed=20261122):
    rng = np.random.default_rng(seed)
    n, d = X.shape
    W = rng.normal(0, 0.01, size=(len(CLASSES), d))
    b = np.zeros(len(CLASSES))
    mW = np.zeros_like(W); vW = np.zeros_like(W)
    mb = np.zeros_like(b); vb = np.zeros_like(b)
    Y = np.zeros((n, len(CLASSES))); Y[np.arange(n), y] = 1.0
    sw = sample_weight / sample_weight.mean()
    for t in range(1, epochs + 1):
        P = softmax(X @ W.T + b)
        G = (P - Y) * sw[:, None] / n
        gW = G.T @ X + l2 * W
        gb = G.sum(axis=0)
        for param, grad, m, v in ((W, gW, mW, vW), (b, gb, mb, vb)):
            m[:] = 0.9 * m + 0.1 * grad
            v[:] = 0.999 * v + 0.001 * grad * grad
            mh = m / (1 - 0.9 ** t); vh = v / (1 - 0.999 ** t)
            param -= lr * mh / (np.sqrt(vh) + 1e-8)
    return W, b


def decide(pA, pB, th):
    act_a = pA[IDX["ACT"]] >= th["act"]
    # Discussion-confident: the current message on its own is confidently
    # chat — chat is its top class, clears the discussion threshold, and
    # would not earn tools by itself. Such a message (talk about actions, a
    # cancellation, a topic change) stands on its own: it blocks the window
    # upgrade.
    disc = (pA[IDX["CHAT"]] >= pA[IDX["ACT"]] and pA[IDX["CHAT"]] >= pA[IDX["READ"]]
            and pA[IDX["CHAT"]] >= th["discussion"]
            and pA[IDX["ACT"]] + pA[IDX["READ"]] < th["offer"])
    upgrade = not disc
    offered_a = pA[IDX["ACT"]] + pA[IDX["READ"]] >= th["offer"]
    offered_b = pB is not None and pB[IDX["ACT"]] + pB[IDX["READ"]] >= th["offer"]
    act_b = pB is not None and pB[IDX["ACT"]] >= th["act"]
    offered = offered_a or (upgrade and offered_b)
    act = act_a or (upgrade and act_b)
    upgraded = upgrade and ((not offered_a and offered_b) or (not act_a and act_b))
    # Veto signal: a separate, more sensitive read of the current message
    # alone. It may only judge a turn the window did NOT carry — a fragment
    # or continuation the conversation turned into a request is exempt by
    # construction, so the veto can never eat a window-carried request. It
    # also never fires when the message itself leans action at the act
    # threshold: a veto false positive silently drops a real gated request
    # before the gate (the 1972 harm), while a false negative merely ends
    # at the approval card, which is the declared safety net.
    veto = (not upgraded and pA[IDX["CHAT"]] >= pA[IDX["ACT"]]
            and pA[IDX["CHAT"]] >= pA[IDX["READ"]]
            and pA[IDX["CHAT"]] >= th.get("veto", 2.0)
            and pA[IDX["ACT"]] < th["act"])
    return {"toolsOffered": bool(offered), "actIntended": bool(act),
            "discussionConfident": bool(disc), "vetoConfident": bool(veto)}


def probs_of(W, b, emb):
    return softmax(f32(W) @ np.asarray(emb, dtype=np.float64) + f32(b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--embeddings", required=True)
    ap.add_argument("--head-out", required=True)
    ap.add_argument("--fixture-out", required=True)
    ap.add_argument("--metrics-out", required=True)
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(args.embeddings) if l.strip()]
    train = [r for r in rows if r["split"] == "train"]
    holdout = [r for r in rows if r["split"] == "holdout"]

    def pair_of(r):
        # The label describes the decision input: the window embedding when a
        # previous user message exists, else the message alone.
        return (r["emb_b"], r["label"]) if r.get("previous") is not None else (r["emb_a"], r["label"])

    pairs = [pair_of(r) for r in train]
    X = np.array([p[0] for p in pairs], dtype=np.float64)
    y = np.array([IDX[p[1]] for p in pairs])
    # Recall on ACT is the expensive direction (a missed action starved the
    # approval gate on build 1972), so ACT pairs carry extra weight.
    weights = np.array([1.5 if r["label"] == "ACT" else 1.0 for r in train])
    W, b = train_head(X, y, weights)
    W32, b32 = f32(W), f32(b)

    def turn_probs(r):
        pA = probs_of(W32, b32, r["emb_a"])
        pB = probs_of(W32, b32, r["emb_b"]) if r.get("emb_b") is not None else None
        return pA, pB

    # --- threshold tuning on TRAIN only: one joint grid search ---
    # The three thresholds interact (the discussion threshold gates the
    # window upgrade the other two rely on), so they are chosen together.
    # Lexicographic objective over train turn decisions:
    #   1. ACT toolsOffered recall must be 1.0 (a missed action starved the
    #      approval gate on build 1972 — the expensive failure).
    #   2. Maximize ACT actIntended recall.
    #   3. Maximize the discussion-flag rate (the veto signal) — subject to
    #      never costing an ACT turn its decisions, which rules 1-2 enforce.
    #   4. Maximize the CHAT not-offered rate (fewer needless tool offers).
    train_turns = [(r, *turn_probs(r)) for r in train]
    act_turns = [(pA, pB) for r, pA, pB in train_turns if r["label"] == "ACT"]
    chat_turns = [(pA, pB) for r, pA, pB in train_turns if r["label"] == "CHAT"]
    disc_turns = [(pA, pB) for r, pA, pB in train_turns if r["kind"] == "discussion"]

    def rates(th_try):
        act = [decide(pA, pB, th_try) for pA, pB in act_turns]
        offered = sum(d["toolsOffered"] for d in act) / max(1, len(act))
        intended = sum(d["actIntended"] for d in act) / max(1, len(act))
        disc = sum(decide(pA, pB, th_try)["discussionConfident"] for pA, pB in disc_turns) / max(1, len(disc_turns))
        chat_off = sum(not decide(pA, pB, th_try)["toolsOffered"] for pA, pB in chat_turns) / max(1, len(chat_turns))
        # Generalization margin: how far the weakest train ACT turn's best
        # offered score sits above the offer threshold. A threshold that
        # merely clears the train set fails on the first unseen phrasing.
        def best_score(pA, pB, thx):
            s = pA[IDX["ACT"]] + pA[IDX["READ"]]
            d = decide(pA, pB, thx)
            if pB is not None and not d["discussionConfident"]:
                s = max(s, pB[IDX["ACT"]] + pB[IDX["READ"]])
            return s
        margin = min(best_score(pA, pB, th_try) - th_try["offer"] for pA, pB in act_turns) if act_turns else 0.0
        return offered, intended, disc, chat_off, margin

    best = None
    for margin_floor in (0.12, 0.05, 0.0):
        for offer in np.arange(0.30, 0.601, 0.02):
            for act_t in np.arange(0.30, 0.701, 0.02):
                for disc_t in np.arange(0.50, 0.901, 0.02):
                    th_try = {"offer": float(offer), "act": float(act_t), "discussion": float(disc_t)}
                    offered, intended, disc, chat_off, margin = rates(th_try)
                    if offered < 1.0 or margin < margin_floor:
                        continue
                    key = (round(intended, 4), round(disc, 4), round(chat_off, 4), round(margin, 4))
                    if best is None or key > best[0]:
                        best = (key, th_try)
        if best is not None:
            break
    if best is None:
        raise SystemExit("no threshold triple reaches train ACT offered recall 1.0")
    th = {k: round(v, 4) for k, v in best[1].items()}
    print("thresholds:", th, "train rates (offered, intended, discussion, chat_not_offered, margin):", rates(th))

    # Veto threshold, tuned separately: it touches no other decision. The
    # constraint is that no train ACT turn whose current message stands
    # alone (single, not window-carried) is ever veto-flagged; among the
    # thresholds satisfying it, maximize the discussion flag rate.
    single_act = [(pA, pB) for r, pA, pB in train_turns if r["label"] == "ACT" and r.get("previous") is None]
    veto_tau = 0.85
    best_veto = -1.0
    for tau in np.arange(0.45, 0.851, 0.01):
        th_try = dict(th, veto=float(tau))
        if any(decide(pA, pB, th_try)["vetoConfident"] for pA, pB in single_act):
            continue
        score = sum(decide(pA, pB, th_try)["vetoConfident"] for pA, pB in disc_turns) / max(1, len(disc_turns))
        if score > best_veto:
            best_veto = score; veto_tau = float(tau)
    th["veto"] = round(veto_tau, 4)
    print("veto threshold:", th["veto"], "train discussion veto rate:", best_veto)
    print("thresholds:", th)

    # --- held-out evaluation ---
    def evaluate(split_rows):
        m = {"n": len(split_rows)}
        act_rows = [r for r in split_rows if r["label"] == "ACT"]
        read_rows = [r for r in split_rows if r["label"] == "READ"]
        chat_rows = [r for r in split_rows if r["label"] == "CHAT"]
        disc_rows = [r for r in split_rows if r["kind"] == "discussion"]
        must = [r for r in split_rows if r["kind"] == "mustpass"]
        dec = {}
        for r in split_rows:
            pA, pB = turn_probs(r)
            dec[id(r)] = decide(pA, pB, th)
        m["act_offered_recall"] = sum(dec[id(r)]["toolsOffered"] for r in act_rows) / max(1, len(act_rows))
        m["act_intended_recall"] = sum(dec[id(r)]["actIntended"] for r in act_rows) / max(1, len(act_rows))
        m["read_offered_rate"] = sum(dec[id(r)]["toolsOffered"] for r in read_rows) / max(1, len(read_rows))
        m["chat_not_offered_rate"] = sum(not dec[id(r)]["toolsOffered"] for r in chat_rows) / max(1, len(chat_rows))
        m["discussion_flagged_rate"] = sum(dec[id(r)]["vetoConfident"] for r in disc_rows) / max(1, len(disc_rows))
        m["mustpass_passed"] = sum(dec[id(r)]["toolsOffered"] and dec[id(r)]["actIntended"] for r in must)
        m["mustpass_total"] = len(must)
        m["misses"] = [
            {"current": r["current"], "previous": r.get("previous"), "label": r["label"],
             "kind": r["kind"], "decision": dec[id(r)]}
            for r in split_rows
            if (r["label"] == "ACT" and not dec[id(r)]["toolsOffered"])
            or (r["label"] == "CHAT" and dec[id(r)]["toolsOffered"])
        ]
        return m

    metrics = {"thresholds": th, "train": evaluate(train), "holdout": evaluate(holdout),
               "train_pairs": len(pairs),
               "counts": {"train": len(train), "holdout": len(holdout)}}
    print(json.dumps(metrics, indent=1))

    head = {
        "format": 1,
        "model": "embeddinggemma-2-text-270m",
        "embeddingForm": "sentence_embedding truncated to 256 dims, L2-renormalized",
        "promptFormat": "task: classification | query: <text>",
        "dim": DIM,
        "classes": CLASSES,
        "weights": [[float(v) for v in row] for row in W32],
        "bias": [float(v) for v in b32],
        "thresholds": th,
        "provenance": "tools/frontdoor/train_frontdoor.py over tools/frontdoor/examples_v1.jsonl",
    }
    with open(args.head_out, "w") as fh:
        json.dump(head, fh)

    fixture_entries = []
    for r in holdout:
        pA, pB = turn_probs(r)
        fixture_entries.append({
            "current": r["current"], "previous": r.get("previous"),
            "label": r["label"], "kind": r["kind"],
            "embA": [float(v) for v in f32(r["emb_a"])],
            "embB": [float(v) for v in f32(r["emb_b"])] if r.get("emb_b") is not None else None,
            "expect": decide(pA, pB, th),
        })
    with open(args.fixture_out, "w") as fh:
        json.dump({"format": 1, "entries": fixture_entries}, fh)
    with open(args.metrics_out, "w") as fh:
        json.dump(metrics, fh, indent=1)


if __name__ == "__main__":
    main()
