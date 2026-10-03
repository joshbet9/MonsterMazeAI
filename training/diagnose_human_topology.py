#!/usr/bin/env python3
"""
Diagnose human-policy-v2 topology construction against raw observer streams.

For selected derived observations this prints:
  - human run/tick/stage
  - observer navigation row/column
  - nearest maze snapshot tick/stage
  - logical 3x3 cell neighbourhood
  - physicalFloor 3x3 cell neighbourhood
  - whether the derived topology block is all-zero

Usage:
  python training/diagnose_human_topology.py \
      --dataset training-data/derived/human-policy-v2.jsonl \
      --root training-data/human-runs
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Optional


def stream_rows(path: Path):
    if not path.exists():
        return
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError:
                continue
            if row.get("recordType") in {"header", "footer"}:
                continue
            if isinstance(row, dict) and "tick" in row:
                yield row


def load_by_tick(path: Path) -> dict[int, dict]:
    rows: dict[int, dict] = {}
    for row in stream_rows(path) or ():
        try:
            rows[int(row["tick"])] = row
        except (TypeError, ValueError):
            continue
    return rows


def load_maze_snapshots(path: Path) -> list[dict]:
    snapshots: list[dict] = []
    for row in stream_rows(path) or ():
        try:
            tick = int(row["tick"])
            stage = int(row.get("stage", -1))
        except (TypeError, ValueError):
            continue
        logical = row.get("maze")
        physical = row.get("physicalFloor")
        if not isinstance(logical, list) or len(logical) != 99:
            continue
        if not isinstance(physical, list) or len(physical) != 99:
            physical = None
        snapshots.append({
            "tick": tick,
            "stage": stage,
            "maze": logical,
            "physicalFloor": physical,
        })
    snapshots.sort(key=lambda x: x["tick"])
    return snapshots


def bits(values: list[str]) -> str:
    return "".join("1" if str(v) != "0" else "0" for v in values)


def neighbourhood(grid: Optional[list[str]], row: int, col: int) -> list[str]:
    if not isinstance(grid, list) or len(grid) != 99:
        return []
    coords = [
        (0, -1), (0, 1), (1, 0), (-1, 0),
        (1, -1), (1, 1), (-1, -1), (-1, 1),
        (0, 0),
    ]
    out = []
    for dr, dc in coords:
        rr = row + dr
        cc = col + dc
        if rr < 0 or rr >= 99 or cc < 0 or cc >= 99:
            out.append("0")
            continue
        try:
            out.append(str(grid[rr])[cc])
        except (IndexError, TypeError):
            out.append("0")
    return out


def matrix3(grid: Optional[list[str]], row: int, col: int) -> list[str]:
    if not isinstance(grid, list) or len(grid) != 99:
        return []
    lines = []
    for dr in (-1, 0, 1):
        chars = []
        for dc in (-1, 0, 1):
            rr = row + dr
            cc = col + dc
            if rr < 0 or rr >= 99 or cc < 0 or cc >= 99:
                chars.append("0")
            else:
                try:
                    chars.append("1" if str(grid[rr])[cc] != "0" else "0")
                except (IndexError, TypeError):
                    chars.append("0")
        lines.append("".join(chars))
    return lines


def choose_samples(rows: list[dict], per_run: int) -> list[dict]:
    by_run: dict[str, list[dict]] = {}
    for row in rows:
        by_run.setdefault(str(row.get("run", "UNKNOWN")), []).append(row)

    chosen: list[dict] = []
    for run in sorted(by_run):
        run_rows = by_run[run]
        missing = [
            r for r in run_rows
            if not any(abs(float(v)) > 1e-12 for v in r.get("observation", [])[32:41])
        ]
        nonzero = [
            r for r in run_rows
            if any(abs(float(v)) > 1e-12 for v in r.get("observation", [])[32:41])
        ]

        pools = [("MISSING", missing), ("NONZERO", nonzero)]
        for label, pool in pools:
            if not pool:
                continue
            count = min(per_run, len(pool))
            indices = sorted({
                int(round(i * (len(pool) - 1) / max(1, count - 1)))
                for i in range(count)
            })
            for idx in indices:
                item = dict(pool[idx])
                item["_diagnosticClass"] = label
                chosen.append(item)

    return chosen


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path,
                        default=Path("training-data/derived/human-policy-v2.jsonl"))
    parser.add_argument("--root", type=Path,
                        default=Path("training-data/human-runs"))
    parser.add_argument("--per-run", type=int, default=3,
                        help="samples per class (missing/nonzero) per run")
    args = parser.parse_args()

    if not args.dataset.exists():
        raise SystemExit(f"Dataset not found: {args.dataset}")

    rows = []
    with args.dataset.open("r", encoding="utf-8-sig") as handle:
        for raw in handle:
            if raw.strip():
                rows.append(json.loads(raw))

    selected = choose_samples(rows, max(1, args.per_run))
    print("========== HUMAN TOPOLOGY DIAGNOSTIC ==========")
    print(f"datasetRows={len(rows)}")
    print(f"selectedSamples={len(selected)}")

    cache: dict[str, tuple[dict[int, dict], dict[int, dict], list[dict]]] = {}

    for row in selected:
        run = str(row["run"])
        if run not in cache:
            prefix = args.root / run
            world = load_by_tick(prefix.with_name(prefix.name + "-world.jsonl"))
            navigation = load_by_tick(prefix.with_name(prefix.name + "-navigation.jsonl"))
            maze = load_maze_snapshots(prefix.with_name(prefix.name + "-maze.jsonl"))
            cache[run] = (world, navigation, maze)

        world, navigation, maze = cache[run]
        tick = int(row["tick"])
        world_row = world.get(tick)
        nav_row = navigation.get(tick)

        snapshot = None
        for candidate in reversed(maze):
            if candidate["tick"] <= tick:
                snapshot = candidate
                break

        if snapshot is not None and int(snapshot["stage"]) != int(row.get("stage", -1)):
            same_stage = [
                candidate for candidate in maze
                if candidate["tick"] <= tick and candidate["stage"] == int(row.get("stage", -1))
            ]
            snapshot = same_stage[-1] if same_stage else None

        nav_row_idx = int(nav_row.get("row", -1)) if nav_row else -1
        nav_col_idx = int(nav_row.get("column", -1)) if nav_row else -1

        derived = row.get("observation", [])[32:41]
        logical = []
        physical = []
        if snapshot is not None and 0 <= nav_row_idx < 99 and 0 <= nav_col_idx < 99:
            logical = neighbourhood(snapshot["maze"], nav_row_idx, nav_col_idx)
            physical = neighbourhood(snapshot["physicalFloor"], nav_row_idx, nav_col_idx)

        print("")
        print(
            f"CLASS={row['_diagnosticClass']} run={run} tick={tick} "
            f"stage={row.get('stage')} derivedTopology={derived}"
        )
        print(
            f"NAV cell=({nav_row_idx},{nav_col_idx}) "
            f"navPresent={nav_row is not None}"
        )
        if world_row:
            center = world_row.get("center")
            print(f"WORLD center={center} mazePattern={world_row.get('mazePattern')}")
        if snapshot is None:
            print("SNAPSHOT none")
            continue

        print(
            f"SNAPSHOT tick={snapshot['tick']} stage={snapshot['stage']} "
            f"physicalAvailable={snapshot['physicalFloor'] is not None}"
        )
        print(f"LOGICAL 3x3={matrix3(snapshot['maze'], nav_row_idx, nav_col_idx)}")
        print(f"PHYSICAL 3x3={matrix3(snapshot['physicalFloor'], nav_row_idx, nav_col_idx)}")
        print(f"LOGICAL9={logical} logicalBits={bits(logical)}")
        print(f"PHYSICAL9={physical} physicalBits={bits(physical)}")

    print("===============================================")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
