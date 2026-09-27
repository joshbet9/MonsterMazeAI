package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StableLiveMovementControllerTest {
    private static MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++) raw[r][c] = 1;
        return new MazeModel(raw);
    }

    private static GameState state(double x, double z, float yaw) {
        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = openMaze();
        s.activePadRow = 0;
        s.activePadColumn = 8;
        s.player.x = x;
        s.player.z = z;
        s.player.yaw = yaw;
        s.player.grounded = true;
        return s;
    }

    @Test
    void reachesStraightLineObjectiveWithoutPlannerOscillation() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        int reachedTick = -1;
        for (int tick = 1; tick <= 240; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(0, 8), false);
            physics.tick(s.player, action);
            if (Math.hypot(s.player.x - 0.5, s.player.z - 8.5) < 0.55) {
                reachedTick = tick;
                break;
            }
        }

        assertTrue(reachedTick > 0, "stable controller did not reach the objective");
        assertTrue(s.player.z > 7.9, "player did not physically travel toward the pad");
    }

    @Test
    void sourceSafePadIntegerCoordinateDoesNotTriggerLaneSafetyStop() {
        GameState s = state(0.0, 0.0, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        Action first = controller.nextAction(s, new Cell(8, 0), false);

        assertEquals(0.0, first.forward(), 1.0e-6);
        assertEquals(0.0, first.strafe(), 1.0e-6);
        assertEquals(-12.0F, first.yawDelta(), 1.0e-6F,
                "the initial 90-degree heading error must turn in place rather than safety-stop");
        assertFalse(controller.lastDecisionDetail().contains("SAFETY_STOP"));
    }

    @Test
    void sourceSafePadLaneOffsetIsPreservedAfterHeadingAligns() {
        GameState s = state(0.0, 0.0, -90.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        Action action = controller.nextAction(s, new Cell(8, 0), false);

        assertTrue(action.forward() > 0.0,
                "a legitimate integer-centred SafePad spawn must be allowed to enter the cardinal route");
        assertEquals(0.0, action.strafe(), 1.0e-6);
        assertEquals(0.0F, action.yawDelta(), 1.0e-6F);
        assertFalse(controller.lastDecisionDetail().contains("SAFETY_STOP"));
    }

    @Test
    void turnsTowardSidewaysObjectiveWithoutStrafingBackAndForth() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        boolean sawForward = false;
        boolean sawStrafe = false;
        for (int tick = 1; tick <= 180; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(8, 0), false);
            sawForward |= action.forward() > 0.0;
            sawStrafe |= Math.abs(action.strafe()) > 0.0;
            physics.tick(s.player, action);
            if (Math.hypot(s.player.x - 8.5, s.player.z - 0.5) < 0.55) break;
        }

        assertTrue(sawForward);
        assertFalse(sawStrafe);
        assertTrue(s.player.x > 7.9);
    }

    @Test
    void neverCombinesForwardDriveWithYawTurn() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        for (int tick = 1; tick <= 220; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(8, 8), false);
            assertFalse(action.forward() > 0.0 && Math.abs(action.yawDelta()) > 0.0);
            physics.tick(s.player, action);
            if (Math.hypot(s.player.x - 8.5, s.player.z - 8.5) < 0.55) return;
        }

        fail("controller did not reach the diagonal objective");
    }

    @Test
    void usesCardinalTurnPointsInsteadOfCuttingAcrossOpenDiagonal() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        boolean reachedCornerArea = false;
        boolean sawTurnInPlace = false;
        for (int tick = 1; tick <= 240; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(4, 4), false);

            if (Math.max(s.player.x, s.player.z) > 3.1 && Math.abs(action.forward()) < 0.001) {
                sawTurnInPlace = true;
            }
            physics.tick(s.player, action);

            if (s.player.z > 3.7 && s.player.x > 3.0) {
                reachedCornerArea = true;
                break;
            }
        }

        assertTrue(reachedCornerArea);
        assertTrue(sawTurnInPlace,
                "controller should brake/turn in place at a committed route turn rather than arc through it");
    }
}
