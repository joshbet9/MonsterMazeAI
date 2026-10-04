# Development matrix gate

Normal PR commits use the fast bottleneck gate only. The expensive 30-cell
natural-termination matrix and ML calibration are intentionally not run for
every controller change.

Promote a candidate to the full matrix by creating or updating:

\`.github/matrix-promotion.txt\`

That file is only a CI trigger. The resulting PR workflows run the full
3-pattern × 5-kit Speed + Modern natural-end matrix and the calibration job.

A candidate should be promoted after the fast gate passes and the change has
a clear mechanistic reason to improve routing, movement continuity, threat
handling, or source-faithful ability use.
