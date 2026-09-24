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

**Milestone 3 is complete.**

The common AI core now has source-grounded game mechanics, tick-level 1.8 movement simulation, maze-aware routing, trajectory beam search, emergency 15-second planning, deterministic search branches, stochastic trajectory evaluation, and a receding-horizon controller. Automated benchmarks include timed jump movement and a 1,000-future Monte Carlo evaluation.

The Minecraft 1.8 observation adapter is now validated against all three source maze patterns, and the common core now has a one-tick closed-loop movement controller that replans from each live world-model observation. The Java 8 Forge adapter and Java 17 AI core remain deliberately isolated; the next integration step is the version-specific execution bridge and live movement validation.

See [docs/milestone-2.md](docs/milestone-2.md) and [docs/milestone-3.md](docs/milestone-3.md) for the completed scope.
## Long-term deployment vision

The long-term goal is for MonsterMazeAI to become a population of AI-controlled competitor players that can be integrated into the Monster Maze game itself. Rather than having one monolithic "bot difficulty", the eventual system should support distinct AI competitors with configurable difficulty and attribute sliders, allowing their behaviour and capabilities to be tuned independently while using the same underlying AI framework.

The immediate objective is deliberately different: first build the strongest possible AI baseline. The read-only live adapter, accurate state model, physics/monster simulation, planning and closed-loop control should be developed and validated without artificially weakening the agent. Once that baseline is reliable, difficulty profiles and attribute controls can be layered on top as deliberate constraints or behavioural variations rather than being baked into the core planner.

This separation is intentional: **baseline intelligence first; competitor personalities/difficulties second; integration into Monster Maze third.** The AI should remain capable of playing at its full measured potential even when later configurations impose lower difficulty or different attributes.

A further long-term feature is **player modelling**. The system should be able to measure a real player's abilities and playstyle from their gameplay, producing a player profile that an NPC can use to emulate that player. This is intended to capture measurable characteristics such as movement and reaction tendencies, route preferences, risk tolerance, monster interactions, ability usage and other demonstrated behaviours. The NPC should be able to reproduce the player's characteristic decision-making while remaining grounded in the same game-state and physics models as the baseline AI.

