package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbilityModelTest {
    private final AbilityModel model = new AbilityModel();

    @Test
    void jumperUsesThreeChargesInQolAndFiveInOriginal() {
        GameState qol = new GameState();
        qol.mode = Mode.MODERN;
        qol.kit = Kit.JUMPER;
        model.initialiseForMode(qol);
        assertEquals(3, qol.ability.charges);

        GameState original = new GameState();
        original.mode = Mode.ORIGINAL;
        original.kit = Kit.JUMPER;
        model.initialiseForMode(original);
        assertEquals(5, original.ability.charges);
    }

    @Test
    void jumperChargeHas750MsGate() {
        GameState s = new GameState();
        s.mode = Mode.MODERN;
        s.kit = Kit.JUMPER;
        s.player.y = 0.5;
        model.initialiseForMode(s);

        assertTrue(model.consumeJumperCharge(s));
        assertEquals(2, s.ability.charges);
        assertFalse(model.consumeJumperCharge(s));

        s.tick += 15;
        assertTrue(model.consumeJumperCharge(s));
        assertEquals(1, s.ability.charges);
    }

    @Test
    void cryoFreezesMonstersForThreeSecondsAndStartsThirtySecondCooldown() {
        GameState s = new GameState();
        s.mode = Mode.MODERN;
        s.kit = Kit.SLOWBALLER;
        model.initialiseForMode(s);
        s.monsters.add(new MonsterState(1, 2, 0, 0));
        s.player.x = 0;
        s.player.y = 0;
        s.player.z = 0;

        assertTrue(model.activate(s));
        assertEquals(60, s.monsters.get(0).frozenUntilTick);
        assertEquals(600, s.ability.cooldownUntilTick);
        assertFalse(model.activate(s));
    }

    @Test
    void repulsorLaunchesNearbyMonstersAndConsumesOneCharge() {
        GameState s = new GameState();
        s.kit = Kit.REPULSOR;
        model.initialiseForMode(s);
        s.monsters.add(new MonsterState(1, 2, 0, 0));

        assertTrue(model.activate(s));
        assertEquals(2, s.ability.charges);
        MonsterState m = s.monsters.get(0);
        assertEquals(1.0, m.vx, 1e-9);
        assertEquals(1.0, m.vy, 1e-9);
        assertTrue(m.launched(s.tick));
    }

    @Test
    void bodyRushTurnsMonsterContactIntoDeflectionAndShortensDuration() {
        GameState s = new GameState();
        s.mode = Mode.MODERN;
        s.kit = Kit.BODY_BUILDER;
        model.initialiseForMode(s);

        assertTrue(model.activate(s));
        assertTrue(model.isBodyRushActive(s));
        long before = s.ability.activeUntilTick;
        model.consumeBodyRushContact(s);
        assertEquals(before - 40, s.ability.activeUntilTick);
    }
}
