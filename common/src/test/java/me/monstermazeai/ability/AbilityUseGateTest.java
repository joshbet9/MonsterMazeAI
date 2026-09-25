package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbilityUseGateTest {
    private static GameState threat(Kit kit) {
        GameState s = new GameState();
        s.kit = kit;
        s.ability.charges = kit == Kit.BODY_BUILDER ? 0 : 3;
        s.ability.activations = kit == Kit.BODY_BUILDER ? 2 : 0;
        s.monsters.add(new MonsterState(1, 1.0, 0, 0));
        return s;
    }

    @Test
    void cryoCannotPulseAgainDuringKnownCooldown() {
        GameState s = threat(Kit.SLOWBALLER);
        AbilityUseGate gate = new AbilityUseGate();
        assertTrue(gate.allow(s));
        gate.record(s);
        s.tick = 599;
        assertFalse(gate.allow(s));
        s.tick = 600;
        assertTrue(gate.allow(s));
    }

    @Test
    void bodyRushIsGatedForItsActiveWindow() {
        GameState s = threat(Kit.BODY_BUILDER);
        AbilityUseGate gate = new AbilityUseGate();
        assertTrue(gate.allow(s));
        gate.record(s);
        s.tick = 199;
        assertFalse(gate.allow(s));
        s.tick = 200;
        assertTrue(gate.allow(s));
    }

    @Test
    void kitChangeDoesNotInheritPreviousKitCooldown() {
        GameState s = threat(Kit.SLOWBALLER);
        AbilityUseGate gate = new AbilityUseGate();
        gate.record(s);
        s.kit = Kit.REPULSOR;
        s.ability.charges = 3;
        assertTrue(gate.allow(s));
    }
}
