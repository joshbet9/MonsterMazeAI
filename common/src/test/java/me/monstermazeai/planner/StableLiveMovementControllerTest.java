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
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
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

            double distance = Math.hypot(s.player.x - 0.5, s.player.z - 8.5);
            if (distance < 0.55) {
                reachedTick = tick;
                break;
            }
        }

        assertTrue(reachedTick > 0, "stable controller did not reach the objective");
        assertTrue(s.player.z > 7.9, "player did not physically travel toward the pad");
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
        assertFalse(sawStrafe,
                "stable steering should use one authoritative heading dimension, not strafe/yaw competition");
        assertTrue(s.player.x > 7.9, "player did not turn and travel toward the sideways objective");
    }

    @Test
    void neverCombinesForwardDriveWithYawTurn() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();
        LegacyMazePhysics physics = new LegacyMazePhysics();

        for (int tick = 1; tick <= 220; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(8, 8), false);

            assertFalse(action.forward() > 0.0 && Math.abs(action.yawDelta()) > 0.0,
                    "forward movement must never be combined with a turn: " + action);

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

        for (int tick = 1; tick <= 220; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(4, 4), false);

            if (s.player.z > 3.1 && Math.abs(action.forward()) < 0.001) {
                sawTurnInPlace = true;
            }
            physics.tick(s.player, action);

            if (s.player.z > 3.7 && s.player.x > 3.0) {
                reachedCornerArea = true;
                break;
            }
        }

        assertTrue(reachedCornerArea, "controller did not traverse the straight corridor");
        assertTrue(sawTurnInPlace,
                "controller should brake/turn in place at a committed route turn rather than arc through it");
    }
}
