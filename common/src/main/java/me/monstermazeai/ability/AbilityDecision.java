package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.monster.MobInteractionDecision;

/**
 * Strategic ability-use policy.
 *
 * Repulsor is a three-charge emergency tool, not a generic "monster nearby"
 * button. The baseline policy uses it only when waiting for a blocked route
 * would make the current Safe Pad deadline unattainable, or when lethal monster
 * contact is imminent at 1-2 hearts.
 *
 * The timing thresholds are centralized so future CPU personalities can tune
 * route-wait tolerance and risk without changing movement code.
 */
public final class AbilityDecision {
    private static final double REPULSOR_RANGE_SQ = 36.0;
    private static final double LETHAL_HEALTH = 4.0;
    private static final double IMMINENT_HIT_RANGE = 1.60;
    private static final int IMMINENT_HIT_TICKS = 6;
    private static final double ESTIMATED_TICKS_PER_BLOCK = 5.0;
    private static final double SAFE_PAD_RADIUS = 2.5;
    private static final double DEADLINE_MARGIN_TICKS = 3.0;

    /*
     * A NO_ROUTE result is transient in live play: a monster can move, be
     * launched, or otherwise stop obstructing the route on a later observation.
     * This reserve is a wait/replan horizon, not a sleep.
     */
    private static final double ROUTE_REOPEN_WAIT_RESERVE_TICKS = 40.0;

    private static final double BODY_RUSH_TRIGGER_SQ = 6.25;
    private static final double CRYO_TRIGGER_SQ = 36.0;
    private static final double CRYO_ROUTE_CORRIDOR = 1.65;

    private AbilityDecision() {}

    public static boolean shouldUse(GameState state) {
        return shouldUse(state, null, null);
    }

    public static boolean shouldUse(GameState state, String objectiveReason, String objectiveDetail) {
        if (state == null || !state.alive || state.completed
                || state.kit == Kit.JUMPER || state.kit == Kit.MAVERICK) {
            return false;
        }

        if (state.kit == Kit.REPULSOR) {
            return state.ability.charges > 0
                    && !isOnActivePad(state)
                    && (repulsorDeadlineEmergency(state, objectiveReason)
                    || repulsorLethalEmergency(state)
                    || repulsorImmediateThreat(state));
        }

        if (state.kit == Kit.BODY_BUILDER) {
            return state.ability.activations > 0
                    && state.ability.activeUntilTick <= state.tick
                    && !isOnActivePad(state)
                    && (bodyRushDeadlineEmergency(state, objectiveReason)
                    || bodyRushLethalEmergency(state)
                    || bodyRushImmediateThreat(state));
        }

        if (state.kit == Kit.SLOWBALLER) {
            return state.tick >= state.ability.cooldownUntilTick
                    && !isOnActivePad(state)
                    && cryoImmediateThreat(state); 
        }
        return false;
    }

    private static boolean repulsorDeadlineEmergency(GameState state, String objectiveReason) {
        if (!"NO_ROUTE".equals(objectiveReason)) return false;

        MonsterState nearest = nearestActiveMonster(state);
        if (nearest == null || distanceSq(state, nearest) > REPULSOR_RANGE_SQ) return false;

        /*
         * The phase timer is the current Safe Pad deadline. A zero/unknown
         * timer must never cause a limited-charge Repulsor burn.
         */
        if (state.phaseTicksRemaining <= 0) return false;

        double dx = state.activePadRow + 0.5 - state.player.x;
        double dz = state.activePadColumn + 0.5 - state.player.z;
        double distanceToPad = Math.max(0.0, Math.hypot(dx, dz) - SAFE_PAD_RADIUS);
        double estimatedFastestTicks = distanceToPad * ESTIMATED_TICKS_PER_BLOCK;

        /*
         * Do not burn a charge simply because the route is absent right now.
         * The maze is dynamic and the monster may clear the route on the next
         * observation. The timer decides when waiting is no longer viable:
         *
         *   fastest traversal
         * + route-reopening wait reserve
         * + safety/reaction margin
         *
         * Only once that complete horizon no longer fits in the phase timer
         * does the baseline policy consider Repulsor necessary.
         */
        double requiredTicks = estimatedFastestTicks
                + ROUTE_REOPEN_WAIT_RESERVE_TICKS
                + DEADLINE_MARGIN_TICKS;

        return requiredTicks >= state.phaseTicksRemaining;
    }

    private static boolean bodyRushDeadlineEmergency(GameState state, String objectiveReason) {
        if (!"NO_ROUTE".equals(objectiveReason)) return false;
        if (MobInteractionDecision.chooseIntentionalBump(state) != null) return false;
        MonsterState nearest = nearestActiveMonster(state);
        if (nearest == null || distanceSq(state, nearest) > BODY_RUSH_TRIGGER_SQ) return false;
        if (state.phaseTicksRemaining <= 0) return false;
        return deadlineEmergency(state);
    }

