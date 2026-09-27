package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FirstPadMovementControllerTest {
    private static GameState state(Kit kit, int jumpCharges, double x, double z, float yaw) {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];

        // Authoritative physical floor for a cardinal L route:
        // (50,49) -> (60,49) -> (60,60).
        for (int row = 50; row <= 60; row++) raw[row][49] = 1;
        for (int column = 49; column <= 60; column++) raw[60][column] = 1;

        // Active Safe Pad surface is explicitly physical floor in the live model.
        for (int row = 58; row <= 62; row++) {
            for (int column = 58; column <= 62; column++) raw[row][column] = 1;
        }

        GameState state = new GameState();
        state.inMonsterMaze = true;
        state.alive = true;
        state.activePadRow = 60;
        state.activePadColumn = 60;
        state.mazePattern = 2;
        state.kit = kit;
        state.maze = new MazeModel(raw);
        state.player.x = x;
        state.player.z = z;
        state.player.y = 0.0;
        state.player.yaw = yaw;
        state.player.grounded = true;
        state.player.jumpCharges = jumpCharges;
        return state;
    }

    @Test
    public void usesForwardSprintAndNoStrafeForBaselineSpeed() {
        FirstPadMovementController controller = new FirstPadMovementController();
        Action action = controller.nextAction(state(Kit.REPULSOR, 0, 50.5, 49.5, -90.0F));

        assertEquals(1.0, action.forward(), 0.0);
        assertEquals(0.0, action.strafe(), 0.0);
        assertTrue(action.sprint());
        assertTrue(action.jump());
        assertEquals(0.0F, action.yawDelta(), 0.0F);
        assertTrue(controller.routeSize() > 1);
    }

    @Test
    public void acquiresInitialHeadingAggressivelyWithoutDrivingAcrossTheMaze() {
        FirstPadMovementController controller = new FirstPadMovementController();

        // First route segment is toward +row => -90 degrees.
        Action action = controller.nextAction(state(Kit.REPULSOR, 0, 50.5, 49.5, 0.0F));

        assertEquals(0.0, action.forward(), 0.0);
        assertFalse(action.sprint());
        assertEquals(30.0F, action.yawDelta(), 0.0F);
        assertEquals(0.0, action.strafe(), 0.0);
    }

    @Test
    public void usesFullForwardOnceHeadingIsCloseEnough() {
        FirstPadMovementController controller = new FirstPadMovementController();

        // Desired -90, current -110 => 20 degree error.
        Action action = controller.nextAction(state(Kit.REPULSOR, 0, 50.5, 49.5, -110.0F));

        assertEquals(1.0, action.forward(), 0.0);
        assertTrue(action.sprint());
        assertEquals(20.0F, action.yawDelta(), 0.0F);
    }

    @Test
    public void cornerTurnUsesAggressiveYawAndReducesForwardTravel() {
        FirstPadMovementController controller = new FirstPadMovementController();

        // Close to (60,49), with the next segment turning toward +column.
        Action action = controller.nextAction(state(Kit.REPULSOR, 0, 59.8, 49.5, -90.0F));

        assertEquals(0.0, action.forward(), 0.0);
        assertFalse(action.sprint());
        assertEquals(30.0F, action.yawDelta(), 0.0F);
        assertEquals(0.0, action.strafe(), 0.0);
    }

    @Test
    public void jumperNeverConsumesChargesInMovementOnlyMode() {
        FirstPadMovementController controller = new FirstPadMovementController();

        Action charged = controller.nextAction(state(Kit.JUMPER, 1, 50.5, 49.5, -90.0F));
        assertFalse(charged.jump());
        assertEquals(1.0, charged.forward(), 0.0);
        assertTrue(charged.sprint());
        assertEquals(0.0, charged.strafe(), 0.0);

        controller.reset();
        Action empty = controller.nextAction(state(Kit.JUMPER, 0, 50.5, 49.5, -90.0F));
        assertFalse(empty.jump());
        assertEquals(1.0, empty.forward(), 0.0);
        assertTrue(empty.sprint());
        assertEquals(0.0, empty.strafe(), 0.0);
    }

    @Test
    public void rejectsAirStartInsteadOfDrivingOffEdge() {
        FirstPadMovementController controller = new FirstPadMovementController();
        GameState state = state(Kit.REPULSOR, 0, 49.5, 49.5, -90.0F);

        Action action = controller.nextAction(state);

        assertEquals(Action.IDLE, action);
        assertEquals(0, controller.routeSize());
    }

    @Test
    public void remainsOnPhysicalFloorEvenWhenLogicalWaypointWouldBeDisabled() {
        FirstPadMovementController controller = new FirstPadMovementController();
        GameState state = state(Kit.REPULSOR, 0, 50.5, 49.5, -90.0F);

        state.maze.setDisabled(51, 49, true);

        Action action = controller.nextAction(state);

        assertEquals(1.0, action.forward(), 0.0);
        assertEquals(0.0, action.strafe(), 0.0);
        assertTrue(controller.routeSize() > 1);
    }
}
