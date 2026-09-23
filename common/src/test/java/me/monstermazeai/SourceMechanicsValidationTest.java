package me.monstermazeai;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.monster.MonsterSimulator;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class SourceMechanicsValidationTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    private static GameState state(Kit kit) {
        GameState s = new GameState();
        s.mode = Mode.MODERN;
        s.maze = openMaze();
        s.player.x = 20.5;
        s.player.y = GameState.PATH_Y;
        s.player.z = 20.5;
        s.player.grounded = true;
        s.player.health = 20;
        s.player.maxHealth = 20;
        s.activePadRow = 20;
        s.activePadColumn = 20;
        s.kit = kit;
        return s;
    }

    @Test
    void jumperHasModeCorrectInitialCharges() {
        AbilityModel abilities = new AbilityModel();
        GameState modern = state(Kit.JUMPER);
        abilities.initialiseForMode(modern);
        assertEquals(3, modern.ability.charges);

        GameState original = state(Kit.JUMPER);
        original.mode = Mode.ORIGINAL;
        abilities.initialiseForMode(original);
        assertEquals(5, original.ability.charges);
    }

    @Test
    void jumperChargeGateIs750msButPostHitGraceIsTwoSeconds() {
        AbilityModel abilities = new AbilityModel();
        GameState s = state(Kit.JUMPER);
        abilities.initialiseForMode(s);
        s.player.y = 1.0;
        s.player.grounded = false;

        assertTrue(abilities.consumeJumperCharge(s));
        assertEquals(2, s.ability.charges);

        // The 15-tick gate is a minimum interval between charge consumption.
        s.tick = 14;
        assertFalse(abilities.consumeJumperCharge(s));

        s.tick = 15;
        assertTrue(abilities.consumeJumperCharge(s));
        assertEquals(1, s.ability.charges);

        // A monster bump gives a separate two-second grace window.
        s.tick = 16;
        s.player.recentMobHitUntilTick = 16;
        assertFalse(abilities.consumeJumperCharge(s));
        s.tick = 55;
        assertFalse(abilities.consumeJumperCharge(s));
        s.tick = 56;
        assertTrue(abilities.consumeJumperCharge(s));
    }

    @Test
    void cryoUsesCooldownNotChargesAndFreezesSixBlockRadius() {
        AbilityModel abilities = new AbilityModel();
        GameState s = state(Kit.SLOWBALLER);
        s.monsters.add(new MonsterState(1, 23.5, 0, 20.5));
        s.monsters.add(new MonsterState(2, 27.6, 0, 20.5));

        assertTrue(abilities.activate(s));
        assertEquals(600, s.ability.cooldownUntilTick);
        assertEquals(60, s.monsters.get(0).frozenUntilTick);
        assertEquals(0, s.monsters.get(1).frozenUntilTick);

        s.tick = 599;
        assertFalse(abilities.activate(s));
        s.tick = 600;
        assertTrue(abilities.activate(s));
        assertEquals(120, s.monsters.get(0).frozenUntilTick);
    }

    @Test
    void bodyRushConsumesActivationAndTwoSecondsPerContact() {
        AbilityModel abilities = new AbilityModel();
        GameState s = state(Kit.BODY_BUILDER);
        abilities.initialiseForMode(s);

        assertTrue(abilities.activate(s));
        assertEquals(1, s.ability.activations);
        assertEquals(200, s.ability.activeUntilTick);

        s.tick = 1;
        abilities.consumeBodyRushContact(s);
        assertEquals(161, s.ability.activeUntilTick);

        assertTrue(abilities.activate(s) == false);
        s.tick = 161;
        assertTrue(abilities.activate(s));
        assertEquals(0, s.ability.activations);
    }

    @Test
    void repulsorLaunchesOnlyWithinSixBlocks() {
        AbilityModel abilities = new AbilityModel();
        GameState s = state(Kit.REPULSOR);
        abilities.initialiseForMode(s);

        MonsterState near = new MonsterState(1, 23.5, 0, 20.5);
        MonsterState far = new MonsterState(2, 27.6, 0, 20.5);
        s.monsters.add(near);
        s.monsters.add(far);

        assertTrue(abilities.activate(s));
        assertEquals(2, s.ability.charges);
        assertEquals(s.tick + 30, near.launchedUntilTick);
        assertEquals(0, far.launchedUntilTick);
        assertTrue(near.vx > 0);
    }

    @Test
    void monsterCollisionUsesSourceThresholdDamageAndGroundBoost() {
        GameState s = state(Kit.JUMPER);
        MonsterState monster = new MonsterState(1, 20.5, 0, 21.0);
        CollisionModel collision = new CollisionModel();
        collision.tryMonsterHit(s, monster);

        assertEquals(16.0, s.player.health);
        assertEquals(0.95, s.player.vy, 1e-9);
        assertFalse(s.player.grounded);
    }

    @Test
    void progressionRequestsPreviewAtTwoSecondsAndAddsWaveAtBoundary() {
        GameState s = state(Kit.JUMPER);
        GameProgressionModel progression = new GameProgressionModel();
        progression.initialise(s);

        assertEquals(225, s.pendingMonsterSpawns);

        s.phaseTicksRemaining = 3 * 20;
        for (int i = 0; i < 20; i++) progression.tick(s);

        assertEquals(2 * 20, s.phaseTicksRemaining);
        assertTrue(s.previewPadRequested);

        s.previewPadRow = 30;
        s.previewPadColumn = 30;
        s.phaseTicksRemaining = 20;

        // Keep the player on the current active pad for the phase boundary.
        for (int i = 0; i < 20; i++) progression.tick(s);

        assertTrue(s.alive);
        assertEquals(2, s.stage);
        assertEquals(30, s.activePadRow);
        assertEquals(30, s.activePadColumn);
        assertEquals(255, s.pendingMonsterSpawns);
    }

    @Test
    void centerDeteriorationStartsAfterTwentyLiveSeconds() {
        GameState s = state(Kit.JUMPER);
        GameProgressionModel progression = new GameProgressionModel();
        progression.initialise(s);
        s.activePadRow = -1;
        s.activePadColumn = -1;
        s.phaseTicksRemaining = 100 * 20;

        for (int i = 0; i < 19 * 20; i++) progression.tick(s);
        assertEquals(11, s.centerSafeZoneDecay);

        for (int i = 0; i < 20; i++) progression.tick(s);
        assertEquals(10, s.centerSafeZoneDecay);
    }
}
