# Live Minecraft Adapters

The common AI is isolated from Minecraft APIs behind WorldAdapter.

## Contract

Each platform adapter implements observe() to produce a detached GameState
snapshot and execute(Action) to translate one semantic action into client
inputs.

The adapter is authoritative for player state, the 99x99 maze, pads, stage and
timer, monsters, kit/ability state, death and completion. The common AI is
authoritative only for prediction and action selection.

## Closed-loop contract

    observe -> plan -> execute N ticks -> observe -> replan

N should remain small enough that prediction error cannot accumulate into a
large open-loop sequence.

## Maze geometry

Monster Maze does not require Minecraft block collision/pathfinding for player
navigation. The adapter captures maze cells and dynamic disabled cells as
navigation information. Player physics remain in the common simulator.

## Platform isolation

    Minecraft 1.8 client  -> WorldAdapter
    Minecraft 1.21.11 client -> WorldAdapter
                                  |
                                  v
                            common AI core

Minecraft classes must not cross into common.

## 1.8 implementation status

The first live target is Minecraft 1.8.9, matching the Mineplex-era client/API.
Forge 1.8.9 build 11.15.1.2318 is used as the development target. The adapter
is intentionally read-only at first: it observes the client and prints a
detached snapshot every second while Monster Maze is detected. It does not
send movement, jump, camera, or ability input.

The initial detector uses:
- the Monster Maze scoreboard ("Safe Pad" and "Stage") when available;
- the player position, velocity, health and grounded state;
- the active Safe Pad beacon;
- the large stained-clay center area to estimate maze center;
- the dominant maze top-block signature to reconstruct the 99x99 path grid;
- snowman entities as the default 1.8 monster representation;
- inventory display names to identify the kit and Jumper charges.

These are deliberately observation heuristics, not yet validated live against
recorded Mineplex observations.

## Implementation order

1. Read-only state capture. **Current.**
2. Validate center, maze, pad, monster and scoreboard detection against a real
   1.8 Monster Maze match.
3. Add robust preview/dynamic-cell detection.
4. Add monster lifecycle/state calibration.
5. Add input execution.
6. Enable closed-loop live control.
7. Calibrate against recorded observations.

Do not enable unattended gameplay until read-only observation reproduces a live
GameState accurately.
