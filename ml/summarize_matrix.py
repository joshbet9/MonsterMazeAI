#!/usr/bin/env python3
"""Aggregate fixed-holdout matrix results across one or more ML gate reports."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


def aggregate(cases):
    grouped = defaultdict(list)
    for case in cases:
        key = (case["mode"], int(case["pattern"]), case["kit"])
        grouped[key].append(case)

    rows = []
    for (mode, pattern, kit), values in sorted(grouped.items()):
        baseline = [int(v["baseline"]) for v in values]
        candidate = [int(v["candidate"]) for v in values]
        delta = [int(v["delta"]) for v in values]
        rows.append({
            "mode": mode,
            "pattern": pattern,
            "kit": kit,
            "samples": len(values),
            "baseline_avg": sum(baseline) / len(baseline),
            "candidate_avg": sum(candidate) / len(candidate),
            "delta_avg": sum(delta) / len(delta),
            "baseline_peak": max(baseline),
            "candidate_peak": max(candidate),
            "delta_min": min(delta),
            "delta_max": max(delta),
            "improved": sum(1 for d in delta if d > 0),
            "worsened": sum(1 for d in delta if d < 0),
            "same": sum(1 for d in delta if d == 0),
        })
    return rows


def bucket_summary(cases, field):
    buckets = defaultdict(list)
    for case in cases:
        if field == "pattern":
            key = int(case["pattern"])
        else:
            key = case[field]
        buckets[key].append(case)

    output = []
    for key, values in sorted(buckets.items(), key=lambda item: str(item[0])):
        baseline = [int(v["baseline"]) for v in values]
        candidate = [int(v["candidate"]) for v in values]
        delta = [int(v["delta"]) for v in values]
        output.append({
            field: key,
            "cases": len(values),
            "baseline_avg": sum(baseline) / len(baseline),
            "candidate_avg": sum(candidate) / len(candidate),
            "delta_avg": sum(delta) / len(delta),
            "baseline_peak": max(baseline),
            "candidate_peak": max(candidate),
            "improved": sum(1 for d in delta if d > 0),
            "worsened": sum(1 for d in delta if d < 0),
            "same": sum(1 for d in delta if d == 0),
        })
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", nargs="+", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    reports = [json.loads(Path(path).read_text(encoding="utf-8")) for path in args.inputs]
    cases = []
    passed = True
    for report in reports:
        passed = passed and bool(report.get("passed"))
        cases.extend(report.get("cases", []))

    baseline = [int(case["baseline"]) for case in cases]
    candidate = [int(case["candidate"]) for case in cases]
    delta = [int(case["delta"]) for case in cases]

    payload = {
        "version": 1,
        "holdout_reports": len(reports),
        "expected_full_matrix_cases": 90,
        "observed_cases": len(cases),
        "passed": passed,
        "overall": {
            "baseline_avg": sum(baseline) / len(baseline) if baseline else None,
            "candidate_avg": sum(candidate) / len(candidate) if candidate else None,
            "delta_avg": sum(delta) / len(delta) if delta else None,
            "baseline_peak": max(baseline) if baseline else None,
            "candidate_peak": max(candidate) if candidate else None,
            "improved": sum(1 for d in delta if d > 0),
            "worsened": sum(1 for d in delta if d < 0),
            "same": sum(1 for d in delta if d == 0),
        },
        "by_mode": bucket_summary(cases, "mode"),
        "by_pattern": bucket_summary(cases, "pattern"),
        "by_kit": bucket_summary(cases, "kit"),
        "matrix": aggregate(cases),
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
