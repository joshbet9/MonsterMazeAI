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
    void repulsorIsReservedForADeadlineBlockedByAMonster() {
        GameState s = new GameState();
        s.kit = Kit.REPULSOR;
        s.ability.charges = 3;
        s.activePadRow = 10;
        s.activePadColumn = 0;
        s.phaseTicksRemaining = 20;
        s.player.x = 0.5;
        s.player.z = 0.5;
        s.monsters.add(new MonsterState(2, 3.0, 0.0, 0.5));

        assertTrue(AbilityDecision.shouldUse(s, "NO_ROUTE", "dynamic-mob-block"));
        s.phaseTicksRemaining = 100;
        assertFalse(AbilityDecision.shouldUse(s, "NO_ROUTE", "dynamic-mob-block"));

        // There is still enough time to tolerate a transient obstruction,
        // replan, and traverse the route.
        s.phaseTicksRemaining = 120;
        assertFalse(AbilityDecision.shouldUse(s, "NO_ROUTE", "dynamic-mob-block"));

        // Once the remaining timer is below the traversal + reopen reserve
        // + safety margin, waiting is no longer viable.
        s.phaseTicksRemaining = 90;
        assertTrue(AbilityDecision.shouldUse(s, "NO_ROUTE", "dynamic-mob-block"));
    }

    @Test
    void repulsorProtectsLowHealthPlayerFromImminentMonsterHit() {
        GameState s = new GameState();
        s.kit = Kit.REPULSOR;
        s.ability.charges = 1;
        s.player.health = 4.0;
        s.player.x = 0.5;
        s.player.z = 0.5;
        MonsterState m = new MonsterState(3, 2.0, 0.0, 0.5);
        m.vx = -0.5;
        s.monsters.add(m);

        assertTrue(AbilityDecision.shouldUse(s, "MOVEMENT_PLANNER", "normal movement"));

        s.player.health = 6.0;
        assertFalse(AbilityDecision.shouldUse(s, "MOVEMENT_PLANNER", "normal movement"));
    }

    @Test
    void repulsorDoesNotFireJustBecauseAMonsterIsNearby() {
        GameState s = new GameState();
        s.kit = Kit.REPULSOR;
        s.ability.charges = 3;
        s.player.x = 0.5;
        s.player.z = 0.5;
        s.monsters.add(new MonsterState(4, 2.0, 0.0, 0.5));

        assertFalse(AbilityDecision.shouldUse(s, "MOVEMENT_PLANNER", "normal movement"));
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
