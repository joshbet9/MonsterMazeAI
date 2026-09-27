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
 * Deterministic movement-only controller for the first Safe Pad milestone.
 *
 * The controller is deliberately narrower than the eventual tactical AI:
 * physical-floor BFS + W-first sprint motor + source-compatible jump input.
 * Monsters, abilities, threat scoring and alternate tactical routes are out of
 * scope for this branch.
 *
 * The important distinction from the original motor is that route following
 * is continuous. Integer cell changes do not invalidate a good route, and
 * corner steering is based on the continuous position/yaw rather than on a
 * single-cell "current cell == route cell" invariant.
 */
public final class FirstPadMovementController {
    public static final int SAFE_PAD_RADIUS = 2;

    private static final double ARRIVAL_TOLERANCE = 0.30;
    /** Maximum lateral distance from the planned polyline before recovery/replan. */
    private static final double ROUTE_CORRIDOR_RADIUS = 1.20;
    /**
     * Small tolerance around the continuous corner centre. The actual turn
     * lead is velocity-derived; this is only a minimum safety margin.
     */
    private static final double CORNER_TOLERANCE = 0.20;
    /** Conservative horizontal drag used when predicting travel during a turn. */
    private static final double MAX_HORIZONTAL_DRAG = 0.91;

    /*
     * Action accepts +/-30 degrees. The old 12 degree cap required 7-8 ticks
     * for a 90 degree turn while the player was still sprinting, which is
     * physically incompatible with one-block-wide corridors.
     */
    private static final float MAX_YAW_PER_TICK = 30.0F;

    /*
     * Forward shaping is intentionally only used while the heading is being
     * acquired. Once the yaw error is small, the motor returns to full W.
     *
     * >55 degrees: turn in place. This is only normally used at bootstrap or
     * immediately after a sharp recovery.
     * 25..55 degrees: retain a small amount of forward input so momentum is not
     * discarded completely.
     * <=25 degrees: full-speed W+sprint.
     */
    private static final float FULL_FORWARD_ERROR = 25.0F;
    private static final float BRAKE_FORWARD_ERROR = 55.0F;

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
        boolean needsBootstrap = route == null
                || goalRow != pad.row()
                || goalColumn != pad.column()
                || routeSignature != signature
                || !routeIsPhysical(state.maze);

        if (needsBootstrap) {
            if (!buildRoute(state, startRow, startColumn, pad)) {
                lastDecisionDetail = "NO_ROUTE bootstrap";
                return Action.IDLE;
            }
        } else if (routeDeviation(state.player.x, state.player.z) > ROUTE_CORRIDOR_RADIUS) {
            /*
             * This is a real route departure, not merely an integer-cell
             * transition. Replan only now. Small lateral drift during a turn
             * remains attached to the original shortest path.
             */
            if (!buildRoute(state, startRow, startColumn, pad)) {
                lastDecisionDetail = "NO_ROUTE corridor_departure="
                        + format(routeDeviation(state.player.x, state.player.z));
                return Action.IDLE;
            }
        }

        reanchorSegment(state);

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
         * Look one segment ahead, but do NOT turn a fixed distance before the
         * corner. That cuts across the corner and can put the player's centre
         * over air. Instead, calculate how far the current horizontal velocity
         * can carry the player while the required yaw change is completed.
         *
         * We deliberately use 0.91 as the drag bound: it is more conservative
         * than normal ground friction and therefore remains safe when the live
         * observer reports the player airborne during a jump-spam tick.
         */
        boolean approachingCorner = false;
        boolean cornerBraking = false;
        boolean atCorner = false;
        int steeringRow = dirRow;
        int steeringColumn = dirColumn;
        double distanceToCorner = Double.MAX_VALUE;

