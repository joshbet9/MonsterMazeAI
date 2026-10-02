#!/usr/bin/env python3
"""Train a state/action return model from simulator counterfactual rollouts."""
from __future__ import annotations
import argparse
import json
import math
import random
from pathlib import Path

import numpy as np

FEATURE_COUNT = 52


def load_rows(path: Path):
    out = []
    with path.open("r", encoding="utf-8-sig") as handle:
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            target = row.get("target_return")
            reward = row.get("reward")
            valid_target = (
                isinstance(target, (int, float))
                and math.isfinite(float(target))
            )
            legacy = (
                isinstance(reward, (int, float))
                and math.isfinite(float(reward))
            )
            if (
                isinstance(row.get("features"), list)
                and len(row["features"]) == FEATURE_COUNT
                and (valid_target or legacy)
                and row.get("episode")
            ):
                out.append(row)
    return out


def build_targets(rows, gamma):
    # Counterfactual rows are direct simulator labels. Center them within each
    # decision point so the network is trained primarily on action advantage:
    # which candidate is better here, rather than simply which states are valuable.
    if rows and all(
        isinstance(row.get("target_return"), (int, float))
        and math.isfinite(float(row["target_return"]))
        for row in rows
    ):
        groups = {}
        for row in rows:
            key = (str(row["episode"]), int(row.get("t", 0)))
            groups.setdefault(key, []).append(row)

        out = []
        for group in groups.values():
            mean_target = sum(float(row["target_return"]) for row in group) / len(group)
            for row in group:
                copy = dict(row)
                copy["raw_target_return"] = float(row["target_return"])
                copy["return_value"] = float(row["target_return"]) - mean_target
                out.append(copy)
        return out

    # Legacy recorder rows still need the original episode return-to-go pass.
    groups = {}
    for row in rows:
        groups.setdefault(str(row["episode"]), []).append(row)

    out = []
    for group in groups.values():
        group.sort(key=lambda row: int(row.get("t", 0)))
        running = 0.0
        for row in reversed(group):
            running = float(row["reward"]) + gamma * running
            copy = dict(row)
            copy["return_value"] = running
            out.append(copy)
    return out


def ranking_metrics(rows, predictions):
    groups = {}
    for index, row in enumerate(rows):
        key = (str(row["episode"]), int(row.get("t", 0)))
        groups.setdefault(key, []).append(index)

    pair_total = 0
    pair_correct = 0
    top1_total = 0
    top1_correct = 0

    for indices in groups.values():
        if len(indices) < 2:
            continue

        target_best = max(indices, key=lambda i: float(rows[i]["return_value"]))
        predicted_best = max(indices, key=lambda i: float(predictions[i]))
        top1_total += 1
        top1_correct += int(target_best == predicted_best)

        for left_pos in range(len(indices)):
            left = indices[left_pos]
            for right_pos in range(left_pos + 1, len(indices)):
                right = indices[right_pos]
                target_delta = float(rows[left]["return_value"]) - float(rows[right]["return_value"])
                if abs(target_delta) < 1e-9:
                    continue
                predicted_delta = float(predictions[left]) - float(predictions[right])
                pair_total += 1
                pair_correct += int(
                    (target_delta > 0 and predicted_delta > 0)
                    or (target_delta < 0 and predicted_delta < 0)
                )

    return {
        "pairwise_accuracy": pair_correct / pair_total if pair_total else 0.0,
        "pairwise_pairs": pair_total,
        "top1_accuracy": top1_correct / top1_total if top1_total else 0.0,
        "decision_points": top1_total,
    }


def grouped_split(rows, fraction, seed):
    ids = sorted({str(row["episode"]) for row in rows})
    rng = random.Random(seed)
    rng.shuffle(ids)
    valid_ids = set(ids[:max(1, int(len(ids) * fraction))])
    train = [row for row in rows if str(row["episode"]) not in valid_ids]
    valid = [row for row in rows if str(row["episode"]) in valid_ids]
    return train, valid


def prepare(rows):
    x = np.asarray([row["features"] for row in rows], dtype=np.float64)
    y = np.asarray([row["return_value"] for row in rows], dtype=np.float64)
    return x, y


