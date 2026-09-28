package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Isolated pad-to-pad speedrun benchmark:
 * maze + player + active pad + live monsters -> dynamic safe route -> W+sprint+jump.
 *
 * After reaching a pad, the controller deliberately waits in place while the
 * server countdown runs. When the active pad changes at the round transition,
 * it rebuilds the best currently safe route from the player's current position.
 *
 * Mobs are hard dynamic obstacles. If a live/predicted mob blocks the selected
 * route, the complete route is rebuilt. The movement controller never performs
 * a separate mob dodge or strafe; it only follows the currently selected route.
 *
 * The controller runs synchronously on the Minecraft client thread.
 */
public final class FirstPadSpeedrunController {
    private static final int SIZE = 99;
    private static final int PAD_RADIUS = 2;
    private static final float MAX_YAW_STEP = 30.0F;
    private static final float ALIGNMENT_TOLERANCE = 10.0F;
    private static final int LOOKAHEAD_CELLS = 3;
    /*
     * Mobs are hard dynamic obstacles. The planner predicts their short-term
     * position and rejects route cells whose estimated player arrival would
     * overlap the conservative collision envelope.
     *
     * Five client ticks per maze cell is deliberately conservative for the
     * W+sprint+jump speedrun. The planner is continuously replanned when the
     * currently selected route becomes unsafe, so this is an arrival estimate,
     * not a physics claim.
     */
    private static final double MOB_HAZARD_RADIUS = 1.25D;
    private static final int MOB_PREDICT_TICKS = 30;
    private static final double ESTIMATED_TICKS_PER_CELL = 5.0D;
    private static final int MOB_ROUTE_LOOKAHEAD_CELLS = 18;

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
         * Mobs are part of route planning, never a movement override.
         *
         * If the selected route has become unsafe since it was planned, build
         * one new complete route from the player's current position. The
         * resulting route is then handled by the exact same movement controller
         * as a mob-free route. There is intentionally no strafe/dodge state.
         */
        if (routeNeedsMobReplan(state)) {
            int oldLength = routeLength;
            int oldIndex = routeIndex;
            if (!buildRoute(state)) {
                if (state.worldTick % 5L == 0L) {
                    System.out.println("[MonsterMazeAI/1.8] MOB ROUTE BLOCKED"
                            + " tick=" + state.worldTick
                            + " routeIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                            + " action=IDLE_WAIT_FOR_CLEAR_PATH");
                }
                return LegacyAction.IDLE;
            }
            if (state.worldTick % 5L == 0L) {
                System.out.println("[MonsterMazeAI/1.8] MOB ROUTE REPLAN"
                        + " tick=" + state.worldTick
                        + " oldIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                        + " newLength=" + routeLength
                        + " newHeading=" + routeRows[Math.min(1, routeLength - 1)]
                        + "," + routeColumns[Math.min(1, routeLength - 1)]);
            }

            targetIndex = Math.min(routeLength - 1, routeIndex + LOOKAHEAD_CELLS);
            targetWorldX = worldX(routeRows[targetIndex], state.center.x);
            targetWorldZ = worldZ(routeColumns[targetIndex], state.center.z);
            desiredYaw = desiredYawTo(state.player.x, state.player.z,
                    targetWorldX, targetWorldZ);
            yawError = normalise(desiredYaw - state.player.yaw);
            yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
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
         * the previous Safe Pad, while physicalFloor has already been rebuilt
         * for the new active pad. Allow only the actual player cell as the BFS
         * seed in that case; all subsequent cells still require physicalFloor.
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

        /*
         * Dynamic A*: each node carries the estimated arrival time for that
         * cell. A neighbour is rejected when a live monster is predicted to
         * occupy its collision envelope when the player arrives.
         *
         * This is deliberately a hard constraint, not a penalty. The planner
         * therefore cannot choose a shorter route through a monster simply
         * because that route has fewer cells.
         */
        int total = SIZE * SIZE;
        double[] bestArrivalTicks = new double[total];
        Arrays.fill(bestArrivalTicks, Double.POSITIVE_INFINITY);
        int[] parent = new int[total];
        Arrays.fill(parent, -2);

        PriorityQueue<RouteNode> open = new PriorityQueue<RouteNode>(
                (a, b) -> Double.compare(a.fScore, b.fScore));

        int start = index(startRow, startColumn);
        bestArrivalTicks[start] = 0.0D;
        parent[start] = -1;
        open.add(new RouteNode(start, 0.0D,
                heuristicTicks(startRow, startColumn, targetRow, targetColumn)));

        int goal = -1;

        while (!open.isEmpty()) {
            RouteNode node = open.poll();
            if (node.gTicks > bestArrivalTicks[node.index] + 1.0E-6D) {
                continue;
            }

            int r = node.index / SIZE;
            int c = node.index % SIZE;

            if (Math.abs(r - targetRow) <= PAD_RADIUS
                    && Math.abs(c - targetColumn) <= PAD_RADIUS) {
                goal = node.index;
                break;
            }

            goal = expandDynamicNeighbour(state, node, r - 1, c, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 1, c, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c - 1, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c + 1, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
        }

        if (goal < 0) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " reason=dynamic-mob-block");
            return false;
        }

        int count = 0;
        for (int p = goal; p >= 0; p = parent[p]) {
            count++;
        }

        int[] newRouteRows = new int[count];
        int[] newRouteColumns = new int[count];
        int p = goal;
        for (int i = count - 1; i >= 0; i--) {
            newRouteRows[i] = p / SIZE;
            newRouteColumns[i] = p % SIZE;
            p = parent[p];
        }

        boolean transitioningFromReachedPad = targetReached;
        boolean mobReplan = routeLength > 0
                && goalRow == targetRow
                && goalColumn == targetColumn
                && !transitioningFromReachedPad;

        routeRows = newRouteRows;
        routeColumns = newRouteColumns;
        routeLength = count;
        routeIndex = Math.min(1, Math.max(0, count - 1));
        goalRow = targetRow;
        goalColumn = targetColumn;
        centerX = state.center.x;
        centerZ = state.center.z;
        targetReached = false;

        /*
         * Only a genuine pad-to-pad transition gets stationary alignment.
         * Mob replans start from the player's current heading and therefore
         * must not introduce a competing alignment state.
         */
        aligningForStage = transitioningFromReachedPad;

        if (startedAtTick == Long.MIN_VALUE) {
            startedAtTick = state.worldTick;
        }

        if (lastLoggedStage != state.stage || mobReplan) {
            System.out.println("[MonsterMazeAI/1.8] PAD ROUTE"
                    + " stage=" + state.stage
                    + " tick=" + state.worldTick
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " length=" + routeLength
                    + " mode=dynamic-mob-safe-A*"
                    + " mobReplan=" + mobReplan);
            lastLoggedStage = state.stage;
        }

        return true;
    }

