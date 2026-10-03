#!/usr/bin/env python3
"""Build canonical v2 locomotion samples from MonsterMazeAI observer runs.

The source run remains the ground truth. This importer reconstructs the
96-feature CPU observation contract and six semantic movement/action labels.
It deliberately does not invent tactical route labels; those will come from
the source-faithful Engine teacher.
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
import re
from pathlib import Path
from typing import Any

FEATURE_COUNT = 96
MAZE_SIZE = 99
HALF_MAZE = 49
KIT_ORDINAL = {
    "JUMPER": 0,
    "SLOWBALL": 1,
    "SLOWBALLER": 1,
    "BODY_BUILDER": 2,
    "BODY BUILDER": 2,
    "REPULSOR": 3,
    "MAVERICK": 4,
}
RAW_PATH_CELLS = {"1", "2", "5", "6"}
COLOR_CODE = re.compile(r"§.")
DEFAULT_PHASE_START = {"SPEED": 60.0, "MODERN": 35.0, "ORIGINAL": 60.0}
BASELINE_PROFILE = [
    0.50, 0.60, 0.50, 0.50, 0.50, 0.50, 0.50, 0.50,
    0.35, 0.50, 0.25, 0.50, 0.50, 0.50, 0.50, 0.25, 0.00,
]


def finite(value: Any, default: float = 0.0) -> float:
    try:
        value = float(value)
    except (TypeError, ValueError):
        return default
    return value if math.isfinite(value) else default


def clamp(value: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, value))


def norm(value: float, scale: float) -> float:
    return clamp(value / scale, -1.0, 1.0)


def normalize_yaw(degrees: float) -> float:
    while degrees >= 180.0:
        degrees -= 360.0
    while degrees < -180.0:
        degrees += 360.0
    return degrees


def strip_format(text: Any) -> str:
    return COLOR_CODE.sub("", str(text or ""))


def stage_from_scoreboard(row: dict[str, Any]) -> int:
    lines = row.get("scoreboardLines") or []
    for index, raw in enumerate(lines):
        if strip_format(raw).strip().lower() != "stage" or index + 1 >= len(lines):
            continue
        match = re.search(r"\d+", strip_format(lines[index + 1]))
        if match:
            return int(match.group())
    try:
        return int(row.get("stage"))
    except (TypeError, ValueError):
        return 0


def mode_from_scoreboard(row: dict[str, Any]) -> str:
    for raw in row.get("scoreboardLines") or []:
        text = strip_format(raw).strip().upper()
        if text in {"SPEED", "MODERN", "ORIGINAL"}:
            return text
    return "UNKNOWN"


def parse_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            if isinstance(value, dict) and value.get("recordType") in {"header", "footer"}:
                continue
            if isinstance(value, dict):
                rows.append(value)
    return rows


def by_tick(rows: list[dict[str, Any]]) -> dict[int, dict[str, Any]]:
    result: dict[int, dict[str, Any]] = {}
    for row in rows:
        tick = row.get("tick")
        if tick is None:
            continue
        try:
            result[int(tick)] = row
        except (TypeError, ValueError):
            continue
    return result


def maze_snapshots(rows: list[dict[str, Any]]) -> tuple[list[int], list[dict[str, Any]]]:
    ordered = []
    for row in rows:
        try:
            tick = int(row["tick"])
        except (KeyError, TypeError, ValueError):
            continue
        ordered.append((tick, row))
    ordered.sort(key=lambda item: item[0])
    return [item[0] for item in ordered], [item[1] for item in ordered]


def latest_maze(
    tick: int, maze_ticks: list[int], maze_rows: list[dict[str, Any]]
) -> dict[str, Any] | None:
    if not maze_ticks:
        return None
    index = bisect.bisect_right(maze_ticks, tick) - 1
    return maze_rows[index] if index >= 0 else None


def cell_is_path(maze_row: str | None, col: int) -> float:
    if maze_row is None or col < 0 or col >= len(maze_row):
        return 0.0
    return 1.0 if maze_row[col] in RAW_PATH_CELLS else 0.0


def local_path_features(
    player_x: float,
    player_z: float,
    center_x: float,
    center_z: float,
    maze_rows: list[str] | None,
) -> list[float]:
    row = math.floor(player_x - (center_x - HALF_MAZE))
    col = math.floor(player_z - (center_z - HALF_MAZE))
    if not maze_rows or len(maze_rows) != MAZE_SIZE:
        return [0.0] * 9

    def path_at(r: int, c: int) -> float:
        if r < 0 or r >= MAZE_SIZE:
            return 0.0
        return cell_is_path(maze_rows[r], c)

    return [
        path_at(row, col - 1),
        path_at(row, col + 1),
        path_at(row + 1, col),
        path_at(row - 1, col),
        path_at(row + 1, col - 1),
        path_at(row - 1, col - 1),
        path_at(row + 1, col + 1),
        path_at(row - 1, col + 1),
        path_at(row, col),
    ]


def monster_features(
    player: dict[str, Any], monsters_row: dict[str, Any] | None
) -> tuple[float, list[float]]:
    px = finite(player.get("x"))
    py = finite(player.get("y"))
    pz = finite(player.get("z"))
    nearest: list[tuple[float, float, float, float]] = []
    count_within_8 = 0

    if monsters_row:
        for item in monsters_row.get("monsters") or []:
            if not isinstance(item, list) or len(item) < 7:
                continue
            mx, my, mz = finite(item[1]), finite(item[2]), finite(item[3])
            dx, dy, dz = mx - px, my - py, mz - pz
            horizontal_d2 = dx * dx + dz * dz
            if horizontal_d2 <= 64.0:
                count_within_8 += 1
            distance_d2 = dx * dx + dy * dy + dz * dz
            nearest.append((distance_d2, dx, dz, dy))

    nearest.sort(key=lambda value: value[0])
    output = [0.0] * 32
    for index, (distance_d2, dx, dz, dy) in enumerate(nearest[:8]):
        base = index * 4
        output[base] = norm(dx, 32.0)
        output[base + 1] = norm(dz, 32.0)
        output[base + 2] = norm(dy, 4.0)
        output[base + 3] = norm(math.sqrt(max(0.0, distance_d2)), 32.0)

    return clamp(count_within_8 / 8.0, 0.0, 1.0), output


def canonical_observation(
    world: dict[str, Any],
    movement: dict[str, Any],
    monsters: dict[str, Any] | None,
    maze: dict[str, Any] | None,
    phase_start: float,
    stage: int,
    mode: str,
) -> list[float]:
    px, py, pz = (finite(movement.get(key)) for key in ("x", "y", "z"))
    vx, vy, vz = (finite(movement.get(key)) for key in ("vx", "vy", "vz"))
    yaw = finite(movement.get("yaw"))
    yaw_rad = math.radians(yaw)

    center = world.get("center") or {}
    cx, cy, cz = (
        finite(center.get("x")),
        finite(center.get("y")),
        finite(center.get("z")),
    )

    pad = world.get("activePad") or {}
    try:
        pad_row = int(pad.get("row", -1))
        pad_col = int(pad.get("column", -1))
    except (TypeError, ValueError):
        pad_row = pad_col = -1

    if pad_row >= 0 and pad_col >= 0 and center:
        target_x = cx - HALF_MAZE + pad_row + 0.5
        target_z = cz - HALF_MAZE + pad_col + 0.5
        pad_dx = target_x - px
        pad_dz = target_z - pz
    else:
        pad_dx = pad_dz = 0.0

    pad_distance = math.hypot(pad_dx, pad_dz)
    pad_bearing = math.atan2(-pad_dx, pad_dz)
    yaw_error = normalize_yaw(math.degrees(pad_bearing) - yaw)

    phase_seconds = finite(world.get("phaseTimerSeconds"), 0.0)
    safe_phase_start = max(1.0, phase_start)
    phase_ratio = clamp(phase_seconds / safe_phase_start, 0.0, 1.0)
    phase_elapsed = (safe_phase_start - phase_seconds) / safe_phase_start

    health = finite(world.get("health"), 20.0)
    max_health = 20.0  # observer v2 does not expose authoritative max health

    kit = str(world.get("kit") or "").upper()
    kit_ordinal = KIT_ORDINAL.get(kit, 0)
    jump_charges = clamp(finite(world.get("jumpCharges"), 0.0) / 5.0, 0.0, 1.0)
    ability_charges = finite(world.get("abilityCharges"), 0.0)
    ability_ready = (
        1.0
        if (
            (kit == "JUMPER" and jump_charges > 0.0)
            or (kit != "JUMPER" and ability_charges > 0.0)
        )
        else 0.0
    )

    pattern = int(world.get("mazePattern", -1)) - 1
    layout = [1.0 if pattern == index else 0.0 for index in range(3)]
    topology = local_path_features(px, pz, cx, cz, (maze or {}).get("maze"))
    monster_count, monster_vector = monster_features(movement, monsters)

    observation = [0.0] * FEATURE_COUNT
    observation[0] = norm(px - cx, 64.0)
    observation[1] = norm(pz - cz, 64.0)
    observation[2] = norm(py - cy, 8.0)
    observation[3] = clamp(vx, -1.0, 1.0)
    observation[4] = clamp(vz, -1.0, 1.0)
    observation[5] = clamp(vy, -1.0, 1.0)
    observation[6] = clamp(math.hypot(vx, vz), 0.0, 1.0)
    observation[7] = math.sin(yaw_rad)
    observation[8] = math.cos(yaw_rad)
    observation[9] = norm(pad_dx, 64.0)
    observation[10] = norm(pad_dz, 64.0)
    observation[11] = norm(pad_distance, 64.0)
    observation[12] = math.sin(pad_bearing)
    observation[13] = math.cos(pad_bearing)
    observation[14] = yaw_error / 180.0
    observation[15] = clamp(stage / 100.0, 0.0, 1.0)
    observation[16] = clamp(phase_seconds / 60.0, 0.0, 1.0)
    observation[17] = phase_ratio
    observation[18] = clamp(health / max_health, 0.0, 1.0)
    observation[19] = clamp(max_health / 30.0, 0.0, 1.0)
    observation[20] = 1.0 if movement.get("grounded") else 0.0
    observation[21] = 1.0 if pad.get("reached") else 0.0
    observation[22] = 1.0 if pad.get("reached") else 0.0
    observation[23] = kit_ordinal / 4.0
    observation[24] = jump_charges
    observation[25] = ability_ready
    observation[26] = 1.0 if mode == "SPEED" else 0.0
    observation[27] = 1.0 if mode == "MODERN" else 0.0
    observation[28:31] = layout
    observation[31] = clamp((stage - 1.0 + phase_elapsed) / 100.0, 0.0, 1.0)
    observation[32:41] = topology
    observation[41] = 1.0 / 8.0
    observation[42] = 1.0 / 8.0
    observation[43] = monster_count
    observation[44:76] = monster_vector
    # Solo human demonstrations have no competitor slots; leave 76-95 zero.
    return [finite(value) for value in observation]


def action_from_input(row: dict[str, Any]) -> list[float | bool]:
    return [
        clamp(finite(row.get("forward")), -1.0, 1.0),
        clamp(finite(row.get("strafe")), -1.0, 1.0),
        clamp(finite(row.get("yawDelta")), -30.0, 30.0),
        bool(row.get("jump")),
        bool(row.get("sprintKey")),
        bool(row.get("rawUseItem") or row.get("mouseRightPulse")),
    ]


def phase_starts(world_rows: list[dict[str, Any]], fallback_mode: str) -> dict[int, float]:
    starts: dict[int, float] = {}
    for row in world_rows:
        stage = stage_from_scoreboard(row)
        timer = finite(row.get("phaseTimerSeconds"), 0.0)
        if stage > 0 and timer > 0:
            starts.setdefault(stage, timer)

    fallback = DEFAULT_PHASE_START.get(fallback_mode, 60.0)
    for stage in {stage_from_scoreboard(row) for row in world_rows}:
        starts.setdefault(stage, fallback)
    return starts


def write_samples(root: Path, output: Path) -> dict[str, Any]:
    manifests = sorted(root.glob("*-manifest.json"))
    output.parent.mkdir(parents=True, exist_ok=True)

    run_summaries: list[dict[str, Any]] = []
    total_samples = 0
    rejected_runs = 0

    with output.open("w", encoding="utf-8") as handle:
        for manifest_path in manifests:
            run_id = manifest_path.name.removesuffix("-manifest.json")
            manifest_rows = parse_jsonl(manifest_path)
            manifest = manifest_rows[0] if manifest_rows else {}
            file_names = manifest.get("files") or []
            paths = {
                Path(name).stem.removeprefix(run_id + "-"): root / name
                for name in file_names
            }

            required = {"input", "movement", "world", "maze", "monsters"}
            if any(name not in paths for name in required):
                rejected_runs += 1
                continue

            inputs = by_tick(parse_jsonl(paths["input"]))
            movements = by_tick(parse_jsonl(paths["movement"]))
            worlds_rows = parse_jsonl(paths["world"])
            worlds = by_tick(worlds_rows)
            monsters = by_tick(parse_jsonl(paths["monsters"]))
            maze_ticks, maze_rows = maze_snapshots(parse_jsonl(paths["maze"]))

            first_world = worlds_rows[0] if worlds_rows else {}
            mode = mode_from_scoreboard(first_world)
            starts = phase_starts(worlds_rows, mode)

            ticks = sorted(set(inputs) & set(movements) & set(worlds))
            included = 0
            stage_values: list[int] = []

            for index, tick in enumerate(ticks):
                world = worlds[tick]
                movement = movements[tick]
                if not world.get("mazeDetected") or not world.get("center"):
                    continue

                stage = stage_from_scoreboard(world)
                if stage <= 0:
                    continue

                mode_at_tick = mode_from_scoreboard(world)
                if mode_at_tick != "UNKNOWN":
                    mode = mode_at_tick

                phase_start = starts.get(
                    stage, DEFAULT_PHASE_START.get(mode, 60.0)
                )
                maze = latest_maze(tick, maze_ticks, maze_rows)
                observation = canonical_observation(
                    world, movement, monsters.get(tick), maze,
                    phase_start, stage, mode,
                )
                action = action_from_input(inputs[tick])

                next_tick = ticks[index + 1] if index + 1 < len(ticks) else None
                next_world = worlds.get(next_tick) if next_tick is not None else None

                row = {
                    "schema_version": 2,
                    "source": "human-observer",
                    "run_id": run_id,
                    "tick": tick,
                    "stage": stage,
                    "mode": mode,
                    "pattern": int(world.get("mazePattern", -1)),
                    "kit": world.get("kit"),
                    "profile": BASELINE_PROFILE,
                    "observation": observation,
                    "action": {
                        "forward": action[0],
                        "strafe": action[1],
                        "yawDelta": action[2],
                        "jump": action[3],
                        "sprint": action[4],
                        "useAbility": action[5],
                    },
                    "nextStage": stage_from_scoreboard(next_world) if next_world else stage,
                    "nextAlive": bool(next_world.get("alive")) if next_world else False,
                    "teacherSource": "human-demonstration",
                }
                handle.write(
                    json.dumps(row, separators=(",", ":"), allow_nan=False) + "\n"
                )
                included += 1
                total_samples += 1
                stage_values.append(stage)

            run_summaries.append({
                "run_id": run_id,
                "source_ticks": len(ticks),
                "samples": included,
                "max_stage": max(stage_values, default=0),
                "mode": mode,
            })

    return {
        "runs": len(run_summaries),
        "samples": total_samples,
        "runs_rejected": rejected_runs,
        "summaries": run_summaries,
        "output": str(output),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input-root", type=Path, default=Path("training-data/human-runs")
    )
    parser.add_argument(
        "--output", type=Path, default=Path("ml-data/human-v2/locomotion.jsonl")
    )
    args = parser.parse_args()
    print(json.dumps(write_samples(args.input_root, args.output), indent=2))


if __name__ == "__main__":
    main()