def standardise(train_x, valid_x):
    mean = train_x.mean(0)
    std = np.where(train_x.std(0) < 1e-8, 1.0, train_x.std(0))
    return (train_x - mean) / std, (valid_x - mean) / std, mean, std


def relu(x):
    return np.maximum(x, 0.0)


class MLP:
    def __init__(self, inp, h1, h2, seed):
        rng = np.random.default_rng(seed)
        self.w1 = (rng.standard_normal((inp, h1)) * math.sqrt(2 / inp)).astype(np.float64)
        self.b1 = np.zeros(h1)
        self.w2 = (rng.standard_normal((h1, h2)) * math.sqrt(2 / h1)).astype(np.float64)
        self.b2 = np.zeros(h2)
        self.w3 = (rng.standard_normal((h2, 1)) * math.sqrt(2 / h2)).astype(np.float64)
        self.b3 = np.zeros(1)

    def forward(self, x):
        z1 = x @ self.w1 + self.b1
        a1 = relu(z1)
        z2 = a1 @ self.w2 + self.b2
        a2 = relu(z2)
        y = a2 @ self.w3 + self.b3
        return y[:, 0], (x, z1, a1, z2, a2)

    def gradients(self, cache, dy):
        x, z1, a1, z2, a2 = cache
        n = x.shape[0]
        d = dy.reshape(-1, 1) / n
        dw3 = a2.T @ d
        db3 = d.sum(0)
        dz2 = (d @ self.w3.T) * (z2 > 0)
        dw2 = a1.T @ dz2
        db2 = dz2.sum(0)
        dz1 = (dz2 @ self.w2.T) * (z1 > 0)
        dw1 = x.T @ dz1
        db1 = dz1.sum(0)
        return dw1, db1, dw2, db2, dw3, db3

    def step(self, gradients, state, step, learning_rate):
        for key, grad in zip(("w1", "b1", "w2", "b2", "w3", "b3"), gradients):
            m = state[key + "_m"]
            v = state[key + "_v"]
            m[:] = 0.9 * m + 0.1 * grad
            v[:] = 0.999 * v + 0.001 * grad * grad
            mh = m / (1 - 0.9 ** step)
            vh = v / (1 - 0.999 ** step)
            getattr(self, key)[:] -= learning_rate * mh / (np.sqrt(vh) + 1e-8)


