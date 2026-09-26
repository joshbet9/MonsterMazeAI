package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

import java.util.List;

/**
 * Stable, corridor-safe closed-loop movement controller for the flat Monster
 * Maze surface.
 *
 * The route planner produces a cardinal cell path. The motor layer must preserve
 * that topology: Minecraft movement is continuous, but the maze is effectively
 * a graph of one-block-wide floor cells. A continuously rotating forward vector
 * can cut a 90-degree corner diagonally across an air cell. That was the main
 * remaining failure mode after the authoritative MovementInput bridge fixed the
 * old keyboard-overwrite problem.
 *
 * The controller therefore uses a strict invariant:
 *
 *   forward movement => zero yaw delta and a cardinal heading
 *
 * Turns happen in place after horizontal velocity has been allowed to decay.
 * Consecutive collinear route cells are compressed into a single movement
 * segment, so the AI does not brake/turn at every cell and the resulting path
 * remains the shortest cardinal route selected by the planner.
 */
public final class StableLiveMovementController {
    private static final double WAYPOINT_ARRIVAL = 0.32;
    private static final double WAYPOINT_BRAKE = 0.70;
    private static final double ROUTE_DEVIATION = 0.55;
    private static final int MIN_REPLAN_INTERVAL = 5;
    private static final double THREAT_RADIUS = 3.0;
    private static final double THREAT_TIME = 6.0;

    /** Minecraft 1.8 yaw is allowed to turn at most 12 degrees per tick. */
    private static final float MAX_TURN_PER_TICK = 12.0F;
    /** Forward input is only permitted when the heading is effectively exact. */
    private static final float HEADING_TOLERANCE = 2.0F;
    /** Let vanilla friction kill lateral/forward momentum before a corner turn. */
    private static final double MAX_TURNING_SPEED = 0.035;
    /** Do not attempt lane recovery once the player is already near the cell edge. */
    private static final double MAX_SAFE_LANE_ERROR = 0.42;

    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();

    private PlayerRoute route;
    /** Index of the next turn/goal cell, not merely the next adjacent cell. */
    private int waypointIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private long lastRouteTick = Long.MIN_VALUE;
    private String lastDecisionDetail = "UNSET";

    public Action nextAction(GameState state, Cell goal, boolean allowJump) {
        if (state == null || state.maze == null || goal == null) {
            reset();
            lastDecisionDetail = "INVALID_INPUT";
            return Action.IDLE;
        }

        if (goal.row() != goalRow || goal.column() != goalColumn) {
            clearRoute();
            goalRow = goal.row();
            goalColumn = goal.column();
        }

        int startRow = (int) Math.floor(state.player.x);
        int startColumn = (int) Math.floor(state.player.z);
        if (!inBounds(startRow, startColumn) || !inBounds(goal.row(), goal.column())) {
            lastDecisionDetail = "OUT_OF_BOUNDS start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
            return Action.IDLE;
        }

        if (route == null || shouldReplan(state, startRow, startColumn)) {
            route = routePlanner.route(state, new Cell(startRow, startColumn), goal);
            waypointIndex = firstTurnWaypoint(route);
            lastRouteTick = state.tick;
            lastDecisionDetail = "ROUTE_REPLAN size=" + route.size()
                    + " start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
        }

        if (route.size() == 1) {
            lastDecisionDetail = "REACHED routeSize=1";
            return Action.IDLE;
        }

        // A route waypoint is a turn cell. Once its centre is reached, switch
        // to the next segment. Never skip over a corner and then turn back.
        while (waypointIndex < route.size() - 1
                && distanceToWaypoint(state, waypointIndex) <= WAYPOINT_ARRIVAL) {
            waypointIndex = nextTurnWaypoint(route, waypointIndex);
        }

        if (waypointIndex >= route.size()) {
            lastDecisionDetail = "REACHED routeSize=" + route.size();
            return Action.IDLE;
        }

        double targetX = route.targetX(waypointIndex);
        double targetZ = route.targetZ(waypointIndex);
        double dx = targetX - state.player.x;
        double dz = targetZ - state.player.z;
        double distance = Math.hypot(dx, dz);

        int startIndex = waypointIndex - 1;
        int startCellRow = route.cells().get(startIndex).row();
        int startCellColumn = route.cells().get(startIndex).column();
        int targetCellRow = route.cells().get(waypointIndex).row();
        int targetCellColumn = route.cells().get(waypointIndex).column();

        int dirRow = Integer.signum(targetCellRow - startCellRow);
        int dirColumn = Integer.signum(targetCellColumn - startCellColumn);

        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) {
            // Defensive failure: PlayerRoute must be cardinal. Never issue a
            // diagonal command if the route invariant is broken.
            lastDecisionDetail += " INVALID_SEGMENT="
                    + startCellRow + "," + startCellColumn + "->"
                    + targetCellRow + "," + targetCellColumn;
            return Action.IDLE;
        }

        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        double speed = Math.hypot(state.player.vx, state.player.vz);

