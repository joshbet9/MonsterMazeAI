package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;

/**
 * Runtime-side pulse gate for abilities whose cooldown is not exposed by the
 * legacy observation contract. Charge-based kits are still authoritative from
 * the observation; this gate mainly prevents repeated cryo/body-rush pulses
 * while the game state catches up.
 */
public final class AbilityUseGate {
    private long nextAllowedTick = Long.MIN_VALUE;
    private Kit lastKit;
    private int lastRepulsorCharges = Integer.MIN_VALUE;

    public boolean allow(GameState state) {
        if (state == null || state.kit == null || !AbilityDecision.shouldUse(state)) return false;
        if (state.kit != lastKit) return true;
        if (state.kit == Kit.REPULSOR && state.ability.charges < lastRepulsorCharges) return true;
        return state.tick >= nextAllowedTick;
    }

    public void record(GameState state) {
        if (state == null) return;
        lastKit = state.kit;
        if (state.kit == Kit.REPULSOR) lastRepulsorCharges = state.ability.charges;
        switch (state.kit) {
            case SLOWBALLER:
                nextAllowedTick = state.tick + 600;
                break;
            case BODY_BUILDER:
                nextAllowedTick = state.tick + 200;
                break;
            case REPULSOR:
                nextAllowedTick = state.tick + 8;
                break;
            default:
                nextAllowedTick = state.tick + 1;
        }
    }
}
