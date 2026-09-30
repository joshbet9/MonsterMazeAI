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
    /**
     * Minecraft 1.8.9 retains the small downward motion value while the player
     * is standing on a solid block. Human-run telemetry repeatedly records
     * this exact value on grounded ticks.
     */
    private static final double GROUND_VERTICAL_MOTION = -0.0784000015258789D;
    private static final int NON_JUMPER_JUMP_COOLDOWN_TICKS = 10;

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
                /*
                 * Monster Maze's non-Jumper jump effect suppresses vertical
                 * lift, but the human traces show the normal 1.8 sprint-jump
                 * horizontal impulse on the jump press. The same traces show
                 * that holding Space does not inject that impulse every tick,
                 * so preserve the vanilla-style jump cooldown state.
                 */
                if (action.sprint()) {
                    float yaw = p.yaw * 0.017453292F;
                    p.vx -= Math.sin(yaw) * SPRINT_JUMP_IMPULSE;
                    p.vz += Math.cos(yaw) * SPRINT_JUMP_IMPULSE;
                }
                p.jumpTicks = NON_JUMPER_JUMP_COOLDOWN_TICKS;
            } else {
                p.vy = JUMP_VELOCITY + (jumpAmplifier > 0 ? ((jumpAmplifier + 1) * 0.1D) : 0.0D);
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

        /*
         * Entity.move() resolves the player's 0.6-wide AABB against the actual
         * maze blocks before the post-move gravity step. This matters at maze
         * edges: horizontal motion is clipped by a neighbouring solid block,
         * while a jump can still cross an air gap because the player's feet
         * are above the block top.
         */
        if (maze != null && p.y < 0.0 && p.y > -0.5 && p.vy <= 0.0
                && hasPhysicalFloor(maze, p.x, p.z)) {
            p.y = 0.0;
            p.vy = 0.0;
            p.grounded = true;
        }

        if (maze != null) {
            MazeCollision collision = new MazeCollision(maze);
            collision.move(p, p.vx, p.vy, p.vz);
        } else {
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
        }

        boolean supported = hasPhysicalFloor(maze, p.x, p.z);
        boolean landed = supported && p.y <= 0.0D + 1.0E-9D && p.vy <= 0.0D;
        if (landed) {
            p.y = 0.0D;
            p.vy = 0.0D;
            p.grounded = true;
        } else {
            p.grounded = false;
        }

        // A collision landing zeroes vertical velocity; do not immediately
        // apply another gravity step in the same tick. The falling tick itself
        // is represented by MazeCollision.move() setting grounded=true.
        if (!p.grounded) {
            p.vy -= GRAVITY;
            p.vy *= AIR_DRAG;
        } else {
            /*
             * Keep the observed grounded motionY. This is part of the 1.8
             * entity state even though the player's feet remain on the floor.
             */
            p.vy = GROUND_VERTICAL_MOTION;
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

        // Player width in 1.8 is 0.6 blocks. Ground support exists while any
        // part of the player's horizontal AABB overlaps a physical floor block.
        final double halfWidth = 0.30D;
        final double minX = x - halfWidth;
        final double maxX = x + halfWidth;
        final double minZ = z - halfWidth;
        final double maxZ = z + halfWidth;
        int minRow = (int) Math.floor(minX);
        int maxRow = (int) Math.floor(Math.nextDown(maxX));
        int minCol = (int) Math.floor(minZ);
        int maxCol = (int) Math.floor(Math.nextDown(maxZ));

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                if (maze.isPhysicalFloor(row, col)) return true;
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
