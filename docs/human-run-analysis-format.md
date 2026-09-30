# Human Run → Simulator Analysis Format

This is the canonical pipeline for turning a recorded Minecraft MonsterMaze run into data that can be compared with the simulator without treating the human's raw movement as a hard-coded perfect route.

## 1. Dataset layout

human-runs/<run-id>/
  manifest.json
  raw/
    recorder output (immutable)
  normalized/
    ticks.jsonl
    stages.jsonl
    decisions.jsonl
    events.jsonl
  analysis/
    summary.json
    simulator-comparison.json
    anomalies.json

Raw recordings are evidence and are never rewritten. Ingestion may map recorder filenames into this layout.

## 2. Manifest

Required identity/provenance fields:

    schemaVersion, runId, source, recordedAt, playerLabel, mode, pattern, kit, mazeSeed, recorderVersion, gameVersion, sourceCommit, quality, notes

Metadata resolution priority:
1. Explicit raw game/scoreboard state.
2. Explicit recorder event.
3. Deterministic reconstruction from objective transitions.
4. Normalized observer fields.
5. Filename/folder metadata.

Conflicts are recorded in anomalies.json; they are never silently overwritten. This is important because the current recorder has already demonstrated that normalized kit/stage fields can disagree with the raw scoreboard.

## 3. Canonical tick state

ticks.jsonl is one normalized object per sampled tick:

    {
      tick, stage, stageTick,
      position: {x, y, z},
      velocity: {x, y, z},
      yaw, pitch, grounded,
      inputs: {forward, strafe, sprint, jump},
      targetPad: {x, z}, targetDistance, routeDistance,
      health,
      mobThreat: {nearestDistance, count, mobs},
      ability: {available, cooldownTicks, charges}
    }

Unavailable values remain null; they are never fabricated.

## 4. Semantic event stream

events.jsonl converts raw changes into events such as:

STAGE_START, STAGE_COMPLETE, PAD_TARGET_CHANGED, PAD_REACHED, DAMAGE, KNOCKBACK, AIRBORNE_START, AIRBORNE_END, FALL_RISK, ABILITY_ACTIVATED, ABILITY_RESULT, MOB_ENTERED_THREAT_RANGE, MOB_LEFT_THREAT_RANGE, DEATH, RUN_END.

A knockback event must preserve pre/post velocity, impulse, damage, source mob/ability when known, and whether recovery was subsequently required.

Example:

    {
      tick: 13864678,
      stage: 49,
      type: "KNOCKBACK",
      source: "MAVERICK",
      damage: 4.0,
      preVelocity: {x: 0.03, y: 0.0, z: 0.18},
      postVelocity: {x: 0.31, y: 0.42, z: 0.86},
      horizontalImpulse: 0.91,
      recoveryRequired: true
    }

High velocity caused by knockback is therefore classified as external displacement, not normal human locomotion.

## 5. Stage records

stages.jsonl contains one record per stage:

    {
      stage, startTick, endTick, durationTicks, completed,
      targetPad, pathDistance, actualDistance,
      stationaryTicks, sprintTicks, jumpPresses, yawRotationDegrees,
      damageEvents, knockbackEvents, abilityUses, recoveryEvents,
      deathCause
    }

## 6. Decision records

The decision layer captures behaviour rather than merely copying movement.

decisions.jsonl records meaningful decision windows:

    {
      tick, stage,
      context: {targetDistance, routeDistance, nearestMobDistance, health, airborne, abilityAvailable},
      action: {forward, strafe, jump, sprint, yawDelta, ability},
      intent, outcome
    }

Initial derived intents:

CONTINUE_PAD_ROUTE, CORRECT_HEADING, AVOID_MOB, COMMIT_AROUND_MOB, RECOVER_KNOCKBACK, RECOVER_FALL, USE_ABILITY, WAIT_FOR_SAFE_WINDOW, REPOSITION, UNKNOWN.

Intent is a derived classification, not a claim about conscious thought.

## 7. Human behavioural metrics

