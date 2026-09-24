package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

/**
 * Closed-loop controller that combines the maze graph with physical planning.
 *
 * The maze supplies navigation waypoints and the monster-aware route planner
 * can prefer safer physical corridors; the BeamSearchPlanner then supplies
 * the physically simulated controls needed to reach the current waypoint.
 */
public final class MazeAwareRecedingHorizonController {
    private final BeamSearchPlanner planner;
    private final int executionTicks;
    private final double waypointTolerance;
    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,
                                               int executionTicks) {
        this(planner, executionTicks, 0.75);
    }

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,
                                               int executionTicks,
                                               double waypointTolerance) {
        if (executionTicks < 1 || waypointTolerance <= 0.0) {
            throw new IllegalArgumentException();
        }
        this.planner = planner;
        this.executionTicks = executionTicks;
        this.waypointTolerance = waypointTolerance;
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump) {
        if (state.maze == null) return new Action[]{Action.IDLE};

        int startRow = (int) Math.floor(state.player.x);
        int startColumn = (int) Math.floor(state.player.z);
        if (startRow < 0 || startColumn < 0
                || startRow >= 99 || startColumn >= 99) {
            return new Action[]{Action.IDLE};
        }

        PlayerRoute route = routePlanner.route(
                state, new Cell(startRow, startColumn), goal);

        int waypoint = route.nextWaypoint(
                state.player.x, state.player.z, 0, waypointTolerance);

        if (route.reached(state.player.x, state.player.z, waypointTolerance)) {
            return new Action[]{Action.IDLE};
        }

        double targetX = route.targetX(waypoint);
        double targetZ = route.targetZ(waypoint);

        BeamSearchPlanner.Plan plan =
                planner.plan(state, targetX, targetZ, allowJump);
        Action[] actions = plan.sequence().actions();
        if (actions.length == 0 || actions[0] == Action.IDLE) {
            /*
             * A live objective that has not been reached must never become
             * motionless merely because the beam's incumbent remained at the
             * source state. Use the first route waypoint as a physically
             * directed one-tick fallback. This still respects the maze route;
             * it does not bypass pathfinding or inject a hard-coded direction.
             */
            double dx = targetX - state.player.x;
            double dz = targetZ - state.player.z;
            if (Math.hypot(dx, dz) > 1.0E-6) {
                float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float delta = desiredYaw - state.player.yaw;
                while (delta >= 180.0F) delta -= 360.0F;
                while (delta < -180.0F) delta += 360.0F;
                return new Action[]{
                        new Action(1, 0, allowJump && !state.player.grounded,
                                true, delta, false)
                };
            }
            return new Action[]{Action.IDLE};
        }

        return java.util.Arrays.copyOf(
                actions, Math.min(executionTicks, actions.length));
    }
}
