package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

import java.util.List;

/**
 * Movement-only controller for the first Safe Pad milestone.
 *
 * This controller intentionally does NOT inspect monsters, abilities, tactical
 * simulations, risk scores, alternate routes, or competitor state. Its only
 * job is to prove the physical movement stack:
 *
 *   live observation -> physical shortest route -> fast forward/sprint motor
 *   -> source-compatible jump input -> first 5x5 Safe Pad.
 *
 * The route is shortest in the authoritative physical-floor graph. The motor
 * is W-first: it never uses strafe as a normal steering primitive. Heading is
 * changed with yaw while forward and sprint remain asserted. A turn is
 * anticipated shortly before a route corner so the player does not have to
 * stop, turn in place, then accelerate again.
 */
public final class FirstPadMovementController {
    public static final int SAFE_PAD_RADIUS = 2;

    private static final double ARRIVAL_TOLERANCE = 0.30;
    private static final double ROUTE_DEVIATION = 0.48;
    private static final double TURN_LEAD = 0.82;
    private static final double HEADING_TOLERANCE = 2.0;
    private static final float MAX_YAW_PER_TICK = 12.0F;

    private PlayerRoute route;
    private int segmentIndex = -1;
    private int goalRow = -1;
    private int goalColumn = -1;
    private long routeSignature = Long.MIN_VALUE;
    private String lastDecisionDetail = "RESET";

    public Action nextAction(GameState state) {
        if (state == null || state.maze == null || !state.inMonsterMaze
                || !state.alive || state.completed
                || state.activePadRow < 0 || state.activePadColumn < 0) {
            reset();
            lastDecisionDetail = "STATE_GATE";
            return Action.IDLE;
        }

        Cell pad = new Cell(state.activePadRow, state.activePadColumn);

        if (state.padReached || isOnPad(state, pad)) {
            lastDecisionDetail = "FIRST_PAD_REACHED";
            return Action.IDLE;
        }

        int startRow = (int) Math.floor(state.player.x);
        int startColumn = (int) Math.floor(state.player.z);
        if (!inBounds(startRow, startColumn)
                || !state.maze.isPhysicalFloor(startRow, startColumn)) {
            lastDecisionDetail = "NO_ROUTE start_not_physical="
                    + startRow + "," + startColumn;
            return Action.IDLE;
        }

        long signature = routeSignature(state, pad);
        if (route == null || goalRow != pad.row() || goalColumn != pad.column()
                || routeSignature != signature || !routeContainsStart(startRow, startColumn)
                || !routeIsPhysical(state.maze)) {
            if (!buildRoute(state, startRow, startColumn, pad)) {
                lastDecisionDetail = "NO_ROUTE bootstrap";
                return Action.IDLE;
            }
        }

        advanceSegment(state);

        if (segmentIndex < 1 || segmentIndex >= route.size()) {
            lastDecisionDetail = "FIRST_PAD_REACHED route_end";
            return Action.IDLE;
        }

        Cell previous = route.cells().get(segmentIndex - 1);
        Cell target = route.cells().get(segmentIndex);

        int dirRow = Integer.signum(target.row() - previous.row());
        int dirColumn = Integer.signum(target.column() - previous.column());
        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) {
            reset();
            lastDecisionDetail = "INVALID_ROUTE_SEGMENT";
            return Action.IDLE;
        }

