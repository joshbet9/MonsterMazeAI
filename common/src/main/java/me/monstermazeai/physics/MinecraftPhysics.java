package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Vanilla Minecraft 1.8-style horizontal movement model.
 *
 * The previous model applied the ground acceleration constant directly
 * (0.16277136 / friction^3), omitting getAIMoveSpeed(). In 1.8 that value is
 * multiplied by the player's movement attribute (normally 0.1), producing
 * roughly 0.1 input acceleration before ground drag. It also ignored yaw when
 * converting forward/strafe input into world X/Z motion. Both errors made the
 * planner forecast the player moving in the wrong direction and/or far too
 * quickly, causing valid live actions to be rejected as unsafe.
 */
public final class MinecraftPhysics implements PhysicsModel {
    private static final double BASE_AI_MOVE_SPEED = 0.10D;
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG_Y = 0.98D;
    private static final double HORIZONTAL_DRAG = 0.91D;
    private static final double GROUND_SLIPPERINESS = 0.60D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_IMPULSE = 0.20D;

    @Override
    public void tick(PlayerState p, Action a) {
        // Action yaw is a control input. The live executor applies the same
        // rotation before Minecraft consumes the movement keys.
        p.yaw = wrapYaw(p.yaw + a.yawDelta());

        if (a.jump() && p.grounded) {
            p.vy = JUMP_VELOCITY;
            p.grounded = false;
            if (a.sprint()) {
                double yaw = Math.toRadians(p.yaw);
                p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
            }
        }

        double strafe = a.strafe();
        double forward = a.forward();
        double inputLen = Math.hypot(strafe, forward);
        if (inputLen > 1.0) {
            strafe /= inputLen;
            forward /= inputLen;
        }

        /*
         * Mirrors EntityLivingBase.moveEntityWithHeading / moveFlying:
         *
         * f4 = slipperiness * 0.91
         * ground input factor = getAIMoveSpeed() * 0.16277136 / f4^3
         * air input factor = 0.02
         *
         * Then moveFlying rotates that local vector by rotationYaw and
         * position integration happens before horizontal drag.
         */
        double friction = 0.02D;
        if (p.grounded) {
            double groundFriction = GROUND_SLIPPERINESS * HORIZONTAL_DRAG;
            double factor = 0.16277136D / (groundFriction * groundFriction * groundFriction);
            friction = BASE_AI_MOVE_SPEED * factor;
        }

        // EntityLivingBase adds 30% of the normal input factor while sprinting.
        if (a.sprint()) {
            friction += (p.grounded ? BASE_AI_MOVE_SPEED : 0.02D) * 0.30D;
        }

        double inputMagnitude = Math.hypot(strafe, forward);
        if (inputMagnitude >= 1.0E-4D) {
            double scale = friction / Math.max(1.0D, inputMagnitude);
            strafe *= scale;
            forward *= scale;

            double yaw = Math.toRadians(p.yaw);
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);

            // Exact Minecraft 1.8 moveFlying transform.
            p.vx += strafe * cos - forward * sin;
            p.vz += forward * cos + strafe * sin;
        }

        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        if (!p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG_Y;
        }

        // Horizontal motion is dragged after movement integration.
        p.vx *= HORIZONTAL_DRAG;
        p.vz *= HORIZONTAL_DRAG;

        if (p.y <= 0.0D) {
            p.y = 0.0D;
            p.vy = 0.0D;
            p.grounded = true;
        } else {
            p.grounded = false;
        }

        if (Math.abs(p.vx) < 0.005D) p.vx = 0.0D;
        if (Math.abs(p.vy) < 0.005D) p.vy = 0.0D;
        if (Math.abs(p.vz) < 0.005D) p.vz = 0.0D;
    }

    private static float wrapYaw(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }
}
