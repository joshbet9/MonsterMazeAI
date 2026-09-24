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

## Milestone 4 — Minecraft 1.8 execution boundary

The Java-8-compatible `LegacyAction` contract and `ActionSink` execution boundary are now implemented. `Minecraft18ActionExecutor` maps normalized forward/back/strafe/jump/sprint controls to vanilla 1.8.9 key bindings and applies bounded camera yaw. Ability intent is surfaced explicitly but is not auto-bound to an unvalidated key or mouse action.

Both Maven and Minecraft 1.8.9 adapter CI are green for this implementation.

## Remaining work after Milestone 4

Milestone 3 does not yet connect to a Minecraft server.

The next milestone is real closed-loop execution validation in Minecraft 1.8.9:

    Minecraft 1.8 adapter   ─┐
                             ├──> WorldModel -> same planner/controller
    Minecraft 1.21.11 adapter┘

The simulator remains the authoritative development environment until those
adapters can populate the same GameState accurately.


## Milestone 5 — Real 1.8 closed-loop execution bridge

The Java-8 Minecraft client and Java-17 AI core are now connected through an
explicit process boundary rather than sharing incompatible runtime classes.

Implemented:

- LegacyProtocol provides a version-neutral binary observation/action stream.
- AiSidecarMain runs the existing ObservationWorldModel and
  LiveTickController in the Java-17 AI process.
- The Minecraft 1.8 client sends the complete live observation each tick and
  receives exactly one normalized action.
- Minecraft18ActionExecutor applies that action through vanilla key bindings
  and bounded camera yaw.
- The bridge is fail-closed: missing runtime configuration, process startup
  failure, protocol failure, lobby state, missing pad, death, completion, or
  invalid state produces IDLE.
- The AI runtime is packaged as a shaded executable common runtime JAR
  containing the common API.
- The sidecar is opt-in through MONSTERMAZE_AI_RUNTIME_JAR; Java 17 can be
  selected with MONSTERMAZE_AI_JAVA or JAVA_HOME_17_X64.
- Protocol and adapter regression tests cover observation/action round trips
  and the disabled-by-default runtime mode.

The resulting live architecture is:

    Minecraft 1.8.9 tick
        -> Minecraft18Observer
        -> LegacyProtocol
        -> Java-17 AiSidecarMain
        -> ObservationWorldModel
        -> LiveTickController
        -> LegacyAction
        -> LegacyProtocol
        -> Minecraft18ActionExecutor
        -> next Minecraft tick

This preserves the existing Java-8/Java-17 isolation while using the same
world model and planner that were validated in the simulator.

### Live validation boundary

CI validates both sides and the protocol, but an actual Monster Maze run still
requires launching the 1.8 client with the generated runtime JAR configured.
The repository does not claim an in-game movement result from CI alone.