        int nextSegmentIndex = segmentIndex + 1;
        if (nextSegmentIndex < route.size()) {
            Cell next = route.cells().get(nextSegmentIndex);
            int nextRow = Integer.signum(next.row() - target.row());
            int nextColumn = Integer.signum(next.column() - target.column());

            if (nextRow != dirRow || nextColumn != dirColumn) {
                distanceToCorner = distanceToCellCenter(
                        state.player.x, state.player.z, target);
                float nextYaw = cardinalYaw(nextRow, nextColumn);
                float turnError = normalise(nextYaw - state.player.yaw);
                int turnTicks = (int) Math.ceil(
                        Math.abs(turnError) / MAX_YAW_PER_TICK);

                /*
                 * Only the velocity component along the incoming route segment
                 * is useful for predicting where the player will be when the
                 * yaw pulse finishes. Lateral velocity is deliberately ignored:
                 * counting it as forward travel makes the controller turn too
                 * late at real corners.
                 */
                double incomingSpeed = projectedIncomingSpeed(
                        state.player.vx, state.player.vz, dirRow, dirColumn);
                double turnTravel = 0.0;
                double retainedSpeed = incomingSpeed;
                for (int i = 0; i < turnTicks; i++) {
                    turnTravel += retainedSpeed;
                    retainedSpeed *= MAX_HORIZONTAL_DRAG;
                }

                /*
                 * A stationary/slow player still needs a small geometric turn
                 * lead. This is not a substitute for the velocity prediction:
                 * it only prevents the zero-velocity boundary case from
                 * waiting until the corner centre before starting a 90-degree
                 * yaw acquisition.
                 */
                double minimumTurnLead = Math.min(0.80, turnTicks * 0.25);
                turnTravel = Math.max(turnTravel, minimumTurnLead);

                /*
                 * Start the yaw turn while coasting. Forward is then held at
                 * zero until the player reaches the corner centre, so the
                 * camera can rotate without adding a diagonal input vector.
                 */
                approachingCorner = distanceToCorner
                        <= turnTravel + CORNER_TOLERANCE;
                if (approachingCorner) {
                    steeringRow = nextRow;
                    steeringColumn = nextColumn;
                    cornerBraking = true;
                    atCorner = distanceToCorner <= 0.30
                            || containingCell(state.player.x, state.player.z).equals(target);
                }
            }
        }

        float desiredYaw = cardinalYaw(steeringRow, steeringColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_PER_TICK, MAX_YAW_PER_TICK);

        double forward;
        boolean sprint = true;
        double absError = Math.abs(yawError);

        if (cornerBraking && !atCorner) {
            /*
             * Coast into the exact corner while the yaw pulse rotates the
             * camera. This is the critical difference from the failed motor:
             * we do not apply W toward the next segment before reaching the
             * corner cell.
             */
            forward = 0.0;
            sprint = false;
        } else if (absError > BRAKE_FORWARD_ERROR) {
            forward = 0.0;
            sprint = false;
        } else if (absError > FULL_FORWARD_ERROR) {
            forward = 0.35;
        } else {
            forward = 1.0;
        }

        if (!routeForwardCellIsPhysical(state.maze, state.player.x, state.player.z)) {
            lastDecisionDetail = "EDGE_GUARD current="
                    + startRow + "," + startColumn
                    + " dir=" + steeringRow + "," + steeringColumn;
            return Action.IDLE;
        }

        /*
         * Movement-only test policy:
         * - non-Jumper kits hold jump to exercise source 1.8 jump-spam speeding;
         * - Jumper never consumes a charged jump in this branch.
         */
        boolean jump = state.kit != Kit.JUMPER;

