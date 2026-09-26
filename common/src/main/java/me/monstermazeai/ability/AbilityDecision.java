package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;

import java.util.List;

/**
 * Strategic ability policy.
 *
 * The policy is deliberately kit-specific, but every kit follows the same
 * decision principle:
 *
 *   use an ability only when its expected immediate benefit is greater than
 *   its resource/opportunity cost and there is a concrete tactical reason.
 *
 * Tactical reasons are limited to:
 *   1. survival (prevent otherwise likely damage/death),
 *   2. deadline (materially improve the chance of reaching the active pad),
 *   3. route clearance (materially reduce a predicted monster conflict).
 *
 * Mere proximity to a monster is never sufficient.
 */
public final class AbilityDecision {
    private static final double IMMEDIATE = 2.5;
    private static final double DANGER = 3.5;
    private static final double ROUTE_THREAT_DISTANCE = 2.25;
    private static final double SPEED = 0.115;
    private static final int MAX_TICKS = 200;

    private AbilityDecision() {}

    public static boolean shouldUse(GameState s) {
        if (s == null || !s.alive || s.completed || s.maze == null || s.kit == null) return false;

        return switch (s.kit) {
            case REPULSOR -> shouldUseRepulsor(s);
            case SLOWBALLER -> shouldUseSlowballer(s);
            case BODY_BUILDER -> shouldUseBodyBuilder(s);
            // Jumper's finite charges are selected by the movement controller,
            // because their value depends on the exact trajectory being driven.
            case JUMPER -> false;
            // Maverick has no activatable ability in this model. Its passive
            // knockback-to-pad behaviour belongs in collision/route simulation.
            case MAVERICK -> false;
        };
    }

    /**
     * Repulsor has only three finite charges, so its hurdle is deliberately
     * high. It is justified only when a concrete route conflict exists and
     * either survival or the active-pad deadline is materially threatened.
     */
    private static boolean shouldUseRepulsor(GameState s) {
        if (s.ability.charges <= 0) return false;

        Threat threat = routeThreat(s);
        if (!threat.exists) return false;

        boolean immediate = threat.nearestDistance <= IMMEDIATE;
        boolean lowHealth = s.player.health <= 4.0 && threat.nearestDistance <= DANGER;
        boolean deadline = deadlineThreat(s);

        // A finite charge is not spent merely to make travel prettier/faster.
        return immediate || lowHealth || deadline;
    }

    /**
     * Slowballer has a long cooldown rather than a finite per-stage charge
     * pool. It can therefore be used more readily, but only when freezing
     * monsters has measurable tactical value.
     */
    private static boolean shouldUseSlowballer(GameState s) {
        if (!qolEnabled(s) || s.tick < s.ability.cooldownUntilTick) return false;

        Threat threat = routeThreat(s);
        if (!threat.exists) return false;

        boolean immediate = threat.nearestDistance <= IMMEDIATE;
        boolean denseThreat = threat.count >= 2;
        boolean lowHealth = s.player.health <= 4.0 && threat.nearestDistance <= DANGER;
        boolean deadline = deadlineThreat(s);

        return immediate || denseThreat || lowHealth || deadline;
    }

    /**
     * Body Builder has two finite activations. Body Rush both protects the
     * player from monster damage and converts contacts into monster launches,
     * but contacts shorten its active duration. It is therefore reserved for
     * a route segment where contact is actually expected, not for arbitrary
     * speed.
     */
    private static boolean shouldUseBodyBuilder(GameState s) {
        if (!qolEnabled(s) || s.ability.activations <= 0
                || s.ability.activeUntilTick > s.tick) return false;

        Threat threat = routeThreat(s);
        if (!threat.exists) return false;

        boolean immediate = threat.nearestDistance <= IMMEDIATE;
        boolean lowHealth = s.player.health <= 4.0 && threat.nearestDistance <= DANGER;
        boolean denseThreat = threat.count >= 2;
        boolean deadline = deadlineThreat(s);

        return immediate || lowHealth || denseThreat || deadline;
    }

    private static boolean deadlineThreat(GameState s) {
        if (s.phaseTicksRemaining <= 0) return false;
        int travel = travelTicks(s);
        // Ten ticks are reserved as a safety margin for pad registration and
        // movement-model error. Ability use is justified only if the normal
        // route cannot meet that conservative deadline.
        return travel + 10 > s.phaseTicksRemaining;
    }

    private static Threat routeThreat(GameState s) {
        if (s.activePadRow < 0 || s.activePadColumn < 0) {
            return Threat.NONE;
        }

        int r = (int) Math.floor(s.player.x);
        int c = (int) Math.floor(s.player.z);

        try {
            PlayerRoute route = new MonsterAwareRoutePlanner().route(
                    s,
                    new Cell(r, c),
                    new Cell(s.activePadRow, s.activePadColumn));

            int count = 0;
            double nearest = Double.POSITIVE_INFINITY;

            for (MonsterState m : s.monsters) {
                if (m.removed || m.launched(s.tick) || m.frozen(s.tick)) continue;

                double currentDistance = Math.hypot(
                        m.x - s.player.x,
                        m.z - s.player.z);

                // A monster already inside the collision envelope is an
                // immediate tactical threat even if the planner can nominate
                // a different future route. Waiting for route geometry to
                // represent an already-developing collision is too late.
                boolean immediateCollision = currentDistance <= IMMEDIATE;

                boolean routeConflict = false;
                if (!immediateCollision) {
                    for (int i = 0; i < route.size(); i++) {
                        double d = Math.hypot(
                                m.x - route.targetX(i),
                                m.z - route.targetZ(i));
                        if (d <= ROUTE_THREAT_DISTANCE) {
                            routeConflict = true;
                            break;
                        }
                    }
                }

                if (immediateCollision || routeConflict) {
                    count++;
                    nearest = Math.min(nearest, currentDistance);
                }
            }

            return count == 0
                    ? Threat.NONE
                    : new Threat(true, count, nearest);
        } catch (IllegalArgumentException ignored) {
            return Threat.NONE;
        }
    }

    private static int travelTicks(GameState s) {
        if (s.activePadRow < 0 || s.activePadColumn < 0) return MAX_TICKS;

        int r = (int) Math.floor(s.player.x);
        int c = (int) Math.floor(s.player.z);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                s.maze,
                new Cell(r, c),
                new Cell(s.activePadRow, s.activePadColumn));

        if (path.isEmpty()) return MAX_TICKS;
        return Math.min(MAX_TICKS,
                (int) Math.ceil(Math.max(0, path.size() - 1) / SPEED));
    }

    private static boolean qolEnabled(GameState s) {
        return s.mode != null && s.mode != me.monstermazeai.game.Mode.ORIGINAL;
    }

    private record Threat(boolean exists, int count, double nearestDistance) {
        private static final Threat NONE =
                new Threat(false, 0, Double.POSITIVE_INFINITY);
    }
}
