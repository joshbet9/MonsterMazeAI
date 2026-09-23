# Milestone 2 — Simulator Playing Quality

Milestone 2 establishes the simulator and planner as a usable game-playing laboratory before Minecraft client integration.

## Completed

- Tick-level 1.8 movement simulation with sprinting, jumping, air control, gravity, friction and yaw control.
- Explicit per-tick movement actions rather than fixed repeated-action macro blocks.
- Target-directed camera steering in the planner action space.
- Maze-aware player routing that is independent of monster waypoint routing.
- Physical-time route estimation using the movement acceleration/friction model.
- Beam-search trajectory optimisation with state diversity pruning.
- Health/time trade-off: damage is a time-equivalent cost rather than an unconditional "avoid all contact" rule.
- Adaptive emergency planning: when 15 seconds or less remain, the search can use the full remaining timer budget.
- Source-grounded monster movement, frozen/launched lifecycle, collision, knockback and ability simulation.
- Deterministic search branches: candidate evaluation cannot consume another candidate's monster RNG stream.
- Stochastic trajectory evaluation across independent monster RNG seeds.
- Cumulative damage tracking so healing on a Safe Pad does not hide earlier monster contact.
- Receding-horizon controller that executes only the first action and replans from the next observation.
- Automated movement benchmarks and a 1,000-future planned-trajectory Monte Carlo benchmark.

## Validation

The Maven CI suite validates source mechanics, maze routing, player movement, planner behaviour, abilities, stochastic trajectory evaluation and the 1,000-future benchmark.

The simulator deliberately remains the authority for candidate trajectories. Heuristics guide search but do not replace tick simulation.

## Boundary of this milestone

Live Minecraft observation, maze ingestion, monster spawning/state ingestion, input execution and 1.8/1.21 client adapters are **not** part of Milestone 2. They are the next integration layer.

The result is a version-neutral AI core that can be tested and benchmarked independently of Minecraft rendering and networking.