    private int expandDynamicNeighbour(LegacyWorldObservation state,
                                       RouteNode node,
                                       int r,
                                       int c,
                                       int fromRow,
                                       int fromColumn,
                                       int targetRow,
                                       int targetColumn,
                                       double[] bestArrivalTicks,
                                       int[] parent,
                                       PriorityQueue<RouteNode> open,
                                       int currentGoal) {
        if (!inBounds(r, c) || !state.physicalFloor[r][c]) {
            return currentGoal;
        }

        int next = index(r, c);
        double arrivalTicks = node.gTicks + ESTIMATED_TICKS_PER_CELL;

        /*
         * The start cell may be on the previous Safe Pad during a phase
         * transition, but every newly entered cell must pass both static floor
         * and dynamic monster safety checks.
         */
        if (isMobBlockedAlongEdge(state, fromRow, fromColumn, r, c, node.gTicks)) {
            return currentGoal;
        }

        if (arrivalTicks + 1.0E-6D >= bestArrivalTicks[next]) {
            return currentGoal;
        }

        bestArrivalTicks[next] = arrivalTicks;
        parent[next] = node.index;

        double heuristic = heuristicTicks(r, c, targetRow, targetColumn);
        open.add(new RouteNode(next, arrivalTicks, arrivalTicks + heuristic));
        return currentGoal;
    }

