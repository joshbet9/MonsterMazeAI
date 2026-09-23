package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Flat-ground Minecraft 1.8 player movement model.
 *
 * This intentionally does NOT model walls, steps, liquids, ladders or block
 * collision. Monster Maze's relevant surface is a flat one-block platform;
 * collision with monsters is handled by CollisionModel.
 *
 * The tick order follows EntityLivingBase.onLivingUpdate:
 * jump -> input decay -> moveEntityWithHeading -> gravity/drag.
 */
public final class LegacyMovementModel implements PhysicsModel {
    private static final float DEFAULT_SLIPPERINESS = 0.6F;
    private static final float LAND_FRICTION = 0.91F;
    private static final float WALK_SPEED = 0.10F;
    private static final float SPRINT_MULTIPLIER = 1.30F;
    private static final float AIR_MOVE_FACTOR = 0.02F;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.9800000190734863D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_IMPULSE = 0.2D;

    @Override
    public void tick(PlayerState p, Action action) {
        // Rotation is part of the per-tick control input. Minecraft applies
        // the player's current yaw to movement, so the requested delta is
        // committed before travel for this tick.
        p.yaw += action.yawDelta();
        while (p.yaw >= 180.0F) p.yaw -= 360.0F;
        while (p.yaw < -180.0F) p.yaw += 360.0F;

        // EntityLivingBase jump handling: holding jump does not repeatedly
        // jump every tick; jumpTicks is set to 10 after a ground jump.
        if (action.jump()) {
            if (p.grounded && p.jumpTicks == 0) {
                jump(p, action.sprint());
                p.jumpTicks = 10;
            }
        } else {
            p.jumpTicks = 0;
        }

        // onLivingUpdate decays the movement inputs before travel.
        double strafe = action.strafe() * 0.98D;
        double forward = action.forward() * 0.98D;

        float friction = p.grounded
                ? DEFAULT_SLIPPERINESS * LAND_FRICTION
                : LAND_FRICTION;

        float movementFactor = p.grounded
                ? (float) (WALK_SPEED
                    * (action.sprint() ? SPRINT_MULTIPLIER : 1.0F)
                    * (0.16277136F / (friction * friction * friction)))
                : AIR_MOVE_FACTOR;

        moveFlying(p, strafe, forward, movementFactor);

        // Entity.moveEntity would normally resolve the displacement against
        // block AABBs. Monster Maze has no steps or ordinary wall geometry
        // relevant to this player model, so the flat surface integrates
        // directly.
        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        // This is the post-move part of EntityLivingBase.moveEntityWithHeading.
        if (!p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;

            if (p.y <= 0.0D) {
                p.y = 0.0D;
                p.vy = 0.0D;
                p.grounded = true;
            }
        } else {
            p.y = 0.0D;
            p.vy = 0.0D;
        }

        p.vx *= friction;
        p.vz *= friction;

        if (Math.abs(p.vx) < 0.005D) p.vx = 0.0D;
        if (Math.abs(p.vy) < 0.005D) p.vy = 0.0D;
        if (Math.abs(p.vz) < 0.005D) p.vz = 0.0D;

        if (p.jumpTicks > 0) p.jumpTicks--;
    }

    private void jump(PlayerState p, boolean sprinting) {
        p.vy = JUMP_VELOCITY;
        p.grounded = false;

        if (sprinting) {
            float yaw = p.yaw * 0.017453292F;
            p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
            p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
        }
    }

    private void moveFlying(PlayerState p, double strafe, double forward, float friction) {
        double magnitude = Math.hypot(strafe, forward);
        if (magnitude < 1.0E-4D) return;

        if (magnitude < 1.0D) magnitude = 1.0D;

        strafe /= magnitude;
        forward /= magnitude;

        double yaw = Math.toRadians(p.yaw);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        p.vx += (strafe * cos - forward * sin) * friction;
        p.vz += (forward * cos + strafe * sin) * friction;
    }
}
