package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

import java.util.List;

/**
 * Minimal closed-loop Monster Maze controller.
 *
 * The board is static, the objective is explicit, and the complete world state
 * is available every tick. Recompute the shortest physical route from the
 * player's actual supported cell every tick instead of maintaining an
 * asynchronous route plan or learned policy.
 *
 * Monster avoidance is deliberately local: only a monster that can physically
 * interfere with the next short segment can brake the player. The controller
 * never invents a future monster path and never replaces a route for a distant
 * threat.
 */
public final class DirectOracleMovementController {
    private static final float MAX_TURN = 30.0F;
    private static final float TURN_IN_PLACE_ERROR = 60.0F;
    private static final float DRIVE_ERROR = 35.0F;

    /** Do not charge into a monster occupying the next corridor cell. */
    private static final double STATIC_BLOCK_DISTANCE = 1.65D;
    /** Moving threats are checked over a short physical horizon only. */
    private static final double MOVING_BLOCK_DISTANCE = 3.25D;
    private static final double CORRIDOR_HALF_WIDTH = 0.85D;
    private static final double MIN_CLOSING_SPEED = 0.015D;
    private static final double MAX_INTERCEPT_TICKS = 18.0D;

    /** Source-compatible non-Jumper speed jump trigger for a real gap edge. */
    private static final double GAP_JUMP_PROGRESS = -0.80D;

    private final AiProfile profile;
    private final PlayerPathfinder pathfinder = new PlayerPathfinder();
    private String lastDecisionDetail = "UNSET";

    public DirectOracleMovementController(AiProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
    }

    public AiProfile profile() {
        return profile;
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public void reset() {
        lastDecisionDetail = "RESET";
    }

    public Action nextAction(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || state.maze == null || goal == null
                || state.activePadRow < 0 || state.activePadColumn < 0) {
            lastDecisionDetail = "INVALID_STATE";
            return Action.IDLE;
        }

        if (regionRadius > 0 && PadModel.isOn(
                state.player,
                goal.row() + 0.5,
                GameState.PAD_SURFACE_Y,
                goal.column() + 0.5)) {
            lastDecisionDetail = "ON_SAFE_PAD";
            return Action.IDLE;
        }

        Cell start = resolveSupportedStartCell(state);
        if (start == null) {
            lastDecisionDetail = "NO_SUPPORT";
            return Action.IDLE;
        }

        PlayerRoute route = route(state, start, goal, regionRadius);
        if (route == null || route.size() <= 1) {
            lastDecisionDetail = "NO_ROUTE start=" + start + " goal=" + goal;
            return Action.IDLE;
        }

        Cell from = route.cells().get(0);
        Cell to = route.cells().get(1);
        int dr = Integer.signum(to.row() - from.row());
        int dc = Integer.signum(to.column() - from.column());

        if (Math.abs(dr) + Math.abs(dc) > 1 && !isGapEdge(state, from, to)) {
            lastDecisionDetail = "INVALID_EDGE " + from + "->" + to;
            return Action.IDLE;
        }

        float desiredYaw = cardinalYaw(dr, dc);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float turn = clamp(yawError, -MAX_TURN, MAX_TURN);

        boolean gap = isGapEdge(state, from, to);
        if (gap) {
            double progress = gapProgress(state, from, to);
            boolean jump = allowJump && state.player.grounded
                    && progress >= GAP_JUMP_PROGRESS;
            if (state.kit != me.monstermazeai.kit.Kit.JUMPER) {
                jump = state.player.grounded && progress >= GAP_JUMP_PROGRESS;
            }
            if (Math.abs(yawError) > TURN_IN_PLACE_ERROR) {
                lastDecisionDetail = "GAP_ALIGN progress=" + format(progress)
                        + " yawError=" + format(yawError);
                return new Action(0.0, 0.0, false, false, turn, false);
            }
            lastDecisionDetail = "GAP_DRIVE progress=" + format(progress)
                    + " yawError=" + format(yawError)
                    + " jump=" + jump;
            return new Action(1.0, 0.0, jump, true, turn, false);
        }

        if (Math.abs(yawError) > TURN_IN_PLACE_ERROR) {
            lastDecisionDetail = "TURN_IN_PLACE yawError=" + format(yawError);
            return new Action(0.0, 0.0, false, false, turn, false);
        }

        double forward;
        boolean sprint;
        float driveTurn = turn;
        double absError = Math.abs(yawError);
        if (absError <= DRIVE_ERROR) {
            forward = 1.0;
            sprint = true;
        } else {
            forward = 0.65;
            sprint = false;
        }

        lastDecisionDetail = "DIRECT_ROUTE"
                + " start=" + from.row() + "," + from.column()
                + " next=" + to.row() + "," + to.column()
                + " routeSize=" + route.size()
                + " yawError=" + format(yawError)
                + " forward=" + format(forward);

        return new Action(forward, 0.0, false, sprint, driveTurn, false);
    }

