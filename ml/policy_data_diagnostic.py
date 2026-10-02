#!/usr/bin/env python3
"""Diagnose counterfactual policy-data signal without running the simulator."""
from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict, Counter
from pathlib import Path

import numpy as np

BASE_STATE_FEATURES = 38
ACTION_FEATURE_NAMES = [
    "forward", "strafe", "jump", "sprint", "yaw_delta", "ability"
]


def load(path):
    rows = []
    with path.open("r", encoding="utf-8-sig") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def group_rows(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(str(row["episode"]), int(row.get("t", 0)))].append(row)
    return list(groups.values())


def family(row):
    return str(row["episode"]).split("|seedOffset=", 1)[0]


def stats(values):
    if not values:
        return (0.0, 0.0, 0.0, 0.0, 0.0)
    a = np.asarray(values, dtype=np.float64)
    return (
        float(np.mean(a)),
        float(np.median(a)),
        float(np.min(a)),
        float(np.max(a)),
        float(np.std(a)),
    )


def pct(x):
    return f"{100.0 * x:.1f}%"


def action_key(features):
    vals = features[38:44]
    return (
        round(float(vals[0]), 3),
        round(float(vals[1]), 3),
        int(round(float(vals[2]))),
        int(round(float(vals[3]))),
        round(float(vals[4]), 3),
        int(round(float(vals[5]))),
    )


def describe_action(key):
    return (
        f"F={key[0]:+.2f} S={key[1]:+.2f} "
        f"J={key[2]} SP={key[3]} Y={key[4]:+.2f} A={key[5]}"
    )


