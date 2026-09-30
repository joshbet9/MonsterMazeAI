package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterRelevance;
import me.monstermazeai.physics.LegacyMovementModel;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.physics.SpeedContactModel;
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

    public boolean shouldUseTacticalAction(GameState state) {
        return needsTacticalSearch(state);
    }

    public Result simulate(GameState source, PlayerRoute route, Cell goal,
                           boolean regionGoal, int regionRadius) {
        GameState state = MonsterRelevance.copyForRoute(source, route);
        initialiseMissingAbilityState(state);
        int waypoint = route.nextWaypoint(state.player.x, state.player.z, 0, WAYPOINT_TOLERANCE);
        MonsterSimulator monsters = monsterSimulator(state, source.tick);
        int simulationLimit = simulationLimit(route);

        for (int elapsed = 1; elapsed <= simulationLimit; elapsed++) {
            state.tick = source.tick + elapsed;
            waypoint = route.nextWaypoint(state.player.x, state.player.z, waypoint, WAYPOINT_TOLERANCE);

            if (goalReached(state, route, waypoint, goal, regionGoal, regionRadius)) {
                return success(elapsed - 1, state, waypoint);
            }

            Action action = needsTacticalSearch(state)
                    ? chooseTacticalAction(state, route, waypoint, goal, regionGoal, regionRadius)
                    : routeFollowerAction(state, route, waypoint);

            step(state, action, monsters);

            if (state.player.health <= 0.0 || !state.alive) {
                state.alive = false;
                return new Result(false, Integer.MAX_VALUE, state.player.health,
                        state.player.damageTaken, state, waypoint);
            }
        }

        return new Result(false, Integer.MAX_VALUE, state.player.health,
                state.player.damageTaken, state, waypoint);
    }

    private int simulationLimit(PlayerRoute route) {
        int routeBudget = (int) Math.ceil(route.size() * ROUTE_TICKS_PER_CELL) + ROUTE_TICK_MARGIN;
        return Math.min(MAX_SIMULATION_TICKS, Math.max(80, routeBudget));
    }

    private Result success(int ticks, GameState state, int waypoint) {
        state.padReached = true;
        return new Result(true, ticks, state.player.health,
                state.player.damageTaken, state, waypoint);
    }

    private void step(GameState state, Action action, MonsterSimulator monsters) {
        if (action.useAbility()) abilities.activate(state);

        PlayerState beforeContact = state.player.copy();
        boolean wasGrounded = state.player.grounded;
        physics.tick(state.player, action, state.maze);

        // Preserve source recovery/fall behaviour; only unrecoverable fall ends
        // a branch.
        if (state.player.y < -3.0) {
            state.alive = false;
            return;
        }

        // Source jumpEvent semantics: consume only after becoming airborne.
        if (state.kit == me.monstermazeai.kit.Kit.JUMPER
                && !wasGrounded && state.player.y > GameState.PATH_Y) {
            abilities.consumeJumperCharge(state);
        }

        monsters.tick(state);
        double healthBeforeBump = state.player.health;
        MonsterMazeBumpModel.apply(state);

        // MonsterManager/UtilAction remains authoritative for the bump itself.
        SpeedContactModel.applyConservativeSlideOutcome(
                beforeContact, state, state.maze, action, state.player.health < healthBeforeBump);

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
        // The tactical branch uses the same local interaction envelope as the
        // full route simulation. Filtering is planner-only; the live observer
        // and source-faithful mechanics retain the complete world snapshot.
        GameState tacticalSource = MonsterRelevance.copyForRoute(source, route);
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(tacticalSource, waypoint, List.of()));

        for (int depth = 0; depth < TACTICAL_HORIZON; depth++) {
            List<Node> next = new ArrayList<>();
            for (Node node : beam) {
                for (Action action : tacticalActions(node.state)) {
                    GameState s = node.state.copyForSimulation();
                    s.tick = source.tick + depth + 1;
                    MonsterSimulator branchMonsters = monsterSimulator(s, source.tick + depth + 1);
                    int wp = route.nextWaypoint(s.player.x, s.player.z, node.waypoint, WAYPOINT_TOLERANCE);
                    step(s, action, branchMonsters);
                    if (s.player.health <= 0.0 || !s.alive) continue;
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

        /*
         * Route progress remains the primary objective, but source-faithful
         * Monster Maze runs are not won by reaching a waypoint while accepting
         * a preventable four-health bump. Rank imminent contact ahead of raw
         * damage history, so the beam can choose a Repulsor/Cryo/Body Rush pulse
         * or a safe steering line before the collision actually happens.
         */
        long contactRisk = imminentContactRisk(state);
        long remaining = Math.max(0, route.size() - 1L - waypoint);
        long distance = Math.min(999_999L, Math.round(distanceToWaypoint(state, route, waypoint) * 1000));
        long damage = Math.min(999_999L, Math.round(state.player.damageTaken * 1000));

        return contactRisk * 10_000_000_000_000_000L
                + remaining * 1_000_000_000_000L
                + distance * 1_000_000L
                + damage;
    }

    private long imminentContactRisk(GameState state) {
        long risk = 0L;
        double playerSpeed = Math.hypot(state.player.vx, state.player.vz);
        double reach = MonsterMazeBumpModel.CONTACT_DISTANCE
                + 0.45 + playerSpeed * TACTICAL_HORIZON;

        for (var monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double dx = monster.x - state.player.x;
            double dy = monster.y - state.player.y;
            double dz = monster.z - state.player.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > reach) continue;

            /*
             * Ability-covered threats are still considered, but with the
             * corresponding source protection discounted. This gives the beam
             * a positive reason to activate the ability without making ability
             * use free or universally optimal.
             */
            double protection = abilityProtection(state, distance);
            double effective = Math.max(0.0, reach - distance - protection);
            risk += Math.min(1_000_000L, Math.round((effective + 0.25) * 1000.0));
        }
        return risk;
    }

    private double abilityProtection(GameState state, double distance) {
        return switch (state.kit) {
            case REPULSOR -> state.ability.charges > 0 && distance <= 6.0 ? 1.25 : 0.0;
            case SLOWBALLER -> state.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && state.tick >= state.ability.cooldownUntilTick
                    && distance <= 6.0 ? 1.0 : 0.0;
            case BODY_BUILDER -> state.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && (state.ability.activeUntilTick > state.tick || state.ability.activations > 0)
                    && distance <= 2.75 ? 1.5 : 0.0;
            default -> 0.0;
        };
    }

    private boolean needsTacticalSearch(GameState state) {
        double playerReach = 0.45 + Math.hypot(state.player.vx, state.player.vz) * TACTICAL_HORIZON;
        double contactReach = MonsterMazeBumpModel.CONTACT_DISTANCE + playerReach;

        for (var m : state.monsters) {
            if (m.removed || m.launched(state.tick) || m.frozen(state.tick)
                    || !MonsterRelevance.withinPlayerRadius(m, state.player, TACTICAL_RELEVANCE_RADIUS)) continue;
            double separationSq = sq(state.player.x - m.x)
                    + sq(state.player.y - m.y)
                    + sq(state.player.z - m.z);
            double monsterReach = Math.hypot(m.vx, m.vz) * TACTICAL_HORIZON;
            double threshold = contactReach + monsterReach;
            if (separationSq <= threshold * threshold) return true;
        }

        // Source ability range is six blocks and is therefore already contained
        // by the local interaction envelope. No distant monster can wake the
        // expensive tactical branch merely because it exists in the world.
        return false;
    }

    private List<Action> tacticalActions(GameState state) {
        /*
         * Preserve the source interaction classes while removing redundant
         * Cartesian combinations. In particular, keep direct strafe and
         * forward-strafe contacts because they are part of the speeding/contact
         * model; StableLiveMovementController remains the normal motor authority.
         */
        List<Action> out = new ArrayList<>(20);
        // Live Monster Maze movement is cardinal-route driven. The tactical
        // simulator must therefore search only actions the live motor can
        // execute without violating the corridor invariant. Yaw steering is
        // retained as an explicit control dimension; lateral/reverse actions
        // are deliberately excluded from the tactical action space.
        addMovement(out, 1, 0, false, 0);
        addMovement(out, 1, 0, true, 0);
        addMovement(out, 1, 0, false, -30);
        addMovement(out, 1, 0, false, 30);
        addMovement(out, 1, 0, true, -30);
        addMovement(out, 1, 0, true, 30);
        /*
         * Ability activation is a real one-tick input, not an abstract planner
         * flag. Only add it when the source kit can actually use it and a local
         * monster makes that use meaningful. This prevents the beam from wasting
         * branches on empty ability presses while allowing the AI to deliberately
         * clear/freeze/deflect a threat before contact.
         */
        if (abilityActionUseful(state)) {
            out.add(new Action(0, 0, false, false, 0, true));
            /*
             * Preserve forward momentum when the source ability can be activated
             * concurrently with movement. The Minecraft bridge executes the
             * ability pulse and movement input on the same tick.
             */
            out.add(new Action(1, 0, false, true, 0, true));
        }
        out.add(new Action(1, 0, true, true, 0, true));
        return out;
    }

    private static void addMovement(List<Action> out, double forward, double strafe,
                                    boolean jump, float turn) {
        boolean moving = Math.abs(forward) > 1.0E-9 || Math.abs(strafe) > 1.0E-9;
        out.add(new Action(forward, strafe, jump, moving, turn, false));
    }

    private boolean abilityActionUseful(GameState state) {
        if (state.kit == me.monstermazeai.kit.Kit.MAVERICK
                || state.kit == me.monstermazeai.kit.Kit.JUMPER) return false;

        if (state.kit == me.monstermazeai.kit.Kit.BODY_BUILDER) {
            return state.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && state.ability.activations > 0
                    && state.ability.activeUntilTick <= state.tick
                    && hasMonsterWithin(state, 2.75);
        }

        if (state.kit == me.monstermazeai.kit.Kit.REPULSOR) {
            return state.ability.charges > 0 && hasMonsterWithin(state, 6.0);
        }

        if (state.kit == me.monstermazeai.kit.Kit.SLOWBALLER) {
            return state.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && state.tick >= state.ability.cooldownUntilTick
                    && hasMonsterWithin(state, 6.0);
        }

        return false;
    }

    private boolean hasMonsterWithin(GameState state, double radius) {
        double radiusSq = radius * radius;
        for (var monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)) continue;
            double dx = monster.x - state.player.x;
            double dy = monster.y - state.player.y;
            double dz = monster.z - state.player.z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return true;
        }
        return false;
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
