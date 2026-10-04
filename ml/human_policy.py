#!/usr/bin/env python3
"""Build and train a small human-input behavior model for Monster Maze.

The model learns the control action chosen by a human from synchronized
movement/navigation/world/monster observations. It is calibration data only;
it does not replace the source-faithful simulator or live controller.
"""
from __future__ import annotations

import argparse
import json
import math
import random
from pathlib import Path
from typing import Iterator

import numpy as np

FEATURES = [
    "stage_norm", "health_ratio", "horizontal_speed", "forward_speed",
    "lateral_speed", "vertical_speed", "grounded", "falling",
    "target_distance_norm", "target_bearing_relative", "movement_bearing_relative",
    "velocity_bearing_relative", "displacement_norm", "jump_charges_norm",
    "ability_charges_norm", "monster_count_norm", "nearest_monster_distance_norm",
    "nearest_monster_closing_norm", "nearest_monster_forward_norm",
    "nearest_monster_lateral_norm", "min_time_to_contact_norm",
]

TARGETS = ["forward", "strafe", "jump", "sprint", "yaw_delta_norm"]


def parse_json(line: str) -> dict:
    return json.loads(line.replace("NaN", "null"))


def iter_records(path: Path) -> Iterator[dict]:
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith('{"recordType"'):
                continue
            yield parse_json(line)


def index_stream(path: Path) -> dict[int, dict]:
    return {int(row["tick"]): row for row in iter_records(path)}


def mode_from_world(world: dict) -> str:
    for line in world.get("scoreboardLines", []):
        if line in {"Original", "Speed", "Modern", "Classic"}:
            return line.upper()
    text = " ".join(str(x) for x in world.get("scoreboardLines", []))
    for name in ("original", "speed", "modern", "classic"):
        if name in text.lower():
            return name.upper()
    return "UNKNOWN"


def relative_angle(degrees: float) -> float:
    return ((degrees + 180.0) % 360.0) - 180.0


def build_feature_row(inp: dict, movement: dict, nav: dict, world: dict, monsters: dict | None) -> dict | None:
    if not world.get("inMaze", False) or not world.get("alive", False):
        return None

    yaw = float(movement.get("yaw", 0.0))
    yaw_rad = math.radians(yaw)

    vx = float(movement.get("vx", 0.0))
    vz = float(movement.get("vz", 0.0))
    speed = math.hypot(vx, vz)

    forward_x = -math.sin(yaw_rad)
    forward_z = math.cos(yaw_rad)
    strafe_x = math.cos(yaw_rad)
    strafe_z = math.sin(yaw_rad)
    forward_speed = vx * forward_x + vz * forward_z
    lateral_speed = vx * strafe_x + vz * strafe_z

    target_bearing = float(nav.get("targetBearing", 0.0) or 0.0)
    movement_bearing = nav.get("movementBearing")
    velocity_bearing = nav.get("velocityBearing")
    movement_relative = (
        relative_angle(float(movement_bearing) - target_bearing) / 180.0
        if movement_bearing is not None else 0.0
    )
    velocity_relative = (
        relative_angle(float(velocity_bearing) - target_bearing) / 180.0
        if velocity_bearing is not None else 0.0
    )

    nearest_distance = 20.0
    nearest_closing = 0.0
    nearest_forward = 0.0
    nearest_lateral = 0.0
    min_ttc = 40.0
    monster_count = 0

    if monsters:
        for monster in monsters.get("monsters", []):
            if len(monster) < 7:
                continue
            mx, my, mz = float(monster[1]), float(monster[2]), float(monster[3])
            mvx, mvz = float(monster[4]), float(monster[6])
            dx = mx - float(movement.get("x", 0.0))
            dy = my - float(movement.get("y", 0.0))
            dz = mz - float(movement.get("z", 0.0))
            horizontal = math.hypot(dx, dz)
            full = math.sqrt(dx * dx + dy * dy + dz * dz)
            if horizontal > 20.0:
                continue
            monster_count += 1

            closing = 0.0
            if full > 1e-9:
                rvx = mvx - vx
                rvz = mvz - vz
                closing = (rvx * -dx + rvz * -dz) / full

            if horizontal < nearest_distance:
                nearest_distance = horizontal
                nearest_closing = closing
                target_rad = math.radians(target_bearing)
                route_x = -math.sin(target_rad)
                route_z = math.cos(target_rad)
                nearest_forward = dx * route_x + dz * route_z
                nearest_lateral = dx * (-route_z) + dz * route_x

            if closing > 1e-6:
                min_ttc = min(min_ttc, max(0.0, full / closing))

    target_row = float(nav.get("targetDx", 0.0) or 0.0)
    target_col = float(nav.get("targetDz", 0.0) or 0.0)
    target_distance = math.hypot(target_row, target_col)

    features = [
        max(0.0, min(1.0, float(world.get("stage", 1)) / 100.0)),
        max(0.0, min(1.0, float(world.get("health", 20.0)) / 20.0)),
        max(-3.0, min(3.0, speed / 0.6)),
        max(-3.0, min(3.0, forward_speed / 0.6)),
        max(-3.0, min(3.0, lateral_speed / 0.6)),
        max(-3.0, min(3.0, float(movement.get("vy", 0.0)) / 0.6)),
        1.0 if movement.get("grounded", False) else 0.0,
        1.0 if float(movement.get("fallDistance", 0.0)) > 0.1 else 0.0,
        max(0.0, min(3.0, target_distance / 100.0)),
        max(-1.0, min(1.0, relative_angle(target_bearing - yaw) / 180.0)),
        max(-1.0, min(1.0, movement_relative)),
        max(-1.0, min(1.0, velocity_relative)),
        max(0.0, min(3.0, float(nav.get("displacement", 0.0)) / 2.0)),
        max(0.0, min(1.0, float(world.get("jumpCharges", 0)) / 3.0)),
        max(0.0, min(1.0, float(world.get("abilityCharges", 0)) / 3.0)),
        max(0.0, min(3.0, monster_count / 10.0)),
        max(0.0, min(1.5, nearest_distance / 20.0)),
        max(-3.0, min(3.0, nearest_closing / 0.6)),
        max(-2.0, min(2.0, nearest_forward / 20.0)),
        max(0.0, min(2.0, abs(nearest_lateral) / 8.0)),
        max(0.0, min(3.0, min_ttc / 20.0)),
    ]

    targets = [
        max(-1.0, min(1.0, float(inp.get("forward", 0.0)))),
        max(-1.0, min(1.0, float(inp.get("strafe", 0.0)))),
        1.0 if inp.get("jump", False) else 0.0,
        1.0 if inp.get("sprintKey", False) else 0.0,
        max(-1.0, min(1.0, float(inp.get("yawDelta", 0.0)) / 30.0)),
    ]

    return {"features": features, "targets": targets, "tick": int(inp["tick"]),
            "stage": int(world.get("stage", 1)), "kit": str(world.get("kit", "")),
            "mode": mode_from_world(world)}


