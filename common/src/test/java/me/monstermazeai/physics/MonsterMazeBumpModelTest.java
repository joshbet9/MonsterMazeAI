package me.monstermazeai.physics;

import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonsterMazeBumpModelTest {
    @Test
    void bumpUsesMonsterToPlayerTrajectoryAndSourceVelocity() {
        PlayerState p = new PlayerState();
        p.x = 1.5; p.y = 0.0; p.z = 0.5; p.grounded = true;
        MonsterState m = new MonsterState(1, 0.5, 0.0, 0.5);

        assertEquals(1, MonsterMazeBumpModel.apply(p, List.of(m), 100));
        assertEquals(1.0, p.vx, 1.0E-9);
        assertEquals(0.95, p.vy, 1.0E-9);
        assertEquals(0.0, p.vz, 1.0E-9);
        assertEquals(16.0, p.health, 1.0E-9);
        assertEquals(120, p.recentMobHitUntilTick);
    }

    @Test
    void contactGeometryChangesKnockbackDirection() {
        PlayerState p = new PlayerState();
        p.x = 1.2; p.y = 0.0; p.z = 1.3; p.grounded = true;
        MonsterState m = new MonsterState(1, 0.5, 0.0, 0.5);

        MonsterMazeBumpModel.apply(p, List.of(m), 0);
        double firstX = p.vx, firstZ = p.vz;

        p.x = 1.3; p.z = 1.2; p.health = 20.0;
        p.damageTaken = 0.0; p.recentMobHitUntilTick = 0;
        MonsterMazeBumpModel.apply(p, List.of(m), 0);

        assertNotEquals(firstX, p.vx, 1.0E-6);
        assertNotEquals(firstZ, p.vz, 1.0E-6);
    }

    @Test
    void overlappingMonstersCanBothDamageOnOneSourceTick() {
        PlayerState p = new PlayerState();
        p.x = 0.5; p.y = 0.0; p.z = 0.5; p.grounded = true;
        MonsterState a = new MonsterState(1, 0.0, 0.0, 0.5);
        MonsterState b = new MonsterState(2, 0.5, 0.0, 0.0);

        assertEquals(2, MonsterMazeBumpModel.apply(p, List.of(a, b), 10));
        assertEquals(12.0, p.health, 1.0E-9);
        assertEquals(8.0, p.damageTaken, 1.0E-9);
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
