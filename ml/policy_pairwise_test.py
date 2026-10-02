#!/usr/bin/env python3
"""Standalone pairwise policy-learning experiment using existing counterfactual data."""
from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np

from policy_trainer import (
    MLP,
    FEATURE_COUNT,
    adam_state,
    build_targets,
    grouped_indices,
    grouped_split,
    load_rows,
    prepare,
    ranking_metrics,
    standardise,
)


def train_pairwise(train_x, train_y, valid_x, valid_y, train_rows, valid_rows,
                   epochs, batch_size, samples_per_epoch, learning_rate,
                   pair_temperature, seed, patience):
    if pair_temperature <= 0:
        raise ValueError("pair_temperature must be positive")

    train_groups = grouped_indices(train_rows)
    pairs = []
    for indices in train_groups:
        targets = train_y[indices]
        for a in range(len(indices)):
            for b in range(a + 1, len(indices)):
                delta = float(targets[a] - targets[b])
                if abs(delta) < 1e-9:
                    continue
                if delta > 0:
                    pairs.append((int(indices[a]), int(indices[b])))
                else:
                    pairs.append((int(indices[b]), int(indices[a])))

    if not pairs:
        raise ValueError("No non-tied training pairs")

    pairs = np.asarray(pairs, dtype=np.int64)
    model = MLP(FEATURE_COUNT, 48, 24, seed)
    adam = adam_state(model)
    rng = np.random.default_rng(seed)

    best_pairwise = -1.0
    best_top1 = -1.0
    best_state = None
    stale = 0
    rng_py = random.Random(seed)

    for epoch in range(1, epochs + 1):
        rng.shuffle(pairs)
        limit = min(len(pairs), max(1, samples_per_epoch // 2))
        pair_batch_size = max(1, batch_size // 2)

        for start in range(0, limit, pair_batch_size):
            pair_batch = pairs[start:start + pair_batch_size]
            left = train_x[pair_batch[:, 0]]
            right = train_x[pair_batch[:, 1]]
            pair_x = np.concatenate((left, right), axis=0)

            scores, cache = model.forward(pair_x)
            n = len(pair_batch)
            diff = (scores[:n] - scores[n:]) / pair_temperature
            diff = np.clip(diff, -30.0, 30.0)
            win_probability = 1.0 / (1.0 + np.exp(-diff))
            pair_gradient = (win_probability - 1.0) / pair_temperature
            dy = np.concatenate((pair_gradient, -pair_gradient))

            model.step(
                model.gradients(cache, dy),
                adam,
                epoch,
                learning_rate,
            )

        valid_scores, _ = model.forward(valid_x)
        metrics = ranking_metrics(valid_rows, valid_scores)

        if epoch == 1 or epoch % 10 == 0:
            print(
                f"epoch={epoch:4d} "
                f"validation_pairwise={metrics['pairwise_accuracy']:.4f} "
                f"validation_top1={metrics['top1_accuracy']:.4f} "
                f"decision_points={metrics['decision_points']}"
            )

        score = (metrics["top1_accuracy"], metrics["pairwise_accuracy"])
        best_score = (best_top1, best_pairwise)

        if score > best_score:
            best_top1 = metrics["top1_accuracy"]
            best_pairwise = metrics["pairwise_accuracy"]
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

    train_scores, _ = model.forward(train_x)
    valid_scores, _ = model.forward(valid_x)
    train_metrics = ranking_metrics(train_rows, train_scores)
    valid_metrics = ranking_metrics(valid_rows, valid_scores)

    return train_metrics, valid_metrics


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--samples-per-epoch", type=int, default=30000)
    parser.add_argument("--learning-rate", type=float, default=0.0005)
    parser.add_argument("--pair-temperature", type=float, default=1.0)
    parser.add_argument("--validation-fraction", type=float, default=0.20)
    parser.add_argument("--min-samples", type=int, default=500)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--patience", type=int, default=25)
    args = parser.parse_args()

    raw = load_rows(Path(args.input))
    if len(raw) < args.min_samples:
        raise SystemExit(f"Need at least {args.min_samples} usable rows; found {len(raw)}")

    rows = build_targets(raw, 0.995)
    train, valid = grouped_split(rows, args.validation_fraction, args.seed)
    train_x, train_y = prepare(train)
    valid_x, valid_y = prepare(valid)
    train_x, valid_x, _, _ = standardise(train_x, valid_x)

    print(
        f"rows={len(rows)} train_rows={len(train)} valid_rows={len(valid)} "
        f"train_decisions={len(grouped_indices(train))} "
        f"valid_decisions={len(grouped_indices(valid))}"
    )

    train_metrics, valid_metrics = train_pairwise(
        train_x, train_y, valid_x, valid_y, train, valid,
        args.epochs, args.batch_size, args.samples_per_epoch,
        args.learning_rate, args.pair_temperature, args.seed, args.patience,
    )

    print("\nFINAL")
    print({
        "train_pairwise_accuracy": train_metrics["pairwise_accuracy"],
        "train_top1_accuracy": train_metrics["top1_accuracy"],
        "validation_pairwise_accuracy": valid_metrics["pairwise_accuracy"],
        "validation_top1_accuracy": valid_metrics["top1_accuracy"],
        "validation_decision_points": valid_metrics["decision_points"],
    })


if __name__ == "__main__":
    main()
