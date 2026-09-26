package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.player.Action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Closed-loop route evaluator for Monster Maze.
 *
 * This is deliberately a simulator rather than a mob-risk formula. A route is
 * judged by the state it actually produces under 1.8 movement and source-game
 * monster bumps. A monster that never intersects the simulated trajectory has
 * no cost; a monster that does intersect can change the trajectory, health and
 * arrival time.
 *
 * The local tactical search is allowed to reverse, strafe and turn into a
 * monster. This is important because the source bump vector is determined by
 * the monster-to-player contact geometry, not by a fixed "knockback penalty".
 */
public final class TacticalRouteSimulator {
    private static final int TACTICAL_HORIZON = 6;
    private static final int TACTICAL_BEAM = 12;
    private static final double CONTACT_LOOKAHEAD_PLAYER = 0.35;
    private static final double WAYPOINT_TOLERANCE = 0.55;
    private static final int MAX_SIMULATION_TICKS = 2400;

    private final LegacyMazePhysics physics = new LegacyMazePhysics();

    public Result simulate(GameState source, PlayerRoute route, Cell goal, boolean regionGoal, int regionRadius) {
        GameState state = source.copy();
        int waypoint = 0;

        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            state.tick = source.tick + tick;

            waypoint = advanceWaypoint(state, route, waypoint);
            if (goalReached(state, route, waypoint, goal, regionGoal, regionRadius)) {
                return new Result(true, tick - 1, state.player.health,
                        state.player.damageTaken, state, waypoint);
            }

            Action action;
            if (needsTacticalSearch(state)) {
                action = chooseTacticalAction(state, route, waypoint, goal, regionGoal, regionRadius);
            } else {
                action = routeFollowerAction(state, route, waypoint);
            }

            physics.tick(state.player, action);
            moveMonsters(state);
            MonsterMazeBumpModel.apply(state.player, state.monsters, state.tick);

            if (state.player.health <= 0.0) {
                return new Result(false, Integer.MAX_VALUE, state.player.health,
                        state.player.damageTaken, state, waypoint);
            }
        }