def adam_state(model):
    state = {}
    for key in ("w1", "b1", "w2", "b2", "w3", "b3"):
        value = getattr(model, key)
        state[key + "_m"] = np.zeros_like(value)
        state[key + "_v"] = np.zeros_like(value)
    return state


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--batch-size", type=int, default=512)
    parser.add_argument("--samples-per-epoch", type=int, default=50000)
    parser.add_argument("--hidden1", type=int, default=48)
    parser.add_argument("--hidden2", type=int, default=24)
    parser.add_argument("--learning-rate", type=float, default=0.001)
    parser.add_argument("--gamma", type=float, default=0.995)
    parser.add_argument("--validation-fraction", type=float, default=0.20)
    parser.add_argument("--min-samples", type=int, default=500)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--report-every", type=int, default=10)
    parser.add_argument("--objective", default="counterfactual_short_horizon_return")
    args = parser.parse_args()

    raw = load_rows(Path(args.input))
    if len(raw) < args.min_samples:
        raise SystemExit(
            f"Need at least {args.min_samples} usable rows; found {len(raw)}"
        )

    rows = build_targets(raw, args.gamma)
    train, valid = grouped_split(rows, args.validation_fraction, args.seed)
    train_x, train_y = prepare(train)
    valid_x, valid_y = prepare(valid)
    train_x, valid_x, input_mean, input_std = standardise(train_x, valid_x)

    target_mean = float(train_y.mean())
    target_std = max(float(train_y.std()), 1e-8)

    model = MLP(FEATURE_COUNT, args.hidden1, args.hidden2, args.seed)
    adam = adam_state(model)
    train_y_norm = (train_y - target_mean) / target_std

    rng = np.random.default_rng(args.seed)
    order = np.arange(len(train))

    for epoch in range(1, args.epochs + 1):
        rng.shuffle(order)
        active = order[:min(len(order), args.samples_per_epoch)]
        for start in range(0, len(active), args.batch_size):
            batch = active[start:start + args.batch_size]
            predicted, cache = model.forward(train_x[batch])
            model.step(
                model.gradients(cache, 2 * (predicted - train_y_norm[batch])),
                adam,
                epoch,
                args.learning_rate,
            )

        if epoch == 1 or epoch % args.report_every == 0 or epoch == args.epochs:
            valid_norm, _ = model.forward(valid_x)
            valid_pred = valid_norm * target_std + target_mean
            print(
                f"epoch={epoch:4d} "
                f"validation_mae={np.mean(np.abs(valid_y - valid_pred)):.4f} "
                f"validation_mse={np.mean((valid_y - valid_pred) ** 2):.4f}"
            )

    train_norm, _ = model.forward(train_x)
    valid_norm, _ = model.forward(valid_x)
    train_pred = train_norm * target_std + target_mean
    valid_pred = valid_norm * target_std + target_mean
    train_ranking = ranking_metrics(train, train_pred)
    valid_ranking = ranking_metrics(valid, valid_pred)

    feature_names = [
        "stage_norm", "health_ratio", "horizontal_speed", "forward_speed",
        "lateral_speed", "vertical_speed", "grounded", "phase_ticks_norm",
        "ability_charges_norm", "ability_active_norm", "pad_distance_norm",
        "pad_direction_cos", "pad_direction_sin", "old_pad_count_norm",
        "preview_pad_distance_norm", "local_floor_north", "local_floor_south",
        "local_floor_east", "local_floor_west", "local_floor_northeast",
        "local_floor_northwest", "local_floor_southeast",
        "local_floor_southwest", "mode_speed", "mode_modern", "kit_jumper",
        "kit_maverick", "kit_slowballer", "kit_repulsor", "kit_body_builder",
        "monster_count_12_norm", "monster_count_20_norm",
        "nearest_monster_distance_norm", "nearest_monster_closing_norm",
        "nearest_monster_forward_norm", "nearest_monster_lateral_norm",
        "max_monster_closing_norm", "min_time_to_contact_norm",
        "action_forward", "action_strafe", "action_jump", "action_sprint",
        "action_yaw_delta", "action_ability",
    ]

    payload = {
        "version": 3,
        "objective": args.objective,
        "architecture": [FEATURE_COUNT, args.hidden1, args.hidden2, 1],
        "feature_names": feature_names,
        "gamma": args.gamma,
        "target_mode": "per_decision_centered_advantage",
        "input_mean": input_mean.tolist(),
        "input_std": input_std.tolist(),
        "target_mean": target_mean,
        "target_std": target_std,
        "target_min": float(train_y.min()),
        "target_max": float(train_y.max()),
        "w1": model.w1.tolist(),
        "b1": model.b1.tolist(),
        "w2": model.w2.tolist(),
        "b2": model.b2.tolist(),
        "w3": model.w3[:, 0].tolist(),
        "b3": model.b3.tolist(),
        "metrics": {
            "rows": len(rows),
            "episodes": len({str(row["episode"]) for row in rows}),
            "train_rows": len(train),
            "validation_rows": len(valid),
            "train_mae": float(np.mean(np.abs(train_y - train_pred))),
            "validation_mae": float(np.mean(np.abs(valid_y - valid_pred))),
            "train_mse": float(np.mean((train_y - train_pred) ** 2)),
            "validation_mse": float(np.mean((valid_y - valid_pred) ** 2)),
            "target_mean": target_mean,
            "target_std": target_std,
            "target_min": float(train_y.min()),
            "target_max": float(train_y.max()),
            "train_pairwise_accuracy": train_ranking["pairwise_accuracy"],
            "validation_pairwise_accuracy": valid_ranking["pairwise_accuracy"],
            "train_pairwise_pairs": train_ranking["pairwise_pairs"],
            "validation_pairwise_pairs": valid_ranking["pairwise_pairs"],
            "train_top1_accuracy": train_ranking["top1_accuracy"],
            "validation_top1_accuracy": valid_ranking["top1_accuracy"],
            "validation_decision_points": valid_ranking["decision_points"],
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))


if __name__ == "__main__":
    main()
