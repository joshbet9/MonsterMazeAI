# Monster Maze CPU Policy Training Pipeline v2

## Objective

Turn MonsterMazeEngine into a continuous teacher/evaluation environment and produce a compact policy that can run inside the MonsterMaze 1.8 server.

The desired development loop is:

Engine rollouts -> candidate states/trajectories -> teacher labels -> policy training -> holdout evaluation -> full matrix evaluation -> model promotion -> Minecraft 1.8 validation -> new failure states -> next training cycle.

No human prompt should be required to begin another training cycle once the local training service is configured.

## Why teacher distillation first

Monster Maze has a difficult mixed action space and long-horizon survival objective.

The existing source-faithful planners can already answer many local questions using exact simulation, although they are too expensive and brittle for production.

Use that capability as a teacher:

expensive reasoning -> good action/short trajectory -> cheap direct policy.

This gives the learner a useful starting distribution.

A reinforcement-learning-only start is deliberately deferred because the policy would otherwise spend a large amount of compute rediscovering basic movement and pad traversal.

## Curriculum

### Phase 1 — locomotion primitives

Environment:
- one target pad;
- no active monsters;
- fixed and randomized starting offsets;
- all relevant kits separately.

Objective:
- stay supported;
- steer to the target;
- perform required jumps/gaps;
- arrive without falling.

### Phase 2 — dynamic maze

Add:
- moving/decaying floor;
- multiple route families;
- timing pressure;
- randomized target offsets.

### Phase 3 — monsters

Add:
- normal movement;
- contact damage;
- knockback;
- monster trajectory uncertainty;
- kit-specific interactions.

### Phase 4 — abilities

Add:
- ability timing;
- resource conservation;
- ability chaining;
- recovery situations.

### Phase 5 — competition

Add:
- human-like competitor trajectories;
- CPU competitors with varied profiles;
- pad races;
- overtaking;
- tactical interference.

### Phase 6 — full distribution

Train over:
- all patterns;
- all modes;
- all kits;
- broad profile distributions;
- the full stage range.

Stage-10 diagnostic tests are smoke tests only.

## Policy architecture

The first production policy is hierarchical.

The tactical head produces route/objective choice, risk posture, monster posture, ability intent, and competitor interaction posture.

The locomotion head produces movement magnitude, steering, strafe, jump, sprint and ability request.

The tactical state is held between tactical decisions. Locomotion is evaluated at 20 Hz.

## Profile conditioning

Training examples sample difficulty, capability attributes and tendencies independently within valid ranges.

The model receives the profile vector explicitly.

Do not train one model per difficulty.

The policy learns a continuous behaviour space that the server can sample/configure.

## Demonstration quality

A teacher may find several actions that are effectively equivalent.

Do not force all equivalent states toward one arbitrary floating-point action.

For discrete heads, record teacher preference or a probability distribution where useful.

For steering, preserve successful tolerance bands when the simulator demonstrates them.

This reduces overfitting to exact teacher trajectories.

## DAgger / hard-state mining

After initial behaviour cloning:

1. run the candidate policy;
2. identify states near failure;
3. label selected states with the teacher;
4. add them to the dataset;
5. retrain;
6. evaluate on fixed holdout seeds.

Priority sampling should favour first-fall states, repeated oscillation, wrong-route selections, missed gaps, late turns, unnecessary monster contact, ability timing failures, and competition losses.

This is substantially more valuable than adding more easy successful trajectories.

## Failure taxonomy

Each terminal episode receives a primary reason:

- fall;
- missed pad;
- death by damage;
- ability misuse;
- timeout;
- invalid action;
- observation mismatch;
- mechanics divergence.

The trainer should report failure distributions, not only final stage.

## Dataset splits

Maintain three sets.

Training: continuously expanded by rollouts and teacher relabelling.

Holdout: fixed seeds/scenarios not used for model updates.

Challenge set: curated difficult states and real Minecraft mismatch cases.

The challenge set is versioned and must not disappear simply because a model learns to pass it.

## Model promotion

A candidate is promoted only after contract validation, holdout evaluation, challenge-set evaluation, full matrix evaluation, and inference-performance measurement.

The candidate is compared against the currently promoted model on the same seeds.

Do not replace the current model merely because its average stage is higher if it introduces severe regressions elsewhere.

## Continuous local service

The intended local workflow is:

Rollout workers -> dataset shards -> trainer -> candidate model -> evaluator -> promoter.

Workers run independently from training so GPU training does not stall CPU simulation generation.

Dataset shards are append-only and versioned.

The trainer consumes only completed shards.

The production model directory contains only explicitly promoted artifacts.

## Data format

Store compact numeric arrays for high-volume training.

Recommended fields:
- observation features;
- profile features;
- tactical intent label;
- locomotion action label;
- next-state summary;
- reward/outcome;
- stage;
- pattern;
- mode;
- kit;
- seed;
- teacher/policy source;
- model version that generated the state.

Detailed human-readable traces remain optional debug artifacts.

## Training/evaluation resource separation

GPU resources are used primarily for model training/inference.

CPU rollout workers run in parallel across cores.

Do not launch one heavyweight JVM for every episode.

## Minecraft feedback loop

Real 1.8 runs are not the normal training path.

They are high-value calibration samples.

A mismatch is classified before adding it to training:

1. mechanics mismatch;
2. observation mismatch;
3. action execution mismatch;
4. legitimate stochastic divergence.

Only genuine policy failures become policy training samples.

Mechanics or execution problems are fixed at their source.

## Self-play

Self-play begins after basic survival and monster interaction are stable.

Use heterogeneous profiles so the policy experiences the variety of opponents that the production server will expose.

Each simulated participant retains its own profile and RNG seed.

## Later reinforcement learning

Once the policy can reliably execute the game, simulation RL can optimize completion probability, stage survival, time-to-pad, damage, ability efficiency, and competitive outcomes.

RL is a fine-tuning layer, not a replacement for the source-faithful teacher and mechanics model.

## Live deployment rule

A live server never updates weights from an ongoing game.

The server loads one promoted model version for the match/session.

This keeps CPU behaviour reproducible and prevents training instability from affecting active games.
