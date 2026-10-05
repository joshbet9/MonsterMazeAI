package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbilityDecisionTest {
    @Test
    void repulsorClearsASecondContactDuringPostHitRecovery() {
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.kit = Kit.REPULSOR;
        state.alive = true;
        state.activePadRow = 40;
        state.activePadColumn = 40;
        state.phaseTicksRemaining = 200;
        state.player.health = 16.0;
        state.player.recentMobHitUntilTick = 20;
        state.player.mobHitGraceUntilTick = 40;
        state.tick = 0;
        state.ability.charges = 3;
        state.monsters.add(new MonsterState(7, 2.0, 0.0, 0.0));

        assertTrue(AbilityDecision.shouldUse(state));
    }

    @Test
    void repulsorDoesNotBurnAChargeForPostHitStateWithNoLocalMonster() {
        GameState state = new GameState();
        state.mode = Mode.MODERN;
        state.kit = Kit.REPULSOR;
        state.alive = true;
        state.activePadRow = 40;
        state.activePadColumn = 40;
        state.phaseTicksRemaining = 200;
        state.player.health = 16.0;
        state.player.recentMobHitUntilTick = 20;
        state.player.mobHitGraceUntilTick = 40;
        state.tick = 0;
        state.ability.charges = 3;

        assertFalse(AbilityDecision.shouldUse(state));
    }
}