        /*
         * Keep the player on the route's cell centreline. Normally this error
         * is tiny. If physics nudges the player sideways inside the current
         * floor cell, briefly correct toward that centre before resuming the
         * cardinal segment. The correction is only permitted while comfortably
         * inside the current cell; near an edge we stop rather than drive into
         * an unknown/air cell.
         */
        double crossTrack = crossTrackError(
                state.player.x, state.player.z, startCellRow + 0.5, startCellColumn + 0.5,
                dirRow, dirColumn);

        Action action;

        if (Math.abs(crossTrack) > MAX_SAFE_LANE_ERROR) {
            action = new Action(0.0, 0.0, false, false, 0.0F, false);
            lastDecisionDetail += " SAFETY_STOP crossTrack=" + format(crossTrack);
        } else if (Math.abs(crossTrack) > 0.18) {
            double laneTargetX = dirRow == 0 ? startCellRow + 0.5 : state.player.x;
            double laneTargetZ = dirColumn == 0 ? startCellColumn + 0.5 : state.player.z;
            float correctionYaw = (float) Math.toDegrees(
                    Math.atan2(-(laneTargetX - state.player.x), laneTargetZ - state.player.z));
            float correctionError = normalise(correctionYaw - state.player.yaw);

            if (speed > MAX_TURNING_SPEED || Math.abs(correctionError) > HEADING_TOLERANCE) {
                action = new Action(
                        0.0, 0.0, false, false,
                        speed <= MAX_TURNING_SPEED
                                ? clamp(correctionError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
                                : 0.0F,
                        false);
            } else {
                action = new Action(1.0, 0.0, false, true, 0.0F, false);
            }
        } else if (Math.abs(yawError) > HEADING_TOLERANCE) {
            /*
             * Corner protocol:
             *  1. stop supplying forward input;
             *  2. let vanilla friction reduce existing momentum;
             *  3. only then rotate in place;
             *  4. resume forward once the heading is cardinal.
             *
             * This prevents the continuous-yaw controller from arcing across
             * the diagonal outside of a 1-cell maze corridor.
             */
            float turn = speed <= MAX_TURNING_SPEED
                    ? clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
                    : 0.0F;
            action = new Action(0.0, 0.0, false, false, turn, false);
        } else {
            boolean brake = distance < WAYPOINT_BRAKE
                    && closingSpeed(state, dx, dz) > 0.04;
            double forward = brake ? 0.0 : 1.0;
            boolean jump = allowJump
                    && state.player.grounded
                    && forward > 0.0
                    && distance > WAYPOINT_ARRIVAL;
            action = new Action(forward, 0.0, jump, forward > 0.0, 0.0F, false);
        }

        lastDecisionDetail += " waypoint=" + waypointIndex + "/" + (route.size() - 1)
                + " target=" + targetX + "," + targetZ
                + " dist=" + format(distance)
                + " dir=" + dirRow + "," + dirColumn
                + " yawError=" + format(yawError)
                + " crossTrack=" + format(crossTrack)
                + " speed=" + format(speed)
                + " output=f=" + action.forward()
                + ",s=" + action.strafe()
                + ",jump=" + action.jump()
                + ",yawDelta=" + action.yawDelta();
        return action;
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public void reset() {
        clearRoute();
        goalRow = -1;
        goalColumn = -1;
        lastRouteTick = Long.MIN_VALUE;
        lastDecisionDetail = "RESET";
    }

    private boolean shouldReplan(GameState state, int startRow, int startColumn) {
        if (route == null) return true;

        Cell current = new Cell(startRow, startColumn);
        if (!route.cells().contains(current)) return true;

        /*
         * A route is committed in the graph, but a continuous player can drift
         * outside its one-cell corridor without changing containingCell yet.
         * Treat that as a genuine deviation rather than continuing to drive
         * toward a stale corner.
         */
        if (distanceFromRouteCorridor(state, route, waypointIndex) > ROUTE_DEVIATION) {
            return true;
        }

        if (state.tick - lastRouteTick < MIN_REPLAN_INTERVAL) {
            return false;
        }

        // Replan only for an imminent threat. Normal monster motion must not
        // cause the route target to flip every tick.
        double threatSq = THREAT_RADIUS * THREAT_RADIUS;
        for (var monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) {
                continue;
            }

            double mx = monster.x + monster.vx * THREAT_TIME;
            double mz = monster.z + monster.vz * THREAT_TIME;
            double mdx = state.player.x - mx;
            double mdz = state.player.z - mz;
            if (mdx * mdx + mdz * mdz <= threatSq) return true;
        }

        return false;
    }

