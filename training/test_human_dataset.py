#!/usr/bin/env python3
"""Regression tests for human-policy-v2 topology provenance."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

from human_dataset import build_run_dataset


def write_jsonl(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, separators=(",", ":")) + "\n")


def make_world(tick: int, stage: int) -> dict:
    return {
        "tick": tick,
        "stage": stage,
        "center": {"x": 0, "y": 64, "z": 0},
        "mazePattern": 1,
        "scoreboardLines": ["SPEED"],
        "phaseTimerSeconds": 60,
        "health": 20,
        "alive": True,
        "completed": False,
        "mazeDetected": True,
        "activePad": {"row": 50, "column": 50, "reached": False},
    }


def make_movement(tick: int) -> dict:
    return {
        "tick": tick,
        "x": 1.5,
        "y": 64,
        "z": 1.5,
        "vx": 0,
        "vy": 0,
        "vz": 0,
        "yaw": 0,
        "grounded": True,
    }


def make_input(tick: int) -> dict:
    return {
        "tick": tick,
        "forward": 1,
        "strafe": 0,
        "yawDelta": 0,
        "jump": False,
        "sprintKey": True,
        "rawUseItem": False,
        "mouseRightPulse": False,
    }


def make_navigation(tick: int) -> dict:
    return {"tick": tick, "row": 50, "column": 50}


def make_maze(tick: int, stage: int, physical: str) -> dict:
    logical = ["0" * 99 for _ in range(99)]
    for r in range(49, 52):
        for c in range(49, 52):
            logical[r] = logical[r][:c] + "1" + logical[r][c + 1:]
    physical_rows = [row for row in logical]
    if physical == "zero":
        physical_rows = ["0" * 99 for _ in range(99)]
    return {
        "tick": tick,
        "stage": stage,
        "maze": logical,
        "physicalFloor": physical_rows,
    }


def main() -> int:
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)

        run = "synthetic-human-run"
        write_jsonl(root / f"{run}-inventory.jsonl", [])
        write_jsonl(root / f"{run}-world.jsonl", [
            make_world(100, 1),
            make_world(150, 2),
            make_world(200, 2),
        ])
        write_jsonl(root / f"{run}-movement.jsonl", [
            make_movement(100),
            make_movement(150),
            make_movement(200),
        ])
        write_jsonl(root / f"{run}-input.jsonl", [
            make_input(100),
            make_input(150),
            make_input(200),
        ])
        write_jsonl(root / f"{run}-navigation.jsonl", [
            make_navigation(100),
            make_navigation(150),
            make_navigation(200),
        ])
        write_jsonl(root / f"{run}-maze.jsonl", [
            make_maze(100, 1, "one"),
            make_maze(200, 2, "zero"),
        ])

        rows, _ = build_run_dataset(root, run)
        by_tick = {row["tick"]: row for row in rows}

        assert by_tick[100]["topologySource"] == "physical-snapshot"
        assert by_tick[150]["topologySource"] == "logical-snapshot-fallback"
        assert by_tick[150]["topologySnapshotTick"] == 100
        assert by_tick[150]["topologyAgeTicks"] == 50
        assert by_tick[150]["observation"][32:41] == [1.0] * 9

        assert by_tick[200]["topologySource"] == "physical-snapshot"
        assert by_tick[200]["observation"][32:41] == [0.0] * 9

    with tempfile.TemporaryDirectory() as temp2:
        root = Path(temp2)

        # Startup/teardown rows without a detected maze centre are not training
        # observations and must be excluded rather than encoded as zero topology.
        write_jsonl(root / f"{run}-world.jsonl", [
            make_world(50, 1),
            {**make_world(75, 1), "mazeDetected": False, "center": None, "mazePattern": -1},
            make_world(100, 1),
            make_world(150, 2),
            make_world(200, 2),
        ])
        write_jsonl(root / f"{run}-movement.jsonl", [
            make_movement(50),
            make_movement(75),
            make_movement(100),
            make_movement(150),
            make_movement(200),
        ])
        write_jsonl(root / f"{run}-input.jsonl", [
            make_input(50),
            make_input(75),
            make_input(100),
            make_input(150),
            make_input(200),
        ])
        write_jsonl(root / f"{run}-navigation.jsonl", [
                make_navigation(50),
                {"tick": 75, "row": -1, "column": -1},
                make_navigation(100),
                make_navigation(150),
                make_navigation(200),
            ])

            rows, meta = build_run_dataset(root, run)
            assert [row["tick"] for row in rows] == [50, 100, 150, 200]
            assert meta["skippedInvalidObservations"] == 1

    print("human_dataset_topology_regression_ok=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

