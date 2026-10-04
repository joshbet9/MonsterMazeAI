#!/usr/bin/env python3
"""Compare two Monster Maze full-run diagnostic logs."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

LINE = re.compile(
    r"(?P<mode>SPEED|MODERN)_FULL_RUN\s+"
    r"pattern=(?P<pattern>\d+)\s+kit=(?P<kit>\S+)\s+maxStage=(?P<stage>\d+)"
)

def parse(path: Path) -> dict[tuple[str, int, str], int]:
    result = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE.search(line)
        if m:
            result[(m.group("mode"), int(m.group("pattern")), m.group("kit"))] = int(m.group("stage"))
    return result

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--ml", required=True)
    args = parser.parse_args()

    baseline = parse(Path(args.baseline))
    ml = parse(Path(args.ml))
    keys = sorted(set(baseline) | set(ml))

    if not keys:
        raise SystemExit("No full-run result lines found")

    improved = worsened = equal = missing = 0
    for key in keys:
        base = baseline.get(key)
        learned = ml.get(key)
        if base is None or learned is None:
            missing += 1
            print(f"{key[0]:6} pattern={key[1]} kit={key[2]:12} baseline={base} ml={learned} status=MISSING")
            continue
        delta = learned - base
        if delta > 0:
            improved += 1
            status = "IMPROVED"
        elif delta < 0:
            worsened += 1
            status = "WORSED"
        else:
            equal += 1
            status = "SAME"
        print(f"{key[0]:6} pattern={key[1]} kit={key[2]:12} baseline={base:3d} ml={learned:3d} delta={delta:+3d} {status}")

    base_values = [baseline[k] for k in keys if k in baseline]
    ml_values = [ml[k] for k in keys if k in ml]
    print()
    print(f"baseline_cases={len(base_values)} baseline_avg={sum(base_values)/len(base_values):.3f} baseline_peak={max(base_values) if base_values else 'NA'}")
    print(f"ml_cases={len(ml_values)} ml_avg={sum(ml_values)/len(ml_values):.3f} ml_peak={max(ml_values) if ml_values else 'NA'}")
    print(f"improved={improved} worsened={worsened} same={equal} missing={missing}")

if __name__ == "__main__":
    main()
