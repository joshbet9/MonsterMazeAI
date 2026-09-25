package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

/**
 * Closed-loop controller that combines the maze graph with physical planning.
 */
public final class MazeAwareRecedingHorizonController {
    private final BeamSearchPlanner planner;
    private final int executionTicks;
    private final double waypointTolerance;
    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();
    private String lastDecisionDetail = "UNSET";

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner, int executionTicks) {
        this(planner, executionTicks, 0.75);
    }

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,
                                               int executionTicks,
                                               double waypointTolerance) {
        if (executionTicks < 1 || waypointTolerance <= 0.0) throw new IllegalArgumentException();
        this.planner = planner;
        this.executionTicks = executionTicks;
        this.waypointTolerance = waypointTolerance;
    }

    public String lastDecisionDetail() { return lastDecisionDetail; }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump) {
        if (state == null) {
            lastDecisionDetail = "NULL_STATE";
            return new Action[]{Action.IDLE};
        }
        if (state.maze == null) {
            lastDecisionDetail = "NO_MAZE";
            return new Action[]{Action.IDLE};
        }

        int startRow = (int) Math.floor(state.player.x);
        int startColumn = (int) Math.floor(state.player.z);
        if (startRow < 0 || startColumn < 0
                || startRow >= MazeModelSize() || startColumn >= MazeModelSize()) {
            lastDecisionDetail = "PLAYER_CELL_OUT_OF_BOUNDS row=" + startRow + " col=" + startColumn;
            return new Action[]{Action.IDLE};
        }

        PlayerRoute route;
        try {
            route = routePlanner.route(state, new Cell(startRow, startColumn), goal);
        } catch (RuntimeException ex) {
            lastDecisionDetail = "ROUTE_EXCEPTION " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            throw ex;
        }

        int waypoint = route.nextWaypoint(
                state.player.x, state.player.z, 0, waypointTolerance);

        if (route.reached(state.player.x, state.player.z, waypointTolerance)) {
            lastDecisionDetail = "ROUTE_REACHED size=" + route.size()
                    + " start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
            return new Action[]{Action.IDLE};
        }

        double targetX = route.targetX(waypoint);
        double targetZ = route.targetZ(waypoint);

        BeamSearchPlanner.Plan plan;
        try {
            plan = planner.plan(state, targetX, targetZ, allowJump);
        } catch (RuntimeException ex) {
            lastDecisionDetail = "PLANNER_EXCEPTION " + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage()
                    + " routeSize=" + route.size()
                    + " waypoint=" + waypoint
                    + " target=" + targetX + "," + targetZ;
            throw ex;
        }

        Action[] actions = plan.sequence().actions();
        Action first = actions.length == 0 ? Action.IDLE : actions[0];
        lastDecisionDetail = "ROUTE size=" + route.size()
                + " start=" + startRow + "," + startColumn
                + " goal=" + goal.row() + "," + goal.column()
                + " waypoint=" + waypoint + "/" + (route.size() - 1)
                + " target=" + targetX + "," + targetZ
                + " planLen=" + actions.length
                + " planReached=" + plan.padReached()
                + " planReason=\"" + plan.decisionReason() + "\""
                + " first=" + describe(first);

        if (actions.length == 0 || actions[0] == Action.IDLE) {
            double dx = targetX - state.player.x;
            double dz = targetZ - state.player.z;
            if (Math.hypot(dx, dz) > 1.0E-6) {
                float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float delta = desiredYaw - state.player.yaw;
                while (delta >= 180.0F) delta -= 360.0F;
                while (delta < -180.0F) delta += 360.0F;
                Action fallback = new Action(1, 0,
                        allowJump && !state.player.grounded, true, delta, false);
                lastDecisionDetail += " FALLBACK=" + describe(fallback);
                return new Action[]{fallback};
            }
            lastDecisionDetail += " FALLBACK_SUPPRESSED_ZERO_VECTOR";
            return new Action[]{Action.IDLE};
        }

        return java.util.Arrays.copyOf(actions, Math.min(executionTicks, actions.length));
    }

    private static int MazeModelSize() {
        return me.monstermazeai.maze.MazeModel.SIZE;
    }

    private static String describe(Action action) {
        return "f=" + action.forward()
                + ",s=" + action.strafe()
                + ",jump=" + action.jump()
                + ",sprint=" + action.sprint()
                + ",yawDelta=" + action.yawDelta()
                + ",ability=" + action.useAbility();
    }
}
