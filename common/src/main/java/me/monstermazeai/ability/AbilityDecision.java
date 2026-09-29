package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;

/**
 * Strategic ability-use policy.
 *
 * Repulsor is deliberately conservative: it is a three-charge emergency tool,
 * not a generic "monster nearby" button. The baseline policy uses it only when
 * a monster is preventing timely progress to the active Safe Pad, or when a
 * lethal monster contact is imminent at 1-2 hearts of remaining health.
 *
 * The thresholds are intentionally centralized so future CPU personalities can
 * tune risk tolerance without changing movement code.
 */
public final class AbilityDecision {
    private static final double REPULSOR_RANGE_SQ = 36.0; // source radius: 6 blocks
    private static final double LETHAL_HEALTH = 4.0;     // 2 hearts
    private static final double IMMINENT_HIT_RANGE = 1.60;
    private static final int IMMINENT_HIT_TICKS = 6;
    private static final double ESTIMATED_TICKS_PER_BLOCK = 5.0;
    private static final double SAFE_PAD_RADIUS = 2.5;
    private static final double DEADLINE_MARGIN_TICKS = 3.0;

    private static final double BODY_RUSH_TRIGGER_SQ = 6.25;
    private static final double CRYO_TRIGGER_SQ = 36.0;

    private AbilityDecision() {}

    public static boolean shouldUse(GameState state) {
        return shouldUse(state, null, null);
    }

    /**
     * Evaluate an ability against the current movement-controller outcome.
     * objectiveReason is intentionally a small stable contract (for example
     * MOVEMENT_PLANNER or NO_ROUTE), while detail is diagnostic only.
     */
    public static boolean shouldUse(GameState state, String objectiveReason, String objectiveDetail) {
        if (state == null || !state.alive || state.completed
                || state.kit == Kit.JUMPER || state.kit == Kit.MAVERICK) {
            return false;
        }

        if (state.kit == Kit.REPULSOR) {
            return state.ability.charges > 0
                    && (repulsorDeadlineEmergency(state, objectiveReason)
                    || repulsorLethalEmergency(state));
        }

        if (state.kit == Kit.BODY_BUILDER && state.ability.activations <= 0) return false;
        if (state.kit == Kit.SLOWBALLER && state.tick < state.ability.cooldownUntilTick) return false;

        double nearestSq = nearestActiveMonsterDistanceSq(state);
        switch (state.kit) {
            case BODY_BUILDER:
                return nearestSq <= BODY_RUSH_TRIGGER_SQ
                        && state.ability.activeUntilTick <= state.tick;
            case SLOWBALLER:
                return nearestSq <= CRYO_TRIGGER_SQ;
            default:
                return false;
        }
    }

    private static boolean repulsorDeadlineEmergency(GameState state, String objectiveReason) {
        if (!"NO_ROUTE".equals(objectiveReason)) return false;

        MonsterState nearest = nearestActiveMonster(state);
        if (nearest == null || distanceSq(state, nearest) > REPULSOR_RANGE_SQ) return false;

        /*
         * A positive phase timer is the server's current-pad deadline. If the
         * fastest geometric route cannot reach the Safe Pad before that
         * deadline, and a monster is the local obstruction, spend a Repulsor.
         * With no timer, NO_ROUTE is not enough to burn a limited charge.
         */
        if (state.phaseTicksRemaining <= 0) return false;

        double dx = state.activePadRow + 0.5 - state.player.x;
        double dz = state.activePadColumn + 0.5 - state.player.z;
        double distanceToPad = Math.max(0.0, Math.hypot(dx, dz) - SAFE_PAD_RADIUS);
        double estimatedFastestTicks = distanceToPad * ESTIMATED_TICKS_PER_BLOCK;

        return estimatedFastestTicks + DEADLINE_MARGIN_TICKS
                >= state.phaseTicksRemaining;
    }

    private static boolean repulsorLethalEmergency(GameState state) {
        if (state.player.health > LETHAL_HEALTH) return false;

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick) || monster.frozen(state.tick)) continue;
            if (distanceSq(state, monster) > REPULSOR_RANGE_SQ) continue;
            if (imminentCollision(state, monster)) return true;
        }
        return false;
    }

    private static boolean imminentCollision(GameState state, MonsterState monster) {
        double dx = monster.x - state.player.x;
        double dz = monster.z - state.player.z;
        double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance <= IMMINENT_HIT_RANGE) return true;

        double horizontalSpeedSq = monster.vx * monster.vx + monster.vz * monster.vz;
        if (horizontalSpeedSq < 1.0E-6) return false;

        double speed = Math.sqrt(horizontalSpeedSq);
        double closingSpeed = (monster.vx * -dx + monster.vz * -dz) / Math.max(horizontalDistance, 1.0E-6);
        if (closingSpeed <= 0.0) return false;

        double timeToContact = horizontalDistance / closingSpeed;
        if (timeToContact > IMMINENT_HIT_TICKS) return false;

        double predictedX = monster.x + monster.vx * timeToContact;
        double predictedZ = monster.z + monster.vz * timeToContact;
        double pdx = predictedX - state.player.x;
        double pdz = predictedZ - state.player.z;
        return pdx * pdx + pdz * pdz <= IMMINENT_HIT_RANGE * IMMINENT_HIT_RANGE;
    }

    private static MonsterState nearestActiveMonster(GameState state) {
        MonsterState best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick) || monster.frozen(state.tick)) continue;
            double distance = distanceSq(state, monster);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = monster;
            }
        }
        return best;
    }

    private static double nearestActiveMonsterDistanceSq(GameState state) {
        MonsterState nearest = nearestActiveMonster(state);
        return nearest == null ? Double.POSITIVE_INFINITY : distanceSq(state, nearest);
    }

    private static double distanceSq(GameState state, MonsterState monster) {
        double dx = state.player.x - monster.x;
        double dy = state.player.y - monster.y;
        double dz = state.player.z - monster.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
