# Milestone 3 — Closed-Loop Game-Playing AI

Milestone 3 turns the Milestone 2 simulator/planner into a navigation and
control stack suitable for a live adapter.

## 3A — Maze-aware movement

Monster Maze does **not** use ordinary wall/block collision for player
movement. The maze is therefore represented as a navigation graph rather than
as Minecraft block AABBs.

Implemented:

- PlayerPathfinder produces physical cardinal routes.
- PlayerRoute converts cells to world-space centre waypoints.
- Disabled monster waypoints remain usable by the player.
- WaypointFollower advances through waypoints without teleporting or
  introducing wall collision.
- MazeAwareRecedingHorizonController feeds the current route waypoint into the
  physical planner.

## 3B — Monster prediction

Implemented:

- Simulator.forecastSnapshots() exposes deterministic per-tick futures.
- MonsterTrajectoryPredictor uses the actual MonsterSimulator.
- Predictions therefore include waypoint selection, intersections, frozen
  monsters and launched/removed monsters.
- BeamSearchPlanner adds a short-horizon predicted monster risk cost when
  monsters are nearby.
- Existing Monte Carlo trajectory evaluation remains available for larger
  future comparisons.

## 3C — Tactical abilities

Implemented:

- Ability actions remain ordinary simulated planner branches.
- Ability branches are now tactically gated by live monster proximity.
- Repulsor, Cryo Blitz and Body Rush use different threat radii.
- The simulator remains authoritative for the actual ability consequence.
- Damage and ability opportunity costs remain part of trajectory scoring.

## 3D — Closed-loop control

Implemented:

- RecedingHorizonController.nextAction() replans from the observed state.
- nextActions() supports a bounded execution window.
- MazeAwareRecedingHorizonController combines maze routing, physical
  planning and bounded execution.
- No predicted state is cached as authoritative after an execution window.

The intended live loop is:

    observe -> route -> plan -> execute a few ticks -> observe -> replan

## 3E — Benchmarking

Implemented deterministic validation for:

- 100 maze route queries;
- physics-driven waypoint completion;
- monster trajectory collision prediction;
- closed-loop replanning;
- 1000-seed Monte Carlo trajectory evaluation.

The benchmark suite uses fixed seeds where randomness is involved so planner
changes can be compared against reproducible scenarios.

## Remaining work after Milestone 3

Milestone 3 does not yet connect to a Minecraft server.

The next milestone is the live adapter layer:

    Minecraft 1.8 adapter   ─┐
                             ├──> WorldModel -> same planner/controller
    Minecraft 1.21.11 adapter┘

The simulator remains the authoritative development environment until those
adapters can populate the same GameState accurately.
