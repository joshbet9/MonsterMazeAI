# Matrix v16 — dynamic monster threat lookahead

## Hypothesis

The source-faithful planner already filters live monsters to a 20-block interaction sphere, but the asynchronous-route application guard historically inspected only the first eight route segments when deciding whether an incoming monster justified changing the current heading.

This experiment keeps the validated v14 production behaviour as the default (8 segments) and evaluates 8, 12, 16, and 20 segment lookahead values against the complete 15 Speed + 15 Modern natural-termination matrix.

## Selection

Candidates are compared cell-for-cell against `docs/calibration/results-matrix-v14.json`. The primary evidence is total stage sum, followed by number of improved/worsened cells, minimum stage, and per-mode means. No candidate is promoted automatically.

