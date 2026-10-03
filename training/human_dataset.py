#!/usr/bin/env python3
"""
Build a canonical Monster Maze CPU-policy dataset from Minecraft 1.8 human runs.

The source corpus is the observer output under:
    training-data/human-runs/

This script is intentionally standard-library-only.  It does not train a model;
it converts synchronized human telemetry into the v2 96-feature observation
contract plus six-dimensional semantic action labels.

Usage:
    python training/human_dataset.py --root training-data/human-runs \
        --output training-data/derived/human-policy-v2.jsonl

    python training/human_dataset.py --root training-data/human-runs \
        --catalog training-data/derived/human-run-catalog.json --catalog-only
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Tuple


SCHEMA_VERSION = 2
FEATURE_COUNT = 96
PROFILE_COUNT = 17

KIT_ORDINAL = {
    "JUMPER": 0,
    "SLOWBALLER": 1,
    "BODY_BUILDER": 2,
    "REPULSOR": 3,
    "MAVERICK": 4,
}

BASELINE_PROFILE = [
    0.50, 0.60, 0.50, 0.50, 0.50, 0.50, 0.50, 0.50,
    0.35, 0.50, 0.25, 0.50, 0.50, 0.50, 0.50, 0.25, 0.00,
]


def finite(value: object, default: float = 0.0) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if math.isfinite(number) else default


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def wrap_degrees(angle: float) -> float:
    result = (angle + 180.0) % 360.0 - 180.0
    return result


def norm(value: float, scale: float, limit: float = 1.0) -> float:
    if scale == 0:
        return 0.0
    return clamp(value / scale, -limit, limit)


def stream_rows(path: Path) -> Iterator[dict]:
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


def load_by_tick(path: Path) -> Dict[int, dict]:
    result: Dict[int, dict] = {}
    for row in stream_rows(path):
        try:
            result[int(row["tick"])] = row
        except (TypeError, ValueError):
            continue
    return result


def parse_manifest(path: Path) -> dict:
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    records = [json.loads(line) for line in lines if line.startswith("{")]
    manifest = next((x for x in records if x.get("recordType") == "manifest"), {})
    footer = next((x for x in records if x.get("recordType") == "footer"), {})
    return {"manifest": manifest, "footer": footer}


def detect_kit(inventory_path: Path) -> str:
    counts = {
        "JUMPER": 0,
        "SLOWBALLER": 0,
        "BODY_BUILDER": 0,
        "REPULSOR": 0,
    }

    for row in stream_rows(inventory_path):
        for item in row.get("items", []) or []:
            if not isinstance(item, dict):
                continue
            text = str(item.get("displayName", "")).lower()
            if "jumps remaining" in text:
                counts["JUMPER"] += 1
            if "slowball" in text:
                counts["SLOWBALLER"] += 1
            if "body rush" in text:
                counts["BODY_BUILDER"] += 1
            if "repulse" in text:
                counts["REPULSOR"] += 1

    best = max(counts, key=counts.get)
    if counts[best] > 0:
        return best
    return "MAVERICK"


def detect_mode(world_rows: Iterable[dict]) -> str:
    for row in world_rows:
        lines = " ".join(str(x) for x in (row.get("scoreboardLines") or []))
        lowered = lines.lower()
        if "modern" in lowered:
            return "MODERN"
        if "speed" in lowered:
            return "SPEED"
    return "UNKNOWN"


def find_center(world: Optional[dict]) -> Optional[Tuple[float, float, float]]:
    if not world:
        return None

    center = world.get("center")
    if isinstance(center, dict):
        try:
            return finite(center["x"]), finite(center["y"]), finite(center["z"])
        except KeyError:
            return None

    # Some observer revisions may encode a simple triple.
    if isinstance(center, (list, tuple)) and len(center) >= 3:
        return finite(center[0]), finite(center[1]), finite(center[2])
    return None


def pad_world_position(world: Optional[dict]) -> Optional[Tuple[float, float, float]]:
    center = find_center(world)
    pad = world.get("activePad") if world else None
    if center is None or not isinstance(pad, dict):
        return None

    try:
        row = int(pad.get("row", -1))
        col = int(pad.get("column", -1))
    except (TypeError, ValueError):
        return None

    if row < 0 or col < 0:
        return None

    cx, cy, cz = center
    return cx - 49.0 + row + 0.5, cy - 1.0, cz - 49.0 + col + 0.5


def mode_bits(world: Optional[dict]) -> Tuple[float, float]:
    if not world:
        return 0.0, 0.0
    lines = " ".join(str(x) for x in (world.get("scoreboardLines") or []))
    lowered = lines.lower()
    return (
        1.0 if "speed" in lowered else 0.0,
        1.0 if "modern" in lowered else 0.0,
    )


def extract_local_topology(maze_row: Optional[dict], movement: dict,
                           center: Optional[Tuple[float, float, float]]) -> List[float]:
    if not maze_row or center is None:
        return [0.0] * 9

    # Prefer physicalFloor: it represents the actually walkable surface,
    # including dynamic deterioration. Fall back to logical maze data for
    # older observer streams that did not emit physicalFloor.
    maze = maze_row.get("physicalFloor")
    if not isinstance(maze, list) or len(maze) != 99:
        maze = maze_row.get("maze")
    if not isinstance(maze, list) or len(maze) != 99:
        return [0.0] * 9

    cx, _cy, cz = center
    px = finite(movement.get("x"))
    pz = finite(movement.get("z"))
    grid_row = int(math.floor(px - (cx - 49.0)))
    grid_col = int(math.floor(pz - (cz - 49.0)))

    coords = [
        (0, -1), (0, 1), (1, 0), (-1, 0),
        (1, -1), (1, 1), (-1, -1), (-1, 1),
        (0, 0),
    ]
    values: List[float] = []
    for dr, dc in coords:
        rr = grid_row + dr
        cc = grid_col + dc
        if rr < 0 or rr >= 99 or cc < 0 or cc >= 99:
            values.append(0.0)
            continue
        try:
            values.append(float(str(maze[rr])[cc] != "0"))
        except (IndexError, TypeError):
            values.append(0.0)
    return values


def monster_features(monster_row: Optional[dict], movement: dict) -> Tuple[List[float], int]:
    if not monster_row:
        return [0.0] * 32, 0

    px = finite(movement.get("x"))
    py = finite(movement.get("y"))
    pz = finite(movement.get("z"))

    monsters: List[Tuple[float, float, float, float, dict]] = []
    for raw in monster_row.get("monsters", []) or []:
        if not isinstance(raw, list) or len(raw) < 4:
            continue
        mx = finite(raw[1])
        my = finite(raw[2])
        mz = finite(raw[3])
        dx = mx - px
        dy = my - py
        dz = mz - pz
        distance = math.sqrt(dx * dx + dy * dy + dz * dz)
        monsters.append((distance, dx, dy, dz, raw))

    monsters.sort(key=lambda x: x[0])
    within8 = sum(1 for x in monsters if math.hypot(x[1], x[3]) <= 8.0)

    out: List[float] = []
    for distance, dx, dy, dz, _raw in monsters[:8]:
        out.extend([
            norm(dx, 32.0),
            norm(dz, 32.0),
            norm(dy, 4.0),
            clamp(distance / 32.0, 0.0, 1.0),
        ])

    while len(out) < 32:
        out.extend([0.0, 0.0, 0.0, 0.0])

    return out[:32], within8


def build_observation(world: dict, movement: dict,
                      maze_row: Optional[dict],
                      monster_row: Optional[dict],
                      population_alive: int = 1,
                      population_humans: int = 1,
                      phase_start_seconds: Optional[int] = None,
                      kit_override: Optional[str] = None) -> Tuple[List[float], dict]:
    features = [0.0] * FEATURE_COUNT
    center = find_center(world)
    px = finite(movement.get("x"))
    py = finite(movement.get("y"))
    pz = finite(movement.get("z"))
    vx = finite(movement.get("vx"))
    vy = finite(movement.get("vy"))
    vz = finite(movement.get("vz"))
    yaw = finite(movement.get("yaw"))

    if center is not None:
        cx, cy, cz = center
        features[0] = norm(px - cx, 64.0)
        features[1] = norm(pz - cz, 64.0)
        features[2] = norm(py - cy, 8.0)

    features[3] = norm(vx, 1.0)
    features[4] = norm(vz, 1.0)
    features[5] = norm(vy, 1.0)
    features[6] = clamp(math.hypot(vx, vz), 0.0, 1.0)

    yaw_rad = math.radians(yaw)
    features[7] = math.sin(yaw_rad)
    features[8] = math.cos(yaw_rad)

    pad_pos = pad_world_position(world)
    pad_reached = False
    if isinstance(world.get("activePad"), dict):
        pad_reached = bool(world["activePad"].get("reached", False))

    if pad_pos is not None:
        tx, _ty, tz = pad_pos
        dx = tx - px
        dz = tz - pz
        distance = math.hypot(dx, dz)
        target_angle = math.degrees(math.atan2(-dx, dz))
        bearing = wrap_degrees(target_angle - yaw)

        features[9] = norm(dx, 64.0)
        features[10] = norm(dz, 64.0)
        features[11] = clamp(distance / 64.0, 0.0, 1.0)
        features[12] = math.sin(math.radians(bearing))
        features[13] = math.cos(math.radians(bearing))
        features[14] = clamp(bearing / 180.0, -1.0, 1.0)

    stage = int(world.get("stage", 1) or 1)
    phase_remaining = int(finite(world.get("phaseTimerSeconds"), 0.0))
    phase_start = phase_start_seconds if phase_start_seconds is not None else max(phase_remaining, 0)
    phase_elapsed_ratio = (float(phase_start - phase_remaining) / float(max(1, phase_start))) if phase_start > 0 else 0.0

    features[15] = clamp(stage / 100.0, 0.0, 1.0)
    features[16] = clamp(float(max(0, phase_remaining)) / 60.0, 0.0, 1.0)
    features[17] = clamp(
        float(max(0, phase_remaining)) / float(max(1, phase_start)), 0.0, 1.0
    ) if phase_start > 0 else 0.0

    health = finite(world.get("health"), 0.0)
    # Monster Maze 1.8 human runs normally have 20 max health.
    max_health = max(20.0, finite(world.get("maxHealth"), 20.0))
    features[18] = clamp(health / max_health, 0.0, 1.0)
    features[19] = clamp(max_health / 30.0, 0.0, 1.0)
    features[20] = 1.0 if movement.get("grounded", False) else 0.0
    features[21] = 1.0 if pad_reached else 0.0
    features[22] = 1.0 if pad_reached else 0.0

    kit = str(kit_override if kit_override is not None else world.get("kit", "MAVERICK")).upper()
    features[23] = float(KIT_ORDINAL.get(kit, 4)) / 4.0
    features[24] = clamp(finite(world.get("jumpCharges"), 0.0) / 5.0, 0.0, 1.0)
    ability_charges = max(0.0, finite(world.get("abilityCharges"), 0.0))
    features[25] = 1.0 if ability_charges > 0.0 else 0.0

    speed_mode, modern_mode = mode_bits(world)
    features[26] = speed_mode
    features[27] = modern_mode

    maze_pattern = int(world.get("mazePattern", -1) or -1)
    features[28] = 1.0 if maze_pattern == 1 else 0.0
    features[29] = 1.0 if maze_pattern == 2 else 0.0
    features[30] = 1.0 if maze_pattern == 3 else 0.0
    features[31] = clamp((stage - 1.0 + phase_elapsed_ratio) / 100.0, 0.0, 1.0)

    features[32:41] = extract_local_topology(maze_row, movement, center)
    features[41] = clamp(float(population_alive) / 8.0, 0.0, 1.0)
    features[42] = clamp(float(population_humans) / 8.0, 0.0, 1.0)

    monster_values, monster_count = monster_features(monster_row, movement)
    features[43] = clamp(float(monster_count) / 8.0, 0.0, 1.0)
    features[44:76] = monster_values

    # Solo observer runs do not yet contain synchronized competitor state.
    # Keep the canonical four slots at zero rather than inventing identities.
    features[76:96] = [0.0] * 20

    extras = {
        "stage": stage,
        "kit": kit,
        "maze_pattern": maze_pattern,
        "mode_speed": bool(speed_mode),
        "mode_modern": bool(modern_mode),
        "pad_reached": pad_reached,
        "monsters_within8": monster_count,
        "alive": bool(world.get("alive", True)),
        "completed": bool(world.get("completed", False)),
    }
    return features, extras


def build_action(input_row: dict) -> dict:
    yaw = clamp(finite(input_row.get("yawDelta"), 0.0), -30.0, 30.0)
    use_ability = bool(
        input_row.get("rawUseItem", False)
        or input_row.get("mouseRightPulse", False)
    )
    return {
        "forward": clamp(finite(input_row.get("forward"), 0.0), -1.0, 1.0),
        "strafe": clamp(finite(input_row.get("strafe"), 0.0), -1.0, 1.0),
        "yawDelta": yaw,
        "jump": bool(input_row.get("jump", False)),
        "sprint": bool(input_row.get("sprintKey", False)),
        "useAbility": use_ability,
    }


def run_prefix(manifest_path: Path) -> str:
    name = manifest_path.name
    suffix = "-manifest.json"
    if not name.endswith(suffix):
        raise ValueError(name)
    return name[:-len(suffix)]


def make_catalog(root: Path) -> List[dict]:
    catalog: List[dict] = []

    for manifest_path in sorted(root.glob("*-manifest.json")):
        prefix = run_prefix(manifest_path)
        parsed = parse_manifest(manifest_path)
        manifest = parsed["manifest"]
        footer = parsed["footer"]

        inventory_path = root / f"{prefix}-inventory.jsonl"
        world_path = root / f"{prefix}-world.jsonl"

        world_rows = list(stream_rows(world_path)) if world_path.exists() else []
        kit = detect_kit(inventory_path) if inventory_path.exists() else "MAVERICK"
        mode = detect_mode(world_rows)
        reason = str(footer.get("reason", ""))

        reached_stage = None
        marker = "reached stage "
        lowered = reason.lower()
        if marker in lowered:
            tail = lowered.split(marker, 1)[1].split("!", 1)[0].strip()
            try:
                reached_stage = int(tail)
            except ValueError:
                reached_stage = None

        ticks = len(world_rows)
        first_tick = world_rows[0].get("tick") if world_rows else None
        last_tick = world_rows[-1].get("tick") if world_rows else None

        catalog.append({
            "run": prefix,
            "kit": kit,
            "kitOrdinal": KIT_ORDINAL.get(kit, 4),
            "mode": mode,
            "reachedStage": reached_stage,
            "records": int(manifest.get("records", 0) or 0),
            "manifestStage": manifest.get("stage"),
            "worldRows": ticks,
            "firstTick": first_tick,
            "lastTick": last_tick,
            "reason": reason,
            "jsonVersion": manifest.get("jsonVersion"),
            "minecraftVersion": manifest.get("minecraftVersion"),
        })

    return catalog


def build_run_dataset(root: Path, prefix: str) -> Tuple[List[dict], dict]:
    world_path = root / f"{prefix}-world.jsonl"
    movement_path = root / f"{prefix}-movement.jsonl"
    input_path = root / f"{prefix}-input.jsonl"
    maze_path = root / f"{prefix}-maze.jsonl"
    monster_path = root / f"{prefix}-monsters.jsonl"

    world = load_by_tick(world_path)
    movement = load_by_tick(movement_path)
    inputs = load_by_tick(input_path)

    # Keep maze/monster processing streaming-sized. These streams can be much
    # larger than world/input, especially because each maze row carries a full
    # 99x99 representation.
    topology_rows: List[Tuple[int, int, List[float]]] = []
    if maze_path.exists():
        for row in stream_rows(maze_path):
            try:
                tick = int(row["tick"])
                row_stage = int(row.get("stage", -1))
            except (TypeError, ValueError):
                continue
            world_row = world.get(tick)
            movement_row = movement.get(tick)
            center = find_center(world_row) if world_row else None
            if world_row and movement_row and center is not None:
                topology_rows.append((
                    tick,
                    row_stage,
                    extract_local_topology(row, movement_row, center),
                ))

    topology_rows.sort(key=lambda item: item[0])

    monsters_by_tick: Dict[int, dict] = {}
    if monster_path.exists():
        for row in stream_rows(monster_path):
            try:
                tick = int(row["tick"])
            except (TypeError, ValueError):
                continue
            if tick in world and tick in movement:
                monsters_by_tick[tick] = row

    shared_ticks = sorted(set(world) & set(movement) & set(inputs))

    kit = detect_kit(root / f"{prefix}-inventory.jsonl")
    mode = detect_mode(world[t] for t in shared_ticks[:20] if t in world)

    phase_starts: Dict[int, int] = {}
    rows: List[dict] = []

    for index, tick in enumerate(shared_ticks):
        world_row = world[tick]
        movement_row = movement[tick]
        input_row = inputs[tick]

        stage = int(world_row.get("stage", 1) or 1)
        phase_remaining = int(finite(world_row.get("phaseTimerSeconds"), 0.0))
        if stage not in phase_starts:
            phase_starts[stage] = max(0, phase_remaining)

        features, extras = build_observation(
            world_row,
            movement_row,
            None,
            monsters_by_tick.get(tick),
            population_alive=1,
            population_humans=1,
            phase_start_seconds=phase_starts[stage],
            kit_override=kit,
        )

        # Maze snapshots are much sparser than tick telemetry. Carry forward
        # the latest snapshot from the same stage instead of requiring an
        # exact-tick match. This preserves the topology state that was known
        # to the observer at the time of the action.
        if topology_rows:
            import bisect
            topology_ticks = [item[0] for item in topology_rows]
            position = bisect.bisect_right(topology_ticks, tick) - 1
            if position >= 0:
                snapshot_tick, snapshot_stage, snapshot_features = topology_rows[position]
                if snapshot_stage == stage:
                    features[32:41] = snapshot_features

        action = build_action(input_row)

        next_tick = shared_ticks[index + 1] if index + 1 < len(shared_ticks) else None
        next_world = world.get(next_tick) if next_tick is not None else None
        next_movement = movement.get(next_tick) if next_tick is not None else None

        next_summary = None
        terminal = False
        if next_world is not None and next_movement is not None:
            next_summary = {
                "tick": next_tick,
                "stage": int(next_world.get("stage", stage) or stage),
                "alive": bool(next_world.get("alive", False)),
                "x": finite(next_movement.get("x")),
                "y": finite(next_movement.get("y")),
                "z": finite(next_movement.get("z")),
                "health": finite(next_world.get("health")),
            }
            terminal = not next_summary["alive"]
        else:
            terminal = not bool(world_row.get("alive", True))

        rows.append({
            "schemaVersion": SCHEMA_VERSION,
            "observation": features,
            "profile": BASELINE_PROFILE,
            "profileSource": "baseline-human-import",
            "action": action,
            "stage": extras["stage"],
            "pattern": extras["maze_pattern"],
            "mode": mode,
            "kit": kit,
            "source": "human",
            "run": prefix,
            "tick": tick,
            "terminal": terminal,
            "completed": extras["completed"],
            "padReached": extras["pad_reached"],
            "monstersWithin8": extras["monsters_within8"],
            "next": next_summary,
        })

    meta = {
        "run": prefix,
        "kit": kit,
        "mode": mode,
        "rows": len(rows),
        "firstTick": shared_ticks[0] if shared_ticks else None,
        "lastTick": shared_ticks[-1] if shared_ticks else None,
        "reachedStage": None,
    }
    return rows, meta


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, separators=(",", ":"), allow_nan=False))
            handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("training-data/human-runs"),
        help="directory containing committed observer runs",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("training-data/derived/human-policy-v2.jsonl"),
        help="derived training JSONL output",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=Path("training-data/derived/human-run-catalog.json"),
        help="run catalog JSON output",
    )
    parser.add_argument(
        "--catalog-only",
        action="store_true",
        help="only create the run catalog; do not build training rows",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    if not root.exists():
        raise SystemExit(f"Human run directory not found: {root}")

    catalog = make_catalog(root)
    args.catalog.parent.mkdir(parents=True, exist_ok=True)
    args.catalog.write_text(
        json.dumps(
            {
                "schemaVersion": SCHEMA_VERSION,
                "featureCount": FEATURE_COUNT,
                "profileCount": PROFILE_COUNT,
                "runs": catalog,
            },
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )

    print("========== HUMAN RUN CATALOG ==========")
    print(f"runs={len(catalog)}")
    for item in catalog:
        print(
            f"{item['run']} kit={item['kit']} mode={item['mode']} "
            f"stage={item['reachedStage']} worldRows={item['worldRows']}"
        )

    if args.catalog_only:
        return 0

    total_rows = 0
    dataset_meta: List[dict] = []
    with args.output.open("w", encoding="utf-8", newline="\n") as handle:
        for item in catalog:
            rows, meta = build_run_dataset(root, item["run"])
            total_rows += len(rows)
            dataset_meta.append(meta)
            for row in rows:
                handle.write(
                    json.dumps(row, separators=(",", ":"), allow_nan=False)
                )
                handle.write("\n")

    print("========== HUMAN DATASET ==========")
    print(f"schemaVersion={SCHEMA_VERSION}")
    print(f"featureCount={FEATURE_COUNT}")
    print(f"profileCount={PROFILE_COUNT}")
    print(f"runs={len(catalog)}")
    print(f"rows={total_rows}")
    print(f"output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
