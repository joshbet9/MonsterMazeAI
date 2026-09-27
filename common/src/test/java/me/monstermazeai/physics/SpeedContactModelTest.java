package me.monstermazeai.physics;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpeedContactModelTest {
    private static MazeModel floorStrip() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) raw[r][c] = 1;
        }
        return new MazeModel(raw);
    }

    @Test
    void directSpeedContactTowardVoidRemovesGuaranteedVerticalRecovery() {
        GameState before = new GameState();
        before.maze = floorStrip();
        before.player.x = 0.5;
        before.player.z = 2.5;
        before.player.vx = -0.12;
        before.player.vz = 0.0;

        GameState after = before.copy();
        after.player.vx = -1.0;
        after.player.vz = 0.0;
        after.player.vy = 0.95;
        after.player.pendingAirborne = true;
        after.player.grounded = false;

        SpeedContactModel.applyConservativeSlideOutcome(
                before, after, Action.forward(false), true);

        assertEquals(0.0, after.player.vy, 1.0E-9);
        assertFalse(after.player.grounded);
    }

    @Test
    void sameSpeedContactAwayFromVoidKeepsSourceVerticalRecovery() {
        GameState before = new GameState();
        before.maze = floorStrip();
        before.player.x = 2.5;
        before.player.z = 2.5;
        before.player.vx = -0.12;

        GameState after = before.copy();
        after.player.vx = -1.0;
        after.player.vy = 0.95;
        after.player.pendingAirborne = true;
        after.player.grounded = false;

        SpeedContactModel.applyConservativeSlideOutcome(
                before, after, Action.forward(false), true);

        assertEquals(0.95, after.player.vy, 1.0E-9);
    }

    @Test
    void nonSpeedContactDoesNotEnterSlideModel() {
        GameState before = new GameState();
        before.maze = floorStrip();
        before.player.x = 0.5;
        before.player.z = 2.5;
        before.player.vx = 0.03;

        GameState after = before.copy();
        after.player.vx = -1.0;
        after.player.vy = 0.95;

        SpeedContactModel.applyConservativeSlideOutcome(
                before, after, Action.forward(false), true);

        assertEquals(0.95, after.player.vy, 1.0E-9);
    }
}
