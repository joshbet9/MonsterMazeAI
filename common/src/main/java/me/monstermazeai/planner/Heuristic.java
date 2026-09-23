package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.TimerModel;
import me.monstermazeai.monster.MonsterState;

public final class Heuristic {
    private static final double DEATH_PENALTY = 1_000_000.0;
    private static final double CRITICAL_HEALTH_PENALTY = 500.0;
    private static final double LOW_HEALTH_PENALTY = 80.0;
    private static final double HIT_DISTANCE_SQ = 1.0;
    private static final double TICKS_PER_BLOCK_FALLBACK = 2.0;

    /**
     * Survival-constrained time objective.
     *
     * Distance alone is not enough: late stages can leave only 15 seconds,
     * so a route that is technically safer but cannot reach the pad before the
     * timer expires is also a losing route. Conversely, health is a resource:
     * taking one 4-HP hit can be correct if it saves enough time.
     */
    public Score evaluate(GameState s, double targetX, double targetZ) {
        double distance = Math.hypot(s.player.x - targetX, s.player.z - targetZ);
        double secondsRemaining = Math.max(0.0, s.phaseTicksRemaining / 20.0);

        double estimatedTravelTicks = distance * TICKS_PER_BLOCK_FALLBACK;
        double timePressure = timePressurePenalty(
                estimatedTravelTicks, s.phaseTicksRemaining);

        double incoming = projectedIncomingDamage(s, 20);
        double healthMargin = s.player.health - incoming;

        double value = estimatedTravelTicks;
        value += timePressure;
        value += survivalPenalty(s, healthMargin);
        value += resourceOpportunityCost(s, secondsRemaining);

        if (!s.alive) value += DEATH_PENALTY;

        return new Score(value, s.alive, s.player.health, distance, incoming);
    }

    /**
     * Projects near-future damage using monster positions and their current
     * velocity. This is intentionally conservative: a monster moving toward
     * the player's projected position contributes more risk than a stationary
     * monster moving away.
     */
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
                        (px-mx)*(px-mx) + (py-my)*(py-my) + (pz-mz)*(pz-mz));
            }

            if (closest < HIT_DISTANCE_SQ) {
                damage += 4.0;
            }
        }

        return damage;
    }

    private double survivalPenalty(GameState s, double healthMargin) {
        if (healthMargin <= 0.0) return CRITICAL_HEALTH_PENALTY;
        if (healthMargin <= 4.0) return LOW_HEALTH_PENALTY;
        return 0.0;
    }

    private double timePressurePenalty(double travelTicks, int remainingTicks) {
        if (remainingTicks <= 0) return 300_000.0;
        if (travelTicks > remainingTicks) {
            // If the pad cannot be reached in the remaining time, this is
            // effectively a failed branch. Still leave it below death so the
            // planner can distinguish impossible timing from actual death.
            return 300_000.0 + (travelTicks - remainingTicks) * 100.0;
        }

        double slack = remainingTicks - travelTicks;
        if (slack < 40.0) return (40.0 - slack) * 100.0;
        return 0.0;
    }

    private double resourceOpportunityCost(GameState s, double secondsRemaining) {
        return switch (s.kit) {
            case JUMPER -> s.ability.charges * 0.15;
            case REPULSOR -> s.ability.charges * 0.5;
            case BODY_BUILDER -> s.ability.activations * 0.25;
            case SLOWBALLER -> {
                // Cryo is cooldown-based, so its cost depends on whether the
                // current 30-second cooldown can still matter before the pad.
                double cooldownSeconds = Math.max(
                        0.0, (s.ability.cooldownUntilTick - s.tick) / 20.0);
                yield cooldownSeconds > secondsRemaining ? 0.2 : 0.0;
            }
            case MAVERICK -> 0.0;
        };
    }

    public double survivalMargin(GameState s) {
        return s.player.health - projectedIncomingDamage(s, 20);
    }
}