        /*
         * At a corner, start steering toward the next segment while the
         * player's centre is still inside the current route cell. This keeps
         * forward+sprint active instead of paying a stop/turn/go penalty.
         */
        int nextSegmentIndex = segmentIndex + 1;
        boolean approachingCorner = false;
        if (nextSegmentIndex < route.size()) {
            Cell next = route.cells().get(nextSegmentIndex);
            int nextRow = Integer.signum(next.row() - target.row());
            int nextColumn = Integer.signum(next.column() - target.column());
            if (nextRow != dirRow || nextColumn != dirColumn) {
                double distanceToCorner = Math.hypot(
                        state.player.x - (target.row() + 0.5),
                        state.player.z - (target.column() + 0.5));
                approachingCorner = distanceToCorner <= TURN_LEAD;
                if (approachingCorner) {
                    dirRow = nextRow;
                    dirColumn = nextColumn;
                }
            }
        }

        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_PER_TICK, MAX_YAW_PER_TICK);

        /*
         * W-first motor. Strafe is deliberately zero: the source-compatible
         * fastest baseline is forward+sprint, with the cursor doing the
         * steering. We keep driving during bounded turns so acceleration and
         * sprint momentum are not thrown away.
         */
        double forward = 1.0;
        boolean sprint = true;

        /*
         * Never drive toward a cell that the authoritative physical graph says
         * is air. The planned route is cardinal and physical, so this is mainly
         * a final live-world guard against an unexpected floor change or a
         * player being pushed away from the route.
         */
        if (!nextPhysicalCell(state.maze, state.player.x, state.player.z, dirRow, dirColumn)) {
            lastDecisionDetail = "EDGE_GUARD current="
                    + startRow + "," + startColumn
                    + " dir=" + dirRow + "," + dirColumn;
            return Action.IDLE;
        }

        /*
         * Source cross-check:
         * - non-Jumper kits are jump-locked server-side, but the 1.8 source
         *   explicitly preserves the jump-spam "speeding" behaviour;
         * - Jumper is the only kit with charged real jumps.
         *
         * Holding jump is therefore intentional here, rather than a generic
         * ability action. Vanilla MovementInput supplies the jump state every
         * tick; the server-side kit code decides whether it becomes a real jump.
         */
        boolean jump = state.kit != Kit.JUMPER || state.player.jumpCharges > 0;

        lastDecisionDetail = "FIRST_PAD_MOTOR"
                + " segment=" + segmentIndex + "/" + (route.size() - 1)
                + " target=" + target.row() + "," + target.column()
                + " dir=" + dirRow + "," + dirColumn
                + " cornerLead=" + approachingCorner
                + " yawError=" + format(yawError)
                + " yawDelta=" + format(yawDelta)
                + " f=1.0"
                + " sprint=true"
                + " jump=" + jump
                + " strafe=0"
                + " routeSize=" + route.size();

        return new Action(forward, 0.0, jump, sprint, yawDelta, false);
    }

    private boolean buildRoute(GameState state, int startRow, int startColumn, Cell pad) {
        try {
            List<Cell> cells = new PlayerPathfinder().shortestPathToRegion(
                    state.maze,
                    new Cell(startRow, startColumn),
                    pad,
                    SAFE_PAD_RADIUS);
            if (cells.isEmpty() || !validRoute(state.maze, cells)) return false;

            route = new PlayerRoute(cells);
            goalRow = pad.row();
            goalColumn = pad.column();
            routeSignature = routeSignature(state, pad);
            segmentIndex = 1;
            lastDecisionDetail = "FIRST_PAD_ROUTE"
                    + " size=" + route.size()
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + pad.row() + "," + pad.column()
                    + " radius=" + SAFE_PAD_RADIUS;
            return true;
        } catch (RuntimeException failure) {
            reset();
            lastDecisionDetail = "NO_ROUTE "
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            return false;
        }
    }

    private void advanceSegment(GameState state) {
        while (segmentIndex < route.size() - 1) {
            Cell current = route.cells().get(segmentIndex);
            double distance = Math.hypot(
                    state.player.x - (current.row() + 0.5),
                    state.player.z - (current.column() + 0.5));

            /*
             * Also detect crossing a waypoint between observations. The route
             * is one cell wide, so once the player is clearly inside the next
             * route cell, turning back toward the old centre is slower and can
             * be fatal at a corner.
             */
            Cell next = route.cells().get(segmentIndex + 1);
            boolean insideNext = containingCell(state.player.x, state.player.z)
                    .equals(next);

            if (distance <= ARRIVAL_TOLERANCE || insideNext) {
                segmentIndex++;
            } else {
                break;
            }
        }
    }

    private boolean routeContainsStart(int row, int column) {
        return route != null && segmentIndex < route.size()
                && route.cells().contains(new Cell(row, column));
    }

    private boolean routeIsPhysical(MazeModel maze) {
        return route != null && validRoute(maze, route.cells());
    }

    private static boolean validRoute(MazeModel maze, List<Cell> cells) {
        if (cells == null || cells.isEmpty()) return false;
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (!maze.isPhysicalFloor(cell.row(), cell.column())) return false;
            if (i == 0) continue;
            Cell previous = cells.get(i - 1);
            int dr = Math.abs(cell.row() - previous.row());
            int dc = Math.abs(cell.column() - previous.column());
            if (dr + dc != 1) return false;
        }
        return true;
    }

    private static boolean nextPhysicalCell(
            MazeModel maze, double x, double z, int dirRow, int dirColumn) {
        Cell current = containingCell(x, z);
        if (!maze.isPhysicalFloor(current.row(), current.column())) return false;
        Cell next = new Cell(current.row() + dirRow, current.column() + dirColumn);
        return maze.isPhysicalFloor(next.row(), next.column());
    }

    private static Cell containingCell(double x, double z) {
        return new Cell((int) Math.floor(x), (int) Math.floor(z));
    }

    private static boolean isOnPad(GameState state, Cell pad) {
        return me.monstermazeai.game.PadModel.isOn(
                state.player, pad.row() + 0.5, GameState.PAD_SURFACE_Y,
                pad.column() + 0.5);
    }

    private static boolean inBounds(int row, int column) {
        return row >= 0 && row < MazeModel.SIZE
                && column >= 0 && column < MazeModel.SIZE;
    }

    private static long routeSignature(GameState state, Cell pad) {
        long h = 1469598103934665603L;
        h ^= state.maze.dynamicSignature();
        h *= 1099511628211L;
        h ^= state.mazePattern;
        h *= 1099511628211L;
        h ^= pad.row();
        h *= 1099511628211L;
        h ^= pad.column();
        h *= 1099511628211L;
        return h;
    }

    private static float cardinalYaw(int rowDirection, int columnDirection) {
        if (rowDirection > 0) return -90.0F;
        if (rowDirection < 0) return 90.0F;
        if (columnDirection > 0) return 0.0F;
        return 180.0F;
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public int routeSize() {
        return route == null ? 0 : route.size();
    }

    public int segmentIndex() {
        return segmentIndex;
    }

    public void reset() {
        route = null;
        segmentIndex = -1;
        goalRow = -1;
        goalColumn = -1;
        routeSignature = Long.MIN_VALUE;
        lastDecisionDetail = "RESET";
    }
}
