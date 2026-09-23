package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Vanilla-style land movement equations used by the prediction simulator.
 * Collision resolution remains version/client specific.
 */
public final class MinecraftPhysics implements PhysicsModel {
    private static final double GRAVITY = 0.08D;
    private static final double AIR_DRAG_Y = 0.98D;
    private static final double AIR_FRICTION = 0.91D;
    private static final double GROUND_SLIPPERINESS = 0.60D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_IMPULSE = 0.20D;

    @Override
    public void tick(PlayerState p, Action a) {
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

        // The exact 1.8/1.21 input acceleration and collision handling are
        // supplied by version adapters; this preserves the vanilla velocity
        // integration order for the common land model.
        double acceleration = p.grounded
                ? 0.16277136D / Math.pow(GROUND_SLIPPERINESS * AIR_FRICTION, 3)
                : 0.02D;
        p.vx += strafe * acceleration;
        p.vz += forward * acceleration;

        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        if (!p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG_Y;
            p.vx *= AIR_FRICTION;
            p.vz *= AIR_FRICTION;
            if (p.y <= 0.0) {
                p.y = 0.0;
                p.vy = 0.0;
                p.grounded = true;
            }
        } else {
            double groundFriction = GROUND_SLIPPERINESS * AIR_FRICTION;
            p.vx *= groundFriction;
            p.vz *= groundFriction;
            p.y = 0.0;
        }

        if (Math.abs(p.vx) < 0.005) p.vx = 0.0;
        if (Math.abs(p.vy) < 0.005) p.vy = 0.0;
        if (Math.abs(p.vz) < 0.005) p.vz = 0.0;
    }
}