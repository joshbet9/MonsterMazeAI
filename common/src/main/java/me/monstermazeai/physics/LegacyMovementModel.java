package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Minecraft 1.8-style land movement prediction.
 * Based on EntityLivingBase.moveEntityWithHeading / jump ordering.
 */
public final class LegacyMovementModel implements PhysicsModel {
    private static final float DEFAULT_SLIPPERINESS = 0.6F;
    private static final float LAND_FRICTION = 0.91F;
    private static final float JUMP_MOVEMENT_FACTOR = 0.02F;
    private static final float WALK_SPEED = 0.1F;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG = 0.9800000190734863D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_IMPULSE = 0.2D;

    @Override
    public void tick(PlayerState p, Action action) {
        if (action.jump() && p.grounded) jump(p, action.sprint());

        float friction = p.grounded
                ? DEFAULT_SLIPPERINESS * LAND_FRICTION
                : LAND_FRICTION;

        float movementFactor = p.grounded
                ? (float)(WALK_SPEED * (0.16277136F /
                    (friction * friction * friction)))
                : JUMP_MOVEMENT_FACTOR;

        moveFlying(p, action.strafe(), action.forward(), movementFactor);

        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        p.vx *= friction;
        p.vz *= friction;

        if (!p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;
            if (p.y <= 0.0) {
                p.y = 0.0;
                p.vy = 0.0;
                p.grounded = true;
            }
        } else {
            p.y = 0.0;
        }

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
        double magnitude = Math.hypot(strafe, forward);
        if (magnitude < 1.0E-4) return;
        if (magnitude < 1.0) magnitude = 1.0;

        strafe /= magnitude;
        forward /= magnitude;

        double yaw = Math.toRadians(p.yaw);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        p.vx += (strafe * cos - forward * sin) * friction;
        p.vz += (forward * cos + strafe * sin) * friction;
    }
}
