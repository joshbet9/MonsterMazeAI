package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

/**
 * Closed-loop controller that combines the maze graph with physical planning.
 *
 * The maze supplies navigation waypoints; the BeamSearchPlanner supplies the
 * physically simulated controls needed to reach the current waypoint. No
 * block/wall collision is introduced.
 */
public final class MazeAwareRecedingHorizonController {
    private final BeamSearchPlanner planner;
    private final int executionTicks;
    private final double waypointTolerance;

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

        PlayerRoute route = PlayerRoute.between(
                state.maze, new Cell(startRow, startColumn), goal);

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
        if (actions.length == 0) return new Action[]{Action.IDLE};

        return java.util.Arrays.copyOf(
                actions, Math.min(executionTicks, actions.length));
    }
}
