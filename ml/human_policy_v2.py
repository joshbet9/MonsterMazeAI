#!/usr/bin/env python3
"""
Train a human-control behaviour model from MonsterMaze human-policy-v2 JSONL.

The human dataset is a 96-feature observation contract with a six-component
semantic action target:
  forward, strafe, jump, sprint, yaw_delta, useAbility

This model is deliberately separate from the counterfactual long-horizon
policy trainer. The counterfactual trainer learns which executable action
produces the best simulator return; this model learns how a recorded human
actually controlled the player in the observed state.

Usage:
  python ml/human_policy_v2.py inspect --input training-data/derived/human-policy-v2.jsonl
  python ml/human_policy_v2.py train --input training-data/derived/human-policy-v2.jsonl \
      --output ml-data/local/human-policy-v2-model.json
"""

from __future__ import annotations

import argparse
import json
import math
import random
from collections import Counter
from pathlib import Path
from typing import Iterable

import numpy as np


SCHEMA_VERSION = 2
FEATURE_COUNT = 96
TARGET_COUNT = 6

TARGET_NAMES = [
    "forward",
    "strafe",
    "jump",
    "sprint",
    "yaw_delta",
    "use_ability",
]

FEATURE_NAMES = [
    "player_offset_x",
    "player_offset_z",
    "player_offset_y",
    "velocity_x",
    "velocity_z",
    "velocity_y",
    "horizontal_speed_norm",
    "yaw_sin",
    "yaw_cos",
    "pad_dx_norm",
    "pad_dz_norm",
    "pad_distance_norm",
    "pad_bearing_sin",
    "pad_bearing_cos",
    "pad_bearing_norm",
    "stage_norm",
    "phase_remaining_norm",
    "phase_remaining_ratio",
    "health_ratio",
    "max_health_norm",
    "grounded",
    "pad_reached",
    "pad_reached_legacy_duplicate",
    "kit_ordinal",
    "jump_charges_norm",
    "ability_available",
    "mode_speed",
    "mode_modern",
    "pattern_1",
    "pattern_2",
    "pattern_3",
    "stage_phase_progress",
    "local_floor_north",
    "local_floor_south",
    "local_floor_east",
    "local_floor_west",
    "local_floor_southeast",
    "local_floor_southwest",
    "local_floor_northwest",
    "local_floor_northeast",
    "local_floor_current",
    "population_alive_norm",
    "population_humans_norm",
    "monster_count_norm",
]

FEATURE_NAMES += [
    f"monster_{slot}_{name}"
    for slot in range(1, 9)
    for name in ("dx_norm", "dz_norm", "dy_norm", "distance_norm")
]

FEATURE_NAMES += [f"competitor_feature_{index}" for index in range(1, 21)]

