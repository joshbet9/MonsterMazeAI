package me.monstermazeai.physics;

import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonsterMazeBumpModelTest {
    @Test
    void speedingAirborneContactGetsNoGroundBoost() {
        PlayerState p = new PlayerState();
        p.x = 1.49; p.y = 0.4; p.z = 0.5; p.grounded = false;
        p.health = 20.0;
        MonsterState m = new MonsterState(1, 0.5, 0.4, 0.5);

        assertEquals(1, MonsterMazeBumpModel.apply(p, List.of(m), 100));
        assertEquals(1.0, p.vx, 1.0E-9);
        assertEquals(0.75, p.vy, 1.0E-9,
                "An airborne/speeding contact must not receive the grounded +0.2 boost.");
        assertEquals(0.0, p.vz, 1.0E-9);
    }

    @Test
    void groundedContactGetsGroundBoost() {
        PlayerState p = new PlayerState();
        p.x = 1.49; p.y = 0.0; p.z = 0.5; p.grounded = true;
        p.health = 20.0;
        MonsterState m = new MonsterState(1, 0.5, 0.0, 0.5);

        assertEquals(1, MonsterMazeBumpModel.apply(p, List.of(m), 100));
        assertEquals(0.95, p.vy, 1.0E-9);
    }

    @Test
    void bumpUsesMonsterToPlayerTrajectoryAndSourceVelocity() {
        PlayerState p = new PlayerState();
        p.x = 1.49; p.y = 0.0; p.z = 0.5; p.grounded = true;
        p.health = 20.0;
        MonsterState m = new MonsterState(1, 0.5, 0.0, 0.5);

        assertEquals(1, MonsterMazeBumpModel.apply(p, List.of(m), 100));
        assertEquals(1.0, p.vx, 1.0E-9);
        assertEquals(0.95, p.vy, 1.0E-9);
        assertEquals(0.0, p.vz, 1.0E-9);
        assertEquals(16.0, p.health, 1.0E-9);
        assertEquals(120, p.recentMobHitUntilTick);
        assertTrue(p.pendingAirborne);
    }

    @Test
    void contactGeometryChangesKnockbackDirection() {
        PlayerState p = new PlayerState();
        p.x = 1.1; p.y = 0.0; p.z = 1.1; p.grounded = true;
        p.health = 20.0;
        MonsterState m = new MonsterState(1, 0.5, 0.0, 0.5);

        MonsterMazeBumpModel.apply(p, List.of(m), 0);
        double firstX = p.vx, firstZ = p.vz;

        p.x = 1.2; p.z = 1.0; p.health = 20.0;
        p.damageTaken = 0.0; p.recentMobHitUntilTick = 0; p.pendingAirborne = false;
        MonsterMazeBumpModel.apply(p, List.of(m), 0);

        assertNotEquals(firstX, p.vx, 1.0E-6);
        assertNotEquals(firstZ, p.vz, 1.0E-6);
    }

    @Test
    void onlyFirstOverlappingMonsterCanDamageOnOneSourceTick() {
        PlayerState p = new PlayerState();
        p.x = 0.5; p.y = 0.0; p.z = 0.5; p.grounded = true;
        p.health = 20.0;
        MonsterState a = new MonsterState(1, 0.0, 0.0, 0.5);
        MonsterState b = new MonsterState(2, 0.5, 0.0, 0.0);

        assertEquals(1, MonsterMazeBumpModel.apply(p, List.of(a, b), 10));
        assertEquals(16.0, p.health, 1.0E-9);
        assertEquals(4.0, p.damageTaken, 1.0E-9);
    }

    @Test
    void bumpVelocityTransitionsToAirborneOnTheNextPhysicsStep() {
        PlayerState p = new PlayerState();
        p.x = 0.5; p.y = 0.0; p.z = 0.5; p.grounded = true;
        p.health = 20.0;
        MonsterState m = new MonsterState(1, 0.0, 0.0, 0.5);

        MonsterMazeBumpModel.apply(p, List.of(m), 10);
        assertTrue(p.pendingAirborne);

        new LegacyMovementModel().tick(p, Action.IDLE);

        assertTrue(p.y > 0.0);
        assertFalse(p.grounded);
        assertTrue(p.vy > 0.0);
    }

    @Test
    void safePadBlocksMonsterBump() {
        GameState game = new GameState();
        game.kit = Kit.JUMPER;
        game.activePadRow = 10;
        game.activePadColumn = 10;
        game.player.x = 10.5;
        game.player.y = 0.0;
        game.player.z = 10.5;
        game.player.grounded = true;
        game.monsters.add(new MonsterState(1, 10.5, 0.0, 10.9));

        assertEquals(0, MonsterMazeBumpModel.apply(game));
        assertEquals(20.0, game.player.health, 1.0E-9);
    }

    @Test
    void bodyRushDeflectsMonsterWithoutDamagingPlayer() {
        GameState game = new GameState();
        game.mode = Mode.MODERN;
        game.kit = Kit.BODY_BUILDER;
        game.ability.activeUntilTick = 200;
        game.player.x = 5.5; game.player.y = 0; game.player.z = 5.5; game.player.grounded = true;
        MonsterState m = new MonsterState(1, 5.9, 0, 5.5);
        game.monsters.add(m);

        assertEquals(1, MonsterMazeBumpModel.apply(game));
        assertEquals(20.0, game.player.health, 1.0E-9);
        assertEquals(160, game.ability.activeUntilTick);
        assertTrue(m.launched(0));
    }

    @Test
    void maverickKnockbackPointsTowardActivePad() {
        GameState game = new GameState();
        game.mode = Mode.MODERN;
        game.kit = Kit.MAVERICK;
        game.activePadRow = 10; game.activePadColumn = 10;
        game.player.x = 5.5; game.player.y = 0; game.player.z = 5.5; game.player.grounded = true;
        MonsterState m = new MonsterState(1, 5.9, 0, 5.5);
        game.monsters.add(m);

        assertEquals(1, MonsterMazeBumpModel.apply(game));
        assertTrue(game.player.vx > 0.0);
        assertTrue(game.player.vz > 0.0);
        assertEquals(4.0, game.player.damageTaken, 1.0E-9);
    }

    @Test
    void cooldownBlocksTheNextTick() {
        PlayerState p = new PlayerState();
        p.x = 0.5; p.y = 0.0; p.z = 0.5; p.grounded = true;
        p.recentMobHitUntilTick = 21;
        MonsterState m = new MonsterState(1, 0.0, 0.0, 0.5);

        assertEquals(0, MonsterMazeBumpModel.apply(p, List.of(m), 20));
        assertEquals(20.0, p.health, 1.0E-9);
    }
}
