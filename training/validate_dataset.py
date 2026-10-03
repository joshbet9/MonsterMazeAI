#!/usr/bin/env python3
"""Validate a derived Monster Maze CPU-policy dataset."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path


FEATURE_COUNT = 96
PROFILE_COUNT = 17
KIT_ORDINAL = {
    "JUMPER": 0,
    "SLOWBALLER": 1,
    "BODY_BUILDER": 2,
    "REPULSOR": 3,
    "MAVERICK": 4,
}


def finite_number(value):
    return isinstance(value, (int, float)) and math.isfinite(float(value))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "dataset",
        type=Path,
        help="JSONL produced by training/human_dataset.py",
    )
    args = parser.parse_args()

    if not args.dataset.exists():
        raise SystemExit(f"Dataset not found: {args.dataset}")

    rows = 0
    bad = 0
    runs = Counter()
    kits = Counter()
    modes = Counter()
    stages = Counter()
    terminal = 0
    completed = 0
    ability = 0
    jump = 0
    nonzero_strafe = 0
    nonzero_yaw = 0
    topology_missing = 0
    competitor_nonzero = 0
    kit_feature_mismatch = 0

    with args.dataset.open("r", encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, 1):
            if not raw.strip():
                continue

            try:
                row = json.loads(raw)
            except json.JSONDecodeError:
                bad += 1
                print(f"BAD_JSON line={line_number}")
                continue

            rows += 1
            problems = []

            if row.get("schemaVersion") != 2:
                problems.append("schemaVersion")
            observation = row.get("observation")
            profile = row.get("profile")
            action = row.get("action")

            if not isinstance(observation, list) or len(observation) != FEATURE_COUNT:
                problems.append("observation_length")
            else:
                if not all(finite_number(x) for x in observation):
                    problems.append("observation_nonfinite")
                if len(observation) >= 96 and any(abs(float(x)) > 1.000001 for x in observation):
                    problems.append("observation_out_of_range")
                if not any(abs(float(x)) > 0.0 for x in observation[32:41]):
                    topology_missing += 1

            if not isinstance(profile, list) or len(profile) != PROFILE_COUNT:
                problems.append("profile_length")
            elif not all(finite_number(x) and 0.0 <= float(x) <= 1.0 for x in profile):
                problems.append("profile_range")

            if not isinstance(action, dict):
                problems.append("action_missing")
            else:
                for key in ("forward", "strafe", "yawDelta"):
                    if key not in action or not finite_number(action[key]):
                        problems.append(f"action_{key}")
                if "forward" in action and abs(float(action["forward"])) > 1.000001:
                    problems.append("forward_range")
                if "strafe" in action and abs(float(action["strafe"])) > 1.000001:
                    problems.append("strafe_range")
                if "yawDelta" in action and abs(float(action["yawDelta"])) > 30.0001:
                    problems.append("yaw_range")

                if bool(action.get("jump")):
                    jump += 1
                if bool(action.get("useAbility")):
                    ability += 1
                if abs(float(action.get("strafe", 0.0))) > 0.01:
                    nonzero_strafe += 1
                if abs(float(action.get("yawDelta", 0.0))) > 0.01:
                    nonzero_yaw += 1

            run = str(row.get("run", "UNKNOWN"))
            kit = str(row.get("kit", "UNKNOWN")).upper()
            mode = str(row.get("mode", "UNKNOWN")).upper()

            if isinstance(observation, list) and len(observation) >= 24 and kit in KIT_ORDINAL:
                expected_kit = KIT_ORDINAL[kit] / 4.0
                if abs(float(observation[23]) - expected_kit) > 1e-7:
                    problems.append("kit_feature_mismatch")
                    kit_feature_mismatch += 1
            stage = int(row.get("stage", 0) or 0)

            runs[run] += 1
            kits[kit] += 1
            modes[mode] += 1
            stages[stage] += 1

            if bool(row.get("terminal")):
                terminal += 1
            if bool(row.get("completed")):
                completed += 1

            if isinstance(observation, list) and len(observation) >= 96:
                if any(abs(float(x)) > 1e-7 for x in observation[76:96]):
                    competitor_nonzero += 1

            if problems:
                bad += 1
                if bad <= 25:
                    print(
                        f"BAD_ROW line={line_number} run={run} "
                        f"tick={row.get('tick')} problems={','.join(problems)}"
                    )

    print("========== HUMAN DATASET VALIDATION ==========")
    print(f"rows={rows}")
    print(f"badRows={bad}")
    print(f"runs={len(runs)}")
    print(f"kits={dict(sorted(kits.items()))}")
    print(f"modes={dict(sorted(modes.items()))}")
    print(f"terminalRows={terminal}")
    print(f"completedRows={completed}")
    print(f"jumpRows={jump}")
    print(f"abilityRows={ability}")
    print(f"nonzeroStrafeRows={nonzero_strafe}")
    print(f"nonzeroYawRows={nonzero_yaw}")
    print(f"topologyMissingRows={topology_missing}")
    print(f"competitorNonzeroRows={competitor_nonzero}")
    print(f"kitFeatureMismatchRows={kit_feature_mismatch}")
    print("stages=" + json.dumps(dict(sorted(stages.items())), sort_keys=True))

    if rows == 0 or bad:
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
