#!/usr/bin/env python3
"""Analyze Minecraft 1.8.9 MonsterMaze human-run recorder output.

Stdlib-only CLI:
  python tools/human_run_analyzer.py <run-directory>
  python tools/human_run_analyzer.py <run-directory> --sim-summary sim.json

The analyzer never mutates raw recorder files. It emits normalized tick/stage/
decision/event JSONL plus summary, anomalies, and optional simulator comparison.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import re
from collections import Counter, defaultdict, deque
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Tuple


KNOWN_KITS = ("MAVERICK", "REPULSOR", "JUMPER", "SLOWBALLER", "BODY_BUILDER")
KNOWN_MODES = ("ORIGINAL", "SPEED", "MODERN")
STREAMS = (
    "movement", "input", "world", "objectives", "navigation",
    "monsters", "maze", "inventory", "collision", "events",
)


def clean_text(value: Any) -> str:
    text = "" if value is None else str(value)
    return re.sub(r"§.", "", text).strip()


def first_int(text: str) -> Optional[int]:
    m = re.search(r"(?<!\d)(\d+)(?!\d)", text.replace(",", ""))
    return int(m.group(1)) if m else None


def iter_jsonl(path: Optional[Path], errors: List[Dict[str, Any]]) -> Iterator[Dict[str, Any]]:
    if path is None or not path.exists():
        return
    with path.open("r", encoding="utf-8") as handle:
        for line_no, raw in enumerate(handle, 1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                value = json.loads(raw)
            except json.JSONDecodeError as exc:
                errors.append({
                    "type": "MALFORMED_JSON",
                    "file": str(path),
                    "line": line_no,
                    "detail": str(exc),
                })
                continue
            if isinstance(value, dict):
                yield value


def load_manifest(run_dir: Path) -> Tuple[Dict[str, Any], List[str], Optional[str]]:
    candidates = [run_dir / "manifest.json"]
    candidates.extend(sorted(run_dir.glob("*-manifest.json")))
    candidates.extend(sorted((run_dir / "raw").glob("*-manifest.json")))
    manifest: Dict[str, Any] = {}
    manifest_path = next((p for p in candidates if p.exists()), None)
    if manifest_path:
        with manifest_path.open("r", encoding="utf-8") as handle:
            for raw in handle:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if obj.get("recordType") == "manifest":
                    manifest = obj
                    break
    end_reason: Optional[str] = None
    if manifest_path:
        with manifest_path.open("r", encoding="utf-8") as handle:
            for raw in handle:
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if obj.get("recordType") == "footer":
                    end_reason = obj.get("reason")
    return manifest, [str(x) for x in manifest.get("files", [])], end_reason


def resolve_stream(run_dir: Path, stream: str, manifest_files: Sequence[str]) -> Optional[Path]:
    roots = (run_dir, run_dir / "raw")
    names = [Path(name).name for name in manifest_files if name.endswith(f"-{stream}.jsonl")]
    for root in roots:
        for name in names:
            path = root / name
            if path.exists():
                return path
    for root in roots:
        for pattern in (f"*-{stream}.jsonl", f"{stream}.jsonl"):
            matches = sorted(root.glob(pattern))
            if matches:
                return matches[-1]
    return None


def load_streams(run_dir: Path, manifest_files: Sequence[str], errors: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    result: Dict[str, List[Dict[str, Any]]] = {}
    for stream in STREAMS:
        result[stream] = list(iter_jsonl(resolve_stream(run_dir, stream, manifest_files), errors))
    return result


def by_tick(records: Iterable[Dict[str, Any]]) -> Dict[int, Dict[str, Any]]:
    out: Dict[int, Dict[str, Any]] = {}
    for record in records:
        tick = record.get("tick")
        if isinstance(tick, (int, float)) and not isinstance(tick, bool):
            out[int(tick)] = record
    return out


def scoreboard_info(lines: Sequence[Any]) -> Dict[str, Any]:
    cleaned = [clean_text(x) for x in lines]
    upper = [x.upper() for x in cleaned]
    mode = next((name.lower() for name in KNOWN_MODES if any(re.search(rf"\b{name}\b", line) for line in upper)), None)

    kit = None
    for name in KNOWN_KITS:
        if any(name in line for line in upper):
            kit = name
            break
    if kit is None:
        for line in cleaned:
            match = re.search(r"\(([^)]+)\)", line)
            if match:
                candidate = re.sub(r"[^A-Za-z_]", "", match.group(1)).upper()
                if candidate in KNOWN_KITS:
                    kit = candidate
                    break

    stage = None
    safe_seconds = None
    def nearby_numeric_value(index: int) -> Optional[int]:
        same = first_int(cleaned[index])
        if same is not None and re.fullmatch(r"\\s*\\d+\\s*", cleaned[index]):
            return same
        candidates = []
        for distance in (1, 2):
            for neighbor_index in (index - distance, index + distance):
                if 0 <= neighbor_index < len(cleaned):
                    value = first_int(cleaned[neighbor_index])
                    if value is None:
                        continue
                    pure = bool(re.fullmatch(r"\\s*\\d+\\s*", cleaned[neighbor_index]))
                    candidates.append((0 if pure else 1, distance, value))
        if same is not None:
            candidates.append((1, 0, same))
        return min(candidates)[2] if candidates else None

    for i, line in enumerate(cleaned):
        lower = line.lower()
        if "stage" in lower:
            value = nearby_numeric_value(i)
            if value is not None and 0 < value < 10000:
                stage = value
        if "second" in lower and ("safe" in lower or "pad" in lower):
            value = nearby_numeric_value(i)
            if value is not None:
                safe_seconds = value
    if safe_seconds is None:
        for line in cleaned:
            match = re.search(r"(\d+)\s*seconds", line, re.IGNORECASE)
            if match:
                safe_seconds = int(match.group(1))
                break

    return {"mode": mode, "kit": kit, "stage": stage, "safePadSeconds": safe_seconds, "lines": cleaned}


def pad_key(record: Optional[Dict[str, Any]]) -> Optional[Tuple[int, int]]:
    if not record or not isinstance(record.get("activePad"), dict):
        return None
    pad = record["activePad"]
    row, col = pad.get("row"), pad.get("column")
    if isinstance(row, int) and isinstance(col, int) and row >= 0 and col >= 0:
        return row, col
    return None


def reconstruct_stages(ticks: Sequence[int], worlds: Dict[int, Dict[str, Any]], navigations: Dict[int, Dict[str, Any]]) -> Dict[int, int]:
    reconstructed: Dict[int, int] = {}
    stage_from_pad = 0
    last_pad: Optional[Tuple[int, int]] = None
    for tick in ticks:
        world = worlds.get(tick, {})
        nav = navigations.get(tick, {})
        key = pad_key(world) or pad_key(nav)
        if key is not None and key != last_pad:
            stage_from_pad += 1
            last_pad = key
        lines = world.get("scoreboardLines")
        info = scoreboard_info(lines) if isinstance(lines, list) else {}
        if isinstance(info.get("stage"), int):
            reconstructed[tick] = info["stage"]
        elif stage_from_pad:
            reconstructed[tick] = stage_from_pad
    return reconstructed


def infer_metadata(manifest: Dict[str, Any], worlds: Dict[int, Dict[str, Any]], end_reason: Optional[str]) -> Dict[str, Any]:
    modes: Counter[str] = Counter()
    kits: Counter[str] = Counter()
    patterns: Counter[int] = Counter()
    observer_kits: Counter[str] = Counter()

    for world in worlds.values():
        lines = world.get("scoreboardLines")
        if isinstance(lines, list):
            info = scoreboard_info(lines)
            if info["mode"]:
                modes[info["mode"]] += 1
            if info["kit"]:
                kits[info["kit"]] += 1
        if world.get("kit"):
            observer_kits[clean_text(world.get("kit")).upper()] += 1
        raw_pattern = world.get("mazePattern")
        if isinstance(raw_pattern, int) and raw_pattern >= 0:
            patterns[raw_pattern] += 1

    raw_pattern = patterns.most_common(1)[0][0] if patterns else None
    metadata: Dict[str, Any] = {
        "schemaVersion": 1,
        "source": "minecraft-human",
        "minecraftVersion": manifest.get("minecraftVersion", "1.8.9"),
        "mode": modes.most_common(1)[0][0] if modes else None,
        "kit": kits.most_common(1)[0][0] if kits else None,
        "pattern": raw_pattern + 1 if raw_pattern is not None else None,
        "rawMazePattern": raw_pattern,
        "terminalReason": end_reason,
        "boundary": manifest.get("boundary"),
    }
    if observer_kits:
        metadata["observerKit"] = observer_kits.most_common(1)[0][0]
    return metadata


def angular_delta(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


def nearest_monster(monster_record: Optional[Dict[str, Any]], px: float, py: float, pz: float) -> Tuple[Optional[float], int]:
    if not monster_record:
        return None, 0
    best: Optional[float] = None
    count = 0
    for entry in monster_record.get("monsters", []):
        if not isinstance(entry, list) or len(entry) < 7:
            continue
        try:
            dx = float(entry[1]) - px
            dy = float(entry[2]) - py
            dz = float(entry[3]) - pz
        except (TypeError, ValueError):
            continue
        distance = math.sqrt(dx * dx + dy * dy + dz * dz)
        count += 1
        if best is None or distance < best:
            best = distance
    return best, count


def derive_external_impulse(current: Dict[str, Any], previous: Optional[Dict[str, Any]], recent_damage: bool) -> bool:
    speed = float(current.get("horizontalSpeed") or 0.0)
    previous_speed = float(previous.get("horizontalSpeed") or 0.0) if previous else 0.0
    if speed >= 0.65:
        return True
    if recent_damage and speed >= 0.45:
        return True
    return speed >= 0.55 and speed - previous_speed >= 0.18


def classify_intent(tick: Dict[str, Any], previous_tick: Optional[Dict[str, Any]], recent_impulse: bool) -> str:
    if recent_impulse:
        return "RECOVER_KNOCKBACK"
    nearest = tick.get("nearestMonsterDistance")
    target_distance = tick.get("targetDistance")
    target_bearing = tick.get("targetBearing")
    movement_bearing = tick.get("movementBearing")
    yaw = tick.get("yaw")
    forward = float(tick.get("inputs", {}).get("forward") or 0.0)
    strafe = float(tick.get("inputs", {}).get("strafe") or 0.0)

    if isinstance(nearest, (int, float)) and nearest <= 5.0:
        if abs(strafe) > 0.1:
            return "AVOID_MOB"
        if target_bearing is not None and movement_bearing is not None:
            if angular_delta(float(movement_bearing), float(target_bearing)) > 25.0:
                return "AVOID_MOB"

    if yaw is not None and target_bearing is not None and target_distance is not None:
        if angular_delta(float(yaw), float(target_bearing)) > 25.0:
            return "CORRECT_HEADING"

    if forward > 0.01 or abs(strafe) > 0.01 or float(tick.get("displacement") or 0.0) > 0.01:
        return "CONTINUE_PAD_ROUTE"

    if previous_tick and isinstance(target_distance, (int, float)):
        previous_distance = previous_tick.get("targetDistance")
        if isinstance(previous_distance, (int, float)) and target_distance > previous_distance + 0.15:
            return "REPOSITION"
    return "UNKNOWN"


def quantile(values: Sequence[float], fraction: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lo, hi = math.floor(index), math.ceil(index)
    if lo == hi:
        return ordered[lo]
    weight = index - lo
    return ordered[lo] * (1.0 - weight) + ordered[hi] * weight


def write_jsonl(path: Path, records: Iterable[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, separators=(",", ":"), sort_keys=True))
            handle.write("\n")


def classify_death(events: Sequence[Dict[str, Any]], end_reason: Optional[str]) -> Optional[str]:
    reason = (end_reason or "").lower()
    recent_knockback = any(e.get("type") == "KNOCKBACK_CANDIDATE" for e in events[-60:])
    if "fell off" in reason:
        return "MOB_KNOCKBACK" if recent_knockback else "FALL_OFF"
    if "weren't on the safe pad" in reason or "wasn't on the safe pad" in reason:
        return "SAFE_PAD_TIMEOUT"
    if "player_dead" in reason:
        return "MOB_KNOCKBACK" if recent_knockback else "PLAYER_DEAD"
    if "completed" in reason:
        return "COMPLETED"
    return None


def death_chain(events: Sequence[Dict[str, Any]]) -> List[str]:
    interesting = {
        "MOB_ENTERED_THREAT_RANGE", "DAMAGE", "KNOCKBACK_CANDIDATE",
        "AIRBORNE_START", "AIRBORNE_END", "FALL_RISK", "TERMINAL_CHAT", "DEATH",
    }
    result: List[str] = []
    for event in events[-80:]:
        event_type = event.get("type")
        if event_type not in interesting:
            continue
        if not result or result[-1] != event_type:
            result.append(str(event_type))
    return result[-12:]


def normalize_run(run_dir: Path, output_dir: Path) -> Dict[str, Any]:
    errors: List[Dict[str, Any]] = []
    manifest, manifest_files, end_reason = load_manifest(run_dir)
    streams = load_streams(run_dir, manifest_files, errors)

    world = by_tick(streams["world"])
    movement = by_tick(streams["movement"])
    inputs = by_tick(streams["input"])
    navigation = by_tick(streams["navigation"])
    monsters = by_tick(streams["monsters"])
    raw_events = [
        e for e in streams["events"]
        if e.get("event") not in ("header", "footer") and e.get("recordType") not in ("header", "footer")
    ]
    events_by_tick: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for event in raw_events:
        if isinstance(event.get("tick"), (int, float)):
            events_by_tick[int(event["tick"])].append(event)

    ticks = sorted(set(world) | set(movement) | set(inputs) | set(navigation) | set(monsters))
    reconstructed = reconstruct_stages(ticks, world, navigation)
    metadata = infer_metadata(manifest, world, end_reason)

    normalized_ticks: List[Dict[str, Any]] = []
    derived_events: List[Dict[str, Any]] = []
    previous: Optional[Dict[str, Any]] = None
    jump_previous = False
    last_pad: Optional[Tuple[int, int]] = None
    damage_ticks: deque[int] = deque(maxlen=10)
    external_ticks: set[int] = set()

    for tick in ticks:
        m, i, w, n, mon = movement.get(tick, {}), inputs.get(tick, {}), world.get(tick, {}), navigation.get(tick, {}), monsters.get(tick, {})
        scoreboard = scoreboard_info(w.get("scoreboardLines", [])) if isinstance(w.get("scoreboardLines"), list) else {}
        stage = scoreboard.get("stage") or reconstructed.get(tick) or w.get("stage")

        px, py, pz = float(m.get("x", 0.0)), float(m.get("y", 0.0)), float(m.get("z", 0.0))
        nearest, nearby = nearest_monster(mon, px, py, pz)
        health_delta = float(w.get("healthDelta", m.get("healthDelta", 0.0)) or 0.0)
        if health_delta < -1e-6:
            damage_ticks.append(tick)
        recent_damage = bool(damage_ticks) and tick - damage_ticks[-1] <= 2
        external = derive_external_impulse(m, previous, recent_damage)
        if external:
            external_ticks.add(tick)

        target_pad = n.get("activePad") if isinstance(n.get("activePad"), dict) else w.get("activePad")
        row = {
            "tick": tick,
            "stage": int(stage) if isinstance(stage, (int, float)) else None,
            "stageSource": (
                "scoreboard" if isinstance(scoreboard.get("stage"), int)
                else "pad_reconstruction" if tick in reconstructed
                else "observer"
            ),
            "position": {"x": px, "y": py, "z": pz},
            "velocity": {
                "x": float(m.get("vx", 0.0) or 0.0),
                "y": float(m.get("vy", 0.0) or 0.0),
                "z": float(m.get("vz", 0.0) or 0.0),
            },
            "yaw": m.get("yaw"),
            "pitch": m.get("pitch"),
            "yawDelta": m.get("yawDelta"),
            "grounded": m.get("grounded"),
            "airborne": m.get("airborne"),
            "fallDistance": m.get("fallDistance"),
            "inputs": {
                "forward": float(i.get("forward", 0.0) or 0.0),
                "strafe": float(i.get("strafe", 0.0) or 0.0),
                "jump": bool(i.get("jump", False)),
                "sprint": bool(i.get("sprintKey", i.get("rawSprint", False))),
                "rawForward": bool(i.get("rawForward", False)),
                "rawBack": bool(i.get("rawBack", False)),
                "rawLeft": bool(i.get("rawLeft", False)),
                "rawRight": bool(i.get("rawRight", False)),
                "rawJump": bool(i.get("rawJump", False)),
                "rawSprint": bool(i.get("rawSprint", False)),
            },
            "displacement": float(m.get("displacement", 0.0) or 0.0),
            "horizontalSpeed": float(m.get("horizontalSpeed", 0.0) or 0.0),
            "externalImpulse": external,
            "health": w.get("health", m.get("health")),
            "healthDelta": health_delta,
            "targetPad": target_pad,
            "targetDistance": n.get("targetDistance"),
            "targetBearing": n.get("targetBearing"),
            "movementBearing": n.get("movementBearing"),
            "velocityBearing": n.get("velocityBearing"),
            "nearestMonsterDistance": nearest,
            "nearbyMonsterCount": nearby,
            "safePadSeconds": scoreboard.get("safePadSeconds", w.get("phaseTimerSeconds")),
            "mode": scoreboard.get("mode"),
            "kit": scoreboard.get("kit"),
            "abilityCharges": w.get("abilityCharges"),
            "jumpCharges": w.get("jumpCharges"),
            "recordedEvents": events_by_tick.get(tick, []),
        }
        normalized_ticks.append(row)

        pad = (target_pad.get("row"), target_pad.get("column")) if isinstance(target_pad, dict) else None
        if row["stage"] is not None and not any(e.get("type") == "STAGE_START" and e.get("stage") == row["stage"] for e in derived_events[-3:]):
            if previous is None or previous.get("stage") != row["stage"]:
                derived_events.append({"tick": tick, "stage": row["stage"], "type": "STAGE_START", "source": "derived"})
        if isinstance(pad, tuple) and len(pad) == 2 and all(isinstance(x, int) for x in pad) and pad != last_pad:
            derived_events.append({
                "tick": tick, "stage": row["stage"], "type": "PAD_TARGET_CHANGED",
                "source": "derived", "row": pad[0], "column": pad[1],
            })
            last_pad = pad
        if previous and bool(row.get("grounded")) != bool(previous.get("grounded")):
            derived_events.append({
                "tick": tick, "stage": row["stage"],
                "type": "AIRBORNE_END" if row.get("grounded") else "AIRBORNE_START",
                "source": "derived",
            })
        if external and not (previous and previous.get("externalImpulse")):
            derived_events.append({
                "tick": tick, "stage": row["stage"], "type": "KNOCKBACK_CANDIDATE",
                "source": "derived", "horizontalSpeed": row["horizontalSpeed"],
                "healthDelta": row["healthDelta"],
            })
        if health_delta < -1e-6:
            derived_events.append({
                "tick": tick, "stage": row["stage"], "type": "DAMAGE",
                "source": "derived", "amount": -health_delta,
            })
        jump = bool(row["inputs"]["jump"])
        if jump and not jump_previous:
            derived_events.append({"tick": tick, "stage": row["stage"], "type": "JUMP_PRESS", "source": "derived"})
        jump_previous = jump
        previous = row

    all_events = sorted(
        [dict(e, source="recorded", type=e.get("event", e.get("type"))) for e in raw_events] + derived_events,
        key=lambda e: (int(e.get("tick", 0)), str(e.get("type", ""))),
    )

    decisions: List[Dict[str, Any]] = []
    segment: Optional[Dict[str, Any]] = None
    previous_tick = None
    for row in normalized_ticks:
        recent_impulse = any(abs(row["tick"] - t) <= 6 for t in external_ticks)
        intent = classify_intent(row, previous_tick, recent_impulse)
        action = {
            "forward": row["inputs"]["forward"], "strafe": row["inputs"]["strafe"],
            "jump": row["inputs"]["jump"], "sprint": row["inputs"]["sprint"],
            "yawDelta": row["yawDelta"],
        }
        context = {
            "targetDistance": row["targetDistance"], "targetBearing": row["targetBearing"],
            "nearestMonsterDistance": row["nearestMonsterDistance"], "health": row["health"],
            "airborne": row["airborne"], "horizontalSpeed": row["horizontalSpeed"],
            "safePadSeconds": row["safePadSeconds"],
        }
        new_segment = (
            segment is None or segment["intent"] != intent
            or segment["stage"] != row["stage"] or segment["endTick"] + 1 != row["tick"]
        )
        if new_segment:
            if segment is not None:
                decisions.append(segment)
            segment = {
                "startTick": row["tick"], "endTick": row["tick"], "stage": row["stage"],
                "intent": intent, "sampleCount": 1,
                "contextAtStart": context, "actionAtStart": action,
            }
        else:
            segment["endTick"] = row["tick"]
            segment["sampleCount"] += 1
        previous_tick = row
    if segment is not None:
        decisions.append(segment)

    by_stage: Dict[int, List[Dict[str, Any]]] = defaultdict(list)
    for row in normalized_ticks:
        if isinstance(row["stage"], int):
            by_stage[row["stage"]].append(row)

    stages: List[Dict[str, Any]] = []
    for stage_number in sorted(by_stage):
        rows = by_stage[stage_number]
        first, last = rows[0], rows[-1]
        duration = last["tick"] - first["tick"] + 1
        actual_distance = sum(float(r["displacement"] or 0.0) for r in rows)
        direct_distance = next((float(r["targetDistance"]) for r in rows if isinstance(r.get("targetDistance"), (int, float))), None)
        stages.append({
            "stage": stage_number,
            "startTick": first["tick"],
            "endTick": last["tick"],
            "durationTicks": duration,
            "completed": stage_number < max(by_stage),
            "targetPad": first.get("targetPad"),
            "directDistance": direct_distance,
            "actualDistance": actual_distance,
            "directExcessRatio": actual_distance / max(direct_distance, 1e-9) if direct_distance else None,
            "stationaryTicks": sum(1 for r in rows if float(r["displacement"] or 0.0) < 0.01),
            "sprintTicks": sum(1 for r in rows if r["inputs"]["sprint"]),
            "jumpPresses": sum(1 for e in all_events if e.get("stage") == stage_number and e.get("type") == "JUMP_PRESS"),
            "yawRotationDegrees": sum(abs(float(r["yawDelta"] or 0.0)) for r in rows),
            "damageEvents": sum(1 for e in all_events if e.get("stage") == stage_number and e.get("type") == "DAMAGE"),
            "knockbackEvents": sum(1 for e in all_events if e.get("stage") == stage_number and e.get("type") == "KNOCKBACK_CANDIDATE"),
            "abilityUses": sum(
                1 for e in raw_events
                if e.get("event", "").startswith("ABILITY")
                and first["tick"] <= int(e.get("tick", -1)) <= last["tick"]
            ),
        })

    stage_reached = max(by_stage) if by_stage else None
    if end_reason:
        match = re.search(r"reached stage\s+(\d+)", end_reason, re.IGNORECASE)
        if match:
            stage_reached = max(stage_reached or 0, int(match.group(1)))

    anomalies = list(errors)
    scoreboard_kits = Counter()
    scoreboard_modes = Counter()
    for w in world.values():
        info = scoreboard_info(w.get("scoreboardLines", [])) if isinstance(w.get("scoreboardLines"), list) else {}
        if info.get("kit"):
            scoreboard_kits[info["kit"]] += 1
        if info.get("mode"):
            scoreboard_modes[info["mode"]] += 1
    observer_kits = Counter(clean_text(w.get("kit")).upper() for w in world.values() if w.get("kit"))
    if scoreboard_kits and observer_kits and scoreboard_kits.most_common(1)[0][0] != observer_kits.most_common(1)[0][0]:
        anomalies.append({
            "type": "KIT_CONFLICT",
            "scoreboard": scoreboard_kits.most_common(1)[0][0],
            "observer": observer_kits.most_common(1)[0][0],
        })
    for tick, w in world.items():
        info = scoreboard_info(w.get("scoreboardLines", [])) if isinstance(w.get("scoreboardLines"), list) else {}
        if isinstance(info.get("stage"), int) and isinstance(w.get("stage"), int) and info["stage"] != w["stage"]:
            anomalies.append({
                "type": "WORLD_STAGE_CONFLICT",
                "tick": tick, "scoreboard": info["stage"], "observer": w["stage"],
            })
            break
    if not streams["events"]:
        anomalies.append({"type": "MISSING_EVENT_STREAM"})
    if not normalized_ticks:
        anomalies.append({"type": "NO_TICK_DATA"})

    normal_speeds = [r["horizontalSpeed"] for r in normalized_ticks if not r["externalImpulse"]]
    durations = [s["durationTicks"] / 20.0 for s in stages if s["durationTicks"] > 0]
    start_tick = normalized_ticks[0]["tick"] if normalized_ticks else None
    end_tick = normalized_ticks[-1]["tick"] if normalized_ticks else None
    damage_count = sum(1 for e in all_events if e.get("type") == "DAMAGE")
    knockback_count = sum(1 for e in all_events if e.get("type") == "KNOCKBACK_CANDIDATE")

    summary = {
        "schemaVersion": 1,
        "run": {
            "startTick": start_tick, "endTick": end_tick,
            "durationSeconds": (end_tick - start_tick) / 20.0 if start_tick is not None and end_tick is not None else None,
            "stageReached": stage_reached, "terminalReason": end_reason,
        },
        "conditions": metadata,
        "quality": "accepted" if normalized_ticks and not any(a["type"] == "MALFORMED_JSON" for a in anomalies) else "partial",
        "metrics": {
            "meanStageTimeSeconds": statistics.mean(durations) if durations else None,
            "medianStageTimeSeconds": statistics.median(durations) if durations else None,
            "directExcessRatioMean": statistics.mean([s["directExcessRatio"] for s in stages if s["directExcessRatio"] is not None])
            if any(s["directExcessRatio"] is not None for s in stages) else None,
            "stationaryFraction": sum(1 for r in normalized_ticks if float(r["displacement"] or 0.0) < 0.01) / len(normalized_ticks) if normalized_ticks else None,
            "sprintFraction": sum(1 for r in normalized_ticks if r["inputs"]["sprint"]) / len(normalized_ticks) if normalized_ticks else None,
            "jumpPresses": sum(1 for e in all_events if e.get("type") == "JUMP_PRESS"),
            "yawRotationDegrees": sum(abs(float(r["yawDelta"] or 0.0)) for r in normalized_ticks),
            "damageEvents": damage_count,
            "damagePerStage": damage_count / max(stage_reached or 1, 1),
            "knockbackCandidates": knockback_count,
            "normalSpeedP50": quantile(normal_speeds, 0.50),
            "normalSpeedP95": quantile(normal_speeds, 0.95),
            "normalSpeedMax": max(normal_speeds) if normal_speeds else None,
        },
        "death": {"cause": classify_death(all_events, end_reason), "causalChain": death_chain(all_events)},
    }

    write_jsonl(output_dir / "normalized" / "ticks.jsonl", normalized_ticks)
    write_jsonl(output_dir / "normalized" / "stages.jsonl", stages)
    write_jsonl(output_dir / "normalized" / "decisions.jsonl", decisions)
    write_jsonl(output_dir / "normalized" / "events.jsonl", all_events)
    (output_dir / "analysis").mkdir(parents=True, exist_ok=True)
    (output_dir / "analysis" / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output_dir / "analysis" / "anomalies.json").write_text(json.dumps(anomalies, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {"summary": summary, "anomalies": anomalies}


def load_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def find_sim_metrics(payload: Dict[str, Any]) -> Dict[str, Any]:
    if isinstance(payload.get("simulator"), dict):
        return payload["simulator"]
    if isinstance(payload.get("metrics"), dict):
        return payload["metrics"]
    if isinstance(payload.get("summary"), dict):
        summary = payload["summary"]
        if isinstance(summary.get("simulator"), dict):
            return summary["simulator"]
        if isinstance(summary.get("metrics"), dict):
            return summary["metrics"]
        return summary
    return payload


def compare_with_simulator(human_summary: Dict[str, Any], sim_payload: Dict[str, Any]) -> Dict[str, Any]:
    human = dict(human_summary.get("metrics", {}))
    human["stageReached"] = human_summary.get("run", {}).get("stageReached")
    sim = find_sim_metrics(sim_payload)
    aliases = {
        "stageReached": ("stageReached", "stage_reached"),
        "meanStageTimeSeconds": ("meanStageTimeSeconds", "mean_stage_time_seconds", "meanStageTime"),
        "directExcessRatioMean": ("directExcessRatioMean", "routeExcessRatio"),
        "stationaryFraction": ("stationaryFraction",),
        "sprintFraction": ("sprintFraction",),
        "jumpPresses": ("jumpPresses",),
        "yawRotationDegrees": ("yawRotationDegrees",),
        "damagePerStage": ("damagePerStage",),
        "knockbackRecoveryRate": ("knockbackRecoveryRate",),
        "normalSpeedP50": ("normalSpeedP50",),
        "normalSpeedP95": ("normalSpeedP95",),
    }
    direction = {
        "stageReached": "higher",
        "meanStageTimeSeconds": "lower",
        "directExcessRatioMean": "lower",
        "stationaryFraction": "lower",
        "damagePerStage": "lower",
        "knockbackRecoveryRate": "higher",
    }
    gaps = []
    for metric, names in aliases.items():
        hv = next((human.get(name) for name in names if human.get(name) is not None), None)
        sv = next((sim.get(name) for name in names if sim.get(name) is not None), None)
        if hv is None or sv is None:
            continue
        gap = float(sv) - float(hv)
        relation = "DIFFERENCE"
        if direction.get(metric) == "higher":
            relation = "SIM_AHEAD" if gap > 0 else "SIM_BEHIND" if gap < 0 else "MATCH"
        elif direction.get(metric) == "lower":
            relation = "SIM_LOWER" if gap < 0 else "SIM_HIGHER" if gap > 0 else "MATCH"
        gaps.append({"metric": metric, "human": hv, "simulator": sv, "simulatorMinusHuman": gap, "relationship": relation})
    return {
        "schemaVersion": 1,
        "conditions": human_summary.get("conditions", {}),
        "human": human,
        "simulator": sim,
        "gaps": gaps,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--sim-summary", type=Path)
    args = parser.parse_args()

    run_dir = args.run_dir.resolve()
    output_dir = args.output_dir.resolve() if args.output_dir else run_dir
    result = normalize_run(run_dir, output_dir)

    if args.sim_summary:
        comparison = compare_with_simulator(result["summary"], load_json(args.sim_summary))
        (output_dir / "analysis" / "simulator-comparison.json").write_text(
            json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )

    print(json.dumps({
        "stageReached": result["summary"]["run"]["stageReached"],
        "mode": result["summary"]["conditions"].get("mode"),
        "kit": result["summary"]["conditions"].get("kit"),
        "pattern": result["summary"]["conditions"].get("pattern"),
        "quality": result["summary"]["quality"],
        "anomalies": len(result["anomalies"]),
        "summary": str(output_dir / "analysis" / "summary.json"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
