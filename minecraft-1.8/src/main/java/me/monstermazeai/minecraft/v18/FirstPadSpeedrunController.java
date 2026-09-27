package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Isolated pad-to-pad speedrun benchmark:
 * maze + player + active pad -> shortest physical-floor route -> W+sprint+jump.
 *
 * After reaching a pad, the controller deliberately waits in place while the
 * server countdown runs. When the active pad changes at the round transition,
 * it rebuilds the shortest route from the player's current position and runs
 * to the new pad.
 *
 * No sidecar, async planner, monster logic, abilities, recovery, replanning,
 * or strafe input. The controller runs synchronously on the Minecraft thread.
 */
public final class FirstPadSpeedrunController {
    private static final int SIZE = 99;
    private static final int PAD_RADIUS = 2;
    private static final float MAX_YAW_STEP = 30.0F;
    private static final float ALIGNMENT_TOLERANCE = 10.0F;
    private static final int LOOKAHEAD_CELLS = 3;
    // Monster Maze source bumps when player/monster positions overlap within
    // 1 block in all three dimensions. We use a small horizontal safety margin
    // because the observer is sampled once per client tick and both entities
    // can move between observations.
    private static final double MOB_HAZARD_RADIUS = 1.25D;
    private static final double MOB_PREDICT_SECONDS = 0.35D;
    private static final int MOB_DODGE_CELLS = 1;
    private static final float MOB_DODGE_MAX_YAW = 38.0F;

    private int[] routeRows;
    private int[] routeColumns;
    private int routeLength;
    private int routeIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private long lastLogTick = Long.MIN_VALUE;
    private boolean targetReached;
    private boolean aligningForStage;
    private int lastLoggedStage = -1;
    private long startedAtTick = Long.MIN_VALUE;

