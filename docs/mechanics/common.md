# Monster Maze AI — Common Mechanics Model

## Static maze
- 99×99 grid; three fixed layouts.
- 0 = empty; 1 = path; 2 = path + spawn; 3/4 = center; 5/6 = center + path; 4/6 = barrier.
- Both versions map layout row/column to world X/Z with a 49-cell half-width offset; path positions are block centres at maze center Y.

## Dynamic graph
Raw topology and current traversability are separate. Safe Pad areas, center deterioration and explicit waypoint disabling can temporarily remove cells from the monster graph. The AI therefore maintains raw topology, current traversability and physical collision geometry.

## Monsters
Monster Maze owns navigation. Each monster stores its previous location, target waypoint and cardinal direction. At a waypoint within 0.4 blocks, it collects cardinal neighbouring path cells, removes an immediate reverse direction when alternatives exist, and randomly chooses the next legal cell. Directions are independent; player position does not choose the route.

This is a stochastic random-walk occupancy problem, not conventional enemy pathfinding.

## Collision as movement
Normal monster contact launches the player horizontally away from the monster, with strength 1.0, vertical add 0.75, vertical cap 1.2 and grounded bonus +0.2, and deals 4 HP with a 1 second player hit cooldown. Only one monster produces the knockback at a time. Safe Pad players are immune.

The planner must consider both avoiding monsters and intentionally approaching one from a chosen direction to obtain useful knockback. Every kit can exploit this movement primitive.

Maverick, in enhanced modes, redirects the player launch toward the active Safe Pad (or preview pad when appropriate), making the collision primitive pad-directed.

## Launched monsters
Repulsor and Body Rush launch monsters away from the player and stop normal monster navigation. A launched monster cannot hit the player during the launch and is later removed after landing/timeout. These abilities can change local occupancy and create routes through dense areas.

## Safe Pads and progression
Safe Pads are 5×5 checkpoint surfaces. First arrival can shorten the phase; all alive players on the active pad can reduce the remaining phase to 4 seconds. Preview appears around 2 seconds remaining. Enhanced modes refill Jumper to 3 charges and make pad jumps free.

For solo AI, the primary objective is highest Safe Pad/stage reached. Time remains strategically important because faster transitions mean fewer subsequent monster spawns.

## Planner state
Minimum state: tick, position, velocity, yaw/pitch, grounded state, maze cell, target pad, stage, phase timer, health/max health, jump charges, ability resources/cooldowns, monster states and disabled cells.

The common planner searches semantic actions rather than raw keypress strings: movement, turn, jump, spam-jump, deliberate monster approach, collision acceptance, avoidance, ability use and wait.

Platform adapters own Minecraft-specific player physics: legacy 1.8 movement versus modern 1.21 movement. The common simulator owns game semantics and replanning.
