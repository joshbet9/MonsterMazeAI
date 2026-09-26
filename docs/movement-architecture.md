# Monster Maze AI movement architecture

## Problem

The previous 1.8 adapter drove movement by calling `KeyBinding.setKeyBindState()` at the end of the client tick. That is not the authoritative movement-input boundary: vanilla reconstructs `MovementInputFromOptions` from the physical keyboard during `EntityPlayerSP.onLivingUpdate()`. Synthetic key state can therefore be replaced by the next keyboard read, producing one-tick or human-input-dependent movement.

## New control path

The live path is now:

```
observation
  -> sidecar planner/controller
  -> LegacyAction
  -> Minecraft18ActionExecutor (stores command)
  -> Minecraft18MovementInput.updatePlayerMoveState()
  -> EntityPlayerSP movement/physics
```

`Minecraft18MovementInput` subclasses the vanilla `MovementInputFromOptions`. It first lets vanilla read the physical keyboard, then, when AI control is enabled, replaces forward/strafe/jump with the current AI command and applies the commanded yaw before vanilla converts the input into movement.

This means the AI owns the actual movement-input state for the tick. Physical WASD is no longer a competing control path while AI mode is enabled.

## Timing

The sidecar decision is still computed from the latest observed state at client-tick END. The resulting command is retained and consumed by the next vanilla movement-input update. This is deliberately one closed-loop tick behind observation rather than attempting to mutate movement after Minecraft has already simulated the tick.

## Physics contract

Common `Action` now clamps yaw changes to the same 30-degree-per-tick limit enforced by the legacy client bridge. This keeps planner simulation and live execution consistent instead of allowing the simulator to turn farther than the client can turn.

## Success criteria

The first validation milestone is intentionally below "optimal AI":

1. AI moves continuously without human keyboard assistance.
2. AI can leave the starting Safe Pad.
3. AI reaches the first active Safe Pad autonomously.
4. Human WASD does not create or sustain AI movement.
5. Runtime traces show commanded movement and observed position change.

Only after this motor-level contract is reliable should route optimality, monster avoidance, ability timing, and competitor modelling be tuned.

## 2026-09-26 stability review

The Mineplex source implementation was reviewed against the live controller. The important source facts are:

- SafePad.isOn is a geometric 5x5-area check around the pad location; the current target should therefore be the pad centre, not an individual beacon block.
- MonsterMaze.spawnSafePad temporarily marks the entire pad area as disabled for monster routing, but players can still stand on it. Player routing therefore remains physical-floor based and must not inherit the monster waypoint restriction.
- The server's monster movement is cardinal waypoint-to-waypoint movement and monsters are removed/respawned when launched by Repulsor. Route risk is therefore dynamic, but the player's low-level steering must not change target every tick just because risk estimates move.
- Minecraft 1.8 movement is acceleration plus horizontal inertia. MovementInputFromOptions is the real input boundary and EntityLivingBase applies the movement input with friction/inertia after the movement step. A stable heading controller is therefore more appropriate than alternating forward/strafe/reverse commands around a cached target.

### Live-control invariant

The live motor path is now:

observed player state -> monster-aware physical route -> committed waypoint -> heading error -> forward-only steering -> Minecraft MovementInput -> vanilla physics

The controller deliberately uses one authoritative movement dimension (forward) and rotates the player toward the waypoint. It does not use strafe to compensate for a simultaneous yaw command. It also holds a waypoint until it is reached/passed, and only replans early for meaningful route deviation or an imminent monster threat. Near a waypoint it removes drive and lets vanilla friction brake the player instead of issuing reverse commands.

This is the intended foundation for later ability optimisation and competitor/NPC modelling: tactical decisions may change, but the motor layer must remain temporally stable and physically grounded.
