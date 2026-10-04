package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterRelevance;
import me.monstermazeai.physics.LegacyMovementModel;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Closed-loop source-world simulator.
 *
 * Performance optimisations are deliberately outside authoritative mechanics:
 * only monsters inside the player's current local interaction radius enter
 * expensive simulation. The live observation remains full, and fresh
 * observations re-evaluate the radius continuously. Source-faithful monster
 * physics/contact semantics remain unchanged.
 */
public final class TacticalRouteSimulator {
    private static final int TACTICAL_HORIZON = 6;
    private static final int TACTICAL_BEAM = 10;
    private static final int MAX_SIMULATION_TICKS = 2400;
    private static final double ROUTE_TICKS_PER_CELL = 12.0;
    private static final int ROUTE_TICK_MARGIN = 40;
    private static final double TACTICAL_RELEVANCE_RADIUS = MonsterRelevance.INTERACTION_RADIUS;
    private static final double WAYPOINT_TOLERANCE = 0.30;

    private final LegacyMovementModel physics = new LegacyMovementModel();
    private final AbilityModel abilities = new AbilityModel();

    public Action nextAction(GameState source, PlayerRoute route, Cell goal,
                           boolean regionGoal, int regionRadius) {
        if (!needsTacticalSearch(source)) return routeFollowerAction(source, route, 0);
        return chooseTacticalAction(source, route, 0, goal, regionGoal, regionRadius);
    }

    private Action routeFollowerAction(GameState state, PlayerRoute route, int waypoint) {
        if (waypoint >= route.size()) return Action.IDLE;
        double dx = route.targetX(waypoint) - state.player.x;
        double dz = route.targetZ(waypoint) - state.player.z;
        double distance = Math.hypot(dx, dz);
        if (distance <= WAYPOINT_TOLERANCE) return Action.IDLE;

        float desiredYaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
        float error = normalise(desiredYaw - state.player.yaw);
        float delta = Math.max(-30, Math.min(30, error));
        double forward = Math.abs(error) > 70 ? 0 : 1;
        boolean sprint = forward != 0;
        return new Action(forward, 0, false, sprint, delta, false);
    }

    private boolean goalReached(GameState state, PlayerRoute route, int waypoint,
                                Cell goal, boolean regionGoal, int radius) {
        if (regionGoal) return isOnExactPadRegion(state, goal);
        return waypoint >= route.size() - 1
                && distanceToWaypoint(state, route, route.size() - 1) <= WAYPOINT_TOLERANCE;
    }

    private boolean isOnActivePad(GameState state) {
        return state.activePadRow >= 0 && state.activePadColumn >= 0
                && me.monstermazeai.game.PadModel.isOn(state.player,
                state.activePadRow + 0.5, GameState.PAD_SURFACE_Y,
                state.activePadColumn + 0.5);
    }

    private boolean isOnExactPadRegion(GameState state, Cell goal) {
        return me.monstermazeai.game.PadModel.isOn(state.player,
                goal.row() + 0.5, GameState.PAD_SURFACE_Y, goal.column() + 0.5);
    }

    private double distanceToWaypoint(GameState state, PlayerRoute route, int waypoint) {
        int index = Math.max(0, Math.min(waypoint, route.size() - 1));
        return Math.hypot(state.player.x - route.targetX(index), state.player.z - route.targetZ(index));
    }

    private static double sq(double v) { return v * v; }

    private static float normalise(float angle) {
        while (angle >= 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private static List<Action> append(List<Action> actions, Action action) {
        ArrayList<Action> out = new ArrayList<>(actions);
        out.add(action);
        return List.copyOf(out);
    }

    public record Result(boolean reached, int arrivalTicks, double remainingHealth,
                         double damageTaken, GameState finalState, int finalWaypoint) {}
    private record Node(GameState state, int waypoint, List<Action> actions) {}
}