Route efficiency:
- pad-to-pad path distance
- actual travelled distance
- excess-distance ratio
- time to pad
- time to first forward motion
- stationary fraction
- completion rate

Locomotion:
- sprint fraction
- forward/strafe fractions
- jump frequency
- normal horizontal speed distribution
- acceleration/deceleration
- yaw-rate distribution
- large-turn frequency

Hazard handling:
- damage per stage
- damage-free stage percentage
- mob encounters per stage
- time in threat range
- knockback frequency
- recovery time after knockback
- successful recovery rate
- causal death chains

Ability usage:
- opportunities
- activations
- activation context
- target/range
- outcome
- cooldown/charge efficiency

Knockback/fall impulses are excluded from normal locomotion metrics.

## 8. Causal death chains

Deaths are represented as chains rather than a single label:

    MOB_ENTERED_THREAT_RANGE → DAMAGE → KNOCKBACK → AIRBORNE_START → SECOND_DAMAGE → FALL_RISK → DEATH

This lets the simulator target recurring mechanisms rather than optimizing against whichever event happened last.

## 9. Simulator comparison

For each human run, compare a long-horizon simulator run under the same mode, recorder-facing pattern, kit, and mechanics version. The AI profile is an explicit simulator parameter because a human recording cannot establish an AI personality profile.

The current CI pipeline resolves mode/kit/pattern from the normalized human run, runs the authentic closed-loop simulator until death, completion, or the safety tick cap, and then feeds that result back through the same comparison layer.

comparison output contains:

    conditions: {...human conditions...}
    simulatorConditions: {...simulator conditions...}
    conditionMatch: true|false
    conditionDifferences: [...]
    human: {stageReached, durationSeconds, meanStageTime, routeExcessRatio, damagePerStage, ...}
    simulator: {stageReached, durationSeconds, damagePerStage, maxHorizontalSpeed, ...}
    gaps: [{metric, human, simulator, simulatorMinusHuman, relationship}]

Do not collapse this into one human-likeness score or ranking. We want actionable behavioural gaps.

## 10. What becomes training/calibration data

The useful learning unit is:

    state/context → observed human action distribution

For example:

    recent Maverick knockback + airborne + target still viable → steer toward target and recover

or:

    target urgency high + mob 5–7 blocks away + viable corridor → continue route with controlled heading correction

These distributions can calibrate AI attributes and tendencies. They must not become coordinate-by-coordinate replay rules.

## 11. Strong-run selection

Runs are tagged accepted, partial, corrupt, or outlier.

An accepted calibration run must have consistent raw evidence, resolvable mode/pattern/kit, reconstructable stage transitions, no critical recorder corruption, useful depth, and behaviour relevant to the calibration question.

Multiple strong runs should be aggregated. One run must never define an AI policy.

## 12. Validation gates

Ingestion must flag:
- scoreboard kit vs normalized kit conflicts
- raw stage vs reconstructed stage conflicts
- terminal stage vs reconstructed stage conflicts
- non-monotonic ticks
- impossible stage transitions
- missing required event fields

Partial datasets may still be analyzed, but affected fields cannot be used as calibration truth.

## 13. Future workflow

1. Play Minecraft run.
2. Stop recording.
3. Copy recorder output into human-runs/inbox/<run-id>/.
4. Run human-run ingest.
5. Validate and reconstruct metadata/stages/events.
6. Emit normalized JSONL and summary.
7. Resolve the simulator mode, kit, and recorder-facing pattern from the normalized human run; choose the AI profile explicitly.
8. Run the matching long-horizon simulator condition without the old Stage-10 stop gate.
9. Normalize simulator output to the same metric vocabulary.
10. Produce human-vs-simulator comparison and explicit condition-match status.
11. Aggregate repeated strong human decisions into calibration distributions.
12. Tune attributes/tendencies and rerun simulator regression tests.
13. Validate the resulting logic in Minecraft.

## 14. Key principle

A strong human run is evidence of successful behaviour, not proof that every individual action was optimal. The system should learn recurring relationships from many strong runs while retaining raw traces for inspection.