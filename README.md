# MonsterMazeAI

An autonomous Minecraft client mod that plays Monster Maze.

## Goal

MonsterMazeAI is a game-playing agent, not a macro or fixed TAS replay. It observes the live Monster Maze state, models the maze, monsters, movement physics and kit abilities, plans for minimum completion time, executes the plan at tick-level precision, and replans when reality differs from prediction.

Two client implementations are planned:

- `1.8/` — Minecraft 1.8 client mod
- `1.21/` — Minecraft 1.21.11 client mod

The AI logic should be shared wherever practical, while version-specific Minecraft integration remains isolated.

## Design principles

1. **Source-grounded mechanics** — Monster Maze behaviour is derived from the existing [MonsterMaze server recreation](https://github.com/joshbet9/MonsterMaze), with in-game testing used to resolve client-side or engine-specific behaviour.
2. **Plan actions, not inputs** — the planner reasons about movement, jumps, abilities and monster interactions rather than replaying a recorded input stream.
3. **Monster interaction is part of movement** — knockback can be avoided, tolerated, or deliberately exploited when it reduces total time.
4. **Continuous replanning** — the agent observes its actual state and can replace a plan when the maze, monsters, physics or timing differ from the prediction.
5. **Tick-level execution** — final movement decisions are made at Minecraft tick granularity so the project can eventually optimize TAS-quality runs.
6. **Measurement over assumptions** — uncertain mechanics become explicit model parameters and can be validated experimentally.

## Planned architecture

```
Minecraft client
    |
    v
Observation / State Capture
    |
    +--> Maze Model
    +--> Player Physics Model
    +--> Monster Model
    +--> Kit / Ability Model
    |
    v
Action Planner
    |
    +--> Route Search
    +--> Movement Simulation
    +--> Ability Scheduling
    +--> Monster Knockback Planning
    |
    v
Tick Controller
    |
    v
Minecraft input / camera / ability actions
```

## Repository layout

```
MonsterMazeAI/
├── docs/
│   ├── architecture.md
│   └── mechanics/
│       ├── 1.8.md
│       └── 1.21.md
├── common/
│   ├── maze/
│   ├── monsters/
│   ├── physics/
│   ├── abilities/
│   ├── pathfinding/
│   ├── planner/
│   └── tas/
├── 1.8/
└── 1.21/
```

## Current status

The repository is intentionally starting with the design and mechanics-extraction phase. No gameplay automation is considered correct until it is backed by the Monster Maze implementation and validated in-game.