    private static int firstTurnWaypoint(PlayerRoute route) {
        if (route.size() <= 1) return route.size();
        return nextTurnWaypoint(route, 0);
    }

    private static int nextTurnWaypoint(PlayerRoute route, int fromIndex) {
        if (fromIndex >= route.size() - 1) return route.size();

        List<Cell> cells = route.cells();
        int previousRow = cells.get(fromIndex).row();
        int previousColumn = cells.get(fromIndex).column();

        int segmentRow = Integer.signum(cells.get(fromIndex + 1).row() - previousRow);
        int segmentColumn = Integer.signum(cells.get(fromIndex + 1).column() - previousColumn);

        for (int i = fromIndex + 1; i < cells.size() - 1; i++) {
            int nextRow = Integer.signum(cells.get(i + 1).row() - cells.get(i).row());
            int nextColumn = Integer.signum(cells.get(i + 1).column() - cells.get(i).column());
            if (nextRow != segmentRow || nextColumn != segmentColumn) {
                return i;
            }
        }
        return cells.size() - 1;
    }

    private static float cardinalYaw(int rowDirection, int columnDirection) {
        // Logical row is world X; logical column is world Z.
        if (rowDirection > 0) return -90.0F; // +X / east
        if (rowDirection < 0) return 90.0F;  // -X / west
        if (columnDirection > 0) return 0.0F; // +Z / south
        return 180.0F; // -Z / north
    }

    private static double crossTrackError(
            double x, double z, double laneX, double laneZ,
            int rowDirection, int columnDirection) {
        if (rowDirection == 0) return x - laneX;
        return z - laneZ;
    }

    private static double closingSpeed(GameState state, double dx, double dz) {
        double length = Math.max(Math.hypot(dx, dz), 1.0E-6);
        return (state.player.vx * dx + state.player.vz * dz) / length;
    }

    private double distanceToWaypoint(GameState state, int index) {
        return Math.hypot(
                state.player.x - route.targetX(index),
                state.player.z - route.targetZ(index));
    }

    private double distanceFromRouteCorridor(GameState state, PlayerRoute route, int targetIndex) {
        if (targetIndex <= 0 || targetIndex >= route.size()) return 0.0;

        Cell start = route.cells().get(targetIndex - 1);
        Cell target = route.cells().get(targetIndex);
        double startX = start.row() + 0.5;
        double startZ = start.column() + 0.5;
        double targetX = target.row() + 0.5;
        double targetZ = target.column() + 0.5;

        double dx = targetX - startX;
        double dz = targetZ - startZ;
        if (Math.abs(dx) > Math.abs(dz)) {
            return Math.abs(state.player.z - startZ);
        }
        return Math.abs(state.player.x - startX);
    }

    private static boolean inBounds(int row, int column) {
        return row >= 0 && row < me.monstermazeai.maze.MazeModel.SIZE
                && column >= 0 && column < me.monstermazeai.maze.MazeModel.SIZE;
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
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private void clearRoute() {
        route = null;
        waypointIndex = 0;
    }
}