    private static boolean bodyRushLethalEmergency(GameState state) {
        if (state.player.health > LETHAL_HEALTH) return false;
        for (MonsterState monster : state.monsters) {
            if (!activeMonster(state, monster)) continue;
            if (distanceSq(state, monster) > BODY_RUSH_TRIGGER_SQ) continue;
            if (imminentCollision(state, monster)) return true;
        }
        return false;
    }

    private static boolean deadlineEmergency(GameState state) {
        if (state.activePadRow < 0 || state.activePadColumn < 0) return false;
        double dx = state.activePadRow + 0.5 - state.player.x;
        double dz = state.activePadColumn + 0.5 - state.player.z;
        double distanceToPad = Math.max(0.0, Math.hypot(dx, dz) - SAFE_PAD_RADIUS);
        double estimatedFastestTicks = distanceToPad * ESTIMATED_TICKS_PER_BLOCK;
        return estimatedFastestTicks + ROUTE_REOPEN_WAIT_RESERVE_TICKS + DEADLINE_MARGIN_TICKS
                >= state.phaseTicksRemaining;
    }

    private static boolean cryoRouteOpening(GameState state, String objectiveReason) {
        if (!"ROUTE_OPENING".equals(objectiveReason)) return false;
        if (state.activePadRow < 0 || state.activePadColumn < 0) return false;

        double toPadX = state.activePadRow + 0.5 - state.player.x;
        double toPadZ = state.activePadColumn + 0.5 - state.player.z;
        double padDistance = Math.hypot(toPadX, toPadZ);
        if (padDistance < 1.0E-6) return false;

        double ux = toPadX / padDistance;
        double uz = toPadZ / padDistance;
        for (MonsterState monster : state.monsters) {
            if (!activeMonster(state, monster)) continue;
            if (distanceSq(state, monster) > CRYO_TRIGGER_SQ) continue;
            double mx = monster.x - state.player.x;
            double mz = monster.z - state.player.z;
            double along = mx * ux + mz * uz;
            if (along <= 0.0 || along >= padDistance) continue;
            double lateral = Math.abs(mx * uz - mz * ux);
            if (lateral <= CRYO_ROUTE_CORRIDOR) return true;
        }
        return false;
    }

    private static boolean repulsorImmediateThreat(GameState state) {
        int nearby = 0;
        for (MonsterState monster : state.monsters) {
            if (!activeMonster(state, monster)) continue;
            double distance = Math.sqrt(distanceSq(state, monster));
            if (distance > 6.0) continue;
            nearby++;
            if (imminentCollision(state, monster)) return true;
        }
        // Preserve charges, but clear a genuine local cluster before it becomes
        // a chain of four-damage bumps.
        return nearby >= 3;
    }

    private static boolean bodyRushImmediateThreat(GameState state) {
        int close = 0;
        for (MonsterState monster : state.monsters) {
            if (!activeMonster(state, monster)) continue;
            double distance = Math.sqrt(distanceSq(state, monster));
            if (distance > 3.5) continue;
            if (distance <= 2.0 || imminentCollision(state, monster)) return true;
            close++;
        }
        return state.player.health <= 12.0 && close >= 1;
    }

    private static boolean cryoImmediateThreat(GameState state) {
        if (state.activePadRow < 0 || state.activePadColumn < 0) return false;

        double toPadX = state.activePadRow + 0.5 - state.player.x;
        double toPadZ = state.activePadColumn + 0.5 - state.player.z;
        double padDistance = Math.hypot(toPadX, toPadZ);
        if (padDistance < 1.0E-6) return false;

        double ux = toPadX / padDistance;
        double uz = toPadZ / padDistance;
        int corridorThreats = 0;
        for (MonsterState monster : state.monsters) {
            if (!activeMonster(state, monster)) continue;
            double distance = Math.sqrt(distanceSq(state, monster));
            if (distance > 6.0) continue;
            double mx = monster.x - state.player.x;
            double mz = monster.z - state.player.z;
            double along = mx * ux + mz * uz;
            double lateral = Math.abs(mx * uz - mz * ux);
            if (along < -0.5 || along > padDistance + 1.0) continue;
            if (lateral <= 2.0) {
                corridorThreats++;
                if (distance <= 2.5 || imminentCollision(state, monster)) return true;
            }
        }
        return corridorThreats >= 2;
    }

    private static boolean isOnActivePad(GameState state) {
        return state.activePadRow >= 0 && state.activePadColumn >= 0
                && me.monstermazeai.game.PadModel.isOn(state.player,
                state.activePadRow + 0.5, GameState.PAD_SURFACE_Y,
                state.activePadColumn + 0.5);
    }

    private static boolean activeMonster(GameState state, MonsterState monster) {
        return monster != null && !monster.removed
                && !monster.launched(state.tick) && !monster.frozen(state.tick);
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

        double closingSpeed = (monster.vx * -dx + monster.vz * -dz)
                / Math.max(horizontalDistance, 1.0E-6);
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
