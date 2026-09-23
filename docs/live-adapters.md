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

## Implementation order

1. Read-only state capture.
2. Maze and pad detection.
3. Monster detection.
4. Input execution.
5. Closed-loop live control.
6. Calibration against recorded observations.

Do not enable unattended gameplay until read-only observation reproduces a live
GameState accurately.