    private boolean isMobBlockedAlongEdge(LegacyWorldObservation state,
                                           int fromRow,
                                           int fromColumn,
                                           int toRow,
                                           int toColumn,
                                           double startTicks) {
        /*
         * Do not only test the cell centre at the estimated arrival time.
         * Player and monster can cross between two cell centres during the
         * same sprint. Sample the complete one-cell transition at one-tick
         * intervals so a crossing mob also invalidates the edge.
         */
        double fromX = worldX(fromRow, state.center.x);
        double fromZ = worldZ(fromColumn, state.center.z);
        double toX = worldX(toRow, state.center.x);
        double toZ = worldZ(toColumn, state.center.z);

        for (int tick = 1; tick <= (int) Math.ceil(ESTIMATED_TICKS_PER_CELL); tick++) {
            double fraction = tick / ESTIMATED_TICKS_PER_CELL;
            if (fraction > 1.0D) {
                fraction = 1.0D;
            }

            double playerX = fromX + (toX - fromX) * fraction;
            double playerZ = fromZ + (toZ - fromZ) * fraction;
            double arrivalTicks = startTicks + tick;

            if (isMobBlockedAtPosition(state, playerX, playerZ, arrivalTicks)) {
                return true;
            }
        }

        return false;
    }

    private boolean isMobBlockedAtPosition(LegacyWorldObservation state,
                                            double playerX,
                                            double playerZ,
                                            double arrivalTicks) {
        if (state.monsters == null || state.monsters.isEmpty()) {
            return false;
        }

        double predictionTicks = Math.min(arrivalTicks, MOB_PREDICT_TICKS);

        for (LegacyWorldObservation.Monster monster : state.monsters) {
            if (monster.removed) {
                continue;
            }

            double predictedX = monster.x + monster.vx * predictionTicks;
            double predictedZ = monster.z + monster.vz * predictionTicks;

            double dx = predictedX - playerX;
            double dz = predictedZ - playerZ;
            double horizontalDistanceSquared = dx * dx + dz * dz;

            /*
             * Contact is a hard failure for this benchmark. We intentionally
             * do not rely on future jump height to declare a mob safe.
             */
            if (horizontalDistanceSquared < MOB_HAZARD_RADIUS * MOB_HAZARD_RADIUS) {
                return true;
            }

            /*
             * Beyond the velocity-prediction horizon, a stationary monster is
             * still a permanent obstacle. Moving monsters will be reconsidered
             * from fresh observations as the player advances.
             */
            if (arrivalTicks > MOB_PREDICT_TICKS) {
                double currentDx = monster.x - playerX;
                double currentDz = monster.z - playerZ;
                if (currentDx * currentDx + currentDz * currentDz
                        < MOB_HAZARD_RADIUS * MOB_HAZARD_RADIUS
                        && Math.abs(monster.vx) + Math.abs(monster.vz) < 0.03D) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean routeNeedsMobReplan(LegacyWorldObservation state) {
        if (state.monsters == null || state.monsters.isEmpty()
                || routeRows == null || routeLength <= 1
                || routeIndex >= routeLength - 1) {
            return false;
        }

        int end = Math.min(routeLength - 1,
                routeIndex + MOB_ROUTE_LOOKAHEAD_CELLS);

        /*
         * Only future route cells matter. The current cell may already be
         * inside a mob's envelope because of the previous tick's movement;
         * rebuilding from that cell cannot retroactively undo the collision.
         */
        for (int i = routeIndex + 1; i <= end; i++) {
            double startTicks = (i - routeIndex - 1) * ESTIMATED_TICKS_PER_CELL;
            if (isMobBlockedAlongEdge(state,
                    routeRows[i - 1], routeColumns[i - 1],
                    routeRows[i], routeColumns[i],
                    startTicks)) {
                return true;
            }
        }

        return false;
    }

    private static double heuristicTicks(int row, int column,
                                         int targetRow, int targetColumn) {
        return (Math.abs(row - targetRow) + Math.abs(column - targetColumn))
                * ESTIMATED_TICKS_PER_CELL;
    }

    private static final class RouteNode {
        private final int index;
        private final double gTicks;
        private final double fScore;

        private RouteNode(int index, double gTicks, double fScore) {
            this.index = index;
            this.gTicks = gTicks;
            this.fScore = fScore;
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
