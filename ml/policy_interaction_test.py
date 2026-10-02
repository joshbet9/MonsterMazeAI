#!/usr/bin/env python3
"""Pairwise policy test with explicit state x action interaction features."""
from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np

from policy_trainer import (
    MLP,
    adam_state,
    build_targets,
    grouped_indices,
    grouped_split,
    load_rows,
    prepare,
    ranking_metrics,
)


STATE_PARTS = list(range(38)) + list(range(44, 52))
ACTION_PARTS = list(range(38, 44))
INPUT_SIZE = len(STATE_PARTS) + len(ACTION_PARTS) + len(STATE_PARTS) * len(ACTION_PARTS)


def augment(x):
    state = x[:, STATE_PARTS]
    action = x[:, ACTION_PARTS]
    interaction = (state[:, :, None] * action[:, None, :]).reshape(len(x), -1)
    return np.concatenate((state, action, interaction), axis=1)


def standardise(train_x, valid_x):
    mean = train_x.mean(0)
    std = np.where(train_x.std(0) < 1e-8, 1.0, train_x.std(0))
    return (train_x - mean) / std, (valid_x - mean) / std


def pairwise_train(train_x, train_y, valid_x, valid_y, train_rows, valid_rows,
                   epochs, batch_size, samples_per_epoch, lr, temperature,
                   seed, patience):
    if temperature <= 0:
        raise ValueError("temperature must be positive")

    groups = grouped_indices(train_rows)
    pairs = []
    for group in groups:
        targets = train_y[group]
        for a in range(len(group)):
            for b in range(a + 1, len(group)):
                delta = float(targets[a] - targets[b])
                if abs(delta) < 1e-9:
                    continue
                if delta > 0:
                    pairs.append((int(group[a]), int(group[b])))
                else:
                    pairs.append((int(group[b]), int(group[a])))

    if not pairs:
        raise ValueError("No non-tied action pairs")

    pairs = np.asarray(pairs, dtype=np.int64)
    model = MLP(INPUT_SIZE, 48, 24, seed)
    adam = adam_state(model)
    rng = np.random.default_rng(seed)
    best = (-1.0, -1.0)
    best_state = None
    stale = 0
    pair_batch_size = max(1, batch_size // 2)

    for epoch in range(1, epochs + 1):
        rng.shuffle(pairs)
        limit = min(len(pairs), max(1, samples_per_epoch // 2))

        for start in range(0, limit, pair_batch_size):
            pb = pairs[start:start + pair_batch_size]
            lx = train_x[pb[:, 0]]
            rx = train_x[pb[:, 1]]
            bx = np.concatenate((lx, rx), axis=0)

            score, cache = model.forward(bx)
            n = len(pb)
            diff = np.clip((score[:n] - score[n:]) / temperature, -30.0, 30.0)
            p = 1.0 / (1.0 + np.exp(-diff))
            g = (p - 1.0) / temperature
            dy = np.concatenate((g, -g))
            model.step(model.gradients(cache, dy), adam, epoch, lr)

        valid_score, _ = model.forward(valid_x)
        m = ranking_metrics(valid_rows, valid_score)

        if epoch == 1 or epoch % 10 == 0:
            print(
                f"epoch={epoch:4d} "
                f"validation_pairwise={m['pairwise_accuracy']:.4f} "
                f"validation_top1={m['top1_accuracy']:.4f} "
                f"decision_points={m['decision_points']}"
            )

        current = (m["top1_accuracy"], m["pairwise_accuracy"])
        if current > best:
            best = current
            stale = 0
            best_state = [
                model.w1.copy(), model.b1.copy(),
                model.w2.copy(), model.b2.copy(),
                model.w3.copy(), model.b3.copy(),
            ]
        else:
            stale += 1
            if stale >= patience:
                print(f"early_stop epoch={epoch} patience={patience}")
                break

    if best_state is not None:
        model.w1[:], model.b1[:], model.w2[:], model.b2[:], model.w3[:], model.b3[:] = best_state

    train_score, _ = model.forward(train_x)
    valid_score, _ = model.forward(valid_x)
    return ranking_metrics(train_rows, train_score), ranking_metrics(valid_rows, valid_score)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch-size", type=int, default=512)
    ap.add_argument("--samples-per-epoch", type=int, default=30000)
    ap.add_argument("--learning-rate", type=float, default=0.0005)
    ap.add_argument("--pair-temperature", type=float, default=1.0)
    ap.add_argument("--validation-fraction", type=float, default=0.20)
    ap.add_argument("--seed", type=int, default=1337)
    ap.add_argument("--patience", type=int, default=25)
    args = ap.parse_args()

    raw = load_rows(Path(args.input))
    rows = build_targets(raw, 0.995)
    train, valid = grouped_split(rows, args.validation_fraction, args.seed)
    train_x, train_y = prepare(train)
    valid_x, valid_y = prepare(valid)

    train_x = augment(train_x)
    valid_x = augment(valid_x)
    train_x, valid_x = standardise(train_x, valid_x)

    print(
        f"raw_features=52 state_features={len(STATE_PARTS)} "
        f"action_features={len(ACTION_PARTS)} "
        f"interaction_features={len(STATE_PARTS)*len(ACTION_PARTS)} "
        f"input_features={INPUT_SIZE}"
    )
    print(
        f"rows={len(rows)} train_rows={len(train)} valid_rows={len(valid)} "
        f"train_decisions={len(grouped_indices(train))} "
        f"valid_decisions={len(grouped_indices(valid))}"
    )

    tr, va = pairwise_train(
        train_x, train_y, valid_x, valid_y, train, valid,
        args.epochs, args.batch_size, args.samples_per_epoch,
        args.learning_rate, args.pair_temperature, args.seed, args.patience,
    )

    print("\nFINAL")
    print({
        "train_pairwise_accuracy": tr["pairwise_accuracy"],
        "train_top1_accuracy": tr["top1_accuracy"],
        "validation_pairwise_accuracy": va["pairwise_accuracy"],
        "validation_top1_accuracy": va["top1_accuracy"],
        "validation_decision_points": va["decision_points"],
    })


if __name__ == "__main__":
    main()