def convert_run(directory: Path, prefix: str, output: Path) -> int:
    input_path = directory / f"{prefix}-input.jsonl"
    movement_path = directory / f"{prefix}-movement.jsonl"
    navigation_path = directory / f"{prefix}-navigation.jsonl"
    world_path = directory / f"{prefix}-world.jsonl"
    monster_path = directory / f"{prefix}-monsters.jsonl"

    movement = index_stream(movement_path)
    navigation = index_stream(navigation_path)
    world = index_stream(world_path)

    monster_iter = iter_records(monster_path) if monster_path.exists() else iter(())
    next_monster = next(monster_iter, None)

    output.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with output.open("a", encoding="utf-8") as handle:
        for inp in iter_records(input_path):
            tick = int(inp["tick"])
            while next_monster is not None and int(next_monster.get("tick", -1)) < tick:
                next_monster = next(monster_iter, None)
            monster = next_monster if next_monster is not None and int(next_monster.get("tick", -1)) == tick else None

            row = build_feature_row(
                inp, movement.get(tick, {}), navigation.get(tick, {}),
                world.get(tick, {}), monster
            )
            if row is None:
                continue
            handle.write(json.dumps(row, separators=(",", ":")) + "\n")
            count += 1
    return count


def convert(args):
    manifests = sorted(Path(args.input).glob("*-manifest.json"))
    if not manifests:
        raise SystemExit(f"No manifests found under {args.input}")

    output = Path(args.output)
    output.unlink(missing_ok=True)
    total = 0
    for manifest in manifests:
        prefix = manifest.name.removesuffix("-manifest.json")
        n = convert_run(manifest.parent, prefix, output)
        print(f"{prefix}: samples={n}")
        total += n
    print(f"total_samples={total}")


def load_dataset(path: Path):
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    x = np.asarray([r["features"] for r in rows], dtype=np.float64)
    y = np.asarray([r["targets"] for r in rows], dtype=np.float64)
    return rows, x, y


