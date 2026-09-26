package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

/**
 * Stable, closed-loop movement controller for the flat Monster Maze surface.
 *
 * The previous live controller searched directly over low-level movement actions
 * every tick. That creates two independent steering dimensions (strafe and yaw)
 * and, combined with a changing monster-risk route, can make the selected
 * waypoint flip back and forth. This controller separates the concerns:
 *
 *   maze route -> committed waypoint -> continuous heading control -> action
 *
 * The route is held until the waypoint/objective is reached, the player has
 * materially left the route, or a genuinely imminent monster threat justifies
 * replanning. This gives the motor layer temporal continuity while retaining
 * closed-loop observation.
 */
public final class StableLiveMovementController {
    private static final double WAYPOINT_ARRIVAL = 0.55;
    private static final double WAYPOINT_SKIP = 0.80;
    private static final double ROUTE_DEVIATION = 2.25;
    private static final int MIN_REPLAN_INTERVAL = 5;
    private static final double THREAT_RADIUS = 3.0;
    private static final double THREAT_TIME = 6.0;
    private static final float MAX_TURN_PER_TICK = 12.0F;
    private static final float HOLD_HEADING_ERROR = 70.0F;

    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();

    private PlayerRoute route;
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
            waypointIndex = 0;
            lastRouteTick = state.tick;
            lastDecisionDetail = "ROUTE_REPLAN size=" + route.size()
                    + " start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
        }

        waypointIndex = route.nextWaypoint(
                state.player.x, state.player.z, waypointIndex, WAYPOINT_SKIP);

        while (waypointIndex < route.size() - 1
                && distanceToWaypoint(state, waypointIndex) <= WAYPOINT_ARRIVAL) {
            waypointIndex++;
        }

        if (waypointIndex >= route.size() - 1
                && distanceToWaypoint(state, waypointIndex) <= WAYPOINT_ARRIVAL) {
            lastDecisionDetail = "REACHED routeSize=" + route.size()
                    + " waypoint=" + waypointIndex;
            return Action.IDLE;
        }

        double targetX = route.targetX(waypointIndex);
        double targetZ = route.targetZ(waypointIndex);
        double dx = targetX - state.player.x;
        double dz = targetZ - state.player.z;
        double distance = Math.hypot(dx, dz);

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        /*
         * Do not translate while the target is substantially behind us. Turning
         * in place for a few ticks is faster than building lateral momentum and
         * then trying to cancel it with a reverse/strafe action.
         */
        double forward = Math.abs(yawError) > HOLD_HEADING_ERROR ? 0.0 : 1.0;

        /*
         * Minecraft 1.8 has meaningful horizontal inertia. Near a waypoint,
         * remove drive when the current velocity is already carrying us into
         * the target. Friction then brakes naturally without introducing a
         * reverse command that would make the controller oscillate.
         */
        if (distance < 1.15) {
            double length = Math.max(distance, 1.0E-6);
            double ux = dx / length;
            double uz = dz / length;
            double closingSpeed = state.player.vx * ux + state.player.vz * uz;
            if (closingSpeed > distance * 0.75) {
                forward = 0.0;
            }
        }

        boolean jump = allowJump
                && state.player.grounded
                && forward > 0.0
                && distance > WAYPOINT_ARRIVAL;

        Action action = new Action(forward, 0.0, jump, forward > 0.0, yawDelta, false);
        lastDecisionDetail += " waypoint=" + waypointIndex + "/" + (route.size() - 1)
                + " target=" + targetX + "," + targetZ
                + " dist=" + String.format(java.util.Locale.ROOT, "%.3f", distance)
                + " yawError=" + String.format(java.util.Locale.ROOT, "%.2f", yawError)
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
        boolean currentCellOnRoute = route.cells().contains(current);
        if (!currentCellOnRoute) return true;

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
            double dx = state.player.x - mx;
            double dz = state.player.z - mz;
            if (dx * dx + dz * dz <= threatSq) return true;
        }

        return false;
    }

    private double distanceToWaypoint(GameState state, int index) {
        return Math.hypot(
                state.player.x - route.targetX(index),
                state.player.z - route.targetZ(index));
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

    private void clearRoute() {
        route = null;
        waypointIndex = 0;
    }
}