        lastDecisionDetail = "FIRST_PAD_MOTOR"
                + " segment=" + segmentIndex + "/" + (route.size() - 1)
                + " target=" + target.row() + "," + target.column()
                + " dir=" + steeringRow + "," + steeringColumn
                + " cornerLead=" + approachingCorner
                + " yawError=" + format(yawError)
                + " yawDelta=" + format(yawDelta)
                + " cornerBrake=" + cornerBraking
                + " cornerDistance=" + format(distanceToCorner)
                + " f=" + format(forward)
                + " sprint=" + sprint
                + " jump=" + jump
                + " strafe=0"
                + " routeSize=" + route.size()
                + " deviation=" + format(routeDeviation(state.player.x, state.player.z));

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
            segmentIndex = Math.min(1, route.size() - 1);
            lastDecisionDetail = "FIRST_PAD_ROUTE"
                    + " size=" + route.size()
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + pad.row() + "," + pad.column()
                    + " radius=" + SAFE_PAD_RADIUS
                    + " cells=" + describeRoute(route);
            return true;
        } catch (RuntimeException failure) {
            reset();
            lastDecisionDetail = "NO_ROUTE "
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            return false;
        }
    }

    /**
     * Keep the existing shortest route while the player is inside its
     * recoverable corridor. Re-anchor the active segment to the closest route
     * cell instead of requiring floor(x),floor(z) to equal a route cell.
     */
    private void reanchorSegment(GameState state) {
        if (route == null || route.size() <= 1) return;

        /*
         * Segment progress may advance when the player has actually entered a
         * route cell. That is a real waypoint transition. What we must not do
         * is use Euclidean proximity to a future cell centre as proof that the
         * waypoint was reached; at a high-speed corner the future centre can
         * become closer before the player enters that cell.
         *
         * If the current containing cell is on the committed route, advance to
         * the segment leaving that cell. If the player is temporarily between
         * cells / off-route but still inside the continuous corridor, retain the
         * committed segment and let the continuous controller handle recovery.
         */
        segmentIndex = Math.max(1, Math.min(segmentIndex, route.size() - 1));

        Cell currentCell = containingCell(state.player.x, state.player.z);
        for (int i = 0; i < route.size() - 1; i++) {
            if (route.cells().get(i).equals(currentCell)) {
                segmentIndex = Math.max(segmentIndex, i + 1);
                break;
            }
        }

        advanceSegment(state);
    }

    private void advanceSegment(GameState state) {
        while (segmentIndex < route.size() - 1) {
            Cell current = route.cells().get(segmentIndex);
            double distance = distanceToCellCenter(
                    state.player.x, state.player.z, current);

            Cell next = route.cells().get(segmentIndex + 1);
            boolean insideNext = containingCell(state.player.x, state.player.z).equals(next);

            if (distance <= ARRIVAL_TOLERANCE || insideNext) {
                segmentIndex++;
            } else {
                break;
            }
        }
    }

    private double routeDeviation(double x, double z) {
        if (route == null || route.size() == 0) return Double.MAX_VALUE;
        if (route.size() == 1) {
            return distanceToCellCenter(x, z, route.cells().get(0));
        }

        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size() - 1; i++) {
            Cell a = route.cells().get(i);
            Cell b = route.cells().get(i + 1);
            double ax = a.row() + 0.5;
            double az = a.column() + 0.5;
            double bx = b.row() + 0.5;
            double bz = b.column() + 0.5;

            double dx = bx - ax;
            double dz = bz - az;
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared == 0.0
                    ? 0.0
                    : ((x - ax) * dx + (z - az) * dz) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));

            double px = ax + t * dx;
            double pz = az + t * dz;
            best = Math.min(best, Math.hypot(x - px, z - pz));
        }
        return best;
    }

    private boolean routeIsPhysical(MazeModel maze) {
        return route != null && validRoute(maze, route.cells());
    }

    private static String describeRoute(PlayerRoute route) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < route.size(); i++) {
            if (i > 0) out.append("->");
            Cell cell = route.cells().get(i);
            out.append(cell.row()).append(',').append(cell.column());
        }
        return out.toString();
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

    private boolean routeForwardCellIsPhysical(MazeModel maze, double x, double z) {
        Cell current = containingCell(x, z);
        if (!maze.isPhysicalFloor(current.row(), current.column())) return false;

        if (route == null || segmentIndex <= 0 || segmentIndex >= route.size()) {
            return true;
        }

        Cell previous = route.cells().get(segmentIndex - 1);
        Cell target = route.cells().get(segmentIndex);
        if (current.equals(previous)) {
            return maze.isPhysicalFloor(target.row(), target.column());
        }
        if (current.equals(target)) {
            if (segmentIndex + 1 >= route.size()) return true;
            Cell next = route.cells().get(segmentIndex + 1);
            return maze.isPhysicalFloor(next.row(), next.column());
        }

        /*
         * We deliberately do not require current to be an exact route cell.
         * The continuous corridor check above decides whether recovery is safe.
         */
        return routeDeviation(x, z) <= ROUTE_CORRIDOR_RADIUS;
    }

    private static Cell containingCell(double x, double z) {
        return new Cell((int) Math.floor(x), (int) Math.floor(z));
    }

    private static double distanceToCellCenter(double x, double z, Cell cell) {
        return Math.hypot(x - (cell.row() + 0.5), z - (cell.column() + 0.5));
    }

    private static double projectedIncomingSpeed(
            double vx, double vz, int rowDirection, int columnDirection) {
        double length = Math.hypot(rowDirection, columnDirection);
        if (length <= 1.0e-9) return 0.0;

        /*
         * row +1 is Minecraft -X; column +1 is +Z. Project velocity onto the
         * direction of the current route segment rather than using total speed.
         */
        double directionX = rowDirection == 0 ? 0.0 : -rowDirection / length;
        double directionZ = columnDirection == 0 ? 0.0 : columnDirection / length;
        return Math.max(0.0, vx * directionX + vz * directionZ);
    }

    private static boolean isOnPad(GameState state, Cell pad) {
        return me.monstermazeai.game.PadModel.isOn(
                state.player, pad.row() + 0.5, GameState.PAD_SURFACE_Y,
                pad.column() + 0.5);
    }

    private static boolean inBounds(int row, int column) {
        return row >= 0 && row < MazeModel.SIZE && column >= 0 && column < MazeModel.SIZE;
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
