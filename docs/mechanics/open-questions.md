# Monster Maze AI — Open Mechanics Questions

The source extraction gives us a strong baseline. The remaining questions below are where your in-game observations are especially valuable, because they determine what an actually optimal player/AI can do rather than what the server code appears to intend.

## Priority 1 — 1.8 movement

### Legacy 'speeding' behaviour
We need an exact description of what happens when a non-Jumper (or a Jumper with no charges) repeatedly presses/holds jump.

Please test:
- standing still + spam Space
- running + hold Space
- running + rapid Space presses
- approaching a 1-block gap + hold Space
- approaching a wider gap + hold Space
- whether yaw changes the resulting movement
- whether behaviour differs on ground vs immediately after landing.

Approximate blocks/second is useful; tick-by-tick position is even better.

### Jumper
- Can Space be held continuously and consume charges automatically?
- Does the client jump immediately on landing?
- Difference between one press and holding?
- Can a charge be consumed while already airborne?
- What happens when the server's 750 ms charge interval has not elapsed?

## Priority 2 — monster knockback

This is likely one of the most important optimisation mechanics.

With a controlled monster, test collisions while:
- moving directly toward it
- moving sideways across it
- moving away
- approaching at different angles
- near a wall/corner
- near a gap
- immediately after jumping
- while falling.

For each, record player position before collision, approximate post-hit velocity/trajectory, landing position, airborne duration, and whether the player can steer or jump during the knockback.

### Maverick
Repeat the tests with Maverick. Establish whether the horizontal direction is exactly toward the Safe Pad centre, how much air control remains, and whether monster hits can deliberately cross gaps.

## Priority 3 — monster behaviour

The source says monsters choose random legal cardinal directions at intersections and avoid immediate U-turns when another option exists.

Useful tests:
- place one monster at a known intersection and record successive directions
- repeat from the same starting state if possible
- determine whether monsters are independent or correlated
- determine whether player position affects their route
- measure actual blocks/tick
- compare 1.8 and 1.21.

If their paths are effectively random walks, the AI can maintain a probabilistic future occupancy map.

## Priority 4 — collision geometry

Move toward a stationary monster in small increments and establish the practical contact boundary. Also determine whether vertical separation changes it and whether multiple monsters can produce contacts in consecutive ticks.

## Priority 5 — abilities

### Repulsor
Measure radius, number of monsters affected, launch distance, edge/gap behaviour, and whether a launched monster can still contact the player.

### Cryo Blitz
Measure affected radius, cooldown start behaviour, whether frozen monsters retain their route state, and what happens when they thaw.

### Body Rush
Measure activation timing, whether rapid contacts consume multiple 2-second chunks, and the exact monster launch trajectory.

## Priority 6 — Safe Pad optimisation

Test whether first arrival materially changes the remaining timer at each stage, whether leaving the pad after touching it is safe, whether standing on it prevents monster bumps, and the exact timing of preview-pad appearance.

An important AI insight is that reaching a Safe Pad early can shorten the phase, so the planner should optimise total run time rather than simply distance to the current objective.

## Priority 7 — maze coordinate validation

The three patterns are already embedded in the source. We need to validate how layout coordinates map to the actual playable floor.

Useful evidence:
- overhead screenshot of each pattern
- arena centre coordinate
- one known path-cell coordinate
- one known wall-cell coordinate
- one known Safe Pad coordinate.

## How we will use the measurements

Observations will become one of four things:
- confirmed rule
- physics parameter
- probability distribution
- unresolved uncertainty.

This is important because the eventual planner should be able to reason about something like: a monster hit costs a certain number of ticks of control but saves more ticks of corridor travel, so the collision route becomes a legitimate candidate.