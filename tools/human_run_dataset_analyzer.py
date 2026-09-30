"""Aggregate the recorded Minecraft Monster Maze human-run dataset.

Usage:
  python tools/human_run_dataset_analyzer.py human-runs --output-dir /tmp/human-runs-analysis

Raw recordings are never modified. Each run is normalized independently from
its explicit manifest, then aggregated into descriptive metrics and decision
distributions. No single run is treated as an optimal policy.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence


NUMERIC_METRICS = (
    "meanStageTimeSeconds",
    "medianStageTimeSeconds",
    "directExcessRatioMean",
    "stationaryFraction",
    "forwardFraction",
    "reverseFraction",
    "strafeFraction",
    "sprintFraction",
    "jumpPressRatePer100Ticks",
    "yawRateP50",
    "yawRateP95",
    "largeTurnFraction",
    "over30TurnFraction",
    "targetHeadingErrorP50",
    "targetHeadingErrorP95",
    "headingCorrectionFraction",
    "continuousForwardRunP50Ticks",
    "continuousForwardRunP95Ticks",
    "damagePerStage",
    "knockbackCandidates",
    "normalSpeedP50",
    "normalSpeedP95",
    "maxHorizontalSpeed",
    "maxTickDisplacement",
)


def load_json(path: Path) -> Dict[str, Any]:
    # Recorder manifests contain a manifest record followed by a footer record.
    # The dataset analyzer only needs the manifest object here.
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            value = json.loads(raw)
            if isinstance(value, dict) and value.get("recordType") == "manifest":
                return value
    return {}


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


def stats(values: Iterable[Any]) -> Optional[Dict[str, Any]]:
    clean: List[float] = []
    for value in values:
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            clean.append(float(value))
    if not clean:
        return None
    return {
        "n": len(clean),
        "mean": statistics.mean(clean),
        "median": statistics.median(clean),
        "p25": quantile(clean, 0.25),
        "p75": quantile(clean, 0.75),
        "p95": quantile(clean, 0.95),
    }


def read_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.exists():
        return []
    result: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            value = json.loads(raw)
            if isinstance(value, dict):
                result.append(value)
    return result


def discover_manifests(dataset_dir: Path) -> List[Path]:
    manifests = []
    for path in dataset_dir.rglob("*-manifest.json"):
        if path.is_file():
            manifests.append(path)
    for path in dataset_dir.rglob("manifest.json"):
        if path.is_file():
            manifests.append(path)
    return sorted(set(manifests))


def analyze_dataset(dataset_dir: Path, output_dir: Path) -> Dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    run_root = output_dir / "runs"
    run_root.mkdir(parents=True, exist_ok=True)

    from human_run_analyzer import normalize_run

    manifests = discover_manifests(dataset_dir)
    run_records: List[Dict[str, Any]] = []
    quality_counts: Counter[str] = Counter()
    anomaly_counts: Counter[str] = Counter()
    metrics_by_kit: Dict[str, Dict[str, List[float]]] = defaultdict(lambda: defaultdict(list))
    metrics_overall: Dict[str, List[float]] = defaultdict(list)
    intent_counts: Dict[str, Counter[str]] = defaultdict(Counter)

    for manifest_path in manifests:
        manifest = load_json(manifest_path)
        run_id = str(manifest.get("runId") or manifest_path.stem.replace("-manifest", ""))
        run_output = run_root / run_id
        result = normalize_run(
            dataset_dir,
            run_output,
            manifest_path=manifest_path,
        )
        summary = result["summary"]
        anomalies = result["anomalies"]
        quality = summary.get("quality", "unknown")
        conditions = summary.get("conditions", {})
        kit = conditions.get("kit") or "UNKNOWN"

        quality_counts[quality] += 1
        for anomaly in anomalies:
            anomaly_type = anomaly.get("type")
            if anomaly_type:
                anomaly_counts[str(anomaly_type)] += 1

        metrics = summary.get("metrics", {})
        for metric in NUMERIC_METRICS:
            value = metrics.get(metric)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                metrics_by_kit[kit][metric].append(float(value))
                metrics_overall[metric].append(float(value))

        decisions = read_jsonl(run_output / "normalized" / "decisions.jsonl")
        for decision in decisions:
            intent = decision.get("intent")
            if intent:
                intent_counts[kit][str(intent)] += int(decision.get("sampleCount", 1) or 1)

        run_records.append({
            "runId": run_id,
            "manifest": str(manifest_path.relative_to(dataset_dir)),
            "conditions": conditions,
            "run": summary.get("run", {}),
            "quality": quality,
            "metrics": metrics,
            "death": summary.get("death", {}),
            "anomalyTypes": sorted({a.get("type") for a in anomalies if a.get("type")}),
        })

    summary = {
        "schemaVersion": 1,
        "dataset": {
            "runCount": len(run_records),
            "qualityCounts": dict(quality_counts),
            "anomalyCounts": dict(anomaly_counts),
            "kits": sorted({r["conditions"].get("kit") for r in run_records if r["conditions"].get("kit")}),
            "modes": sorted({r["conditions"].get("mode") for r in run_records if r["conditions"].get("mode")}),
            "patterns": sorted({r["conditions"].get("pattern") for r in run_records if r["conditions"].get("pattern") is not None}),
        },
        "overallMetrics": {
            metric: stats(values) for metric, values in sorted(metrics_overall.items())
        },
        "metricsByKit": {
            kit: {metric: stats(values) for metric, values in sorted(metric_values.items())}
            for kit, metric_values in sorted(metrics_by_kit.items())
        },
        "decisionSamplesByKit": {
            kit: dict(sorted(counter.items()))
            for kit, counter in sorted(intent_counts.items())
        },
        "runs": run_records,
    }

    (output_dir / "dataset-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (output_dir / "runs.jsonl").write_text(
        "".join(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n" for record in run_records),
        encoding="utf-8",
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset_dir", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    summary = analyze_dataset(args.dataset_dir.resolve(), args.output_dir.resolve())
    print(json.dumps({
        "runCount": summary["dataset"]["runCount"],
        "kits": summary["dataset"]["kits"],
        "qualityCounts": summary["dataset"]["qualityCounts"],
        "anomalyTypes": sorted(summary["dataset"]["anomalyCounts"]),
        "summary": str((args.output_dir.resolve() / "dataset-summary.json")),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
