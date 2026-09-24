package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;

/**
 * Strategic ability-use policy. It deliberately returns an intent only when
 * an ability has a concrete defensive purpose; the execution layer is still
 * responsible for emitting the one-tick input pulse.
 */
public final class AbilityDecision {
    private static final double REPULSOR_TRIGGER_SQ = 12.25;
    private static final double BODY_RUSH_TRIGGER_SQ = 6.25;
    private static final double CRYO_TRIGGER_SQ = 36.0;

    private AbilityDecision() {}

    public static boolean shouldUse(GameState state) {
        if (state == null || !state.alive || state.completed
                || state.kit == Kit.JUMPER || state.kit == Kit.MAVERICK) {
            return false;
        }

        if (state.kit == Kit.REPULSOR && state.ability.charges <= 0) return false;
        if (state.kit == Kit.BODY_BUILDER && state.ability.activations <= 0) return false;
        if (state.kit == Kit.SLOWBALLER && state.tick < state.ability.cooldownUntilTick) return false;

        double nearestSq = nearestActiveMonsterDistanceSq(state);
        switch (state.kit) {
            case REPULSOR:
                return nearestSq <= REPULSOR_TRIGGER_SQ;
            case BODY_BUILDER:
                return nearestSq <= BODY_RUSH_TRIGGER_SQ
                        && state.ability.activeUntilTick <= state.tick;
            case SLOWBALLER:
                return nearestSq <= CRYO_TRIGGER_SQ;
            default:
                return false;
        }
    }

    private static double nearestActiveMonsterDistanceSq(GameState state) {
        double best = Double.POSITIVE_INFINITY;
        for (MonsterState m : state.monsters) {
            if (m.removed || m.launched(state.tick) || m.frozen(state.tick)) continue;
            double dx = state.player.x - m.x;
            double dy = state.player.y - m.y;
            double dz = state.player.z - m.z;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }
}
