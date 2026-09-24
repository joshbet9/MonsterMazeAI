package me.monstermazeai.validation;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic action/physics acceptance tests for the pre-live boundary.
 *
 * These tests validate the simulator contract and explicit tolerance envelopes.
 * They do not claim a real Minecraft client has been driven; that requires a
 * live 1.8.9 validation run in Milestone 13.
 */
class PhysicsActionValidationTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        }
        return new MazeModel(raw);
    }

    private static GameState state(Kit kit) {
        GameState s = new GameState();
        s.mode = Mode.MODERN;
        s.maze = openMaze();
        s.kit = kit;
        s.player.x = 50.5;
        s.player.y = GameState.PATH_Y;
        s.player.z = 50.5;
        s.player.yaw = 0.0f;
        s.player.grounded = true;
        new AbilityModel().initialiseForMode(s);
        return s;
    }

    private static Simulator simulator(GameState s) {
        return new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(s.maze, new Random(12345L), 0.0),
                new CollisionModel());
    }

    @Test
    void idleActionIsPhysicallyStable() {
        GameState s = state(Kit.JUMPER);
        Simulator simulator = simulator(s);
        for (int i = 0; i < 20; i++) simulator.tick(s, Action.IDLE);

        assertEquals(50.5, s.player.x, 1e-9);
        assertEquals(50.5, s.player.z, 1e-9);
        assertEquals(GameState.PATH_Y, s.player.y, 1e-9);
        assertEquals(0.0, s.player.vx, 1e-9);
        assertEquals(0.0, s.player.vz, 1e-9);
        assertTrue(s.player.grounded);
    }

    @Test
    void sprintForwardProducesMonotonicMovementWithinExpectedEnvelope() {
        GameState s = state(Kit.JUMPER);
        Simulator simulator = simulator(s);
        double previousZ = s.player.z;

        for (int i = 0; i < 20; i++) {
            simulator.tick(s, Action.forward(false));
            assertTrue(s.player.z > previousZ);
            previousZ = s.player.z;
        }

        double displacement = s.player.z - 50.5;
        assertTrue(displacement > 2.0, "Sprint displacement too small: " + displacement);
        assertTrue(displacement < 8.0, "Sprint displacement outside tolerance: " + displacement);
        assertEquals(50.5, s.player.x, 1e-9);
    }

    @Test
    void jumpHasSourceShapedArcAndReturnsToGround() {
        GameState s = state(Kit.JUMPER);
        Simulator simulator = simulator(s);

        simulator.tick(s, new Action(1, 0, true, true, 0.0f, false));
        double apex = s.player.y;
        assertTrue(s.player.y > GameState.PATH_Y);
        assertFalse(s.player.grounded);
        assertEquals(2, s.ability.charges);

        for (int i = 0; i < 30 && !s.player.grounded; i++) {
            simulator.tick(s, Action.IDLE);
            apex = Math.max(apex, s.player.y);
        }

        assertTrue(apex > GameState.PATH_Y + 0.35);
        assertTrue(apex < GameState.PATH_Y + 1.5);
        assertTrue(s.player.grounded);
        assertEquals(GameState.PATH_Y, s.player.y, 1e-9);
        assertEquals(0.0, s.player.vy, 1e-9);
    }

    @Test
    void yawDeltaChangesMovementDirectionBeforePhysicsStep() {
        GameState s = state(Kit.JUMPER);
        Simulator simulator = simulator(s);

        simulator.tick(s, Action.forward(false).withYawDelta(90.0f));

        assertTrue(s.player.x < 50.5);
        assertEquals(50.5, s.player.z, 0.25);
    }

    @Test
    void repulsorAbilityProducesDeterministicMonsterLaunch() {
        GameState s = state(Kit.REPULSOR);
        MonsterState monster = new MonsterState(7, 51.5, GameState.PATH_Y, 50.5);
        s.monsters.add(monster);
        Simulator simulator = simulator(s);

        simulator.tick(s, new Action(0, 0, false, false, 0.0f, true));

        assertEquals(2, s.ability.charges);
        assertTrue(monster.launched(s.tick));
        assertTrue(monster.vx > 0.0);
        assertEquals(0.0, monster.vz, 1e-9);
        assertEquals(1.0, monster.vy, 1e-9);
    }

    @Test
    void bodyBuilderAbilityConvertsMonsterContactIntoLaunch() {
        GameState s = state(Kit.BODY_BUILDER);
        s.ability.activations = 2;
        MonsterState monster = new MonsterState(8, 50.5, GameState.PATH_Y, 51.0);
        s.monsters.add(monster);
        Simulator simulator = simulator(s);

        simulator.tick(s, new Action(0, 0, false, false, 0.0f, true));
        assertEquals(1, s.ability.activations);
        assertTrue(s.ability.activeUntilTick > s.tick);

        double healthBefore = s.player.health;
        simulator.tick(s, Action.IDLE);

        assertEquals(healthBefore, s.player.health, 1e-9);
        assertTrue(monster.launched(s.tick));
        assertTrue(monster.vz > 0.0);
    }

    @Test
    void simulatorPreservesFiniteStateAcrossCombinedActions() {
        GameState s = state(Kit.JUMPER);
        Simulator simulator = simulator(s);
        Action action = new Action(1.0, -1.0, true, true, 30.0f, false);

        for (int i = 0; i < 40; i++) {
            simulator.tick(s, action);
            assertTrue(Double.isFinite(s.player.x));
            assertTrue(Double.isFinite(s.player.y));
            assertTrue(Double.isFinite(s.player.z));
            assertTrue(Double.isFinite(s.player.vx));
            assertTrue(Double.isFinite(s.player.vy));
            assertTrue(Double.isFinite(s.player.vz));
        }

        assertTrue(s.player.y >= GameState.PATH_Y);
        assertTrue(s.player.y < GameState.PATH_Y + 2.0);
    }
}
