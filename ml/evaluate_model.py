#!/usr/bin/env python3
"""Gate a learned model against a fixed deterministic holdout."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


LINE = re.compile(
    r"(?P<mode>SPEED|MODERN)_FULL_RUN\s+"
    r"pattern=(?P<pattern>\d+)\s+kit=(?P<kit>\S+)\s+maxStage=(?P<stage>\d+)"
)


def parse(path: Path) -> dict[tuple[str, int, str], int]:
    out: dict[tuple[str, int, str], int] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = LINE.search(line)
        if match:
            out[
                (match.group("mode"), int(match.group("pattern")), match.group("kit"))
            ] = int(match.group("stage"))
    return out


def evaluate(
    baseline: dict[tuple[str, int, str], int],
    candidate: dict[tuple[str, int, str], int],
) -> dict:
    keys = sorted(set(baseline) | set(candidate))
    common = [key for key in keys if key in baseline and key in candidate]
    missing = len(keys) - len(common)
    deltas = {key: candidate[key] - baseline[key] for key in common}

    baseline_values = [baseline[key] for key in common]
    candidate_values = [candidate[key] for key in common]
    baseline_avg = (
        sum(baseline_values) / len(baseline_values) if baseline_values else float("nan")
    )
    candidate_avg = (
        sum(candidate_values) / len(candidate_values) if candidate_values else float("nan")
    )
    baseline_peak = max(baseline_values) if baseline_values else None
    candidate_peak = max(candidate_values) if candidate_values else None

    worsened = sum(1 for delta in deltas.values() if delta < 0)
    improved = sum(1 for delta in deltas.values() if delta > 0)

    passed = (
        missing == 0
        and worsened == 0
        and candidate_avg >= baseline_avg
        and candidate_peak is not None
        and candidate_peak >= baseline_peak
    )

    cases = []
    for mode, pattern, kit in common:
        cases.append(
            {
                "mode": mode,
                "pattern": pattern,
                "kit": kit,
                "baseline": baseline[(mode, pattern, kit)],
                "candidate": candidate[(mode, pattern, kit)],
                "delta": deltas[(mode, pattern, kit)],
            }
        )

    return {
        "passed": passed,
        "baseline_cases": len(baseline),
        "candidate_cases": len(candidate),
        "common_cases": len(common),
        "missing": missing,
        "improved": improved,
        "worsened": worsened,
        "same": len(deltas) - improved - worsened,
        "baseline_avg": baseline_avg,
        "candidate_avg": candidate_avg,
        "baseline_peak": baseline_peak,
        "candidate_peak": candidate_peak,
        "min_delta": min(deltas.values()) if deltas else None,
        "max_delta": max(deltas.values()) if deltas else None,
        "cases": cases,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--json-output")
    args = parser.parse_args()

    metrics = evaluate(parse(Path(args.baseline)), parse(Path(args.candidate)))
    print(json.dumps(metrics, indent=2))

    if args.json_output:
        output = Path(args.json_output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")

    return 0 if metrics["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