def rank_corr(x, y):
    if len(x) < 2:
        return 0.0
    rx = np.argsort(np.argsort(np.asarray(x)))
    ry = np.argsort(np.argsort(np.asarray(y)))
    sx = float(np.std(rx))
    sy = float(np.std(ry))
    if sx < 1e-12 or sy < 1e-12:
        return 0.0
    return float(np.corrcoef(rx, ry)[0, 1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    args = ap.parse_args()

    rows = load(Path(args.input))
    groups = [g for g in group_rows(rows) if len(g) >= 2]
    print("========== POLICY DATA DIAGNOSTIC ==========")
    print(f"rows={len(rows)} decision_points={len(groups)}")
    print(f"episodes={len({str(r['episode']) for r in rows})}")

    candidate_counts = [len(g) for g in groups]
    print(
        "candidates_avg={:.1f} min={} max={}".format(
            *stats(candidate_counts)[:1], min(candidate_counts), max(candidate_counts)
        )
    )

    # Confirm state/action decomposition.
    state_variation = []
    action_variation = []
    for g in groups:
        mat = np.asarray([r["features"] for r in g], dtype=np.float64)
        state_variation.append(float(np.max(np.ptp(mat[:, :BASE_STATE_FEATURES], axis=0))))
        action_variation.append(float(np.max(np.ptp(mat[:, BASE_STATE_FEATURES:], axis=0))))
    print(
        "state_feature_max_within_decision={:.6g} "
        "action_feature_max_within_decision={:.6g}".format(
            max(state_variation), max(action_variation)
        )
    )

    # Oracle / baseline signal.
    spreads, advantages = [], []
    baseline_best = 0
    ties = 0
    best_actions = Counter()
    for g in groups:
        targets = np.asarray([float(r["target_return"]) for r in g])
        best = float(np.max(targets))
        base = float(targets[0])
        spreads.append(float(np.max(targets) - np.min(targets)))
        advantages.append(best - base)
        baseline_best += int(np.isclose(base, best, atol=1e-9))
        ties += int(np.sum(np.isclose(targets, best, atol=1e-9)) > 1)
        for r in g:
            if math.isclose(float(r["target_return"]), best, abs_tol=1e-9):
                best_actions[action_key(r["features"])] += 1

    print("")
    print("========== ORACLE SIGNAL ==========")
    print("mean_target_spread={:.3f}".format(np.mean(spreads)))
    print("median_target_spread={:.3f}".format(np.median(spreads)))
    print("mean_oracle_advantage_over_baseline={:.3f}".format(np.mean(advantages)))
    print("baseline_oracle_top1={}".format(pct(baseline_best / len(groups))))
    print("multiway_best_ties={}".format(pct(ties / len(groups))))
    print("nonbaseline_oracle_best={}".format(pct(1.0 - baseline_best / len(groups))))

    print("")
    print("========== BEST ACTIONS ==========")
    for key, count in best_actions.most_common(12):
        print(f"{count:3d}  {describe_action(key)}")

    # Family breakdown.
    print("")
    print("========== FAMILY BREAKDOWN ==========")
    family_groups = defaultdict(list)
    for g in groups:
        family_groups[family(g[0])].append(g)

    for fam in sorted(family_groups):
        gs = family_groups[fam]
        adv = []
        base = 0
        top = []
        for g in gs:
            targets = np.asarray([float(r["target_return"]) for r in g])
            adv.append(float(np.max(targets) - targets[0]))
            base += int(np.isclose(np.max(targets), targets[0], atol=1e-9))
            top.append(float(np.max(targets)))
        print(
            f"{fam}: decisions={len(gs):2d} "
            f"baseline_best={pct(base/len(gs))} "
            f"mean_adv={np.mean(adv):.2f} "
            f"mean_best={np.mean(top):.2f}"
        )

    # Action component signal: within each decision point, correlate each
    # action feature with oracle return. This is deliberately descriptive.
    print("")
    print("========== ACTION FEATURE SIGNAL ==========")
    for i, name in enumerate(ACTION_FEATURE_NAMES):
        idx = BASE_STATE_FEATURES + i
        xs, ys = [], []
        for g in groups:
            for r in g:
                xs.append(float(r["features"][idx]))
                ys.append(float(r["target_return"]))
        print(f"{name:10s} global_rank_corr={rank_corr(xs, ys):+.3f}")

    # Within-group action feature deltas relative to baseline.
    print("")
    print("========== DELTA-TO-BASELINE SIGNAL ==========")
    for i, name in enumerate(ACTION_FEATURE_NAMES):
        deltas, outcomes = [], []
        idx = BASE_STATE_FEATURES + i
        for g in groups:
            base = g[0]
            base_target = float(base["target_return"])
            base_value = float(base["features"][idx])
            for r in g[1:]:
                deltas.append(float(r["features"][idx]) - base_value)
                outcomes.append(float(r["target_return"]) - base_target)
        print(
            f"{name:10s} delta_rank_corr={rank_corr(deltas, outcomes):+.3f}"
        )

    # State-feature variation across decision points. This identifies whether
    # there is actually enough state diversity for state-conditioned learning.
    print("")
    print("========== STATE DIVERSITY ==========")
    state_matrix = np.asarray(
        [g[0]["features"][:BASE_STATE_FEATURES] for g in groups],
        dtype=np.float64,
    )
    labels = [
        "stage", "health", "horizontal_speed", "forward_speed", "lateral_speed",
        "vertical_speed", "grounded", "phase", "ability_charges",
        "ability_active", "pad_distance", "pad_cos", "pad_sin", "old_pad_count",
        "preview_pad_distance", "floor_N", "floor_S", "floor_E", "floor_W",
        "floor_NE", "floor_NW", "floor_SE", "floor_SW", "mode_speed",
        "mode_modern", "kit_jumper", "kit_maverick", "kit_slowballer",
        "kit_repulsor", "kit_body_builder", "monster_12", "monster_20",
        "nearest_dist", "nearest_closing", "nearest_forward",
        "nearest_lateral", "max_closing", "min_ttc",
    ]
    stds = np.std(state_matrix, axis=0)
    informative = sorted(
        [(float(stds[i]), labels[i]) for i in range(len(labels))],
        reverse=True
    )
    for value, name in informative[:15]:
        print(f"{name:24s} std={value:.4f}")

    # Candidate-position diagnostic. The recorder's candidate subset may make
    # candidate index informative even though index is not an explicit feature.
    print("")
    print("========== CANDIDATE INDEX ==========")
    index_best = Counter()
    for g in groups:
        targets = [float(r["target_return"]) for r in g]
        best = max(targets)
        for i, t in enumerate(targets):
            if math.isclose(t, best, abs_tol=1e-9):
                index_best[i] += 1
    for i in range(max(candidate_counts)):
        if index_best[i]:
            print(f"candidate_index={i} oracle_best={index_best[i]} ({pct(index_best[i]/len(groups))})")

    print("")
    print("========== INTERPRETATION ==========")
    print("state_feature_max_within_decision should be ~0: candidate rows share one state.")
    print("Low candidate/action diversity means the learner cannot discover much beyond the sampled action set.")
    print("A strong delta_rank_corr for one action dimension suggests useful local action signal.")
    print("Large family-to-family differences suggest more seeds/cases are needed before judging generalisation.")
    print("========================================")


if __name__ == "__main__":
    main()