    private PlayerRoute route(GameState state, Cell start, Cell goal, int regionRadius) {
        List<Cell> cells;
        if (regionRadius > 0) {
            cells = pathfinder.shortestPathToRegionWithoutGaps(
                    state.maze, start, goal, regionRadius);
            if (cells.isEmpty()) {
                cells = pathfinder.shortestPathToRegion(
                        state.maze, start, goal, regionRadius);
            }
        } else {
            cells = pathfinder.shortestPathWithoutGaps(state.maze, start, goal);
            if (cells.isEmpty()) {
                cells = pathfinder.shortestPath(state.maze, start, goal);
            }
        }
        return cells.isEmpty() ? null : new PlayerRoute(cells);
    }

    private boolean localMonsterBlocksNextSegment(GameState state, Cell from, Cell to) {
        double sx = from.row() + 0.5D;
        double sz = from.column() + 0.5D;
        double ex = to.row() + 0.5D;
        double ez = to.column() + 0.5D;

        double dx = ex - sx;
        double dz = ez - sz;
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-9D) return false;
        dx /= len;
        dz /= len;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) {
                continue;
            }

            double rx = monster.x - state.player.x;
            double rz = monster.z - state.player.z;
            double longitudinal = rx * dx + rz * dz;
            double lateral = Math.abs(rx * dz - rz * dx);
            if (longitudinal < -0.15D || lateral > CORRIDOR_HALF_WIDTH) continue;

            double distance = Math.hypot(rx, rz);
            if (distance <= STATIC_BLOCK_DISTANCE) return true;

            double closing = (monster.vx * dx + monster.vz * dz)
                    - (state.player.vx * dx + state.player.vz * dz);

            if (distance <= MOVING_BLOCK_DISTANCE && closing < -MIN_CLOSING_SPEED) {
                double intercept = distance / Math.max(
                        Math.abs(closing), MIN_CLOSING_SPEED);
                if (intercept <= MAX_INTERCEPT_TICKS) return true;
            }
        }
        return false;
    }

    private static Cell resolveSupportedStartCell(GameState state) {
        double x = state.player.x;
        double z = state.player.z;
        int baseRow = (int) Math.floor(x);
        int baseColumn = (int) Math.floor(z);

        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int row = baseRow - 1; row <= baseRow + 1; row++) {
            for (int column = baseColumn - 1; column <= baseColumn + 1; column++) {
                if (!state.maze.isPhysicalFloor(row, column)) continue;
                double cx = row + 0.5D;
                double cz = column + 0.5D;
                double d = Math.hypot(x - cx, z - cz);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new Cell(row, column);
                }
            }
        }
        return best;
    }

    private static boolean isGapEdge(GameState state, Cell from, Cell to) {
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0) || (Math.abs(dc) == 2 && dr == 0))) {
            return false;
        }
        int middleRow = from.row() + Integer.signum(dr);
        int middleColumn = from.column() + Integer.signum(dc);
        return state.maze.isPhysicalFloor(from.row(), from.column())
                && !state.maze.isPhysicalFloor(middleRow, middleColumn)
                && state.maze.isPhysicalFloor(to.row(), to.column());
    }

    private static double gapProgress(GameState state, Cell from, Cell to) {
        double fx = from.row() + 0.5D;
        double fz = from.column() + 0.5D;
        double dx = to.row() - from.row();
        double dz = to.column() - from.column();
        double len = Math.hypot(dx, dz);
        if (len < 1.0E-9D) return 0.0D;
        dx /= len;
        dz /= len;
        return (state.player.x - fx) * dx + (state.player.z - fz) * dz;
    }

    private static float cardinalYaw(int dr, int dc) {
        return (float) Math.toDegrees(Math.atan2(-dr, dc));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
