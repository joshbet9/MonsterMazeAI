#!/usr/bin/env python3
"""Train and inspect the Monster Maze route-value model.

The Java simulator is authoritative. Each JSONL row contains one concrete
route candidate evaluated from the same source-faithful state. The model learns
to predict that candidate's simulator cost so expensive route evaluation can
eventually be replaced by a learned ranking layer.

This first implementation is intentionally offline/shadow-only.
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

import numpy as np


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            features = row.get("features")
            target = row.get("target")
            if not isinstance(features, list) or len(features) != 34:
                continue
            if not isinstance(target, (int, float)) or not math.isfinite(target):
                continue
            rows.append(row)
    return rows


def grouped_split(rows: list[dict], validation_fraction: float, seed: int):
    groups: dict[str, list[dict]] = {}
    for row in rows:
        groups.setdefault(str(row.get("group", "")), []).append(row)

    keys = list(groups)
    rng = random.Random(seed)
    rng.shuffle(keys)

    target_count = max(1, int(len(keys) * validation_fraction))
    validation_keys = set(keys[:target_count])

    train = [row for key, group in groups.items() if key not in validation_keys for row in group]
    valid = [row for key, group in groups.items() if key in validation_keys for row in group]
    return train, valid


def relu(x: np.ndarray) -> np.ndarray:
    return np.maximum(x, 0.0)


class MLP:
    def __init__(self, input_dim: int, hidden1: int, hidden2: int, seed: int):
        rng = np.random.default_rng(seed)
        self.w1 = (rng.standard_normal((input_dim, hidden1)) * math.sqrt(2.0 / input_dim)).astype(np.float64)
        self.b1 = np.zeros(hidden1, dtype=np.float64)
        self.w2 = (rng.standard_normal((hidden1, hidden2)) * math.sqrt(2.0 / hidden1)).astype(np.float64)
        self.b2 = np.zeros(hidden2, dtype=np.float64)
        self.w3 = (rng.standard_normal((hidden2, 1)) * math.sqrt(2.0 / hidden2)).astype(np.float64)
        self.b3 = np.zeros(1, dtype=np.float64)

    def forward(self, x: np.ndarray):
        z1 = x @ self.w1 + self.b1
        a1 = relu(z1)
        z2 = a1 @ self.w2 + self.b2
        a2 = relu(z2)
        y = a2 @ self.w3 + self.b3
        return y[:, 0], (x, z1, a1, z2, a2)

    def gradients(self, cache, dy: np.ndarray):
        x, z1, a1, z2, a2 = cache
        n = x.shape[0]
        dy2 = dy.reshape(-1, 1) / n

        dw3 = a2.T @ dy2
        db3 = dy2.sum(axis=0)
        da2 = dy2 @ self.w3.T
        dz2 = da2 * (z2 > 0)
        dw2 = a1.T @ dz2
        db2 = dz2.sum(axis=0)
        da1 = dz2 @ self.w2.T
        dz1 = da1 * (z1 > 0)
        dw1 = x.T @ dz1
        db1 = dz1.sum(axis=0)

        return (dw1, db1, dw2, db2, dw3, db3)

    def step(self, grads, state, t, learning_rate):
        beta1, beta2, eps = 0.9, 0.999, 1e-8
        for key, grad in zip(("w1","b1","w2","b2","w3","b3"), grads):
            m = state[key + "_m"]
            v = state[key + "_v"]
            m[...] = beta1 * m + (1.0 - beta1) * grad
            v[...] = beta2 * v + (1.0 - beta2) * (grad * grad)
            mhat = m / (1.0 - beta1 ** t)
            vhat = v / (1.0 - beta2 ** t)
            value = getattr(self, key)
            value[...] -= learning_rate * mhat / (np.sqrt(vhat) + eps)


def init_adam(model: MLP) -> dict[str, np.ndarray]:
    result = {}
    for key in ("w1","b1","w2","b2","w3","b3"):
        value = getattr(model, key)
        result[key + "_m"] = np.zeros_like(value)
        result[key + "_v"] = np.zeros_like(value)
    return result


def prepare(rows: list[dict]):
    x = np.asarray([row["features"] for row in rows], dtype=np.float64)
    y = np.asarray([row["target"] for row in rows], dtype=np.float64)
    return x, y


def standardise(train_x, valid_x, train_y):
    mean = train_x.mean(axis=0)
    std = train_x.std(axis=0)
    std = np.where(std < 1e-8, 1.0, std)

    target_mean = float(train_y.mean())
    target_std = float(train_y.std())
    if target_std < 1e-8:
        target_std = 1.0

    return (
        (train_x - mean) / std,
        (valid_x - mean) / std,
        (train_y - target_mean) / target_std,
        mean,
        std,
        target_mean,
        target_std,
    )


def pairwise_accuracy(rows: list[dict], predictions: np.ndarray) -> float:
    grouped: dict[str, list[tuple[float, float]]] = {}
    for row, pred in zip(rows, predictions):
        grouped.setdefault(str(row.get("group", "")), []).append((float(row["target"]), float(pred)))

    correct = 0
    total = 0
    for pairs in grouped.values():
        for i, (target_i, pred_i) in enumerate(pairs):
            for target_j, pred_j in pairs[i + 1:]:
                if target_i == target_j:
                    continue
                total += 1
                target_prefers_i = target_i < target_j
                pred_prefers_i = pred_i < pred_j
                if target_prefers_i == pred_prefers_i:
                    correct += 1
    return correct / total if total else float("nan")


def train(args):
    rows = load_rows(Path(args.input))
    if len(rows) < args.min_samples:
        raise SystemExit(f"Need at least {args.min_samples} usable rows; found {len(rows)}")

    train_rows, valid_rows = grouped_split(rows, args.validation_fraction, args.seed)
    if not train_rows or not valid_rows:
        raise SystemExit("Grouped split produced an empty train or validation set")

    train_x, train_y = prepare(train_rows)
    valid_x, valid_y = prepare(valid_rows)
    train_x, valid_x, train_y_z, mean, std, target_mean, target_std = standardise(
        train_x, valid_x, train_y
    )
    valid_y_z = (valid_y - target_mean) / target_std

    model = MLP(train_x.shape[1], args.hidden1, args.hidden2, args.seed)
    adam = init_adam(model)

    indices = np.arange(len(train_rows))
    rng = np.random.default_rng(args.seed)
    for epoch in range(1, args.epochs + 1):
        rng.shuffle(indices)
        for start in range(0, len(indices), args.batch_size):
            batch = indices[start:start + args.batch_size]
            pred, cache = model.forward(train_x[batch])
            dy = 2.0 * (pred - train_y_z[batch])
            grads = model.gradients(cache, dy)
            model.step(grads, adam, epoch, args.learning_rate)

        if epoch == 1 or epoch % args.report_every == 0 or epoch == args.epochs:
            pred, _ = model.forward(valid_x)
            mae = float(np.mean(np.abs(pred - valid_y_z)))
            print(f"epoch={epoch:4d} validation_mae_z={mae:.4f}")

    train_pred_z, _ = model.forward(train_x)
    valid_pred_z, _ = model.forward(valid_x)
    train_pred = train_pred_z * target_std + target_mean
    valid_pred = valid_pred_z * target_std + target_mean

    train_mae = float(np.mean(np.abs(train_pred - train_y)))
    valid_mae = float(np.mean(np.abs(valid_pred - valid_y)))
    accuracy = pairwise_accuracy(valid_rows, valid_pred)

    payload = {
        "version": 1,
        "architecture": [len(mean), args.hidden1, args.hidden2, 1],
        "feature_names": [
            "stage_norm","mode_original","mode_speed","mode_modern",
            "kit_jumper","kit_maverick","kit_slowballer","kit_repulsor","kit_body_builder",
            "health_ratio","horizontal_speed","forward_speed","lateral_speed",
            "first_heading_cos","first_heading_sin","grounded","ability_charges_norm",
            "phase_ticks_norm","goal_distance_norm","route_size_norm","route_distance_norm",
            "route_gap_count_norm","route_turn_count_norm","route_first_edge_norm",
            "route_alignment_cos","route_deviation_norm","monster_count_12_norm",
            "monster_count_20_norm","nearest_monster_distance_norm","nearest_monster_closing_norm",
            "nearest_monster_forward_norm","nearest_monster_lateral_norm",
            "max_monster_closing_norm","min_time_to_contact_norm"
        ],
        "input_mean": mean.tolist(),
        "input_std": std.tolist(),
        "target_mean": target_mean,
        "target_std": target_std,
        "w1": model.w1.tolist(),
        "b1": model.b1.tolist(),
        "w2": model.w2.tolist(),
        "b2": model.b2.tolist(),
        "w3": model.w3.tolist(),
        "b3": model.b3.tolist(),
        "metrics": {
            "rows": len(rows),
            "train_rows": len(train_rows),
            "validation_rows": len(valid_rows),
            "train_mae": train_mae,
            "validation_mae": valid_mae,
            "validation_pairwise_accuracy": accuracy,
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))


def inspect(args):
    rows = load_rows(Path(args.input))
    targets = np.asarray([row["target"] for row in rows], dtype=np.float64)
    reached = sum(bool(row.get("reached")) for row in rows)
    print(f"rows={len(rows)} reached={reached} failed={len(rows)-reached}")
    if len(targets):
        print(f"target_min={targets.min():.3f}")
        print(f"target_median={np.median(targets):.3f}")
        print(f"target_max={targets.max():.3f}")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(required=True)

    train_parser = sub.add_parser("train")
    train_parser.add_argument("--input", required=True)
    train_parser.add_argument("--output", required=True)
    train_parser.add_argument("--epochs", type=int, default=250)
    train_parser.add_argument("--batch-size", type=int, default=256)
    train_parser.add_argument("--hidden1", type=int, default=32)
    train_parser.add_argument("--hidden2", type=int, default=16)
    train_parser.add_argument("--learning-rate", type=float, default=0.002)
    train_parser.add_argument("--validation-fraction", type=float, default=0.20)
    train_parser.add_argument("--min-samples", type=int, default=100)
    train_parser.add_argument("--seed", type=int, default=1337)
    train_parser.add_argument("--report-every", type=int, default=25)
    train_parser.set_defaults(func=train)

    inspect_parser = sub.add_parser("inspect")
    inspect_parser.add_argument("--input", required=True)
    inspect_parser.set_defaults(func=inspect)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
