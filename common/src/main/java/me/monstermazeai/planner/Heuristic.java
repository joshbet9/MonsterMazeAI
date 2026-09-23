package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.monster.MonsterState;

import java.util.List;

public final class Heuristic {
    private static final double DEATH_PENALTY = 1_000_000.0;
    private static final double FAILED_TIMER_PENALTY = 750_000.0;
    private static final double LOW_HEALTH_PENALTY = 120.0;
    private static final double CRITICAL_HEALTH_PENALTY = 2_000.0;
    private static final double MAX_PREDICTION_TICKS = 200.0;
    private static final double GROUND_ACCELERATION = 0.13D;
    private static final double GROUND_FRICTION = 0.6D * 0.91D;
    private final PlayerPathfinder pathfinder = new PlayerPathfinder();

    public Score evaluate(GameState s, double targetX, double targetZ) {
        if (!s.alive) {
            return new Score(DEATH_PENALTY, false, s.player.health,
                    distance(s, targetX, targetZ), 0.0);
        }

        double euclidean = distance(s, targetX, targetZ);
        if (Double.isNaN(targetX) || Double.isNaN(targetZ)) {
            return new Score(100_000.0, true, s.player.health, euclidean, 0.0);
        }

        if (s.padReached) {
            return new Score(s.phaseTicksRemaining * 0.01, true,
                    s.player.health, 0.0, 0.0);
        }

        double routeBlocks = routeDistance(s, targetX, targetZ);
        double projectedTicks = estimateTimeToPad(s, routeBlocks);
        double timerSlack = s.phaseTicksRemaining - projectedTicks;

        double value = projectedTicks;
        if (timerSlack < 0) {
            value += FAILED_TIMER_PENALTY + (-timerSlack * 250.0);
        } else if (timerSlack < 20) {
            value += (20 - timerSlack) * 35.0;
        }

        double incoming = projectedIncomingDamage(s, (int)Math.min(
                MAX_PREDICTION_TICKS, Math.max(20.0, projectedTicks)));
        double remainingHealth = s.player.health - incoming;

        if (remainingHealth <= 0) value += CRITICAL_HEALTH_PENALTY;
        else if (remainingHealth <= 4) value += LOW_HEALTH_PENALTY;

        value += abilityOpportunityCost(s, projectedTicks);
        return new Score(value, true, s.player.health, routeBlocks, incoming);
    }

    /**
     * Returns a maze-aware path length instead of assuming the pad can be reached
     * through barriers. Player coordinates are converted to the containing maze
     * cell, while the pad target is represented by its containing cell.
     */
    private double routeDistance(GameState s, double targetX, double targetZ) {
        int sr = (int)Math.floor(s.player.x);
        int sc = (int)Math.floor(s.player.z);
        int tr = (int)Math.floor(targetX);
        int tc = (int)Math.floor(targetZ);

        if (sr < 0 || sc < 0 || tr < 0 || tc < 0
                || sr >= 99 || sc >= 99 || tr >= 99 || tc >= 99) {
            return Double.POSITIVE_INFINITY;
        }

        List<Cell> path = pathfinder.shortestPath(
                s.maze, new Cell(sr, sc), new Cell(tr, tc));
        if (path.isEmpty()) return Double.POSITIVE_INFINITY;

        double fractionalStart = Math.hypot(
                s.player.x - (sr + 0.5), s.player.z - (sc + 0.5));
        double fractionalGoal = Math.hypot(
                targetX - (tr + 0.5), targetZ - (tc + 0.5));
        return Math.max(0.0, path.size() - 1) + fractionalStart + fractionalGoal;
    }

    private double estimateTimeToPad(GameState s, double routeBlocks) {
        if (Double.isInfinite(routeBlocks)) return MAX_PREDICTION_TICKS;
        if (routeBlocks <= 0.0) return 0.0;

        // Estimate travel time using the same per-tick ground acceleration and
        // friction shape as LegacyMovementModel rather than treating the
        // player's instantaneous speed as a constant. The projection is only
        // a heuristic; the simulator remains authoritative for candidate
        // trajectories.
        double dx = s.targetPadX() - s.player.x;
        double dz = s.targetPadZ() - s.player.z;
        double length = Math.hypot(dx, dz);
        double ux = length > 1.0E-9 ? dx / length : 0.0;
        double uz = length > 1.0E-9 ? dz / length : 0.0;
        double velocity = s.player.vx * ux + s.player.vz * uz;

        if (!s.player.grounded) {
            // Air control is substantially weaker in 1.8. Use the current
            // projected velocity but never let it imply an unrealistically
            // fast route completion.
            velocity = Math.max(0.0, velocity);
            double airSpeed = Math.max(0.08, velocity);
            return Math.min(MAX_PREDICTION_TICKS, routeBlocks / airSpeed);
        }

        double distance = 0.0;
        int ticks = 0;
        while (distance < routeBlocks && ticks < (int) MAX_PREDICTION_TICKS) {
            velocity += GROUND_ACCELERATION;
            distance += Math.max(0.0, velocity);
            velocity *= GROUND_FRICTION;
            ticks++;
        }
        return ticks;
    }

    private double distance(GameState s, double x, double z) {
        if (Double.isNaN(x) || Double.isNaN(z)) return Double.POSITIVE_INFINITY;
        return Math.hypot(s.player.x - x, s.player.z - z);
    }

    private double projectedIncomingDamage(GameState s, int horizonTicks) {
        double damage = 0.0;
        for (MonsterState m : s.monsters) {
            if (m.removed || m.launched(s.tick) || m.frozen(s.tick)) continue;

            double closest = Double.POSITIVE_INFINITY;
            for (int t = 0; t <= horizonTicks; t++) {
                double px = s.player.x + s.player.vx * t;
                double py = s.player.y + s.player.vy * t;
                double pz = s.player.z + s.player.vz * t;
                double mx = m.x + m.vx * t;
                double my = m.y + m.vy * t;
                double mz = m.z + m.vz * t;
                closest = Math.min(closest,
                        sq(px-mx) + sq(py-my) + sq(pz-mz));
            }
            if (closest < 1.0) damage += 4.0;
        }
        return damage;
    }

    private double sq(double v) { return v * v; }

    private double abilityOpportunityCost(GameState s, double travelTicks) {
        return switch (s.kit) {
            case JUMPER -> s.ability.charges * 0.15;
            case REPULSOR -> s.ability.charges * 0.5;
            case BODY_BUILDER -> s.ability.activations * 0.25;
            case SLOWBALLER -> 0.0; // Cryo is a 30s cooldown, not a charge pool.
            case MAVERICK -> 0.0;
        };
    }

    public double survivalMargin(GameState s) {
        return s.player.health - projectedIncomingDamage(s, 40);
    }
}
