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
        tick(p, action, null);
    }

    /**
     * Same 1.8 movement model with optional physical-floor support.
     * A maze-aware tick lets knockback carry an airborne player over an edge
     * while still allowing recovery before the player actually falls.
     */
    public void tick(PlayerState p, Action action, me.monstermazeai.maze.MazeModel maze) {
        p.yaw += action.yawDelta();
        while (p.yaw >= 180.0F) p.yaw -= 360.0F;
        while (p.yaw < -180.0F) p.yaw += 360.0F;

        boolean groundedAtStart = p.grounded;
        float friction = groundedAtStart ? SLIPPERINESS * GROUND_FRICTION : GROUND_FRICTION;

        if (p.jumpTicks > 0) p.jumpTicks--;
        if (!action.jump()) p.jumpTicks = 0;

        if (action.jump() && groundedAtStart && p.jumpTicks == 0) {
            p.vy = JUMP_VELOCITY;
            p.grounded = false;
            if (action.sprint()) {
                float yaw = p.yaw * 0.017453292F;
                p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
            }
            p.jumpTicks = 10;
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

        // EntityLivingBase.onLivingUpdate() damps both movement inputs before
        // moveEntityWithHeading(). This is part of the authoritative 1.8.9
        // movement path, not a controller-side tuning factor.
        moveFlying(p, action.strafe() * 0.98, action.forward() * 0.98, movementFactor);

        // The source client resolves movement before the post-move gravity/
        // drag update. The common maze has no side walls, so Y is the only
        // continuous collision axis here.
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
            // The player walked/slid off the physical maze while still at
            // floor height. Let gravity take over instead of inventing a
            // floor underneath the void.
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

        // Match Minecraft's AxisAlignedBB/block collision semantics: the
        // player's 0.6-block footprint is supported when it has any positive
        // X/Z overlap with a physical floor block. Do not substitute corner
        // samples or an arbitrary minimum overlap area.
        final double halfWidth = 0.30;
        final double minX = x - halfWidth;
        final double maxX = x + halfWidth;
        final double minZ = z - halfWidth;
        final double maxZ = z + halfWidth;

        int minRow = (int) Math.floor(minX);
        int maxRow = (int) Math.floor(maxX - 1.0E-12);
        int minColumn = (int) Math.floor(minZ);
        int maxColumn = (int) Math.floor(maxZ - 1.0E-12);

        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (!maze.isPhysicalFloor(row, column)) continue;

                double cellMinX = row;
                double cellMaxX = row + 1.0;
                double cellMinZ = column;
                double cellMaxZ = column + 1.0;
                double overlapX = Math.min(maxX, cellMaxX) - Math.max(minX, cellMinX);
                double overlapZ = Math.min(maxZ, cellMaxZ) - Math.max(minZ, cellMinZ);
                if (overlapX > 0.0 && overlapZ > 0.0) return true;
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
