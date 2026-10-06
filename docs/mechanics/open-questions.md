# Monster Maze AI — Open Mechanics Questions

The source extraction gives us a strong baseline. The remaining questions below are where your in-game observations are especially valuable, because they determine what an actually optimal player/AI can do rather than what the server code appears to intend.

## Priority 1 — 1.8 movement

### Legacy 'speeding' behaviour
We need an exact description of what happens when a non-Jumper (or a Jumper with no charges) repeatedly presses/holds jump.

Please test:
- standing still + spam Space  (nothing happens)
- running + hold Space  (moves at normal speed and gets a slight momentum boost every second or so
- running + rapid Space presses (moves at great speed, guessing around x1.5, depends how quickly spacebar is pressed though)
- approaching a 1-block gap + hold Space (pressing space gives you momentum to go over one block gaps if timed correctly)
- approaching a wider gap + hold Space (it can only cross a one block gap if timed well, nothing larger)
- whether yaw changes the resulting movement (unsure what this means)
- whether behaviour differs on ground vs immediately after landing. (unsure what this means, if its asked because in 1.21 there's a delay in speed it was just because i wasnt sure how speed interacted in the air)

Approximate blocks/second is useful; tick-by-tick position is even better.

### Jumper
- Can Space be held continuously and consume charges automatically? (just like in vanilla minecraft yes)
- Does the client jump immediately on landing? (jumping occurs when a player is grounded and they press jump)
- Difference between one press and holding? (yes, if its held the player will continuously jump until they are out of jumps)
- Can a charge be consumed while already airborne? (no, at least not intentionally)
- What happens when the server's 750 ms charge interval has not elapsed? (unsure what this means)

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

(only thing to say to this is if you hit the sides of mobs, especially if you "speed/spam jump" into a mob your kb can be very limited However in 95% of cases and 100% of cases if you hit a mob it'll just hit you back the way you went into it

### Maverick
Repeat the tests with Maverick. Establish whether the horizontal direction is exactly toward the Safe Pad centre, how much air control remains, and whether monster hits can deliberately cross gaps.

(maverick always sends towards the safe pad and im guessing likely the centre but not sure. one thing thats important for your knowledge is that every kit can utilise mob hits to get out of areas of high density or get sent towards the pad. A player would just hit the mob from a specific direction to get the right KB)

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

(MOB directions are random and are independent, in both versions. also the speed in 1.21 is the same as 1.8, even if it says its less)

## Priority 4 — collision geometry

Move toward a stationary monster in small increments and establish the practical contact boundary. Also determine whether vertical separation changes it and whether multiple monsters can produce contacts in consecutive ticks.

(only one monster causes kb at once)

## Priority 5 — abilities

### Repulsor
Measure radius, number of monsters affected, launch distance, edge/gap behaviour, and whether a launched monster can still contact the player.

(launched monster cant hit player)

### Cryo Blitz
Measure affected radius, cooldown start behaviour, whether frozen monsters retain their route state, and what happens when they thaw.

(monsters start moving again as normal after thawing)

### Body Rush
Measure activation timing, whether rapid contacts consume multiple 2-second chunks, and the exact monster launch trajectory.

(launch is same logic as repulsor im pretty sure, rapid contacts can consume additional it depends on how rapid)

## Priority 6 — Safe Pad optimisation

Test whether first arrival materially changes the remaining timer at each stage, whether leaving the pad after touching it is safe, whether standing on it prevents monster bumps, and the exact timing of preview-pad appearance.

An important AI insight is that reaching a Safe Pad early can shorten the phase, so the planner should optimise total run time rather than simply distance to the current objective.

(total run time is irrelevant, highest safe pad is relevant. however the quicker the game goes the less mobs spawn by certain pad. So it's still worth getting to safe pads as soon as possible, as long as it doesnt put your health in a predicament)

## Priority 7 — maze coordinate validation

The three patterns are already embedded in the source. We need to validate how layout coordinates map to the actual playable floor.

Useful evidence:
- overhead screenshot of each pattern
- arena centre coordinate
- one known path-cell coordinate
- one known wall-cell coordinate
- one known Safe Pad coordinate.

(you can access all this from the source repo)

## How we will use the measurements

Observations will become one of four things:
- confirmed rule
- physics parameter
- probability distribution
- unresolved uncertainty.

This is important because the eventual planner should be able to reason about something like: a monster hit costs a certain number of ticks of control but saves more ticks of corridor travel, so the collision route becomes a legitimate candidate.
