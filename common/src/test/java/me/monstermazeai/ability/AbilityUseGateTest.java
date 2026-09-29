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
        if (kit == Kit.BODY_BUILDER) {
            s.player.health = 4.0;
            s.monsters.get(0).vx = -0.4;
        }
        if (kit == Kit.SLOWBALLER) {
            s.activePadRow = 8;
            s.activePadColumn = 0;
            s.player.x = 0.5;
            s.player.z = 0.5;
        }
        return s;
    }

    @Test
    void cryoCannotPulseAgainDuringKnownCooldown() {
        GameState s = threat(Kit.SLOWBALLER);
        AbilityUseGate gate = new AbilityUseGate();
        assertTrue(gate.allow(s, "ROUTE_OPENING", "monster blocks efficient route"));
        gate.record(s);
        s.tick = 599;
        assertFalse(gate.allow(s, "ROUTE_OPENING", "monster blocks efficient route"));
        s.tick = 600;
        assertTrue(gate.allow(s));
    }

    @Test
    void bodyRushIsGatedForItsActiveWindow() {
        GameState s = threat(Kit.BODY_BUILDER);
        AbilityUseGate gate = new AbilityUseGate();
        assertTrue(gate.allow(s, "MOVEMENT_PLANNER", "imminent contact"));
        gate.record(s);
        s.tick = 199;
        assertFalse(gate.allow(s, "MOVEMENT_PLANNER", "imminent contact"));
        s.tick = 200;
        assertTrue(gate.allow(s, "MOVEMENT_PLANNER", "imminent contact"));
    }

    @Test
    void kitChangeDoesNotInheritPreviousKitCooldown() {
        GameState s = threat(Kit.SLOWBALLER);
        AbilityUseGate gate = new AbilityUseGate();
        gate.record(s);
        s.kit = Kit.REPULSOR;
        s.ability.charges = 3;
        assertTrue(gate.allow(s, true));
    }
}
