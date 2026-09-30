# Human-run analysis

The 1.8.9 recorder writes synchronized JSONL streams. The analyzer converts those streams into a common state/event/decision representation and can compare the resulting metrics with simulator output.

## Run locally

From the repository root:

    python tools/human_run_analyzer.py path/to/human-speed-run-directory

With a simulator summary:

    python tools/human_run_analyzer.py path/to/human-speed-run-directory --sim-summary path/to/simulator-summary.json

The input directory may be the recorder's flat output directory or the canonical run directory described in docs/human-run-analysis-format.md.

## Outputs

    normalized/ticks.jsonl
    normalized/stages.jsonl
    normalized/decisions.jsonl
    normalized/events.jsonl
    analysis/summary.json
    analysis/anomalies.json
    analysis/simulator-comparison.json

Raw recorder files are not modified.

The analyzer uses raw scoreboard values to recover mode, kit, stage and Safe Pad timer when observer fields conflict. Maze pattern is converted from the simulator's zero-based mazePattern to the human-facing one-based pattern number.

Derived movement, knockback, and intent fields are explicitly marked as derived.