    public LegacyAction next(LegacyWorldObservation state) {
        if (state == null || !state.inMonsterMaze || !state.mazeDetected
                || !state.alive || state.completed || state.center == null
                || state.pad == null || state.pad.row < 0 || state.pad.column < 0) {
            reset();
            return LegacyAction.IDLE;
        }

        boolean atTarget = state.pad.reached || isInsidePad(state);

        /*
         * Once the current pad is reached, stop all movement and wait. The
         * observer intentionally keeps reporting the current active beacon
         * until the server's phase transition promotes the preview pad.
         */
        if (atTarget) {
            if (!targetReached) {
                targetReached = true;
                long elapsed = startedAtTick == Long.MIN_VALUE
                        ? 0L : state.worldTick - startedAtTick;
                System.out.println("[MonsterMazeAI/1.8] PAD REACHED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " elapsedTicks=" + elapsed
                        + " routeLength=" + routeLength);
            }
            return LegacyAction.IDLE;
        }

        /*
         * A new stage is identified by a changed active pad. This is the
         * authoritative transition signal; the preview beacon is deliberately
         * ignored by Minecraft18Observer until it becomes the active pad.
         */
        if (centerX != state.center.x || centerZ != state.center.z
                || goalRow != state.pad.row || goalColumn != state.pad.column
                || routeLength == 0) {
            if (!buildRoute(state)) {
                return LegacyAction.IDLE;
            }
            if (startedAtTick == Long.MIN_VALUE) {
                startedAtTick = state.worldTick;
            }
        }

        /*
         * A new active pad can be a large heading change from the previous
         * stage. Do the turn while stationary before allowing any forward
         * movement. The next pad is intentionally unknown before the server
         * transition, so this is the earliest safe point at which the new
         * route can be used for pre-alignment.
         */
        if (aligningForStage) {
            int headingIndex = firstRouteHeadingIndex();
            double headingWorldX = worldX(routeRows[headingIndex], state.center.x);
            double headingWorldZ = worldZ(routeColumns[headingIndex], state.center.z);

            float desiredYaw = desiredYawTo(state.player.x, state.player.z,
                    headingWorldX, headingWorldZ);
            float yawError = normalise(desiredYaw - state.player.yaw);
            float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

            if (Math.abs(yawError) <= ALIGNMENT_TOLERANCE) {
                aligningForStage = false;
                System.out.println("[MonsterMazeAI/1.8] PAD ALIGNED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " heading=" + routeRows[headingIndex] + "," + routeColumns[headingIndex]
                        + " yaw=" + format(state.player.yaw)
                        + " desiredYaw=" + format(desiredYaw));
            } else {
                if (state.worldTick % 2L == 0L) {
                    System.out.println("[MonsterMazeAI/1.8] PAD ALIGN"
                            + " stage=" + state.stage
                            + " tick=" + state.worldTick
                            + " heading=" + routeRows[headingIndex] + "," + routeColumns[headingIndex]
                            + " yaw=" + format(state.player.yaw)
                            + " desiredYaw=" + format(desiredYaw)
                            + " yawDelta=" + format(yawDelta));
                }
                return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
            }
        }

        if (targetReached || routeLength <= 1) {
            return LegacyAction.IDLE;
        }

        advanceRouteIndex(state);

        if (routeIndex >= routeLength) {
            return LegacyAction.IDLE;
        }

        /*
         * Aim at an actual physical point on the route ahead. This is
         * deliberately route following, not strategic look-ahead.
         */
        int targetIndex = Math.min(routeLength - 1, routeIndex + LOOKAHEAD_CELLS);
        double targetWorldX = worldX(routeRows[targetIndex], state.center.x);
        double targetWorldZ = worldZ(routeColumns[targetIndex], state.center.z);

        float desiredYaw = desiredYawTo(state.player.x, state.player.z,
                targetWorldX, targetWorldZ);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

        /*
         * Monster logic is deliberately layered on top of the proven route
         * follower. We do not change the route for every distant mob. Only an
         * imminent collision gets a local dodge, preserving the shortest-route
         * speedrun everywhere else.
         *
         * The source collision/knockback is position based (< 1 block in X/Y/Z)
         * and has a 1-second player bump cooldown. The observer supplies the
         * live monster velocity, so we can predict the short-term crossing
         * instead of reacting only after contact.
         */
        MobDodge dodge = findMobDodge(state);
        if (dodge != null) {
            yawDelta = dodge.yawDelta;
            if (state.worldTick % 5L == 0L) {
                System.out.println("[MonsterMazeAI/1.8] MOB DODGE"
                        + " tick=" + state.worldTick
                        + " monster=" + dodge.monsterId
                        + " type=" + dodge.visualType
                        + " distance=" + format(dodge.distance)
                        + " side=" + dodge.side
                        + " yawDelta=" + format(yawDelta));
            }
        }

        // Deliberately jump-spam: one tick pressed, one tick released.
        boolean jumpPulse = (state.worldTick & 1L) == 0L;

        if (state.worldTick % 10L == 0L) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN"
                    + " tick=" + state.worldTick
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " route=" + routeIndex + "/" + (routeLength - 1)
                    + " aim=" + targetIndex
                    + " target=" + routeRows[targetIndex] + "," + routeColumns[targetIndex]
                    + " yaw=" + format(state.player.yaw)
                    + " desiredYaw=" + format(desiredYaw)
                    + " yawDelta=" + format(yawDelta)
                    + " jump=" + jumpPulse);
        }

        return new LegacyAction(1.0f, 0.0f, jumpPulse, true, yawDelta, false);
    }

    public void reset() {
        routeRows = null;
        routeColumns = null;
        routeLength = 0;
        routeIndex = 0;
        goalRow = -1;
        goalColumn = -1;
        centerX = Integer.MIN_VALUE;
        centerZ = Integer.MIN_VALUE;
        startedAtTick = Long.MIN_VALUE;
        targetReached = false;
        aligningForStage = false;
        lastLoggedStage = -1;
        lastLogTick = Long.MIN_VALUE;
    }

    private boolean buildRoute(LegacyWorldObservation state) {
        int startRow = row(state.player.x, state.center.x);
        int startColumn = row(state.player.z, state.center.z);
        if (!inBounds(startRow, startColumn)) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn);
            return false;
        }

        int targetRow = state.pad.row;
        int targetColumn = state.pad.column;

        /*
         * At a phase transition the player is still physically standing on
         * the previous Safe Pad, but ObservationWorldModel's physicalFloor
         * is rebuilt from the newly active pad/topology. That can legitimately
         * make the old pad's logical cells false even though the player is
         * standing on its physical surface.
         *
         * Treat the player's current cell as a valid BFS seed only when this
         * is an actual pad-to-pad transition and the player is still inside
         * the previous pad. We do NOT make the whole old pad traversable:
         * BFS may leave this seed only through cells reported as physical
         * floor. This preserves the physical-floor route model while allowing
         * the route to start from the player's real post-countdown position.
         */
        boolean standingOnPreviousPad = targetReached
                && goalRow >= 0
                && goalColumn >= 0
                && Math.abs(startRow - goalRow) <= PAD_RADIUS
                && Math.abs(startColumn - goalColumn) <= PAD_RADIUS;

        if (!state.physicalFloor[startRow][startColumn] && !standingOnPreviousPad) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn);
            return false;
        }

        int[] parent = new int[SIZE * SIZE];
        Arrays.fill(parent, -2);
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();

        int start = index(startRow, startColumn);
        parent[start] = -1;
        queue.add(start);

        int goal = -1;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int r = current / SIZE;
            int c = current % SIZE;

            if (Math.abs(r - targetRow) <= PAD_RADIUS
                    && Math.abs(c - targetColumn) <= PAD_RADIUS) {
                goal = current;
                break;
            }

            enqueue(r - 1, c, current, state.physicalFloor, parent, queue);
            enqueue(r + 1, c, current, state.physicalFloor, parent, queue);
            enqueue(r, c - 1, current, state.physicalFloor, parent, queue);
            enqueue(r, c + 1, current, state.physicalFloor, parent, queue);
        }

        if (goal < 0) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn);
            return false;
        }

        int count = 0;
        for (int p = goal; p >= 0; p = parent[p]) {
            count++;
        }

        routeRows = new int[count];
        routeColumns = new int[count];
        int p = goal;
        for (int i = count - 1; i >= 0; i--) {
            routeRows[i] = p / SIZE;
            routeColumns[i] = p % SIZE;
            p = parent[p];
        }

        // Capture this before resetting targetReached. A pad identity change
        // is the authoritative stage transition; the observer's numeric stage
        // counter is not sufficient to decide whether this is a pad-to-pad
        // transition.
        boolean transitioningFromReachedPad = targetReached;

        routeLength = count;
        routeIndex = 1;
        goalRow = targetRow;
        goalColumn = targetColumn;
        centerX = state.center.x;
        centerZ = state.center.z;
        targetReached = false;
        // Pre-align whenever a new route is revealed after we were already
        // safely stopped on the previous pad. Do not key this off state.stage:
        // the active-pad transition is the authoritative stage boundary and
        // the observer's stage value may remain unchanged across that update.
        aligningForStage = transitioningFromReachedPad;
        startedAtTick = state.worldTick;

        if (lastLoggedStage != state.stage) {
            System.out.println("[MonsterMazeAI/1.8] PAD STAGE START"
                    + " stage=" + state.stage
                    + " tick=" + state.worldTick
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " length=" + routeLength
                    + " mode=W+sprint+jump-spam+yaw-route");
            lastLoggedStage = state.stage;
        }

        return true;
    }

    private static void enqueue(int r, int c, int from, boolean[][] floor,
                                int[] parent, ArrayDeque<Integer> queue) {
        if (r < 0 || r >= SIZE || c < 0 || c >= SIZE || !floor[r][c]) {
            return;
        }

        int next = index(r, c);
        if (parent[next] != -2) {
            return;
        }

        parent[next] = from;
        queue.addLast(next);
    }

    private MobDodge findMobDodge(LegacyWorldObservation state) {
        if (state.monsters == null || state.monsters.isEmpty()) {
            return null;
        }

        /*
         * Only consider monsters that are actually on the player's current
         * horizontal corridor. A mob several cells away is not a reason to
         * disturb an optimal route.
         */
        LegacyWorldObservation.Monster threat = null;
        double bestTime = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (LegacyWorldObservation.Monster monster : state.monsters) {
            if (monster.removed) continue;

            double rx = monster.x - state.player.x;
            double rz = monster.z - state.player.z;
            double horizontalDistance = Math.sqrt(rx * rx + rz * rz);
            if (horizontalDistance > 5.0D) continue;

            double rvx = monster.vx - state.player.vx;
            double rvz = monster.vz - state.player.vz;
            double rvSquared = rvx * rvx + rvz * rvz;

            double t = 0.0D;
            if (rvSquared > 1.0E-6D) {
                t = -(rx * rvx + rz * rvz) / rvSquared;
                t = Math.max(0.0D, Math.min(MOB_PREDICT_SECONDS, t));
            }

            double closestX = rx + rvx * t;
            double closestZ = rz + rvz * t;
            double closestHorizontal = Math.sqrt(closestX * closestX + closestZ * closestZ);

            double verticalAtClosest = (monster.y - state.player.y)
                    + (monster.vy - state.player.vy) * t;

            // Mirror the source's 3D collision requirement conservatively.
            if (Math.abs(verticalAtClosest) >= 1.15D
                    || closestHorizontal >= MOB_HAZARD_RADIUS) {
                continue;
            }

            if (t < bestTime || (Math.abs(t - bestTime) < 1.0E-4D
                    && closestHorizontal < bestDistance)) {
                threat = monster;
                bestTime = t;
                bestDistance = closestHorizontal;
            }
        }

        if (threat == null) return null;

        /*
         * Pick a one-cell lateral dodge relative to the current route heading.
         * We only use cells that are physically traversable. This keeps the
         * dodge inside the same floor topology and avoids turning toward an
         * edge simply because a mob is nearby.
         */
        int playerRow = row(state.player.x, state.center.x);
        int playerColumn = row(state.player.z, state.center.z);
        int headingIndex = Math.min(routeLength - 1, Math.max(routeIndex, routeIndex + 1));
        int nextRow = routeRows[headingIndex];
        int nextColumn = routeColumns[headingIndex];

        int dr = Integer.signum(nextRow - playerRow);
        int dc = Integer.signum(nextColumn - playerColumn);

        // If the route point is currently in the same cell, use the next
        // distinct route segment to establish the local forward direction.
        if (dr == 0 && dc == 0) {
            for (int i = headingIndex + 1; i < routeLength; i++) {
                dr = Integer.signum(routeRows[i] - playerRow);
                dc = Integer.signum(routeColumns[i] - playerColumn);
                if (dr != 0 || dc != 0) break;
            }
        }

        if (dr == 0 && dc == 0) return null;

        // Cardinal route direction -> two perpendicular candidate cells.
        int leftRow = playerRow - dc * MOB_DODGE_CELLS;
        int leftColumn = playerColumn + dr * MOB_DODGE_CELLS;
        int rightRow = playerRow + dc * MOB_DODGE_CELLS;
        int rightColumn = playerColumn - dr * MOB_DODGE_CELLS;

        boolean leftSafe = inBounds(leftRow, leftColumn)
                && state.physicalFloor[leftRow][leftColumn];
        boolean rightSafe = inBounds(rightRow, rightColumn)
                && state.physicalFloor[rightRow][rightColumn];

        if (!leftSafe && !rightSafe) {
            /*
             * A one-cell corridor leaves no lateral escape. Do not invent a
             * strafe or intentionally hit the mob here; the source knockback
             * can slide the player off the maze edge. The normal route action
             * is retained and the next tick gets another prediction.
             */
            return null;
        }

        double leftWorldX = worldX(leftRow, state.center.x);
        double leftWorldZ = worldZ(leftColumn, state.center.z);
        double rightWorldX = worldX(rightRow, state.center.x);
        double rightWorldZ = worldZ(rightColumn, state.center.z);

        double leftMobDistance = distanceSquared(leftWorldX, leftWorldZ, threat.x, threat.z);
        double rightMobDistance = distanceSquared(rightWorldX, rightWorldZ, threat.x, threat.z);

        int dodgeRow;
        int dodgeColumn;
        int side;

        if (leftSafe && (!rightSafe || leftMobDistance >= rightMobDistance)) {
            dodgeRow = leftRow;
            dodgeColumn = leftColumn;
            side = -1;
        } else {
            dodgeRow = rightRow;
            dodgeColumn = rightColumn;
            side = 1;
        }

        float dodgeYaw = desiredYawTo(
                state.player.x, state.player.z,
                worldX(dodgeRow, state.center.x),
                worldZ(dodgeColumn, state.center.z));
        float dodgeError = normalise(dodgeYaw - state.player.yaw);

        return new MobDodge(
                threat.id,
                threat.visualType,
                Math.min(bestDistance, MOB_HAZARD_RADIUS),
                side,
                clamp(dodgeError, -MOB_DODGE_MAX_YAW, MOB_DODGE_MAX_YAW));
    }

    private static double distanceSquared(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static final class MobDodge {
        private final int monsterId;
        private final String visualType;
        private final double distance;
        private final int side;
        private final float yawDelta;

        private MobDodge(int monsterId, String visualType, double distance,
                         int side, float yawDelta) {
            this.monsterId = monsterId;
            this.visualType = visualType;
            this.distance = distance;
            this.side = side;
            this.yawDelta = yawDelta;
        }
    }

    private void advanceRouteIndex(LegacyWorldObservation state) {
        int playerRow = row(state.player.x, state.center.x);
        int playerColumn = row(state.player.z, state.center.z);

        /*
         * Find the closest future route point rather than requiring an exact
         * one-tick cell match. This lets sprinting cross cell boundaries
         * without freezing the route cursor at an old direction.
         */
        int bestIndex = routeIndex;
        double bestDistance = Double.MAX_VALUE;
        int end = Math.min(routeLength - 1, routeIndex + 12);

        for (int i = routeIndex; i <= end; i++) {
            int dr = routeRows[i] - playerRow;
            int dc = routeColumns[i] - playerColumn;
            double distance = dr * dr + dc * dc;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        if (bestIndex > routeIndex) {
            routeIndex = bestIndex;
        }
    }

    private boolean isInsidePad(LegacyWorldObservation state) {
        double x = state.player.x - (state.center.x - 49);
        double z = state.player.z - (state.center.z - 49);
        double baseY = state.center.y - 1.0D;
        return Math.abs(x - state.pad.row) < 2.5D
                && Math.abs(z - state.pad.column) < 2.5D
                && state.player.y > baseY
                && state.player.y < baseY + 5.0D;
    }


    private int firstRouteHeadingIndex() {
        if (routeLength <= 1) {
            return 0;
        }

        int startRow = routeRows[0];
        int startColumn = routeColumns[0];

        for (int i = 1; i < routeLength; i++) {
            if (routeRows[i] != startRow || routeColumns[i] != startColumn) {
                return i;
            }
        }

        return Math.min(1, routeLength - 1);
    }

    private static float desiredYawTo(double fromX, double fromZ,
                                      double targetX, double targetZ) {
        double dx = targetX - fromX;
        double dz = targetZ - fromZ;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static int row(double world, int center) {
        return (int) Math.floor(world - (center - 49));
    }

    private static double worldX(int routeRow, int centerX) {
        return (centerX - 49) + routeRow + 0.5D;
    }

    private static double worldZ(int routeColumn, int centerZ) {
        return (centerZ - 49) + routeColumn + 0.5D;
    }

    private static int index(int r, int c) {
        return r * SIZE + c;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private static float normalise(float angle) {
        while (angle > 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
