# Source-faithful performance architecture

This document records the performance rules for the live Monster Maze AI.

## Authoritative 1.8 source facts

The implementation is checked against `joshbet9/MonsterMaze`'s 1.8 source, especially:

- `MonsterManager.bump()`: exact 3-D contact uses a squared horizontal prefilter followed by the source `< 1.0` 3-D distance check; pad immunity is checked before bumping; a normal bump deals 4 damage; the source cooldown is 1 second; QOL knockback is supplied by `UtilAction.velocity()`.
- `MonsterManager.move()`: monsters use cardinal maze waypoints and `CreatureMoveFast` at `1.4 * speedMultiplier`; launched and frozen monster state changes whether normal movement runs.
- `MonsterManager.launch()` / `tickLaunched()`: launched monsters leave normal waypoint movement and have their own lifecycle.
- `SafePad.isOn()`: the player objective is the physical pad area, not merely the beacon block.
- `GameManager.isOnAnyPad()`: active, preview and old pads can provide pad immunity.
- Modern/Speed 1.8 mode definitions retain speeding movement, enhanced kit mechanics, 3 Jumper charges, Safe Pad recharge, Maverick and the secondary abilities.

The AI simulator must preserve these semantics. Performance changes are not permitted to replace source mechanics with heuristics.

## Optimisation invariants

### 1. Full route simulation retains the observed monster population

The long-horizon route evaluator does not apply the old arbitrary local monster-radius route cutoff. A distant monster may become relevant later, so the route-level simulator retains the full observed population.

### 2. Tactical branch pruning is horizon-bounded

The tactical beam currently looks six ticks ahead. A branch-only monster envelope is therefore allowed to exclude monsters that cannot reach the player/contact envelope during those six ticks. This is an optimisation of an explicitly bounded search branch, not a change to the world model.

Launched and frozen monsters are retained in the branch state because their source lifecycle/state must remain available to the simulator; only removed monsters are discarded by the branch filter.

### 3. Maze models are shared, not mutated

A 99x99 `MazeModel` is observational input to player/monster physics during simulation. `GameState.copyForSimulation()` therefore shares the maze object while deep-copying player, monster, ability and other mutable state.

No simulation code may call `MazeModel.setDisabled()` or `setPhysicalFloor()`. If future simulation requires mutable map state, it must introduce a copy-on-write representation rather than mutating the shared observation.

### 4. Speeding contact remains source-grounded

The source `MonsterManager` bump and `UtilAction.velocity()` model remain authoritative. `SpeedContactModel` only changes the planning consequence of the observed high-speed edgeward slide and never invents a replacement knockback force.

### 5. Safe Pad semantics remain physical

Player routing continues to use the physical-floor graph. Monster waypoint disabling around a Safe Pad must not be inherited by player routing.

## Performance target

The immediate goal is to make the existing source-faithful planner computationally bounded enough that asynchronous execution can later be used as a safety mechanism rather than as a substitute for optimisation.

The next server-oriented phase should benchmark:

- CPU time per decision;
- allocations per decision;
- full-field monster counts;
- tactical branch counts;
- route candidate counts;
- worst-case decisions near a dense monster field;
- multiple simultaneous AI players.

No fixed CPU target is treated as achieved until measured on the server workload.
