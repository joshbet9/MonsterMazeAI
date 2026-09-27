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
        p.yaw += action.yawDelta();
        while (p.yaw >= 180.0F) p.yaw -= 360.0F;
        while (p.yaw < -180.0F) p.yaw += 360.0F;

        boolean groundedAtStart = p.grounded;
        float friction = groundedAtStart ? SLIPPERINESS * GROUND_FRICTION : GROUND_FRICTION;

        if (action.jump() && groundedAtStart && p.jumpTicks == 0) {
            p.vy = JUMP_VELOCITY;
            p.grounded = false;
            if (action.sprint()) {
                float yaw = p.yaw * 0.017453292F;
                p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
            }
            p.jumpTicks = 10;
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

        // The source client resolves movement before the post-move gravity/
        // drag update. The common maze has no side walls, so Y is the only
        // continuous collision axis here.
        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;

        if (!groundedAtStart || p.pendingAirborne || !p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;
            if (p.y <= 0.0 && p.vy <= 0.0) {
                p.y = 0.0;
                p.vy = 0.0;
                p.grounded = true;
            } else {
                p.grounded = false;
            }
        } else {
            p.y = 0.0;
            p.vy = 0.0;
            p.grounded = true;
        }

        p.pendingAirborne = false;
        p.vx *= friction;
        p.vz *= friction;

        if (Math.abs(p.vx) < 0.005) p.vx = 0;
        if (Math.abs(p.vy) < 0.005) p.vy = 0;
        if (Math.abs(p.vz) < 0.005) p.vz = 0;
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
