# AI architecture baseline

This branch establishes the perfect-control baseline before difficulty/tendency tuning is added.

## Control pipeline

1. **Live observation** — authoritative maze, physical floor, active Safe Pad, player physics, monsters, health, cooldown/charge state.
2. **Monster-aware route planner** — rebuilds from live state and scores routes by travel time plus predicted monster interception probability, expected damage, lethal risk and future corridor risk.
3. **Continuous movement controller** — converts the cell route into a continuous trajectory, cuts corners only when the swept path is physically safe, anticipates braking, and keeps the movement vector aligned with the route instead of chasing individual cell centres.
4. **Knockback recovery** — treats monster knockback as a first-class physics event. It predicts the post-hit trajectory, brakes/steers toward recoverable floor, suppresses dangerous sprint-jump impulses at exposed edges, and then returns control to route planning.
5. **Ability optimiser** — preserves scarce stage resources unless survival, deadline failure, or an immediate threat justifies spending them. Cooldown-based abilities are evaluated separately from charge-based abilities.
6. **Jump optimiser** — holding jump is part of the normal movement policy for non-Jumper kits as well as Jumper. Jumper charge consumption remains governed by the ability model; after charges are exhausted, ordinary repeated jump timing continues.
7. **Live executor** — emits one-tick Minecraft 1.8.9 inputs and replans continuously from the newest observation.

## Knockback policy

The AI is allowed to benefit from a monster hit when the predicted knockback moves it toward a safe route and the health cost is acceptable. It is never required to avoid every monster. Conversely, a hit near an edge immediately changes the control objective from route progress to survival/recovery.

The same physics simulator is used for movement forecasts, route-risk estimation and recovery decisions so these components share one model of acceleration, friction, jumping, knockback and collision.

## Future difficulty/personality layer

The perfect baseline should remain deterministic and skill-maximising. Future competitor profiles can tune the baseline rather than replacing it, for example:

- route skill
- movement/cornering skill
- monster prediction skill
- jump timing skill
- ability skill
- risk tolerance
- monster avoidance tendency
- ability conservation
- corner-cutting tendency
- jump frequency
- time-vs-safety preference

Real-player NPCs can use measured movement, damage, jump, sprint, turning and ability-use telemetry to populate those tendencies while retaining the same underlying objective and physics model.
