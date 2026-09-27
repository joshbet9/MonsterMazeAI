package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Isolated first-pad benchmark:
 * maze + player + active pad -> shortest physical-floor route -> W+sprint+jump.
 *
 * No sidecar, async planner, monster logic, abilities, recovery, replanning,
 * or strafe input. The controller runs synchronously on the Minecraft thread.
 */
public final class FirstPadSpeedrunController {
    private static final int SIZE = 99;
    private static final int PAD_RADIUS = 2;
    private static final float MAX_YAW_STEP = 30.0F;
    private static final int LOOKAHEAD_CELLS = 3;

    private int[] routeRows;
    private int[] routeColumns;
    private int routeLength;
    private int routeIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private long lastLogTick = Long.MIN_VALUE;
    private boolean finished;
    private long startedAtTick = Long.MIN_VALUE;

    public LegacyAction next(LegacyWorldObservation state) {
        if (state == null || !state.inMonsterMaze || !state.mazeDetected
                || !state.alive || state.completed || state.center == null
                || state.pad == null || state.pad.row < 0 || state.pad.column < 0) {
            reset();
            return LegacyAction.IDLE;
        }

        if (state.pad.reached || isInsidePad(state)) {
            if (!finished) {
                finished = true;
                long elapsed = startedAtTick == Long.MIN_VALUE
                        ? 0L : state.worldTick - startedAtTick;
                System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN REACHED"
                        + " tick=" + state.worldTick
                        + " elapsedTicks=" + elapsed
                        + " routeLength=" + routeLength);
            }
            return LegacyAction.IDLE;
        }

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

        if (finished || routeLength <= 1) {
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

        double dx = targetWorldX - state.player.x;
        double dz = targetWorldZ - state.player.z;

        /*
         * Minecraft yaw:
         * 0 = +Z, -90 = +X, 180 = -Z, 90 = -X.
         */
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

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
        finished = false;
        lastLogTick = Long.MIN_VALUE;
    }

    private boolean buildRoute(LegacyWorldObservation state) {
        int startRow = row(state.player.x, state.center.x);
        int startColumn = row(state.player.z, state.center.z);
        if (!inBounds(startRow, startColumn)
                || !state.physicalFloor[startRow][startColumn]) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn);
            return false;
        }

        int targetRow = state.pad.row;
        int targetColumn = state.pad.column;

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

        routeLength = count;
        routeIndex = 1;
        goalRow = targetRow;
        goalColumn = targetColumn;
        centerX = state.center.x;
        centerZ = state.center.z;

        System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN ROUTE"
                + " start=" + startRow + "," + startColumn
                + " pad=" + targetRow + "," + targetColumn
                + " length=" + routeLength
                + " mode=W+sprint+jump-spam+yaw-route");

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
