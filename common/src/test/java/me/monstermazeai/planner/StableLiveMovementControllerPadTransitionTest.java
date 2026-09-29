package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StableLiveMovementControllerPadTransitionTest {
    private static MazeModel transitionMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        MazeModel maze = new MazeModel(raw);

        // The old SafePad is deliberately absent from the canonical raw layout.
        // The new active pad and the post-pad corridor remain real physical floor.
        for (int row = 28; row <= 32; row++) {
            for (int col = 18; col <= 22; col++) {
                maze.setPhysicalFloor(row, col, true);
            }
        }
        for (int row = 23; row <= 27; row++) {
            maze.setPhysicalFloor(row, 20, true);
        }
        return maze;
    }

    @Test
    void bridgesFromPreviousSafePadWhenItsRawCellDisappearsAtTransition() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.alive = true;
        state.maze = transitionMaze();
        state.activePadRow = 20;
        state.activePadColumn = 20;
        state.player.x = 20.0;
        state.player.z = 20.0;
        state.player.y = 0.0;
        state.player.yaw = -90.0F;
        state.player.grounded = true;

        StableLiveMovementController controller = new StableLiveMovementController();

        // Establish the previous objective while standing on its source-accurate pad.
        state.tick = 1L;
        Action oldPadAction = controller.nextAction(state, new Cell(20, 20), false);
        assertEquals(0.0, oldPadAction.forward(), 1.0e-6);

        // The server replaces the active pad. The old pad is no longer represented
        // in physicalFloor, but the player is still physically standing on it.
        state.tick = 2L;
        state.activePadRow = 30;
        state.activePadColumn = 20;

        Action nextPadAction = controller.nextAction(state, new Cell(30, 20), false);

        assertTrue(nextPadAction.forward() > 0.0,
                "the first post-transition decision must leave the previous SafePad");
        assertEquals(0.0, nextPadAction.strafe(), 1.0e-6);
        assertFalse(controller.lastDecisionDetail().contains("No physical route"),
                "previous-pad disappearance must not make the next objective appear disconnected");
    }

    @Test
    void doesNotInventOldPadFloorWhenPlayerIsNotOnPreviousPad() {
        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.alive = true;
        state.maze = transitionMaze();

        // Give the first objective a legitimate route so the controller can
        // remember it, then remove that route before the pad transition.
        for (int row = 10; row <= 20; row++) {
            state.maze.setPhysicalFloor(row, 10, true);
        }
        for (int column = 10; column <= 20; column++) {
            state.maze.setPhysicalFloor(20, column, true);
        }

        state.activePadRow = 20;
        state.activePadColumn = 20;
        state.player.x = 10.5;
        state.player.z = 10.5;
        state.player.y = 0.0;
        state.player.yaw = 0.0F;
        state.player.grounded = true;

        StableLiveMovementController controller = new StableLiveMovementController();

        state.tick = 1L;
        Action first = controller.nextAction(state, new Cell(20, 20), false);
        assertTrue(first.forward() > 0.0);

        for (int row = 10; row <= 20; row++) {
            state.maze.setPhysicalFloor(row, 10, false);
        }
        for (int column = 10; column <= 20; column++) {
            state.maze.setPhysicalFloor(20, column, false);
        }

        state.tick = 2L;
        state.activePadRow = 30;
        state.activePadColumn = 20;

        Action action = controller.nextAction(state, new Cell(30, 20), false);

        assertEquals(0.0, action.forward(), 1.0e-6);
        assertEquals(0.0, action.strafe(), 1.0e-6);
        assertEquals(0.0F, action.yawDelta(), 1.0e-6F);
        assertTrue(controller.lastDecisionDetail().contains("No physical route")
                        || action == Action.IDLE,
                "the transition bridge must not turn arbitrary air into a valid starting surface");
    }
}
