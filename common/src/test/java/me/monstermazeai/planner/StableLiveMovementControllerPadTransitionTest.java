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
        state.player.yaw = 0.0F;
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
        state.activePadRow = 20;
        state.activePadColumn = 20;
        state.player.x = 10.5;
        state.player.z = 10.5;
        state.player.y = 0.0;
        state.player.yaw = 0.0F;
        state.player.grounded = true;

        StableLiveMovementController controller = new StableLiveMovementController();

        state.tick = 1L;
        controller.nextAction(state, new Cell(20, 20), false);

        state.tick = 2L;
        state.activePadRow = 30;
        state.activePadColumn = 20;

        Action action = controller.nextAction(state, new Cell(30, 20), false);

        assertEquals(0.0, action.forward(), 1.0e-6);
        assertTrue(controller.lastDecisionDetail().contains("No physical route")
                        || controller.lastDecisionDetail().contains("OUT_OF_BOUNDS")
                        || action == Action.IDLE,
                "the transition bridge must not turn arbitrary air into a valid starting surface");
    }
}