        return new Result(false, Integer.MAX_VALUE, state.player.health,
                state.player.damageTaken, state, waypoint);
    }

    private Action chooseTacticalAction(GameState source, PlayerRoute route, int waypoint,
                                        Cell goal, boolean regionGoal, int regionRadius) {
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(source.copy(), waypoint, List.of()));

        for (int depth = 0; depth < TACTICAL_HORIZON; depth++) {
            ArrayList<Node> next = new ArrayList<>();
            for (Node node : beam) {
                for (Action action : tacticalActions(node.state)) {
                    GameState s = node.state.copy();
                    s.tick = source.tick + depth + 1;

                    int wp = advanceWaypoint(s, route, node.waypoint);
                    physics.tick(s.player, action);
                    moveMonsters(s);
                    MonsterMazeBumpModel.apply(s.player, s.monsters, s.tick);

                    if (s.player.health <= 0.0) continue;

                    next.add(new Node(s, wp, append(node.actions, action)));
                }
            }

            next.sort(Comparator.comparingLong(n ->
                    tacticalRank(n.state, route, n.waypoint, goal, regionGoal, regionRadius)));
            if (next.size() > TACTICAL_BEAM) {
                next.subList(TACTICAL_BEAM, next.size()).clear();
            }
            beam = next;
            if (beam.isEmpty()) return routeFollowerAction(source, route, waypoint);
        }

        return beam.get(0).actions.isEmpty()
                ? routeFollowerAction(source, route, waypoint)
                : beam.get(0).actions.get(0);
    }

    private long tacticalRank(GameState state, PlayerRoute route, int waypoint,
                              Cell goal, boolean regionGoal, int regionRadius) {
        if (goalReached(state, route, waypoint, goal, regionGoal, regionRadius)) return 0L;

        // Lexicographic packing: route progress dominates contact damage,
        // then geometric distance. No hand-tuned "monster penalty" is used.
        long remaining = route.size() - 1L - waypoint;
        long distanceMicros = Math.min(999_999L,
                Math.round(distanceToWaypoint(state, route, waypoint) * 1_000.0));
        long damageMicros = Math.min(999_999L,
                Math.round(state.player.damageTaken * 1_000.0));

        return remaining * 1_000_000_000_000L
                + distanceMicros * 1_000_000L
                + damageMicros;
    }

    private boolean needsTacticalSearch(GameState state) {
        double playerSpeed = Math.hypot(state.player.vx, state.player.vz);
        double playerReach = CONTACT_LOOKAHEAD_PLAYER + 0.35 + playerSpeed * TACTICAL_HORIZON;

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double mx = monster.x;
            double mz = monster.z;
            double dx = state.player.x - mx;
            double dz = state.player.z - mz;
            double separation = Math.hypot(dx, dz);
            double monsterReach = Math.hypot(monster.vx, monster.vz) * TACTICAL_HORIZON;

            if (separation <= MonsterMazeBumpModel.CONTACT_DISTANCE
                    + playerReach + monsterReach) {
                return true;
            }
        }
        return false;
    }

    private List<Action> tacticalActions(GameState state) {
        boolean jump = state.player.grounded;
        return List.of(
                new Action(1, 0, false, true, 0, false),
                new Action(-1, 0, false, false, 0, false),
                new Action(0, 1, false, false, 0, false),
                new Action(0, -1, false, false, 0, false),
                new Action(1, 1, false, true, 0, false),
                new Action(1, -1, false, true, 0, false),
                new Action(-1, 1, false, false, 0, false),
                new Action(-1, -1, false, false, 0, false),
                new Action(0, 0, false, false, 0, false),
                new Action(1, 0, jump, true, -30, false),
                new Action(1, 0, jump, true, 30, false),
                new Action(-1, 0, false, false, -30, false),
                new Action(-1, 0, false, false, 30, false)
        );
    }

    private Action routeFollowerAction(GameState state, PlayerRoute route, int waypoint) {
        if (waypoint >= route.size()) return Action.IDLE;

        double dx = route.targetX(waypoint) - state.player.x;
        double dz = route.targetZ(waypoint) - state.player.z;
        double distance = Math.hypot(dx, dz);
        if (distance <= WAYPOINT_TOLERANCE) return Action.IDLE;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float error = normalise(desiredYaw - state.player.yaw);
        float delta = Math.max(-30.0F, Math.min(30.0F, error));

        // Turning in place while the waypoint is substantially behind avoids
        // building lateral momentum in the wrong direction.
        double forward = Math.abs(error) > 90.0F ? 0.0 : 1.0;
        return new Action(forward, 0, false, forward > 0.0, delta, false);
    }

    private void moveMonsters(GameState state) {
        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;
            monster.x += monster.vx;
            monster.y += monster.vy;
            monster.z += monster.vz;
        }
    }

    private int advanceWaypoint(GameState state, PlayerRoute route, int waypoint) {
        int index = Math.max(0, Math.min(waypoint, route.size() - 1));
        while (index < route.size() - 1
                && distanceToWaypoint(state, route, index) <= WAYPOINT_TOLERANCE) {
            index++;
        }
        return index;
    }

    private boolean goalReached(GameState state, PlayerRoute route, int waypoint,
                                Cell goal, boolean regionGoal, int radius) {
        if (regionGoal) {
            int row = (int) Math.floor(state.player.x);
            int column = (int) Math.floor(state.player.z);
            return Math.abs(row - goal.row()) <= radius
                    && Math.abs(column - goal.column()) <= radius;
        }
        return waypoint >= route.size() - 1
                && distanceToWaypoint(state, route, route.size() - 1) <= WAYPOINT_TOLERANCE;
    }

    private double distanceToWaypoint(GameState state, PlayerRoute route, int waypoint) {
        int index = Math.max(0, Math.min(waypoint, route.size() - 1));
        return Math.hypot(state.player.x - route.targetX(index),
                state.player.z - route.targetZ(index));
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static List<Action> append(List<Action> actions, Action action) {
        ArrayList<Action> copy = new ArrayList<>(actions);
        copy.add(action);
        return List.copyOf(copy);
    }

    public record Result(boolean reached, int arrivalTicks, double remainingHealth,
                         double damageTaken, GameState finalState, int finalWaypoint) {}
    private record Node(GameState state, int waypoint, List<Action> actions) {}
}
