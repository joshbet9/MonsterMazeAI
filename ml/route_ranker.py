#!/usr/bin/env python3
"""Train and inspect the Monster Maze route-ranking model.

The Java simulator is authoritative. Each JSONL row contains one concrete
route candidate evaluated from the same source-faithful state. The model learns
a scalar route score, but the training objective is pairwise: for candidates
from the same state, a lower simulator cost must receive a lower learned score.

This makes the learned objective match the planner's actual job: rank routes,
not predict an absolute simulator number.
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path

import numpy as np


FEATURE_COUNT = 34


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
            if not isinstance(features, list) or len(features) != FEATURE_COUNT:
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
        self.w1 = (
            rng.standard_normal((input_dim, hidden1)) * math.sqrt(2.0 / input_dim)
        ).astype(np.float64)
        self.b1 = np.zeros(hidden1, dtype=np.float64)
        self.w2 = (
            rng.standard_normal((hidden1, hidden2)) * math.sqrt(2.0 / hidden1)
        ).astype(np.float64)
        self.b2 = np.zeros(hidden2, dtype=np.float64)
        self.w3 = (
            rng.standard_normal((hidden2, 1)) * math.sqrt(2.0 / hidden2)
        ).astype(np.float64)
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
        for key, grad in zip(("w1", "b1", "w2", "b2", "w3", "b3"), grads):
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
    for key in ("w1", "b1", "w2", "b2", "w3", "b3"):
        value = getattr(model, key)
        result[key + "_m"] = np.zeros_like(value)
        result[key + "_v"] = np.zeros_like(value)
    return result


def prepare(rows: list[dict]):
    x = np.asarray([row["features"] for row in rows], dtype=np.float64)
    y = np.asarray([row["target"] for row in rows], dtype=np.float64)
    return x, y


def standardise(train_x, valid_x):
    mean = train_x.mean(axis=0)
    std = train_x.std(axis=0)
    std = np.where(std < 1e-8, 1.0, std)

    return (
        (train_x - mean) / std,
        (valid_x - mean) / std,
        mean,
        std,
    )


def build_pair_set(rows: list[dict]):
    """Return within-state preference pairs.

    Each pair is represented by (left_index, right_index, preference_sign),
    where sign=+1 means left is preferred and sign=-1 means right is preferred.
    Equal simulator targets are intentionally omitted because they contain no
    ranking information.
    """
    groups: dict[str, list[int]] = {}
    for index, row in enumerate(rows):
        groups.setdefault(str(row.get("group", "")), []).append(index)

    left: list[int] = []
    right: list[int] = []
    sign: list[float] = []

    for indices in groups.values():
        for a in range(len(indices) - 1):
            i = indices[a]
            target_i = float(rows[i]["target"])
            for b in range(a + 1, len(indices)):
                j = indices[b]
                target_j = float(rows[j]["target"])
                if target_i == target_j:
                    continue
                left.append(i)
                right.append(j)
                sign.append(1.0 if target_i < target_j else -1.0)

    return (
        np.asarray(left, dtype=np.int64),
        np.asarray(right, dtype=np.int64),
        np.asarray(sign, dtype=np.float64),
    )


def sigmoid(values: np.ndarray) -> np.ndarray:
    result = np.empty_like(values)
    positive = values >= 0.0
    result[positive] = 1.0 / (1.0 + np.exp(-values[positive]))
    exp_values = np.exp(values[~positive])
    result[~positive] = exp_values / (1.0 + exp_values)
    return result


def pairwise_logloss(scores_left: np.ndarray,
                     scores_right: np.ndarray,
                     signs: np.ndarray) -> float:
    margin = signs * (scores_left - scores_right)
    z = -margin
    return float(np.mean(np.maximum(0.0, z) + np.log1p(np.exp(-np.abs(z)))))


def pairwise_accuracy(rows: list[dict], predictions: np.ndarray) -> float:
    grouped: dict[str, list[tuple[float, float]]] = {}
    for row, pred in zip(rows, predictions):
        grouped.setdefault(str(row.get("group", "")), []).append(
            (float(row["target"]), float(pred))
        )

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


def top1_accuracy(rows: list[dict], predictions: np.ndarray) -> float:
    groups: dict[str, list[int]] = {}
    for index, row in enumerate(rows):
        groups.setdefault(str(row.get("group", "")), []).append(index)

    correct = 0
    total = 0
    for indices in groups.values():
        if len(indices) < 2:
            continue
        best_target = min(float(rows[i]["target"]) for i in indices)
        predicted_index = min(indices, key=lambda i: float(predictions[i]))
        if float(rows[predicted_index]["target"]) == best_target:
            correct += 1
        total += 1

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
    train_x, valid_x, mean, std = standardise(train_x, valid_x)
    _ = train_y, valid_y

    pair_left, pair_right, pair_sign = build_pair_set(train_rows)
    if len(pair_left) == 0:
        raise SystemExit("No non-tied within-state ranking pairs were found")

    model = MLP(train_x.shape[1], args.hidden1, args.hidden2, args.seed)
    adam = init_adam(model)

    pair_order = np.arange(len(pair_left), dtype=np.int64)
    rng = np.random.default_rng(args.seed)

    for epoch in range(1, args.epochs + 1):
        rng.shuffle(pair_order)
        if len(pair_order) > args.pair_samples_per_epoch:
            active = pair_order[:args.pair_samples_per_epoch]
        else:
            active = pair_order

        for start in range(0, len(active), args.batch_size):
            batch_pairs = active[start:start + args.batch_size]
            left = pair_left[batch_pairs]
            right = pair_right[batch_pairs]
            signs = pair_sign[batch_pairs]

            batch_x = np.concatenate((train_x[left], train_x[right]), axis=0)
            scores, cache = model.forward(batch_x)
            half = len(batch_pairs)
            score_left = scores[:half]
            score_right = scores[half:]

            margin = signs * (score_left - score_right)
            wrong_probability = sigmoid(-margin)

            dy = np.empty(scores.shape[0], dtype=np.float64)
            # MLP.gradients divides by the number of duplicated samples (2 * pairs).
            # Multiply pair gradients by 2 so the resulting network gradient is the
            # mean gradient of the pairwise loss.
            pair_grad = -2.0 * signs * wrong_probability
            dy[:half] = pair_grad
            dy[half:] = -pair_grad

            grads = model.gradients(cache, dy)
            model.step(grads, adam, epoch, args.learning_rate)

        if epoch == 1 or epoch % args.report_every == 0 or epoch == args.epochs:
            valid_pred, _ = model.forward(valid_x)
            loss = pairwise_validation_logloss(valid_rows, valid_pred)
            accuracy = pairwise_accuracy(valid_rows, valid_pred)
            top1 = top1_accuracy(valid_rows, valid_pred)
            print(
                f"epoch={epoch:4d} validation_pairwise_logloss={loss:.4f} "
                f"pairwise_accuracy={accuracy:.4f} top1_accuracy={top1:.4f}"
            )

    train_pred, _ = model.forward(train_x)
    valid_pred, _ = model.forward(valid_x)
    train_accuracy = pairwise_accuracy(train_rows, train_pred)
    valid_accuracy = pairwise_accuracy(valid_rows, valid_pred)
    train_top1 = top1_accuracy(train_rows, train_pred)
    valid_top1 = top1_accuracy(valid_rows, valid_pred)
    train_loss = pairwise_logloss_from_rows(train_rows, train_pred)
    valid_loss = pairwise_logloss_from_rows(valid_rows, valid_pred)

    payload = {
        "version": 2,
        "objective": "pairwise_route_ranking",
        "architecture": [len(mean), args.hidden1, args.hidden2, 1],
        "feature_names": [
            "stage_norm", "mode_original", "mode_speed", "mode_modern",
            "kit_jumper", "kit_maverick", "kit_slowballer", "kit_repulsor", "kit_body_builder",
            "health_ratio", "horizontal_speed", "forward_speed", "lateral_speed",
            "first_heading_cos", "first_heading_sin", "grounded", "ability_charges_norm",
            "phase_ticks_norm", "goal_distance_norm", "route_size_norm", "route_distance_norm",
            "route_gap_count_norm", "route_turn_count_norm", "route_first_edge_norm",
            "route_alignment_cos", "route_deviation_norm", "monster_count_12_norm",
            "monster_count_20_norm", "nearest_monster_distance_norm", "nearest_monster_closing_norm",
            "nearest_monster_forward_norm", "nearest_monster_lateral_norm",
            "max_monster_closing_norm", "min_time_to_contact_norm"
        ],
        "input_mean": mean.tolist(),
        "input_std": std.tolist(),
        "target_mean": 0.0,
        "target_std": 1.0,
        "w1": model.w1.tolist(),
        "b1": model.b1.tolist(),
        "w2": model.w2.tolist(),
        "b2": model.b2.tolist(),
        "w3": model.w3[:, 0].tolist(),
        "b3": model.b3.tolist(),
        "metrics": {
            "rows": len(rows),
            "train_rows": len(train_rows),
            "validation_rows": len(valid_rows),
            "train_pairs": int(len(pair_left)),
            "pair_samples_per_epoch": int(args.pair_samples_per_epoch),
            "train_pairwise_logloss": train_loss,
            "validation_pairwise_logloss": valid_loss,
            "train_pairwise_accuracy": train_accuracy,
            "validation_pairwise_accuracy": valid_accuracy,
            "train_top1_accuracy": train_top1,
            "validation_top1_accuracy": valid_top1,
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))


def pairwise_logloss_from_rows(rows: list[dict], predictions: np.ndarray) -> float:
    grouped: dict[str, list[tuple[float, float]]] = {}
    for row, prediction in zip(rows, predictions):
        grouped.setdefault(str(row.get("group", "")), []).append(
            (float(row["target"]), float(prediction))
        )

    losses: list[float] = []
    for values in grouped.values():
        for i, (target_i, score_i) in enumerate(values):
            for target_j, score_j in values[i + 1:]:
                if target_i == target_j:
                    continue
                sign = 1.0 if target_i < target_j else -1.0
                margin = sign * (score_i - score_j)
                z = -margin
                losses.append(float(max(0.0, z) + math.log1p(math.exp(-abs(z)))))
    return sum(losses) / len(losses) if losses else float("nan")


def pairwise_validation_logloss(rows: list[dict], predictions: np.ndarray) -> float:
    return pairwise_logloss_from_rows(rows, predictions)


def inspect(args):
    rows = load_rows(Path(args.input))
    targets = np.asarray([row["target"] for row in rows], dtype=np.float64)
    pair_left, _, _ = build_pair_set(rows)
    reached = sum(bool(row.get("reached")) for row in rows)
    print(f"rows={len(rows)} reached={reached} failed={len(rows)-reached}")
    print(f"ranking_pairs={len(pair_left)}")
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
    train_parser.add_argument("--epochs", type=int, default=120)
    train_parser.add_argument("--batch-size", type=int, default=256)
    train_parser.add_argument("--pair-samples-per-epoch", type=int, default=50000)
    train_parser.add_argument("--hidden1", type=int, default=32)
    train_parser.add_argument("--hidden2", type=int, default=16)
    train_parser.add_argument("--learning-rate", type=float, default=0.002)
    train_parser.add_argument("--validation-fraction", type=float, default=0.20)
    train_parser.add_argument("--min-samples", type=int, default=100)
    train_parser.add_argument("--seed", type=int, default=1337)
    train_parser.add_argument("--report-every", type=int, default=20)
    train_parser.set_defaults(func=train)

    inspect_parser = sub.add_parser("inspect")
    inspect_parser.set_defaults(func=inspect)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
