#!/usr/bin/env python3
"""Evaluate a trained MonsterMaze human-policy-v2 model on JSONL rows."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
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


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open("r", encoding="utf-8-sig") as handle:
        for line_number, raw in enumerate(handle, 1):
            raw = raw.strip()
            if not raw:
                continue
            row = json.loads(raw)
            if row.get("schemaVersion") != SCHEMA_VERSION:
                raise SystemExit(
                    f"Unsupported schemaVersion={row.get('schemaVersion')} at line {line_number}"
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
    if not rows:
        raise SystemExit("Dataset contains no rows")
    return rows


def action_vector(row: dict) -> np.ndarray:
    action = row["action"]
    return np.asarray(
        [
            float(action.get("forward", 0.0)),
            float(action.get("strafe", 0.0)),
            1.0 if bool(action.get("jump", False)) else 0.0,
            1.0 if bool(action.get("sprint", False)) else 0.0,
            max(-1.0, min(1.0, float(action.get("yawDelta", 0.0)) / 30.0)),
            1.0 if bool(action.get("useAbility", False)) else 0.0,
        ],
        dtype=np.float64,
    )


def relu(value: np.ndarray) -> np.ndarray:
    return np.maximum(value, 0.0)


def load_model(path: Path) -> dict:
    model = json.loads(path.read_text(encoding="utf-8"))
    architecture = model.get("architecture")
    if architecture != [96, 64, 64, 6]:
        raise SystemExit(
            f"Expected architecture [96,64,64,6] for the current evaluator; got {architecture}"
        )
    if model.get("source_schema_version") != SCHEMA_VERSION:
        raise SystemExit(
            f"Unsupported model source_schema_version={model.get('source_schema_version')}"
        )
    if model.get("target_names") != TARGET_NAMES:
        raise SystemExit("Model target_names do not match the v2 action contract")

    model["input_mean"] = np.asarray(model["input_mean"], dtype=np.float64)
    model["input_std"] = np.asarray(model["input_std"], dtype=np.float64)
    model["w1"] = np.asarray(model["w1"], dtype=np.float64)
    model["b1"] = np.asarray(model["b1"], dtype=np.float64)
    model["w2"] = np.asarray(model["w2"], dtype=np.float64)
    model["b2"] = np.asarray(model["b2"], dtype=np.float64)
    model["w3"] = np.asarray(model["w3"], dtype=np.float64)
    model["b3"] = np.asarray(model["b3"], dtype=np.float64)

    expected_shapes = {
        "input_mean": (96,),
        "input_std": (96,),
        "w1": (96, 64),
        "b1": (64,),
        "w2": (64, 64),
        "b2": (64,),
        "w3": (64, 6),
        "b3": (6,),
    }
    for name, shape in expected_shapes.items():
        if model[name].shape != shape:
            raise SystemExit(
                f"Invalid {name} shape: expected {shape}, got {model[name].shape}"
            )
    if not np.all(np.isfinite(model["input_mean"])) or not np.all(np.isfinite(model["input_std"])):
        raise SystemExit("Model normalization contains non-finite values")
    if np.any(model["input_std"] <= 0.0):
        raise SystemExit("Model input_std must be positive")
    return model


def predict(model: dict, rows: Iterable[dict]) -> tuple[np.ndarray, np.ndarray]:
    observations = np.asarray([row["observation"] for row in rows], dtype=np.float64)
    targets = np.asarray([action_vector(row) for row in rows], dtype=np.float64)

    x = (observations - model["input_mean"]) / model["input_std"]
    a1 = relu(x @ model["w1"] + model["b1"])
    a2 = relu(a1 @ model["w2"] + model["b2"])
    prediction = np.tanh(a2 @ model["w3"] + model["b3"])
    return targets, prediction


def metrics(target: np.ndarray, prediction: np.ndarray) -> dict:
    mae = np.mean(np.abs(target - prediction), axis=0)
    rmse = np.sqrt(np.mean((target - prediction) ** 2, axis=0))

    result = {
        "rows": int(len(target)),
        "overall_mae": float(np.mean(mae)),
        "overall_rmse": float(np.mean(rmse)),
        "forward_mae": float(mae[0]),
        "strafe_mae": float(mae[1]),
        "yaw_mae": float(mae[4]),
        "jump_accuracy": float(np.mean((target[:, 2] >= 0.5) == (prediction[:, 2] >= 0.5))),
        "sprint_accuracy": float(np.mean((target[:, 3] >= 0.5) == (prediction[:, 3] >= 0.5))),
        "ability_accuracy": float(np.mean((target[:, 5] >= 0.5) == (prediction[:, 5] >= 0.5))),
        "continuous_action_within_0.10": float(
            np.mean(
                np.all(
                    np.abs(target[:, [0, 1, 4]] - prediction[:, [0, 1, 4]]) <= 0.10,
                    axis=1,
                )
            )
        ),
    }

    for index, name in ((2, "jump"), (3, "sprint"), (5, "ability")):
        expected = target[:, index] >= 0.5
        actual = prediction[:, index] >= 0.5
        positives = int(np.sum(expected))
        true_positives = int(np.sum(expected & actual))
        predicted_positives = int(np.sum(actual))
        result[f"{name}_rows"] = positives
        result[f"{name}_predicted_positive_rows"] = predicted_positives
        result[f"{name}_precision"] = (
            float(true_positives / predicted_positives) if predicted_positives else 0.0
        )
        result[f"{name}_recall"] = (
            float(true_positives / positives) if positives else 0.0
        )

    return result


def print_metrics(name: str, values: dict) -> None:
    print(
        f"{name}: rows={values['rows']} "
        f"mae={values['overall_mae']:.4f} "
        f"rmse={values['overall_rmse']:.4f} "
        f"forward_mae={values['forward_mae']:.4f} "
        f"strafe_mae={values['strafe_mae']:.4f} "
        f"yaw_mae={values['yaw_mae']:.4f} "
        f"jump_acc={values['jump_accuracy']:.4f} "
        f"jump_precision={values['jump_precision']:.4f} "
        f"jump_recall={values['jump_recall']:.4f} "
        f"ability_precision={values['ability_precision']:.4f} "
        f"ability_recall={values['ability_recall']:.4f} "
        f"within_0.10={values['continuous_action_within_0.10']:.4f}"
    )


def grouped_report(rows: list[dict], target: np.ndarray, prediction: np.ndarray, key_fn):
    groups: dict[str, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        groups[str(key_fn(row))].append(index)

    result = {}
    for key in sorted(groups):
        indices = groups[key]
        result[key] = metrics(target[indices], prediction[indices])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    rows = load_rows(Path(args.input))
    model = load_model(Path(args.model))
    target, prediction = predict(model, rows)

    validation_runs = set(
        str(value)
        for value in model.get("training", {}).get("validation_runs", [])
    )
    if not validation_runs:
        # The training artifact historically stores validation_runs outside the
        # training object only in console output. Treat all rows as all-data and
        # report run-level results without pretending to have a holdout split.
        validation_runs = set()

    all_metrics = metrics(target, prediction)
    holdout_indices = [
        index for index, row in enumerate(rows)
        if str(row.get("run")) in validation_runs
    ]
    seen_indices = [
        index for index, row in enumerate(rows)
        if str(row.get("run")) not in validation_runs
    ]

    print("========== HUMAN POLICY V2 EVALUATION ==========")
    print(f"model_architecture={model['architecture']}")
    print(f"dataset_rows={len(rows)}")
    print(f"dataset_runs={len({str(row.get('run')) for row in rows})}")
    print(f"recorded_validation_runs={sorted(validation_runs)}")
    print_metrics("ALL", all_metrics)

    if holdout_indices:
        print_metrics("HOLDOUT", metrics(target[holdout_indices], prediction[holdout_indices]))
    if seen_indices:
        print_metrics("SEEN", metrics(target[seen_indices], prediction[seen_indices]))

    by_run = grouped_report(
        rows,
        target,
        prediction,
        lambda row: row.get("run", "UNKNOWN"),
    )
    by_mode_kit = grouped_report(
        rows,
        target,
        prediction,
        lambda row: f"{str(row.get('mode', 'UNKNOWN')).upper()}:{str(row.get('kit', 'UNKNOWN')).upper()}",
    )

    print("")
    print("========== BY RUN ==========")
    for run, values in by_run.items():
        subset = [row for row in rows if str(row.get("run")) == run]
        mode = str(subset[0].get("mode", "UNKNOWN")).upper()
        kit = str(subset[0].get("kit", "UNKNOWN")).upper()
        max_stage = max(int(row.get("stage", 1) or 1) for row in subset)
        split = "HOLDOUT" if run in validation_runs else "SEEN"
        print(
            f"{run} split={split} mode={mode} kit={kit} maxStage={max_stage} "
            f"rows={values['rows']} mae={values['overall_mae']:.4f} "
            f"forward_mae={values['forward_mae']:.4f} strafe_mae={values['strafe_mae']:.4f} "
            f"yaw_mae={values['yaw_mae']:.4f} within_0.10={values['continuous_action_within_0.10']:.4f}"
        )

    print("")
    print("========== MODE x KIT ==========")
    for key, values in by_mode_kit.items():
        print(
            f"{key} rows={values['rows']} mae={values['overall_mae']:.4f} "
            f"forward_mae={values['forward_mae']:.4f} "
            f"strafe_mae={values['strafe_mae']:.4f} "
            f"yaw_mae={values['yaw_mae']:.4f} "
            f"jump_acc={values['jump_accuracy']:.4f} "
            f"within_0.10={values['continuous_action_within_0.10']:.4f}"
        )

    stage_groups = grouped_report(
        rows,
        target,
        prediction,
        lambda row: (
            "01-05" if int(row.get("stage", 1) or 1) <= 5 else
            "06-10" if int(row.get("stage", 1) or 1) <= 10 else
            "11-20" if int(row.get("stage", 1) or 1) <= 20 else
            "21-30" if int(row.get("stage", 1) or 1) <= 30 else
            "31+"
        ),
    )

    print("")
    print("========== STAGE BANDS ==========")
    for key, values in stage_groups.items():
        print(
            f"{key} rows={values['rows']} mae={values['overall_mae']:.4f} "
            f"forward_mae={values['forward_mae']:.4f} "
            f"strafe_mae={values['strafe_mae']:.4f} "
            f"yaw_mae={values['yaw_mae']:.4f} "
            f"within_0.10={values['continuous_action_within_0.10']:.4f}"
        )

    payload = {
        "model": {
            "architecture": model["architecture"],
            "source_schema_version": model["source_schema_version"],
            "recorded_validation_runs": sorted(validation_runs),
        },
        "all": all_metrics,
        "holdout": metrics(target[holdout_indices], prediction[holdout_indices])
        if holdout_indices else None,
        "seen": metrics(target[seen_indices], prediction[seen_indices])
        if seen_indices else None,
        "by_run": by_run,
        "by_mode_kit": by_mode_kit,
        "stage_bands": stage_groups,
    }

    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(payload, indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        print("")
        print(f"output={output}")

    print("===============================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
