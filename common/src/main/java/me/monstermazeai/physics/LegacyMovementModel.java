package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/** Minecraft 1.8 EntityLivingBase movement ordering on the Monster Maze floor. */
public final class LegacyMovementModel implements PhysicsModel {
    private static final float SLIPPERINESS = 0.6F;
    private static final float GROUND_FRICTION = 0.91F;
    private static final float WALK_SPEED = 0.10F;
    private static final float SPRINT_MULTIPLIER = 1.30F;
    private static final float AIR_MOVE_FACTOR = 0.02F;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.9800000190734863D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_IMPULSE = 0.2D;

    @Override
    public void tick(PlayerState p, Action action) {
        tick(p, action, null, 0);
    }

    /**
     * Same 1.8 movement model with optional physical-floor support.
     * A maze-aware tick lets knockback carry an airborne player over an edge
     * while still allowing recovery before the player actually falls.
     */
    public void tick(PlayerState p, Action action, me.monstermazeai.maze.MazeModel maze) {
        tick(p, action, maze, 0);
    }

    /**
     * Same movement with the source MonsterMaze jump effect amplifier. An
     * amplifier of -10 is the kit jump lock: it prevents vertical jumping but
     * deliberately preserves the sprint jump's horizontal impulse used by the
     * non-Jumper jump-spam speed technique.
     */
    public void tick(PlayerState p, Action action, me.monstermazeai.maze.MazeModel maze,
                     int jumpAmplifier) {
        p.yaw += action.yawDelta();
        while (p.yaw >= 180.0F) p.yaw -= 360.0F;
        while (p.yaw < -180.0F) p.yaw += 360.0F;

        boolean groundedAtStart = p.grounded;
        float friction = groundedAtStart ? SLIPPERINESS * GROUND_FRICTION : GROUND_FRICTION;

        if (action.jump() && groundedAtStart && p.jumpTicks == 0) {
            if (jumpAmplifier <= -2) {
                // Monster Maze applies Jump -10 to non-Jumpers. That blocks the
                // vertical impulse but the sprint-jump's horizontal impulse is
                // still applied by the source jump routine.
                p.vy = 0.0D;
                if (action.sprint()) {
                    float yaw = p.yaw * 0.017453292F;
                    p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                    p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
                }
                p.jumpTicks = 0;
            } else {
                p.vy = JUMP_VELOCITY + ((jumpAmplifier + 1) * 0.1D);
                p.grounded = false;
                if (action.sprint()) {
                    float yaw = p.yaw * 0.017453292F;
                    p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                    p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
                }
                p.jumpTicks = 10;
            }
        } else if (!action.jump()) {
            p.jumpTicks = 0;
        } else if (p.jumpTicks > 0) {
            p.jumpTicks--;
        }

        float movementFactor;
        if (groundedAtStart) {
            movementFactor = WALK_SPEED
                    * (action.sprint() ? SPRINT_MULTIPLIER : 1.0F)
                    * (0.16277136F / (friction * friction * friction));
        } else {
            movementFactor = AIR_MOVE_FACTOR
                    * (action.sprint() ? SPRINT_MULTIPLIER : 1.0F);
        }

        moveFlying(p, action.strafe(), action.forward(), movementFactor);

        // Minecraft resolves the entity movement before post-move gravity/drag.
        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        if (!groundedAtStart || p.pendingAirborne || !p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;
            if (p.y <= 0.0 && p.vy <= 0.0 && hasPhysicalFloor(maze, p.x, p.z)) {
                p.y = 0.0;
                p.vy = 0.0;
                p.grounded = true;
            } else {
                p.grounded = false;
            }
        } else if (hasPhysicalFloor(maze, p.x, p.z)) {
            p.y = 0.0;
            p.vy = 0.0;
            p.grounded = true;
        } else {
            // The player walked/was pushed off the physical maze at floor
            // height. Do not invent support beneath the void.
            p.grounded = false;
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;
        }

        p.pendingAirborne = false;
        p.vx *= friction;
        p.vz *= friction;

        if (Math.abs(p.vx) < 0.005) p.vx = 0;
        if (Math.abs(p.vy) < 0.005) p.vy = 0;
        if (Math.abs(p.vz) < 0.005) p.vz = 0;
    }

    private static boolean hasPhysicalFloor(me.monstermazeai.maze.MazeModel maze, double x, double z) {
        if (maze == null) return true;

        // Minecraft's player has width, so keep support while any of the
        // central hitbox samples still overlap a physical floor cell.
        final double halfWidth = 0.30;
        double[] xs = {x - halfWidth, x + halfWidth};
        double[] zs = {z - halfWidth, z + halfWidth};
        for (double sampleX : xs) {
            for (double sampleZ : zs) {
                if (maze.isPhysicalFloor((int)Math.floor(sampleX), (int)Math.floor(sampleZ))) return true;
            }
        }
        return false;
    }

    private static void moveFlying(PlayerState p, double strafe, double forward, float factor) {
        double magnitude = strafe * strafe + forward * forward;
        if (magnitude < 1.0E-4) return;
        magnitude = Math.sqrt(magnitude);
        if (magnitude < 1.0) magnitude = 1.0;
        double scale = factor / magnitude;
        strafe *= scale;
        forward *= scale;

        double yaw = Math.toRadians(p.yaw);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        p.vx += strafe * cos - forward * sin;
        p.vz += forward * cos + strafe * sin;
    }
}
