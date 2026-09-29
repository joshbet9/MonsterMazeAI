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
    }

    @Test
    void combinesForwardDriveWithYawSteeringForModerateHeadingError() {
        GameState s = state(0.5, 0.5, -20.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        Action action = controller.nextAction(s, new Cell(0, 8), false);

        assertTrue(action.forward() > 0.0,
                "moderate heading error should not force an unnecessary stop");
        assertTrue(Math.abs(action.yawDelta()) > 0.0,
                "cursor/yaw steering should be applied in the same tick as forward movement");
        assertTrue(Math.abs(action.yawDelta()) <= 12.0F);
        assertFalse(action.strafe() != 0.0);
    }

    @Test
    void usesInPlaceTurnForLargeHeadingError() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        Action action = controller.nextAction(s, new Cell(8, 0), false);

        assertEquals(0.0, action.forward(), 1.0e-6,
                "a 90-degree corner acquisition must not cut across the corridor");
        assertEquals(0.0, action.strafe(), 1.0e-6);
        assertEquals(-12.0F, action.yawDelta(), 1.0e-6F);
    }

    @Test
    void commitsSafePadEdgeCrossingInsteadOfTreatingPadSurfaceAsOrdinaryMazeFloor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        raw[0][1] = 1;
        // The live observer exposes the source SafePad's 5x5 replacement as
        // physical floor even where the canonical maze layout was air.
        for (int row = 0; row < 5; row++) {
            for (int col = 2; col <= 4; col++) {
                raw[row][col] = 0;
            }
        }

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = new MazeModel(raw);
        for (int row = 0; row < 5; row++) {
            for (int col = 2; col <= 4; col++) s.maze.setPhysicalFloor(row, col, true);
        }
        s.activePadRow = 0;
        s.activePadColumn = 4;
        s.player.x = 0.5;
        s.player.z = 1.0;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.tick = 100;

        StableLiveMovementController controller = new StableLiveMovementController();
        Action action = controller.nextAction(s, new Cell(0, 4), true);

        assertEquals(1.0, action.forward(), 0.0);
        assertTrue(action.sprint());
        assertTrue(action.jump(), "the committed pad-edge transition must use the permitted jump input");
        assertEquals(0.0, action.strafe(), 0.0);
        assertTrue(controller.lastDecisionDetail().contains("PAD_ENTRY_CROSS"),
                controller.lastDecisionDetail());

        // A changed threat must not replace the committed edge transition with
        // an unrelated route command on the next observation.
        s.tick++;
        Action second = controller.nextAction(s, new Cell(0, 4), true);
        assertTrue(second.forward() > 0.0 || Math.abs(second.yawDelta()) > 0.0);
        assertTrue(controller.lastDecisionDetail().contains("PAD_ENTRY_CROSS"),
                controller.lastDecisionDetail());
    }

    @Test
    void commitsAndExecutesOneBlockGapAtTakeoff() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][10] = 1;
        raw[10][12] = 1;
        MazeModel maze = new MazeModel(raw);

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = maze;
        s.activePadRow = 10;
        s.activePadColumn = 14;
        s.player.x = 10.5;
        s.player.z = 10.15;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.tick = 1;

        StableLiveMovementController controller = new StableLiveMovementController();
        Action approach = controller.nextAction(s, new Cell(10, 14), true);
        assertEquals(1.0, approach.forward(), 0.0);
        assertFalse(approach.jump(), "before the takeoff boundary the controller should approach, not pulse early");

        s.player.z = 10.99;
        s.tick++;
        Action committed = controller.nextAction(s, new Cell(10, 14), true);
        assertTrue(committed.forward() > 0.0);
        assertTrue(committed.jump(), "the committed gap must pulse jump at the takeoff boundary");
        assertTrue(controller.lastDecisionDetail().contains("GAP_"), controller.lastDecisionDetail());

        // During the unsupported span, the controller must keep the committed
        // edge rather than declaring the route invalid because the current
        // containing cell is the missing middle block.
        s.player.z = 11.10;
        s.player.grounded = false;
        s.tick++;
        Action airborne = controller.nextAction(s, new Cell(10, 14), true);
        assertTrue(airborne.forward() > 0.0);
        assertTrue(airborne.jump());
        assertTrue(controller.lastDecisionDetail().contains("GAP_EXECUTE"), controller.lastDecisionDetail());
    }

    @Test
    void bootstrapsImmediatelyThenDoesNotReplanEveryObservation() {
        GameState s = state(0.5, 0.5, -45.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        s.tick = 1;
        Action first = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(first.forward() > 0.0 || Math.abs(first.yawDelta()) > 0.0,
                "the first live observation must produce a non-idle bootstrap control action");
        assertTrue(controller.lastDecisionDetail().contains("BOOTSTRAP_ROUTE"));

        s.tick = 2;
        Action second = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(second.forward() > 0.0 || Math.abs(second.yawDelta()) > 0.0,
                "the live motor must continue controlling while the strategic planner evaluates in the background");
        assertFalse(controller.lastDecisionDetail().contains("ROUTE_REPLAN"),
                "the live control thread must never synchronously execute the expensive route simulation");

        long plansAfterSecondObservation = controller.routePlanCount();
        s.tick = 3;
        Action third = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(third.forward() > 0.0 || Math.abs(third.yawDelta()) > 0.0,
                "a slow strategic plan must not leave the motor idle");
        assertEquals(plansAfterSecondObservation, controller.routePlanCount(),
                "unchanged local world state must not start another strategic simulation");
    }

    @Test
    void reducesTurnPulseNearCardinalHeading() {
        GameState s = state(0.5, 0.5, -87.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        Action action = controller.nextAction(s, new Cell(8, 0), false);

        assertTrue(action.forward() > 0.0);
        assertTrue(Math.abs(action.yawDelta()) < 3.0F,
                "small heading errors must not receive a full 12-degree correction");
        assertEquals(0.0, action.strafe(), 1.0e-6);
    }

    @Test
    void activePadChangeImmediatelyUsesTheNewOrdinaryRoute() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController();

        s.tick = 1;
        Action first = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(first.forward() > 0.0 || Math.abs(first.yawDelta()) > 0.0);

        // Simulate the server activating a new pad while an ordinary floor
        // route remains available. The previous objective must not leave the
        // full-routing motor latched in IDLE.
        s.activePadRow = 8;
        s.activePadColumn = 8;
        s.tick = 2;
        Action afterTransition = controller.nextAction(s, new Cell(8, 8), false);

        assertTrue(afterTransition.forward() > 0.0 || Math.abs(afterTransition.yawDelta()) > 0.0,
                "a reachable new active pad must remain actionable without requiring a gap jump");
        assertFalse(controller.lastDecisionDetail().contains("REACHED"),
                controller.lastDecisionDetail());
    }

    @Test
    void activePadTransitionCanRouteFromAnOldPadWhoseCanonicalCellWasRemoved() {
        GameState s = state(10.5, 10.5, 0.0F);
        s.maze.setPhysicalFloor(10, 10, false);
        s.oldPads.add(new Cell(10, 10));
        s.activePadRow = 20;
        s.activePadColumn = 20;

        StableLiveMovementController controller = new StableLiveMovementController();
        s.tick = 1;
        Action action = controller.nextAction(s, new Cell(20, 20), false);

        assertTrue(action.forward() > 0.0 || Math.abs(action.yawDelta()) > 0.0,
                "the previous SafePad must be usable as a temporary routing bridge after pad activation");
        assertFalse(controller.lastDecisionDetail().contains("No physical route"));
        assertFalse(controller.lastDecisionDetail().contains("INVALID_INPUT"));
    }

}
