# MonsterMazeAI CPU Opponent Architecture v2

## Mission

MonsterMazeAI exists to build the intelligence that will ultimately run CPU opponents inside the MonsterMaze 1.8 server.

The final consumer is not this repository. The final consumer is the MonsterMaze CPU-player runtime.

The AI project therefore has two distinct products:

1. **Training system** — may use expensive simulation, search, Monte Carlo, Python, large datasets and many parallel environments.
2. **Runtime policy artifact** — compact, deterministic, Java-8-compatible inference that can run inside a Spigot 1.8 server.

Training and runtime must never be coupled.

## System boundary

```
                 MonsterMazeEngine
              fast source-faithful world
                         |
                 rollout / teacher
                         v
                +------------------+
                |  TRAINING SYSTEM |
                |                  |
                | teacher search   |
                | policy training  |
                | self-play        |
                | profile sampling |
                | evaluation       |
                +---------+--------+
                          |
                    policy artifact
                          |
                          v
                 +------------------+
                 | RUNTIME POLICY   |
                 | Java 8 friendly  |
                 +---------+--------+
                          |
                          v
                    MonsterMaze
                    1.8 server
```

## Core design change

The current system has accumulated several live planners and movement controllers. That machinery is too expensive and too brittle to be the production CPU brain.

The new production architecture is hierarchical and learned:

```
Live state + profile
       |
       v
Strategic/Tactical Policy       ~5 Hz
       |
       | objective / route / risk / ability intent
       v
Locomotion Policy               20 Hz
       |
       | movement + turn + jump + sprint + ability
       v
Source-valid Action
```

The existing search/planner code becomes a **teacher and research tool**, not the final runtime.

## Semantic state contract

The AI sees a version-neutral observation model.

### Self

- position/velocity;
- yaw;
- grounded;
- health/max health;
- kit and resources;
- ability cooldowns;
- stage and time;
- active/preview pad;
- current run phase.

### Maze

- layout identifier;
- compact local physical-floor geometry;
- route context to the active objective;
- gap/corner opportunities.

### Threats

- local monster summaries;
- local competing-player summaries;
- contact/knockback context.

### Profile

The policy receives an explicit profile vector rather than hidden behaviour modifiers.

This is what makes one trained policy capable of producing many CPU competitors.

## Policy model

The preferred action representation is multi-head rather than one scalar regression target.

A candidate runtime network has:

```
features + profile
        |
     small MLP
        |
   +----+-----------+---------+---------+
   |    |           |         |         |
 move yaw         jump      sprint    ability
 head head         head       head      head
```

The exact width is a benchmark decision. The first goal is a compact network that can run for every CPU every tick without server-side search.

The output action is sampled or selected according to the configured profile.

For deterministic server behaviour, the runtime can use a per-opponent seeded RNG for stochastic choices. The seed is part of the CPU run state, not a global mutable RNG.

## Why profile conditioning matters

Difficulty, attributes and tendencies should be inputs to the policy instead of being implemented as dozens of post-policy hacks.

A profile has two conceptual groups.

### Capability

- movement precision;
- turn precision;
- reaction delay;
- planning horizon;
- recovery skill;
- threat awareness;
- ability timing;
- execution consistency.

### Tendencies

- risk tolerance;
- aggression;
- route bias;
- monster-contact preference;
- ability conservation;
- jump preference;
- direction-change preference;
- pad contest/overtake preference.

A difficulty preset provides sensible ranges for these values, but direct attributes remain configurable.

The policy is trained over the profile distribution so it learns what a cautious or aggressive decision means rather than merely adding random mistakes after inference.

## Training strategy

The training loop is deliberately staged.

### Stage A — mechanics and action contract

Before learning, establish that the same observation/action semantics work in MonsterMazeEngine and the 1.8 server.

### Stage B — teacher trajectories

Use the existing planner/search machinery against MonsterMazeEngine to produce successful trajectories.

Search is allowed to be slow here.

It is not allowed to run in the final server.

### Stage C — behaviour cloning

Train the locomotion policy from teacher trajectories.

Initial target:

- leave the first pad;
- traverse one gap;
- reach the next pad;
- survive ordinary movement variation.

Do not optimize stage 60 before this is reliable.

### Stage D — DAgger/search distillation

Let the learned policy generate states.

At sampled states, run the expensive teacher/search evaluator to label a better action.

Add those states to the training set and retrain.

This is the main replacement for the endless live-controller patch loop: the AI is trained on the mistakes it actually makes.

### Stage E — stochastic robustness

Randomize:

- monster trajectories;
- starting offsets;
- pad timing;
- legitimate movement noise;
- competing-player positions;
- profile parameters.

The goal is to learn robust behaviour rather than one deterministic trace.

### Stage F — self-play

Populate simulations with multiple policy-controlled players using different profiles.

Teach the tactical layer about:

- pad races;
- route contests;
- overtaking;
- blocking/interference where relevant;
- timing around other players.