assert len(FEATURE_NAMES) == FEATURE_COUNT


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8-sig") as handle:
        for line_number, raw in enumerate(handle, 1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(
                    f"Invalid JSON at line {line_number}: {exc}"
                ) from exc

            if row.get("schemaVersion") != SCHEMA_VERSION:
                raise SystemExit(
                    f"Unsupported schemaVersion={row.get('schemaVersion')}; "
                    f"expected {SCHEMA_VERSION}"
                )

            observation = row.get("observation")
            action = row.get("action")
            if not isinstance(observation, list) or len(observation) != FEATURE_COUNT:
                raise SystemExit(
                    f"Invalid observation length at line {line_number}: "
                    f"expected {FEATURE_COUNT}, got "
                    f"{len(observation) if isinstance(observation, list) else 'non-list'}"
                )
            if not isinstance(action, dict):
                raise SystemExit(f"Missing action at line {line_number}")

            rows.append(row)

    return rows


def action_vector(row: dict) -> np.ndarray:
    action = row["action"]
    yaw = float(action.get("yawDelta", 0.0))
    return np.asarray(
        [
            float(action.get("forward", 0.0)),
            float(action.get("strafe", 0.0)),
            1.0 if bool(action.get("jump", False)) else 0.0,
            1.0 if bool(action.get("sprint", False)) else 0.0,
            max(-1.0, min(1.0, yaw / 30.0)),
            1.0 if bool(action.get("useAbility", False)) else 0.0,
        ],
        dtype=np.float64,
    )


def prepare(rows: Iterable[dict]) -> tuple[np.ndarray, np.ndarray]:
    rows_list = list(rows)
    x = np.asarray([r["observation"] for r in rows_list], dtype=np.float64)
    y = np.asarray([action_vector(r) for r in rows_list], dtype=np.float64)
    return x, y


def split_by_run(
    rows: list[dict],
    validation_fraction: float,
    seed: int,
) -> tuple[list[dict], list[dict]]:
    runs = sorted({str(row["run"]) for row in rows})
    if len(runs) < 2:
        raise SystemExit("At least two distinct human runs are required for holdout validation.")

    rng = random.Random(seed)
    rng.shuffle(runs)

    validation_count = max(1, int(round(len(runs) * validation_fraction)))
    validation_count = min(validation_count, len(runs) - 1)
    validation_runs = set(runs[:validation_count])

    train = [row for row in rows if str(row["run"]) not in validation_runs]
    valid = [row for row in rows if str(row["run"]) in validation_runs]
    return train, valid


def relu(x: np.ndarray) -> np.ndarray:
    return np.maximum(x, 0.0)


class MLP:
    def __init__(self, inputs: int, hidden1: int, hidden2: int, outputs: int, seed: int):
        rng = np.random.default_rng(seed)
        self.w1 = (
            rng.standard_normal((inputs, hidden1)) * math.sqrt(2.0 / inputs)
        ).astype(np.float64)
        self.b1 = np.zeros(hidden1, dtype=np.float64)
        self.w2 = (
            rng.standard_normal((hidden1, hidden2)) * math.sqrt(2.0 / hidden1)
        ).astype(np.float64)
        self.b2 = np.zeros(hidden2, dtype=np.float64)
        self.w3 = (
            rng.standard_normal((hidden2, outputs)) * math.sqrt(2.0 / hidden2)
        ).astype(np.float64)
        self.b3 = np.zeros(outputs, dtype=np.float64)

    def forward(self, x: np.ndarray):
        z1 = x @ self.w1 + self.b1
        a1 = relu(z1)
        z2 = a1 @ self.w2 + self.b2
        a2 = relu(z2)
        raw = a2 @ self.w3 + self.b3
        pred = np.tanh(raw)
        return pred, (x, z1, a1, z2, a2, pred)

    def gradients(self, cache, target: np.ndarray):
        x, z1, a1, z2, a2, pred = cache
        n = max(1, x.shape[0])

        d_raw = (
            2.0 * (pred - target) / n
        ) * (1.0 - pred * pred)

        dw3 = a2.T @ d_raw
        db3 = d_raw.sum(axis=0)

        da2 = d_raw @ self.w3.T
        dz2 = da2 * (z2 > 0.0)
        dw2 = a1.T @ dz2
        db2 = dz2.sum(axis=0)

        da1 = dz2 @ self.w2.T
        dz1 = da1 * (z1 > 0.0)
        dw1 = x.T @ dz1
        db1 = dz1.sum(axis=0)

        return dw1, db1, dw2, db2, dw3, db3


def make_adam_state(model: MLP) -> dict[str, np.ndarray]:
    state: dict[str, np.ndarray] = {}
    for name in ("w1", "b1", "w2", "b2", "w3", "b3"):
        value = getattr(model, name)
        state[f"{name}_m"] = np.zeros_like(value)
        state[f"{name}_v"] = np.zeros_like(value)
    return state


def adam_step(
    model: MLP,
    gradients,
    state: dict[str, np.ndarray],
    step: int,
    learning_rate: float,
) -> None:
    beta1 = 0.9
    beta2 = 0.999
    for name, gradient in zip(
        ("w1", "b1", "w2", "b2", "w3", "b3"),
        gradients,
    ):
        moment = state[f"{name}_m"]
        velocity = state[f"{name}_v"]

        moment[:] = beta1 * moment + (1.0 - beta1) * gradient
        velocity[:] = beta2 * velocity + (1.0 - beta2) * gradient * gradient

        moment_hat = moment / (1.0 - beta1**step)
        velocity_hat = velocity / (1.0 - beta2**step)

        getattr(model, name)[:] -= (
            learning_rate * moment_hat / (np.sqrt(velocity_hat) + 1e-8)
        )


def regression_metrics(target: np.ndarray, prediction: np.ndarray) -> dict:
    mae = np.mean(np.abs(target - prediction), axis=0)
    rmse = np.sqrt(np.mean((target - prediction) ** 2, axis=0))

    binary_indices = [2, 3, 5]
    binary_accuracy = []
    for index in binary_indices:
        expected = target[:, index] >= 0.5
        actual = prediction[:, index] >= 0.5
        binary_accuracy.append(float(np.mean(expected == actual)))

    continuous_indices = [0, 1, 4]
    exact = np.mean(
        np.all(
            np.abs(target[:, continuous_indices] - prediction[:, continuous_indices]) <= 0.10,
            axis=1,
        )
    )

    return {
        "overall_mae": float(np.mean(mae)),
        "overall_rmse": float(np.mean(rmse)),
        "forward_mae": float(mae[0]),
        "strafe_mae": float(mae[1]),
        "jump_accuracy": binary_accuracy[0],
        "sprint_accuracy": binary_accuracy[1],
        "yaw_mae": float(mae[4]),
        "ability_accuracy": binary_accuracy[2],
        "continuous_action_within_0.10": float(exact),
    }


def print_split_summary(name: str, rows: list[dict]) -> None:
    runs = sorted({str(row["run"]) for row in rows})
    mode_counts = Counter(str(row.get("mode", "UNKNOWN")) for row in rows)
    kit_counts = Counter(str(row.get("kit", "UNKNOWN")) for row in rows)
    actions = np.asarray([action_vector(row) for row in rows], dtype=np.float64)

    print(f"{name}_rows={len(rows)}")
    print(f"{name}_runs={len(runs)}")
    print(f"{name}_modes={dict(sorted(mode_counts.items()))}")
    print(f"{name}_kits={dict(sorted(kit_counts.items()))}")
    print(
        f"{name}_jump_rate={np.mean(actions[:, 2]):.4f} "
        f"{name}_sprint_rate={np.mean(actions[:, 3]):.4f} "
        f"{name}_ability_rate={np.mean(actions[:, 5]):.4f}"
    )


def inspect(args: argparse.Namespace) -> int:
    rows = load_rows(Path(args.input))
    x, y = prepare(rows)

    runs = Counter(str(row["run"]) for row in rows)
    modes = Counter(str(row.get("mode", "UNKNOWN")) for row in rows)
    kits = Counter(str(row.get("kit", "UNKNOWN")) for row in rows)

    topology_nonzero = int(np.count_nonzero(np.any(np.abs(x[:, 32:41]) > 1e-12, axis=1)))
    competitor_nonzero = int(np.count_nonzero(np.any(np.abs(x[:, 76:96]) > 1e-12, axis=1)))

    print("========== HUMAN POLICY V2 INSPECTION ==========")
    print(f"schemaVersion={SCHEMA_VERSION}")
    print(f"featureCount={FEATURE_COUNT}")
    print(f"rows={len(rows)}")
    print(f"runs={len(runs)}")
    print(f"modes={dict(sorted(modes.items()))}")
    print(f"kits={dict(sorted(kits.items()))}")
    print(f"topologyNonzeroRows={topology_nonzero}")
    print(f"competitorNonzeroRows={competitor_nonzero}")

    print("")
    print("========== ACTION DISTRIBUTION ==========")
    for index, name in enumerate(TARGET_NAMES):
        values = y[:, index]
        if index in (2, 3, 5):
            print(f"{name:12s} rate={np.mean(values):.4f}")
        else:
            print(
                f"{name:12s} mean={np.mean(values):+.4f} "
                f"std={np.std(values):.4f} "
                f"min={np.min(values):+.4f} max={np.max(values):+.4f}"
            )

    print("")
    print("========== PER-RUN COVERAGE ==========")
    for run, count in sorted(runs.items()):
        subset = [row for row in rows if str(row["run"]) == run]
        mode = str(subset[0].get("mode", "UNKNOWN"))
        kit = str(subset[0].get("kit", "UNKNOWN"))
        max_stage = max(int(row.get("stage", 1)) for row in subset)
        print(f"{run} mode={mode} kit={kit} rows={count} maxStage={max_stage}")

    print("========================================")
    return 0


def train(args: argparse.Namespace) -> int:
    rows = load_rows(Path(args.input))
    if len(rows) < args.min_samples:
        raise SystemExit(
            f"Need at least {args.min_samples} rows; found {len(rows)}"
        )

    train_rows, valid_rows = split_by_run(
        rows,
        args.validation_fraction,
        args.seed,
    )
    train_x, train_y = prepare(train_rows)
    valid_x, valid_y = prepare(valid_rows)

    input_mean = np.mean(train_x, axis=0)
    input_std = np.std(train_x, axis=0)
    input_std = np.where(input_std < 1e-8, 1.0, input_std)

    train_x = (train_x - input_mean) / input_std
    valid_x = (valid_x - input_mean) / input_std

    model = MLP(FEATURE_COUNT, args.hidden1, args.hidden2, TARGET_COUNT, args.seed)
    adam = make_adam_state(model)
    rng = np.random.default_rng(args.seed)

    n = len(train_rows)
    batch_size = min(args.batch_size, n)
    for epoch in range(1, args.epochs + 1):
        order = rng.permutation(n)

        for start in range(0, n, batch_size):
            batch = order[start : start + batch_size]
            prediction, cache = model.forward(train_x[batch])
            gradients = model.gradients(cache, train_y[batch])
            adam_step(model, gradients, adam, epoch * max(1, math.ceil(n / batch_size)) + start // batch_size + 1, args.learning_rate)

        if epoch == 1 or epoch % args.report_every == 0 or epoch == args.epochs:
            valid_pred, _ = model.forward(valid_x)
            metrics = regression_metrics(valid_y, valid_pred)
            print(
                f"epoch={epoch:4d} "
                f"validation_mae={metrics['overall_mae']:.4f} "
                f"jump_acc={metrics['jump_accuracy']:.4f} "
                f"sprint_acc={metrics['sprint_accuracy']:.4f} "
                f"ability_acc={metrics['ability_accuracy']:.4f}"
            )

    train_pred, _ = model.forward(train_x)
    valid_pred, _ = model.forward(valid_x)

    train_metrics = regression_metrics(train_y, train_pred)
    valid_metrics = regression_metrics(valid_y, valid_pred)

    payload = {
        "version": 1,
        "source_schema_version": SCHEMA_VERSION,
        "source_kind": "human-policy-v2",
        "architecture": [
            FEATURE_COUNT,
            args.hidden1,
            args.hidden2,
            TARGET_COUNT,
        ],
        "feature_names": FEATURE_NAMES,
        "target_names": TARGET_NAMES,
        "target_encoding": {
            "forward": "clamped [-1,1]",
            "strafe": "clamped [-1,1]",
            "jump": "binary {0,1}",
            "sprint": "binary {0,1}",
            "yaw_delta": "yawDelta / 30, clamped [-1,1]",
            "use_ability": "binary {0,1}",
        },
        "input_mean": input_mean.tolist(),
        "input_std": input_std.tolist(),
        "w1": model.w1.tolist(),
        "b1": model.b1.tolist(),
        "w2": model.w2.tolist(),
        "b2": model.b2.tolist(),
        "w3": model.w3.tolist(),
        "b3": model.b3.tolist(),
        "training": {
            "rows": len(rows),
            "train_rows": len(train_rows),
            "validation_rows": len(valid_rows),
            "train_runs": len({str(row["run"]) for row in train_rows}),
            "validation_runs": len({str(row["run"]) for row in valid_rows}),
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "seed": args.seed,
            "validation_fraction": args.validation_fraction,
            "train_metrics": train_metrics,
            "validation_metrics": valid_metrics,
        },
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )

    print("")
    print("========== HUMAN POLICY V2 TRAINING COMPLETE ==========")
    print(json.dumps(payload["training"], indent=2, allow_nan=False))
    print(f"output={output}")

    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(required=True)

    inspect_parser = subparsers.add_parser("inspect")
    inspect_parser.add_argument("--input", required=True)
    inspect_parser.set_defaults(func=inspect)

    train_parser = subparsers.add_parser("train")
    train_parser.add_argument("--input", required=True)
    train_parser.add_argument("--output", required=True)
    train_parser.add_argument("--epochs", type=int, default=60)
    train_parser.add_argument("--batch-size", type=int, default=1024)
    train_parser.add_argument("--hidden1", type=int, default=64)
    train_parser.add_argument("--hidden2", type=int, default=32)
    train_parser.add_argument("--learning-rate", type=float, default=0.001)
    train_parser.add_argument("--validation-fraction", type=float, default=0.20)
    train_parser.add_argument("--min-samples", type=int, default=500)
    train_parser.add_argument("--seed", type=int, default=1337)
    train_parser.add_argument("--report-every", type=int, default=10)
    train_parser.set_defaults(func=train)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
