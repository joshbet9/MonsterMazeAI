package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

/**
 * Predictive recovery controller used after monster knockback.
 *
 * It treats the player's current velocity as part of the control problem and
 * chooses the safest reachable floor direction rather than simply continuing
 * the old route. Once the predicted trajectory is safely back inside the
 * maze, normal route control can resume.
 */
public final class KnockbackRecoveryController {
    private static final double EDGE_MARGIN = 0.22;
    private static final double SAMPLE_STEP = 0.05;
    private static final int HORIZON = 12;
    private static final float MAX_YAW_DELTA = 35.0F;

    private final Simulator simulator;

    public KnockbackRecoveryController(Simulator simulator) {
        if (simulator == null) throw new IllegalArgumentException("simulator");
        this.simulator = simulator;
    }

    public boolean shouldRecover(GameState state) {
        if (state == null || state.maze == null || !state.alive) return false;
        if (state.player.recentMobHitUntilTick <= state.tick) return false;

        double nextX = state.player.x + state.player.vx;
        double nextZ = state.player.z + state.player.vz;
        return !safePosition(state, nextX, nextZ)
                || edgeDistance(state, state.player.x, state.player.z) < 0.45
                || Math.hypot(state.player.vx, state.player.vz) > 0.55;
    }

    public Action nextAction(GameState state, boolean allowJump) {
        if (!shouldRecover(state)) return Action.IDLE;

        Candidate best = null;
        double[] dirs = {
                0, 45, 90, 135, 180, 225, 270, 315
        };

        for (double degrees : dirs) {
            double rad = Math.toRadians(degrees);
            double tx = state.player.x + Math.cos(rad) * 1.5;
            double tz = state.player.z + Math.sin(rad) * 1.5;
            if (!hasSafeCorridor(state, tx, tz)) continue;

            float desiredYaw = (float) Math.toDegrees(Math.atan2(-(
                    tx - state.player.x), tz - state.player.z));
            float yawDelta = wrap(desiredYaw - state.player.yaw);
            yawDelta = clamp(yawDelta, -MAX_YAW_DELTA, MAX_YAW_DELTA);

            for (int strafe : new int[] {-1, 0, 1}) {
                Action action = new Action(1, strafe, allowJump,
                        true, yawDelta, false);
                GameState next = simulator.forecast(state, action, 3,
                        simulator.monsterSeed() ^ state.tick ^ Double.doubleToLongBits(degrees));
                if (!state.alive || !safePosition(next, next.player.x, next.player.z)) continue;

                double clearance = edgeDistance(next, next.player.x, next.player.z);
                double velocity = Math.hypot(next.player.vx, next.player.vz);
                double targetDistance = Math.hypot(tx - next.player.x, tz - next.player.z);
                double score = targetDistance - clearance * 1.5 + velocity * 0.5;
                if (best == null || score < best.score) {
                    best = new Candidate(action, score);
                }
            }
        }

        if (best != null) return best.action;

        // If every candidate predicts an edge crossing, stop accelerating into
        // the fall and turn toward the nearest safe direction.
        double[] safe = nearestSafeDirection(state);
        float yaw = (float) Math.toDegrees(Math.atan2(-(safe[0] - state.player.x),
                safe[1] - state.player.z));
        return new Action(0, 0, allowJump, false,
                clamp(wrap(yaw - state.player.yaw), -MAX_YAW_DELTA, MAX_YAW_DELTA), false);
    }

    private boolean hasSafeCorridor(GameState state, double tx, double tz) {
        double distance = Math.hypot(tx - state.player.x, tz - state.player.z);
        int steps = Math.max(2, (int) Math.ceil(distance / SAMPLE_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            if (!safePosition(state,
                    state.player.x + (tx - state.player.x) * t,
                    state.player.z + (tz - state.player.z) * t)) return false;
        }
        return true;
    }

    private boolean safePosition(GameState state, double x, double z) {
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        if (row < 0 || col < 0 || row >= MazeModel.SIZE || col >= MazeModel.SIZE) return false;
        if (!state.maze.isPhysicalFloor(row, col)) return false;
        return edgeDistance(state, x, z) >= EDGE_MARGIN;
    }

    private double edgeDistance(GameState state, double x, double z) {
        int row = (int) Math.floor(x);
        int col = (int) Math.floor(z);
        double best = Math.min(Math.min(x - row, row + 1.0 - x),
                Math.min(z - col, col + 1.0 - z));
        if (!state.maze.isPhysicalFloor(row, col)) return -1.0;
        return best;
    }

    private boolean boundaryHasFloor(GameState state, double x, double z, int row, int col) {
        final double eps = EDGE_MARGIN;
        if (x - row < eps && row > 0) return state.maze.isPhysicalFloor(row - 1, col);
        if (row + 1.0 - x < eps && row + 1 < MazeModel.SIZE) return state.maze.isPhysicalFloor(row + 1, col);
        if (z - col < eps && col > 0) return state.maze.isPhysicalFloor(row, col - 1);
        return col + 1 < MazeModel.SIZE && state.maze.isPhysicalFloor(row, col + 1);
    }

    private double[] nearestSafeDirection(GameState state) {
        double best = Double.POSITIVE_INFINITY;
        double bestX = state.player.x, bestZ = state.player.z;
        for (int r = Math.max(0, (int) state.player.x - 3);
             r <= Math.min(MazeModel.SIZE - 1, (int) state.player.x + 3); r++) {
            for (int c = Math.max(0, (int) state.player.z - 3);
                 c <= Math.min(MazeModel.SIZE - 1, (int) state.player.z + 3); c++) {
                if (!state.maze.isPhysicalFloor(r, c)) continue;
                double x = r + 0.5, z = c + 0.5;
                double d = Math.hypot(x - state.player.x, z - state.player.z);
                if (d < best) {
                    best = d; bestX = x; bestZ = z;
                }
            }
        }
        return new double[]{bestX, bestZ};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float wrap(float value) {
        while (value >= 180.0F) value -= 360.0F;
        while (value < -180.0F) value += 360.0F;
        return value;
    }

    private record Candidate(Action action, double score) {}
}
