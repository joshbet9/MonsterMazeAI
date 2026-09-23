# MonsterMazeAI Architecture

## 1. Observation

The client adapter converts Minecraft's live state into a version-neutral snapshot.

A snapshot should eventually contain:

- player position, velocity, yaw and pitch
- on-ground / jump state
- collision geometry around the player
- maze cells and traversable corridors
- current safe pad and stage
- monster positions, velocities and relevant states
- current kit and ability cooldowns/resources
- game timer and completion state

The common planner must not depend directly on Minecraft classes.

## 2. World model

The world model represents the maze as traversable geometry rather than merely a 2D shortest-path grid.

A node may represent:

- a corridor cell
- a turning point
- a jump opportunity
- an ability setup position
- a deliberate monster-contact setup

Edges carry an estimated tick cost and a predicted state transition.

## 3. Action model

Actions are semantic operations such as:

- WALK
- SPRINT
- TURN
- JUMP
- SPAM_JUMP
- USE_ABILITY
- APPROACH_MONSTER
- ACCEPT_KNOCKBACK
- AVOID_MONSTER
- WAIT

The controller expands these into per-tick Minecraft inputs.

## 4. Planning

The planner should optimize expected completion time while strongly penalizing death or unrecoverable states.

A future implementation can use a search such as A*, Dijkstra, beam search, or an incremental replanner depending on the state-space size.

The important property is that movement physics and monster interactions are part of the transition model. A geometrically shorter route is not necessarily faster.

## 5. Simulation

The simulator predicts short action sequences before execution.

It should model:

- acceleration/deceleration
- sprinting
- jumping and repeated jumping
- air control
- collision
- monster knockback
- ability effects
- safe-pad timing
- route-specific recovery

The simulator should be deterministic where the underlying game behaviour is deterministic.

## 6. Replanning

The agent should not blindly commit to an entire run.

At regular tick intervals, compare:

- predicted state
- observed state

If the error exceeds a configurable threshold, invalidate the affected plan prefix and search again from the observed state.

## 7. TAS / optimization mode

The project should support recording a successful run as a structured action trace rather than only recording raw key presses.

This allows:

- comparing routes
- measuring tick costs
- identifying bottlenecks
- replaying a known solution
- mutating individual decisions
- eventually searching for faster variants

A replay must remain a debugging/benchmarking feature, not the core intelligence of the agent.


## Simulator implementation plan

The next implementation layer is a deterministic short-horizon simulator, separated from Minecraft rendering and input. Given an observed state and semantic actions, it predicts the next tick state. The real client remains authoritative; after each short execution horizon, observed state replaces prediction and the planner replans.

Core modules: MazeModel, MonsterSimulator, platform-specific PlayerPhysics (1.8 and 1.21), CollisionModel, AbilityModel and GameModel. The planner uses receding-horizon search rather than immediately attempting a full-maze A* search. Candidate actions include movement, turning, jumping, deliberate monster interception, collision acceptance, ability use and waiting.

Monster futures are stochastic. Search should sample multiple legal monster futures and retain outcome distributions rather than a single optimistic route. Fixed seeds provide deterministic simulator tests.

The minimum simulator state includes tick, platform/mode, stage, phase timer, maze pattern, dynamic disabled cells, active/preview pads, player position/velocity/orientation/grounded/health/kit/jump and ability resources, and per-monster position/velocity/waypoint/direction/frozen/launched state.

Initial raw metrics are pad progress, time-to-pad, survival, health remaining, distance to pad, monster exposure and remaining movement/ability resources. These are metrics for search, not a baked-in universal route ranking.