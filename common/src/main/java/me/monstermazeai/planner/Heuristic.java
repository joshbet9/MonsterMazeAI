package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.List;

public final class Heuristic {
    private static final double DEATH_PENALTY = 1_000_000.0;
    private static final double FAILED_TIMER_PENALTY = 750_000.0;
    private static final double LOW_HEALTH_PENALTY = 120.0;
    private static final double CRITICAL_HEALTH_PENALTY = 2_000.0;
    private static final double MAX_PREDICTION_TICKS = 200.0;

    public Score evaluate(GameState s, double targetX, double targetZ) {
        if (!s.alive) {
            return new Score(DEATH_PENALTY, false, s.player.health,
                    distance(s, targetX, targetZ), 0.0);
        }

        double distance = distance(s, targetX, targetZ);
        if (Double.isNaN(targetX) || Double.isNaN(targetZ)) {
            return new Score(100_000.0, true, s.player.health, distance, 0.0);
        }

        if (s.padReached) {
            // A reached pad is a concrete success state, not merely a smaller
            // distance. Give the planner a strong terminal signal.
            return new Score(s.phaseTicksRemaining * 0.01,
                    true, s.player.health, 0.0, 0.0);
        }

        double projectedTicks = estimateTimeToPad(s, targetX, targetZ);
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

        if (remainingHealth <= 0) {
            value += CRITICAL_HEALTH_PENALTY;
        } else if (remainingHealth <= 4) {
            value += LOW_HEALTH_PENALTY;
        }

        value += abilityOpportunityCost(s, projectedTicks);

        return new Score(value, true, s.player.health, distance, incoming);
    }

    /**
     * Uses the current simulated velocity plus a conservative movement-speed
     * estimate. This is deliberately an estimate for ranking, while actual
     * candidate states are produced by MinecraftPhysics.
     */
    private double estimateTimeToPad(GameState s, double targetX, double targetZ) {
        double distance = distance(s, targetX, targetZ);
        double speed = Math.hypot(s.player.vx, s.player.vz);
        double effective = Math.max(0.45, speed);
        return Math.min(MAX_PREDICTION_TICKS, distance / effective);
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
        // Finite-charge abilities are resources whose value depends on how
        // much of the current route remains. Cryo is different: its resource
        // is time until cooldown, not a charge count.
        return switch (s.kit) {
            case JUMPER -> s.ability.charges * 0.15;
            case REPULSOR -> s.ability.charges * 0.5;
            case BODY_BUILDER -> s.ability.activations * 0.25;
            case SLOWBALLER -> {
                double cooldownTicks = Math.max(0,
                        s.ability.cooldownUntilTick - s.tick);
                yield cooldownTicks > travelTicks ? 0.0 : 0.0;
            }
            case MAVERICK -> 0.0;
        };
    }

    public double survivalMargin(GameState s) {
        return s.player.health - projectedIncomingDamage(s, 40);
    }
}
