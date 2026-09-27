package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.physics.LegacyMovementModel;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.physics.SpeedContactModel;
import me.monstermazeai.player.Action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Closed-loop source-world simulator.
 *
 * Every candidate action is executed through the same player movement model,
 * source-derived monster movement, kit ability model and MonsterManager bump
 * rules. The live motor supports the same action dimensions, so the planner
 * cannot select an action that the adapter cannot execute.
 */
public final class TacticalRouteSimulator {
    private static final int TACTICAL_HORIZON = 8;
    private static final int TACTICAL_BEAM = 20;
    private static final int MAX_SIMULATION_TICKS = 2400;
    private static final double WAYPOINT_TOLERANCE = 0.30;

    private final LegacyMovementModel physics = new LegacyMovementModel();
    private final AbilityModel abilities = new AbilityModel();

    public Action nextAction(GameState source, PlayerRoute route, Cell goal,
                              boolean regionGoal, int regionRadius) {
        if (!needsTacticalSearch(source)) return routeFollowerAction(source, route, 0);
        return chooseTacticalAction(source, route, 0, goal, regionGoal, regionRadius);
    }

    public boolean shouldUseTacticalAction(GameState state) {
        return needsTacticalSearch(state);
    }

    public Result simulate(GameState source, PlayerRoute route, Cell goal,
                           boolean regionGoal, int regionRadius) {
        GameState state = source.copy();
        initialiseMissingAbilityState(state);
        int waypoint = route.nextWaypoint(state.player.x, state.player.z, 0, WAYPOINT_TOLERANCE);
        MonsterSimulator monsters = monsterSimulator(state, source.tick);

        for (int elapsed = 1; elapsed <= MAX_SIMULATION_TICKS; elapsed++) {
            state.tick = source.tick + elapsed;
            waypoint = route.nextWaypoint(state.player.x, state.player.z, waypoint, WAYPOINT_TOLERANCE);

            if (goalReached(state, route, waypoint, goal, regionGoal, regionRadius)) {
                return success(elapsed - 1, state, waypoint);
            }

            Action action = needsTacticalSearch(state)
                    ? chooseTacticalAction(state, route, waypoint, goal, regionGoal, regionRadius)
                    : routeFollowerAction(state, route, waypoint);

            step(state, action, monsters);

            if (state.player.health <= 0.0) {
                state.alive = false;
                return new Result(false, Integer.MAX_VALUE, state.player.health,
                        state.player.damageTaken, state, waypoint);
            }
        }

        return new Result(false, Integer.MAX_VALUE, state.player.health,
                state.player.damageTaken, state, waypoint);
    }

    private Result success(int ticks, GameState state, int waypoint) {
        state.padReached = true;
        return new Result(true, ticks, state.player.health,
                state.player.damageTaken, state, waypoint);
    }

    private void step(GameState state, Action action, MonsterSimulator monsters) {
        if (action.useAbility()) abilities.activate(state);

        GameState beforeContact = state.copy();
        boolean wasGrounded = state.player.grounded;
        physics.tick(state.player, action);

        // Source jumpEvent runs once per server tick. It consumes a Jumper
        // charge only after the player is airborne above the maze floor and
        // never while in the source's post-bump grace window.
        if (state.kit == me.monstermazeai.kit.Kit.JUMPER
                && !wasGrounded && state.player.y > GameState.PATH_Y) {
            abilities.consumeJumperCharge(state);
        }

        monsters.tick(state);
        double healthBeforeBump = state.player.health;
        MonsterMazeBumpModel.apply(state);

        // A direct, high-speed forward approach can produce the observed
        // "speeding into the mob" slide: the horizontal knockback carries the
        // player toward the void while vertical recovery is weak or absent.
        // Keep the source bump authoritative, then conservatively evaluate that
        // contact as a no-vertical-recovery outcome when the projected path
        // leaves the physical maze floor.
        SpeedContactModel.applyConservativeSlideOutcome(
                beforeContact, state, action, state.player.health < healthBeforeBump);

        // Source pad healing is applied by GameManager when the active pad is
        // reached. Route simulation ends at the current objective, so the
        // transition is applied here to make the returned state authoritative.
        if (isOnActivePad(state)) {
            abilities.onReachedPad(state, true);
            state.padReached = true;
        }
    }

