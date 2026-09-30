# 1.8.9 human run recorder

The Minecraft 1.8.9 adapter contains an optional human-run recorder for calibrating the simulator against strong real gameplay.

## Recording a run

1. Build/install the current Minecraft 1.8.9 adapter.
2. Start Minecraft with the adapter and enter Monster Maze normally.
3. Press **F7** to enable recording.
4. Play the run normally. The recorder does not enable the AI or modify movement.
5. The recorder automatically opens a file when a Monster Maze round is detected and closes it when the round ends or the world is left.
6. Press **F7** again to stop recording early.

Files are written under:

`human-runs/human-speed-run-YYYYMMDD-HHmmss.jsonl`

The file is JSON Lines: a header, one tick record per client tick, and a footer.

## What is captured

Each tick contains:

- world tick, stage, SafePad timer and elapsed run time
- player position, velocity, yaw/pitch, grounded state and health
- actual movement input captured from Forge's `InputUpdateEvent`
- sprint-key state, jump, forward/strafe values and right-click pulses
- raw per-tick yaw delta, including whether it fits the common 30-degree action limit
- detected kit and ability/jump charges
- maze centre, active SafePad and derived logical cell
- all observed Monster Maze monsters and their position/velocity
- the logical maze and live physical-floor matrix when first observed or changed

The recorder intentionally omits scoreboard title/lines and player identity/chat data. Large static maze matrices are emitted only on their first observation or when they change, so normal ticks remain lightweight.

## Why the raw yaw is retained

The common AI action representation currently limits `yawDelta` to 30 degrees per tick because that is the adapter's AI command contract. Human telemetry therefore retains the **raw** observed yaw delta and a boolean indicating whether it is within that limit rather than silently clipping it. This lets simulator validation detect a real control/physics mismatch.

## Analysis pipeline

A recorded run is intended to become:

`human JSONL -> common observation/action stream -> simulator replay -> human-vs-AI comparison`

This allows route length, travel time, stationary ticks, turning, jumping, pad arrival timing, monster interactions and ability use to be measured against the same world observations used by the AI.
