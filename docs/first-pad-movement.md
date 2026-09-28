# First-pad movement-only branch

This branch isolates the highest-priority milestone:

`spawn -> fastest viable physical route -> first active 5x5 Safe Pad`

It deliberately does not run monster simulation, tactical search, ability strategy,
risk scoring, competitor models, or background strategic planning.

## Cross-check against MonsterMaze source

The implementation was checked against the 1.8 source in
`joshbet9/MonsterMaze`:

- `MazeGenerator` uses a 99x99 layout and maps layout rows to world X and
  columns to world Z.
- Layout values 1/2/5/6 are monster waypoints, while 3/4/5/6 are also
  physical centre-safe-zone floor.
- `GameManager.spawnSafePad()` calls `MazeGenerator.disablePadArea()`.
- `disablePadArea()` covers exactly a 5x5 area, offsets -2 through +2 in both
  axes.
- Safe Pad construction creates a physical surface for the whole 5x5 area.
- 1.8 `KitManager.tickJumpLock()` gives non-Jumper players the -10 jump lock
  but explicitly preserves the original jump-spam "speeding" behaviour.
- Only Jumper has real charged jumps; the source `jumpEvent()` consumes a
  charge no more often than every 750 ms and does not turn the charge into a
  double-jump.
- Source 1.8 mode documentation states that Original keeps the original
  "speeding" movement behaviour.
- Source monster bumping is intentionally absent from this branch: the branch
  is proving the physical motor before tactical/contact logic is restored.

## Motor policy

1. Use the shortest physical route to any cell in the active 5x5 pad.
2. Hold forward at full input.
3. Hold sprint while moving.
4. Keep strafe at zero during normal movement.
5. Turn using bounded 12-degree cursor/yaw pulses.
6. Begin a 90-degree turn shortly before a route corner instead of stopping
   and turning in place.
7. Non-Jumper: hold jump input continuously to exercise the source 1.8
   jump-spam speeding mechanic.
8. Jumper: hold jump input only while charged jumps remain.
9. Never deliberately use an ability in this branch.
10. Never drive toward an air cell. The route is validated against the physical
    floor and the live motor has a final route-forward floor guard.
11. Every adapter command is a one-client-tick intent. Delayed decisions are
    rejected instead of holding stale W/jump/sprint input.

## Why this is intentionally separate

The full strategic controller can eventually be restored above this motor.
The first-pad branch is a controlled experiment: if the AI cannot reliably
reach the first pad with only route topology + mechanically valid high-speed
movement, adding monster prediction or ability strategy only makes diagnosis
harder.

The intended restoration order is:

`first-pad motor -> stable routing -> abilities -> monster observation ->
contact physics -> tactical decisions -> risk-aware routing -> full AI`
