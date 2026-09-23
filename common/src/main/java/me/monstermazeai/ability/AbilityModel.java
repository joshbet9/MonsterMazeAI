package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;

public final class AbilityModel {
    public void initialise(AbilityState state, Kit kit) {
        state.charges = switch (kit) {
            case JUMPER -> 3;
            case SLOWBALLER -> 16;
            case BODY_BUILDER -> 2;
            case REPULSOR -> 3;
            case MAVERICK -> 0;
        };
        state.cooldownUntilTick = 0;
        state.activeUntilTick = 0;
        state.activations = kit == Kit.BODY_BUILDER ? 2 : 0;
    }

    public void applyEnhancedPadRefill(GameState game, AbilityState abilities) {
        if (game.kit == Kit.JUMPER) abilities.charges = 3;
    }
}