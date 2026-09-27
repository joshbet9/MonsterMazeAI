package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.PlayerState;

/**
 * Planner-only spatial relevance filter.
 *
 * Monster Maze's authoritative monster/contact mechanics remain unchanged. This
 * class only decides which observed monsters are worth carrying into expensive
 * AI simulation.
 *
 * The planner deliberately uses a strict local interaction sphere around the
 * player's current position. This keeps the expensive source-faithful simulator
 * bounded to the world that can affect the next decisions. Fresh observations
 * are still processed continuously, so a monster entering the 20-block sphere
 * is considered on the next planning pass rather than being carried through a
 * long future-route simulation.
 */
public final class MonsterRelevance {
    public static final double INTERACTION_RADIUS = 20.0;
    private static final double RADIUS_SQUARED =
            INTERACTION_RADIUS * INTERACTION_RADIUS;

    private MonsterRelevance() {}

    /**
     * Copy only monsters that can currently interact with the player.
     *
     * The route argument is retained for source/binary compatibility with the
     * planner call sites, but relevance is intentionally based on the player's
     * current position rather than the entire future route. A 49-cell route
     * must not turn a 20-block local interaction model into a 49-block-wide
     * monster simulation.
     */
    public static GameState copyForRoute(GameState source, PlayerRoute route) {
        GameState state = source.copyForSimulation();
        state.monsters.removeIf(m ->
                m.removed || !withinPlayerRadius(
                        m, state.player, INTERACTION_RADIUS));
        return state;
    }

    public static boolean withinPlayerRadius(MonsterState monster,
                                             PlayerState player,
                                             double radius) {
        if (monster == null || player == null || monster.removed) return false;
        double dx = monster.x - player.x;
        double dy = monster.y - player.y;
        double dz = monster.z - player.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    public static boolean withinRouteEnvelope(MonsterState monster,
                                               PlayerRoute route) {
        if (monster == null || monster.removed || route == null) return false;

        double best = Double.POSITIVE_INFINITY;
        double px = monster.x;
        double py = monster.y;
        double pz = monster.z;

        if (route.size() == 1) {
            double dx = px - route.targetX(0);
            double dy = py - GameState.PATH_Y;
            double dz = pz - route.targetZ(0);
            return dx * dx + dy * dy + dz * dz <= RADIUS_SQUARED;
        }

        for (int i = 0; i < route.size() - 1; i++) {
            double ax = route.targetX(i);
            double az = route.targetZ(i);
            double bx = route.targetX(i + 1);
            double bz = route.targetZ(i + 1);

            double abx = bx - ax;
            double abz = bz - az;
            double lengthSquared = abx * abx + abz * abz;

            double t = lengthSquared <= 1.0E-12
                    ? 0.0
                    : ((px - ax) * abx + (pz - az) * abz) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));

            double nearestX = ax + t * abx;
            double nearestZ = az + t * abz;
            double dx = px - nearestX;
            double dy = py - GameState.PATH_Y;
            double dz = pz - nearestZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < best) best = distanceSquared;
            if (best <= RADIUS_SQUARED) return true;
        }

        return best <= RADIUS_SQUARED;
    }
}
