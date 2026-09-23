package me.monstermazeai.planner;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.monster.MonsterState;

import java.util.ArrayList;
import java.util.List;

public final class Heuristic {
    private static final double DEATH_PENALTY = 1_000_000.0;
    private static final double LOW_HEALTH_PENALTY = 180.0;
    private static final double HEALTH_BUFFER = 4.0;
    private static final double HIT_DISTANCE_SQ = 1.0;

    /**
     * Scores a state by estimated completion time first, then by survival risk.
     *
     * The important distinction is that damage is not automatically "bad":
     * spending health can be faster than taking a long detour. The planner
     * therefore estimates imminent hits and asks whether the current kit has
     * an ability that can cheaply remove that risk.
     */
    public Score evaluate(GameState s, double targetX, double targetZ) {
        double distance = Math.hypot(s.player.x - targetX, s.player.z - targetZ);
        double risk = imminentDamageRisk(s);
        double healthDeficit = Math.max(0.0, s.player.maxHealth - s.player.health);

        double value = distance;
        value += risk * 18.0;
        value += healthDeficit * 0.8;
        if (s.player.health <= 4.0) value += LOW_HEALTH_PENALTY;
        if (!s.alive) value += DEATH_PENALTY;

        // A state with a dangerous monster immediately ahead should not be
        // discarded merely because it spent a charge: survival is more
        // important than resource hoarding, while unnecessary use is still
        // represented by the lost-resource opportunity cost below.
        value += resourceOpportunityCost(s);

        return new Score(value, s.alive, s.player.health, distance, risk);
    }

    private double imminentDamageRisk(GameState s) {
        double risk = 0.0;
        for (MonsterState m : s.monsters) {
            if (m.removed || m.launched(s.tick) || m.frozen(s.tick)) continue;
            double dx = s.player.x - m.x;
            double dy = s.player.y - m.y;
            double dz = s.player.z - m.z;
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 < 4.0) {
                // Monster damage is 4 HP, and the normal hit cooldown is 20 ticks.
                risk += (4.0 / Math.max(0.25, Math.sqrt(d2)));
            }
        }
        return risk;
    }

    private double resourceOpportunityCost(GameState s) {
        return switch (s.kit) {
            case JUMPER -> s.ability.charges * 0.5;
            case REPULSOR -> s.ability.charges * 2.0;
            case SLOWBALLER -> s.ability.cooldownUntilTick > s.tick ? 1.0 : 0.0;
            case BODY_BUILDER -> s.ability.activations * 0.5;
            case MAVERICK -> 0.0;
        };
    }

    public double survivalMargin(GameState s) {
        double incoming = imminentDamageRisk(s);
        return s.player.health - incoming;
    }
}
