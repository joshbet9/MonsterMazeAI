# CPU Policy Runtime Contract v2

## Purpose

Define the smallest stable contract between the trained Monster Maze policy and the production 1.8 server.

The production server must consume a **direct policy**.

It must not enumerate candidate actions and ask a learned value model to score them.

## Inputs

The policy input is a fixed-order primitive vector composed of:

### Self state

- normalized stage;
- remaining phase time;
- health ratio;
- horizontal/vertical velocity;
- grounded state;
- yaw-relative motion;
- jump resources;
- ability resources/cooldowns;
- current pad/next-pad context.

### Maze context

- static layout id;
- local physical-floor mask;
- objective direction;
- route family/edge context;
- imminent gap/corner information.

### Threat context

- nearest relevant monsters;
- a small fixed number of additional threat slots ordered by relevance;
- relative position and velocity;
- contact/closing information;
- frozen/launched state where tactically relevant.

### Competitor context

- nearest relevant human/CPU players;
- relative position/velocity;
- pad progress;
- distance to the current objective;
- contest/overtake context.

### Profile

Capability attributes and behavioural tendencies are explicit inputs.

The feature manifest, names and ordering are part of the model artifact.

## Outputs

The production policy uses separate action heads.

Recommended first implementation:

```
movement magnitude
steering angle / yaw delta
strafe tendency
jump
sprint
primary ability
enhanced ability
tactical confidence
```

The semantic adapter converts these into the public action contract:

```
forward
strafe
yawDelta
jump
sprint
useAbility
```

The exact neural architecture is benchmarked independently from the semantic contract.

## Tactical intent

The policy stack has a slower tactical layer and a fast locomotion layer.

### Tactical layer

Runs at a small cadence and on important game events.

It produces an intent record such as:

- objective target;
- route family;
- risk preference;
- monster interaction mode;
- ability intent;
- competitor interaction mode.

### Locomotion layer

Runs at 20 Hz and converts current physical state + tactical intent + profile into an action.

The tactical layer is stateful but compact.

The locomotion layer should not invoke pathfinding.

## Direct-policy requirement

A trained policy must produce an action in one forward pass.

The following are explicitly training/teacher operations only:

- BeamSearchPlanner;
- RecedingHorizonController;
- Monte Carlo evaluation;
- candidate-action enumeration;
- trajectory simulation.

The server must never need to call those components to recover a useful action.

## Training transition

Current scalar `PolicyActionModel` functionality can be retained temporarily as a research/teacher component.

The replacement runtime artifact must be a direct-action policy artifact.

Training should proceed:

```
teacher/search
    -> demonstrations
    -> direct policy
    -> policy rollouts
    -> teacher relabelling of difficult states
    -> retraining
    -> holdout gate
```

## Profile conditioning

Profile values must be included in the policy input.

This allows one model to support:

- baseline capability;
- multiple difficulty presets;
- individual attributes;
- behavioural tendencies;
- player-model-derived profiles.

Do not create a different neural model for every difficulty.

## Runtime determinism

Production action selection is deterministic for:

```
model version
observation
profile
opponent seed
```

A seeded RNG may be used for intentional tendency variance or action sampling.

No process-global randomness may control CPU behaviour.

## Export format

The model artifact must contain:

- schema version;
- feature manifest;
- profile manifest;
- action-head manifest;
- architecture;
- weights;
- normalization parameters;
- model checksum;
- training metadata;
- holdout/evaluation summary.

The artifact must be loadable by both MonsterMazeEngine and the 1.8 server runtime without semantic conversion.

## Versioning

The policy contract is versioned independently from game mechanics.

A model is not accepted by the server if its feature schema or action schema is unsupported.

The server should reject an incompatible model at load time rather than silently interpreting fields differently.

## Runtime implementation constraints

Production inference should:

- use Java 8-compatible code;
- use primitive arrays;
- preload weights;
- reuse input/output buffers;
- avoid per-tick allocations;
- avoid reflection;
- avoid JSON parsing in the hot path;
- avoid external ML libraries unless a measured server-compatible option is proven necessary.

A dependency-free MLP is the preferred baseline.

## Performance measurement

Measure:

- microseconds per tactical decision;
- microseconds per locomotion decision;
- allocations per decision;
- simultaneous CPU count;
- server tick duration with 0/1/4/8/... CPUs;
- worst-case dense-monster conditions.

Do not define a target as achieved from an isolated desktop benchmark.

The authoritative workload is the real MonsterMaze 1.8 server.
