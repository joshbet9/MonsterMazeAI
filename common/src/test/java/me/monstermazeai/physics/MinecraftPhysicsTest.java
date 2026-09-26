package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftPhysicsTest {
    @Test
    void forwardMovementUsesPlayerYaw() {
        MinecraftPhysics physics = new MinecraftPhysics();
        PlayerState p = new PlayerState();
        p.grounded = true;
        p.yaw = 90.0f;

        physics.tick(p, new Action(1, 0, false, false, 0, false));

        assertTrue(p.x < -0.05, "Yaw 90 forward must move toward negative X");
        assertEquals(0.0, p.z, 0.02, "Yaw 90 forward must not move materially along Z");
    }

    @Test
    void yawDeltaAffectsTheSameMovementTick() {
        MinecraftPhysics physics = new MinecraftPhysics();
        PlayerState p = new PlayerState();
        p.grounded = true;
        p.yaw = 0.0f;

        physics.tick(p, new Action(1, 0, false, false, 90.0f, false));

        assertTrue(p.x < -0.05, "A yaw turn must affect movement immediately");
        assertEquals(0.0, p.z, 0.02);
        assertEquals(90.0f, p.yaw, 0.001f);
    }

    @Test
    void groundAccelerationMatchesVanillaScaleRatherThanRawConstant() {
        MinecraftPhysics physics = new MinecraftPhysics();
        PlayerState p = new PlayerState();
        p.grounded = true;
        p.yaw = 0.0f;

        physics.tick(p, new Action(1, 0, false, false, 0, false));

        // Vanilla 1.8 uses getAIMoveSpeed (~0.1) * 0.16277136 / 0.546^3,
        // then applies 0.91 horizontal drag after integration.
        assertTrue(Math.abs(p.vz) < 0.10,
                "The simulator must not apply the raw 0.16277136/friction^3 value as acceleration");
        assertTrue(p.z > 0.0);
    }

    @Test
    void diagonalInputIsNormalizedLikeMoveFlying() {
        MinecraftPhysics physics = new MinecraftPhysics();
        PlayerState p = new PlayerState();
        p.grounded = true;
        p.yaw = 0.0f;

        physics.tick(p, new Action(1, 1, false, false, 0, false));

        assertTrue(p.x > 0.0 && p.z > 0.0);
        assertTrue(Math.hypot(p.vx, p.vz) < 0.10,
                "Diagonal input must not exceed the normalized vanilla input magnitude");
    }
}
