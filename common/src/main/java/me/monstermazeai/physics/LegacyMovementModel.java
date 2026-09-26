package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Flat-ground Minecraft 1.8 player movement model.
 *
 * Constants and ordering mirror the 1.8 EntityLivingBase/EntityPlayer path
 * used by Monster Maze. Ordinary block collision is intentionally excluded
 * from this common model; Monster Maze's player surface is represented by the
 * physical floor graph.
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
        p.yaw += action.yawDelta();
        while (p.yaw >= 180.0F) p.yaw -= 360.0F;
        while (p.yaw < -180.0F) p.yaw += 360.0F;

        // EntityLivingBase.onLivingUpdate decrements an existing jump delay
        // before evaluating the current jump input.
        if (p.jumpTicks > 0) p.jumpTicks--;

        if (action.jump() && p.grounded && p.jumpTicks == 0) {
            jump(p, action.sprint());
            p.jumpTicks = 10;
        } else if (!action.jump()) {
            p.jumpTicks = 0;
        }

        // Vanilla decays movement input immediately before travel.
        double strafe = action.strafe() * 0.98D;
        double forward = action.forward() * 0.98D;

        float friction = p.grounded
                ? DEFAULT_SLIPPERINESS * LAND_FRICTION
                : LAND_FRICTION;

        float movementFactor;
        if (p.grounded) {
            movementFactor = (float) (WALK_SPEED
                    * (action.sprint() ? SPRINT_MULTIPLIER : 1.0F)
                    * (0.16277136F / (friction * friction * friction)));
        } else {
            // EntityPlayer raises jumpMovementFactor by 30% while sprinting.
            movementFactor = AIR_MOVE_FACTOR
                    * (action.sprint() ? (1.0F + 0.3F) : 1.0F);
        }

        moveFlying(p, strafe, forward, movementFactor);

        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        // EntityLivingBase applies gravity after movement, then air drag.
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
        double magnitude = strafe * strafe + forward * forward;
        if (magnitude < 1.0E-4D) return;

        magnitude = Math.sqrt(magnitude);
        if (magnitude < 1.0D) magnitude = 1.0D;

        double scale = friction / magnitude;
        strafe *= scale;
        forward *= scale;

        double yaw = Math.toRadians(p.yaw);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        p.vx += strafe * cos - forward * sin;
        p.vz += forward * cos + strafe * sin;
    }
}