### Stage G — full matrix promotion

Evaluate the complete Pattern × Mode × Kit matrix with sampled difficulty/attribute/tendency profiles.

There is no permanent stage-10 training ceiling.

Stage-limited runs are smoke tests only.

## Search/planner transition

The following current components are explicitly development/teacher components:

- BeamSearchPlanner;
- RecedingHorizonController;
- StableLiveMovementController;
- FirstPadSpeedrunController;
- TacticalRouteSimulator.

They may be retained until their training value is replaced.

The production runtime must not call them.

This prevents the production brain from growing indefinitely as new edge cases are discovered.

## Training data

Each training sample should retain:

```
schema_version
model_input
profile_vector
selected_action
next_state_summary
reward / advantage
episode outcome
pattern
mode
kit
stage
seed
teacher_or_policy_source
```

The raw trajectory remains valuable for replay and debugging.

Derived features should be reproducible from the semantic observation rather than becoming the only stored representation.

## Dataset lifecycle

The training system should continuously maintain:

```
candidate
  -> rollout
  -> collect
  -> label/improve
  -> train
  -> holdout evaluation
  -> regression evaluation
  -> promote/reject
```

A failed candidate must not replace the current best model automatically.

Models and datasets must be versioned independently.

## Full matrix

The matrix is an evaluation product, not a training constraint.

For every cell retain:

- attempts;
- mean/median stage;
- high-percentile stage;
- maximum stage;
- completion rate;
- elimination reason distribution;
- average damage;
- ability usage;
- inference cost.

The important target is strong peak performance combined with improvement over time, not a requirement that every random run hits the aspirational stage range.

## Runtime export

The training pipeline must export a self-describing artifact containing:

- schema version;
- feature manifest;
- profile manifest;
- network architecture;
- weights;
- normalization constants;
- action-head definitions;
- model checksum;
- training/evaluation metadata.

The artifact must be consumable by:

1. MonsterMazeEngine;
2. MonsterMazeAI runtime tests;
3. MonsterMaze 1.8 CPU runtime.

No format conversion should change action semantics.

## Java 8 runtime requirement

The production inference implementation must be compatible with the 1.8 server.

The runtime should therefore:

- avoid Python;
- avoid a JVM sidecar;
- avoid network inference;
- avoid a heavyweight ML framework;
- use primitive arrays / compact numeric buffers;
- load the model once;
- avoid allocation in the hot path.

A dependency-free Java implementation is preferred for the first production runtime.

## Feature computation

Features must be based on authoritative state, but the runtime feature path should be compact.

Static information such as the selected maze layout should be precomputed.

Dynamic monster/player context should be reduced once per match tick and then transformed into each bot's local perspective.

The feature encoder must be identical between Engine evaluation and server runtime.

## Runtime determinism

For a fixed:

```
state
+ profile
+ model version
+ opponent RNG seed
```

the CPU decision should be deterministic.

This makes regressions and training replay reproducible.

## Model promotion gates

A model is promotable only if it:

1. passes mechanics/action-contract tests;
2. passes holdout evaluation;
3. does not regress previously protected matrix cells beyond configured tolerance;
4. passes Engine runtime inference;
5. passes 1.8 integration inference;
6. remains within the measured inference/server CPU budget.

## Player modelling

Player-specific NPC behaviour remains a higher-level profile problem.

Observed gameplay can be converted into the same tendency/capability vector used by the policy.

Examples of measurable signals:

- reaction latency;
- movement precision;
- route preferences;
- jump frequency/timing;
- monster contact decisions;
- ability timing;
- risk decisions;
- overtake behaviour.

The model should reproduce measured behaviour while retaining the same physics and action validity as every other CPU.

## Non-goals

MonsterMazeAI is not the authoritative Monster Maze game implementation.

It must not become a second divergent game server.

MonsterMazeEngine supplies a fast mechanics model for development and training; MonsterMaze remains the final live arbiter.

The AI should not encode Minecraft/Bukkit classes in the common policy layer.


## Prefer authoritative simulation state

The final server CPU has a significant advantage over the current 1.8 client prototype: it receives authoritative state directly.

The learned policy should therefore be trained against the same compact semantic observation that the server can provide, rather than against a simulated visual/perceptual pipeline.

The observation should intentionally exclude information a player would not reasonably have. This prevents the policy from learning server omniscience and lets difficulty/awareness attributes control how much contextual information reaches the brain.

## Route catalogue

The policy should consume route context produced by a static route catalogue.

The catalogue can be built from the fixed three maze patterns and provides candidate physical route families, gap opportunities and turn context.

The production policy selects among route context; it does not run pathfinding.

This makes route knowledge deterministic game knowledge and leaves the learned component responsible for deciding how to execute and when to trade time, risk and competition.

## Training implication

The teacher can still use expensive route search and trajectory simulation.

The exported policy must learn the result of that reasoning rather than carrying the search algorithm into production.

This is the intended distillation boundary.
