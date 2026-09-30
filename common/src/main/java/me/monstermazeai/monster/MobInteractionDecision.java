package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;

/**
 * Strategic live monster-contact policy.
 *
 * Normal contact costs four health and launches the player. Therefore contact
 * is only deliberately entered when the Safe Pad deadline is already outside
 * ordinary travel time and the resulting source bump points toward the pad.
 */
public final class MobInteractionDecision {
    private static final double MIN_SAFE_HEALTH = 4.0; // 2 hearts: never intentionally contact.
    private static final double CONTACT_RANGE = 2.75;
    private static final double CONTACT_RANGE_SQ = CONTACT_RANGE * CONTACT_RANGE;
    private static final double PAD_RADIUS = 2.5;
    private static final double ESTIMATED_TICKS_PER_BLOCK = 5.0;
    private static final double EMERGENCY_MARGIN_TICKS = 8.0;

    private MobInteractionDecision() {}

    public static MonsterState chooseIntentionalBump(GameState state) {
        if (state == null || !state.alive || state.completed
                || state.player.health <= MIN_SAFE_HEALTH
                || state.activePadRow < 0 || state.activePadColumn < 0
                || state.phaseTicksRemaining <= 0 || onActivePad(state)) {
            return null;
        }

        double padDx = state.activePadRow + 0.5 - state.player.x;
        double padDz = state.activePadColumn + 0.5 - state.player.z;
        double padDistance = Math.max(0.0, Math.hypot(padDx, padDz) - PAD_RADIUS);
        double ordinaryTicks = padDistance * ESTIMATED_TICKS_PER_BLOCK;

        if (ordinaryTicks + EMERGENCY_MARGIN_TICKS < state.phaseTicksRemaining) {
            return null;
        }

        double padLen = Math.hypot(padDx, padDz);
        if (padLen < 1.0E-9) return null;
        double padUx = padDx / padLen;
        double padUz = padDz / padLen;

        /*
         * A normal bump is a real source movement primitive: the server gives
         * the player a unit horizontal velocity away from the monster. Good
         * players can exploit a monster that is already beside them and whose
         * bump vector points strongly toward the next pad. Do not reserve this
         * for deadline emergencies; allow an opportunistic bump when it has a
         * clear directional benefit and enough health to absorb the four damage.
         */
        boolean emergency = ordinaryTicks + EMERGENCY_MARGIN_TICKS >= state.phaseTicksRemaining;

        MonsterState best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double mx = monster.x - state.player.x;
            double mz = monster.z - state.player.z;
            double distanceSq = mx * mx
                    + (monster.y - state.player.y) * (monster.y - state.player.y)
                    + mz * mz;
            if (distanceSq > CONTACT_RANGE_SQ) continue;

            double horizontal = Math.hypot(mx, mz);
            if (horizontal < 0.15) continue;

            // Normal bump velocity is player - monster.
            double bumpUx = -mx / horizontal;
            double bumpUz = -mz / horizontal;
            double towardPad = bumpUx * padUx + bumpUz * padUz;

            /*
             * Emergency mode keeps the old conservative threshold. Outside an
             * emergency, require an even cleaner alignment so ordinary routing
             * is never replaced merely because a mob happens to be nearby.
             */
            double requiredAlignment = emergency ? 0.70 : 0.88;
            if (towardPad < requiredAlignment) continue;

            /*
             * Prefer a contact that is close to the one-block source collision
             * radius and points strongly toward the pad. The emergency state
             * gets a modest score bonus, but opportunistic use remains valid.
             */
            double distancePenalty = Math.abs(horizontal - 1.0);
            double emergencyBonus = emergency ? -0.5 : 0.0;
            double score = distancePenalty - towardPad * 3.0 + emergencyBonus;
            if (score < bestScore) {
                bestScore = score;
                best = monster;
            }
        }
        return best;
    }

    private static boolean onActivePad(GameState state) {
        return PadModel.isOn(state.player,
                state.activePadRow + 0.5,
                GameState.PAD_SURFACE_Y,
                state.activePadColumn + 0.5);
    }
}