def train(args):
    rows, x, y = load_dataset(Path(args.input))
    if len(rows) < args.min_samples:
        raise SystemExit(f"Need at least {args.min_samples} samples; found {len(rows)}")

    rng = np.random.default_rng(args.seed)
    idx = np.arange(len(rows))
    rng.shuffle(idx)
    cut = max(1, int(len(idx) * (1.0 - args.validation_fraction)))
    train_idx, valid_idx = idx[:cut], idx[cut:]

    mean = x[train_idx].mean(axis=0)
    std = np.where(x[train_idx].std(axis=0) < 1e-8, 1.0, x[train_idx].std(axis=0))
    xt = (x[train_idx] - mean) / std
    xv = (x[valid_idx] - mean) / std if len(valid_idx) else xt[:0]

    rng = np.random.default_rng(args.seed)
    w1 = (rng.standard_normal((len(FEATURES), args.hidden1)) * math.sqrt(2.0 / len(FEATURES))).astype(np.float64)
    b1 = np.zeros(args.hidden1)
    w2 = (rng.standard_normal((args.hidden1, args.hidden2)) * math.sqrt(2.0 / args.hidden1)).astype(np.float64)
    b2 = np.zeros(args.hidden2)
    w3 = (rng.standard_normal((args.hidden2, len(TARGETS))) * math.sqrt(2.0 / args.hidden2)).astype(np.float64)
    b3 = np.zeros(len(TARGETS))

    for epoch in range(args.epochs):
        order = train_idx.copy()
        rng.shuffle(order)
        for start in range(0, len(order), args.batch_size):
            batch_idx = order[start:start + args.batch_size]
            xb = (x[batch_idx] - mean) / std
            yb = y[batch_idx]
            z1 = xb @ w1 + b1
            a1 = np.maximum(z1, 0.0)
            z2 = a1 @ w2 + b2
            a2 = np.maximum(z2, 0.0)
            pred = np.tanh(a2 @ w3 + b3)
            diff = pred - yb
            dp = (2.0 * diff / max(1, len(batch_idx))) * (1.0 - pred * pred)
            dw3 = a2.T @ dp
            db3 = dp.sum(axis=0)
            da2 = dp @ w3.T
            dz2 = da2 * (z2 > 0)
            dw2 = a1.T @ dz2
            db2 = dz2.sum(axis=0)
            da1 = dz2 @ w2.T
            dz1 = da1 * (z1 > 0)
            dw1 = xb.T @ dz1
            db1 = dz1.sum(axis=0)

            for w, g in ((w1, dw1), (b1, db1), (w2, dw2), (b2, db2), (w3, dw3), (b3, db3)):
                w -= args.learning_rate * g

    def infer(zx):
        z1 = zx @ w1 + b1
        a1 = np.maximum(z1, 0.0)
        z2 = a1 @ w2 + b2
        a2 = np.maximum(z2, 0.0)
        return np.tanh(a2 @ w3 + b3)

    train_pred = infer((x[train_idx] - mean) / std)
    valid_pred = infer(xv) if len(valid_idx) else np.empty((0, len(TARGETS)))

    train_mae = float(np.mean(np.abs(train_pred - y[train_idx])))
    valid_mae = float(np.mean(np.abs(valid_pred - y[valid_idx]))) if len(valid_idx) else float("nan")

    payload = {
        "version": 1,
        "feature_names": FEATURES,
        "target_names": TARGETS,
        "architecture": [len(FEATURES), args.hidden1, args.hidden2, len(TARGETS)],
        "input_mean": mean.tolist(),
        "input_std": std.tolist(),
        "w1": w1.tolist(), "b1": b1.tolist(),
        "w2": w2.tolist(), "b2": b2.tolist(),
        "w3": w3.tolist(), "b3": b3.tolist(),
        "metrics": {
            "samples": len(rows),
            "train_samples": len(train_idx),
            "validation_samples": len(valid_idx),
            "train_mae": train_mae,
            "validation_mae": valid_mae,
        },
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload["metrics"], indent=2))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(required=True)
    p = sub.add_parser("convert")
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(func=convert)

    p = sub.add_parser("train")
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--epochs", type=int, default=150)
    p.add_argument("--batch-size", type=int, default=256)
    p.add_argument("--hidden1", type=int, default=32)
    p.add_argument("--hidden2", type=int, default=16)
    p.add_argument("--learning-rate", type=float, default=0.002)
    p.add_argument("--validation-fraction", type=float, default=0.20)
    p.add_argument("--min-samples", type=int, default=500)
    p.add_argument("--seed", type=int, default=1337)
    p.set_defaults(func=train)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