    private void initialiseMissingAbilityState(GameState state) {
        if (state.ability == null) state.ability = new me.monstermazeai.ability.AbilityState();
        if (state.ability.charges == 0 && state.kit != me.monstermazeai.kit.Kit.BODY_BUILDER
                && state.kit != me.monstermazeai.kit.Kit.MAVERICK
                && state.kit != me.monstermazeai.kit.Kit.SLOWBALLER) {
            abilities.initialiseForMode(state);
        }
    }

    private MonsterSimulator monsterSimulator(GameState state, long seed) {
        long stableSeed = 0x4D4D5AL ^ seed;
        for (var m : state.monsters) stableSeed = stableSeed * 31L + m.id;
        double speedMultiplier = 1.0 + 0.2 * ((Math.max(1, state.stage) - 1) / 5);
        return new MonsterSimulator(state.maze, new Random(stableSeed), 1.4 * speedMultiplier);
    }

    private Action chooseTacticalAction(GameState source, PlayerRoute route, int waypoint,
                                        Cell goal, boolean regionGoal, int regionRadius) {
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(source.copy(), waypoint, List.of()));

        for (int depth = 0; depth < TACTICAL_HORIZON; depth++) {
            List<Node> next = new ArrayList<>();
            for (Node node : beam) {
                for (Action action : tacticalActions(node.state)) {
                    GameState s = node.state.copy();
                    s.tick = source.tick + depth + 1;
                    MonsterSimulator branchMonsters = monsterSimulator(s, source.tick + depth + 1);
                    int wp = route.nextWaypoint(s.player.x, s.player.z, node.waypoint, WAYPOINT_TOLERANCE);
                    step(s, action, branchMonsters);
                    if (s.player.health <= 0.0) continue;
                    next.add(new Node(s, wp, append(node.actions, action)));
                }
            }
            next.sort(Comparator.comparingLong(n ->
                    tacticalRank(n.state, route, n.waypoint, goal, regionGoal, regionRadius)));
            if (next.size() > TACTICAL_BEAM) next.subList(TACTICAL_BEAM, next.size()).clear();
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
        long remaining = Math.max(0, route.size() - 1L - waypoint);
        long distance = Math.min(999_999L, Math.round(distanceToWaypoint(state, route, waypoint) * 1000));
        long damage = Math.min(999_999L, Math.round(state.player.damageTaken * 1000));
        return remaining * 1_000_000_000_000L + distance * 1_000_000L + damage;
    }

    private boolean needsTacticalSearch(GameState state) {
        double reach = 0.45 + Math.hypot(state.player.vx, state.player.vz) * TACTICAL_HORIZON;
        for (var m : state.monsters) {
            if (m.removed || m.launched(state.tick) || m.frozen(state.tick)) continue;
            double separation = Math.sqrt(
                    sq(state.player.x - m.x) + sq(state.player.y - m.y) + sq(state.player.z - m.z));
            double monsterReach = Math.hypot(m.vx, m.vz) * TACTICAL_HORIZON;
            if (separation <= MonsterMazeBumpModel.CONTACT_DISTANCE + reach + monsterReach) return true;
        }
        // Ability use can be strategically useful before contact. Let the beam
        // search decide when a QOL ability is available and monsters are within
        // its actual source radius.
        if (state.kit != me.monstermazeai.kit.Kit.JUMPER) {
            for (var m : state.monsters) {
                double d = Math.sqrt(sq(state.player.x-m.x)+sq(state.player.y-m.y)+sq(state.player.z-m.z));
                if (d <= 6.0) return true;
            }
        }
        return false;
    }

    private List<Action> tacticalActions(GameState state) {
        boolean jump = state.kit == me.monstermazeai.kit.Kit.JUMPER
                && state.player.grounded && state.player.jumpCharges > 0;
        List<Action> out = new ArrayList<>();
        double[] turns = {-30, 0, 30};
        double[] inputs = {-1, 0, 1};
        for (double forward : inputs) {
            for (double strafe : inputs) {
                for (double turn : turns) {
                    if (forward == 0 && strafe == 0 && turn != 0) continue;
                    out.add(new Action(forward, strafe, jump && forward >= 0, forward != 0, (float)turn, false));
                }
            }
        }
        // Ability is an independent right-click action; include it separately
        // so the source item is not implicitly consumed on every movement tick.
        out.add(new Action(0, 0, false, false, 0, true));
        out.add(new Action(1, 0, jump, true, 0, true));
        out.add(new Action(-1, 0, false, false, 0, true));
        return out;
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
        if (regionGoal) {
            return isOnExactPadRegion(state, goal);
        }
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
