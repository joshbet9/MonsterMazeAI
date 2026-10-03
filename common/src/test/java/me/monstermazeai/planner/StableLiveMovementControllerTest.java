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
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
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
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        Action first = controller.nextAction(s, new Cell(8, 0), false);

        assertEquals(0.0, first.forward(), 1.0e-6);
        assertEquals(0.0, first.strafe(), 1.0e-6);
        assertEquals(-30.0F, first.yawDelta(), 1.0e-6F,
                "the initial 90-degree heading error must turn in place rather than safety-stop");
        assertFalse(controller.lastDecisionDetail().contains("SAFETY_STOP"));
    }

    @Test
    void sourceSafePadLaneOffsetIsPreservedAfterHeadingAligns() {
        GameState s = state(0.0, 0.0, -90.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

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
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        LegacyMazePhysics physics = new LegacyMazePhysics();

        boolean sawForward = false;
        int strafeTicks = 0;
        for (int tick = 1; tick <= 180; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(8, 0), false);
            sawForward |= action.forward() > 0.0;
            if (Math.abs(action.strafe()) > 0.0) strafeTicks++;
            physics.tick(s.player, action);
            if (Math.hypot(s.player.x - 8.5, s.player.z - 0.5) < 0.55) break;
        }

        assertTrue(sawForward);
        assertTrue(strafeTicks <= 8,
                "sideways approach should use strafe only as a bounded corner-turn aid, not oscillate");
    }

    @Test
    void combinesForwardDriveWithYawSteeringForModerateHeadingError() {
        GameState s = state(0.5, 0.5, -20.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        Action action = controller.nextAction(s, new Cell(0, 8), false);

        assertTrue(action.forward() > 0.0,
                "moderate heading error should not force an unnecessary stop");
        assertTrue(Math.abs(action.yawDelta()) > 0.0,
                "cursor/yaw steering should be applied in the same tick as forward movement");
        assertTrue(Math.abs(action.yawDelta()) <= 30.0F);
        assertFalse(action.strafe() != 0.0);
    }

    @Test
    void usesInPlaceTurnForLargeHeadingError() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        Action action = controller.nextAction(s, new Cell(8, 0), false);

        assertEquals(0.0, action.forward(), 1.0e-6,
                "a 90-degree corner acquisition must not cut across the corridor");
        assertEquals(0.0, action.strafe(), 1.0e-6);
        assertEquals(-30.0F, action.yawDelta(), 1.0e-6F);
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

        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
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
        raw[10][9] = 1;
        raw[10][10] = 1;
        raw[10][12] = 1;
        for (int column = 13; column <= 30; column++) raw[10][column] = 1;
        MazeModel maze = new MazeModel(raw);

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = maze;
        // Regression uses a non-Jumper because this is specifically the
        // source "speeding" gap-crossing technique: Jump -10 blocks vertical
        // lift but the sprint-jump routine still supplies horizontal impulse.
        s.kit = me.monstermazeai.kit.Kit.MAVERICK;
        s.activePadRow = 10;
        s.activePadColumn = 30;
        s.player.x = 10.5;
        s.player.z = 9.0;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.tick = 1;

        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        Action approach = controller.nextAction(s, new Cell(10, 30), true);
        assertEquals(1.0, approach.forward(), 0.0);
        // Normal live movement may already be jump-spamming for a Jumper;
        // the important invariant is that the committed edge still emits a
        // jump input at the takeoff boundary.

        s.player.z = 10.99;
        s.tick++;
        Action committed = controller.nextAction(s, new Cell(10, 30), true);
        assertTrue(committed.forward() > 0.0);
        assertTrue(committed.jump(), "the committed gap must pulse jump at the takeoff boundary");
        assertTrue(controller.lastDecisionDetail().contains("GAP_"), controller.lastDecisionDetail());

        // The committed edge is now owned by the gap motor; the live
        // controller must issue the edge-timed jump before the source block
        // boundary rather than relying on ordinary jump-spam cadence.
    }

    @Test
    void bootstrapsImmediatelyThenDoesNotReplanEveryObservation() {
        GameState s = state(0.5, 0.5, -45.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

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
    void advancesPastOvershotTurnInsteadOfReversingTowardStaleWaypoint() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        raw[1][0] = 1;
        raw[2][0] = 1;
        raw[2][1] = 1;
        raw[2][2] = 1;

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = new MazeModel(raw);
        s.activePadRow = 2;
        s.activePadColumn = 2;
        s.player.x = 0.5;
        s.player.z = 0.5;
        s.player.yaw = -90.0F;
        s.player.grounded = true;
        s.tick = 1;

        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        controller.nextAction(s, new Cell(2, 2), false);

        // Simulate vanilla momentum carrying the player past the first turn
        // before the next observation is processed.
        s.player.x = 2.8;
        s.player.z = 0.5;
        s.player.vx = 0.12;
        s.player.vz = 0.0;
        s.player.yaw = -90.0F;
        s.tick = 2;

        Action action = controller.nextAction(s, new Cell(2, 2), false);

        assertTrue(controller.lastDecisionDetail().contains("dir=0,1"),
                controller.lastDecisionDetail());
        assertTrue(action.forward() >= 0.0,
                "the controller must not reverse into the already-passed waypoint");
    }

    @Test
    void reducesTurnPulseNearCardinalHeading() {
        GameState s = state(0.5, 0.5, -87.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        Action action = controller.nextAction(s, new Cell(8, 0), false);

        assertTrue(action.forward() > 0.0);
        assertTrue(Math.abs(action.yawDelta()) < 3.0F,
                "small heading errors must not receive a full 12-degree correction");
        assertEquals(0.0, action.strafe(), 1.0e-6);
    }

    @Test
    void mobHitClearsStaleRouteAndWaitsForGroundBeforeResuming() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        s.tick = 1;
        Action first = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(first.forward() > 0.0 || Math.abs(first.yawDelta()) > 0.0);

        /*
         * Monster Maze normal bump damage is four health. The live controller
         * must use that authoritative observation to invalidate the pre-hit
         * route even if the sidecar did not observe the exact velocity packet.
         */
        s.tick = 2;
        s.player.health -= 4.0;
        s.player.grounded = false;
        s.player.y = 1.0;
        s.player.vx = 0.35;
        s.player.vy = -0.20;
        s.player.vz = 0.20;

        Action airborne = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(airborne.forward() > 0.0 || Math.abs(airborne.yawDelta()) > 0.0,
                "airborne recovery must steer back toward a predicted physical landing surface");
        assertTrue(controller.lastDecisionDetail().contains("MOB_HIT"),
                controller.lastDecisionDetail());

        s.tick = 3;
        Action stillAirborne = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(stillAirborne.forward() > 0.0 || Math.abs(stillAirborne.yawDelta()) > 0.0);

        s.tick = 42;
        s.player.grounded = true;
        s.player.y = 0.0;
        s.player.vx = 0.0;
        s.player.vy = 0.0;
        s.player.vz = 0.0;

        Action recovered = controller.nextAction(s, new Cell(0, 8), false);
        assertTrue(recovered.forward() > 0.0 || Math.abs(recovered.yawDelta()) > 0.0,
                "once grounded, the controller must rebuild from the post-hit position");
        assertFalse(controller.lastDecisionDetail().contains("MOB_HIT_AIRBORNE_RECOVERY"));
    }

    @Test
    void facesNewPadBeforeDrivingOffPreviouslyReachedPad() {
        GameState s = state(0.5, 8.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        s.tick = 1;
        Action reached = controller.nextAction(s, new Cell(0, 8), false);
        assertEquals(Action.IDLE, reached);

        // The next SafePad has spawned while the player is still standing on
        // the old pad. The first route segment is +X, so the controller should
        // turn in place rather than immediately drive with the old heading.
        s.activePadRow = 8;
        s.activePadColumn = 8;
        s.tick = 2;

        Action turn = controller.nextAction(s, new Cell(8, 8), false);
        assertEquals(0.0, turn.forward(), 1.0e-6);
        assertEquals(0.0, turn.strafe(), 1.0e-6);
        assertEquals(-30.0F, turn.yawDelta(), 1.0e-6F);
        assertTrue(controller.lastDecisionDetail().contains("PAD_TRANSITION_FACE"),
                controller.lastDecisionDetail());

        // Finish the deliberate turn. Once aligned, normal route driving is
        // released immediately rather than adding an artificial pause.
        s.player.yaw = -90.0F;
        s.tick = 3;
        Action drive = controller.nextAction(s, new Cell(8, 8), false);
        assertTrue(drive.forward() > 0.0,
                "aligned pad transition must immediately release into forward movement");
        assertEquals(0.0, drive.strafe(), 1.0e-6);
        assertFalse(controller.lastDecisionDetail().contains("PAD_TRANSITION_FACE"),
                controller.lastDecisionDetail());
    }

    @Test
    void activePadChangeImmediatelyUsesTheNewOrdinaryRoute() {
        GameState s = state(0.5, 0.5, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

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
    void activePadTransitionBridgesWholeOldPadEvenWhenCurrentCellStillLooksLikeFloor() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        // Only the current cell from the old pad remains marked as canonical
        // floor. The source SafePad surface itself is no longer part of the
        // canonical maze after the new pad activates.
        raw[10][10] = 1;
        // Physical maze route begins at the far edge of the old 5x5 pad and
        // continues to the newly activated pad.
        for (int row = 12; row <= 20; row++) raw[row][10] = 1;
        for (int column = 10; column <= 20; column++) raw[20][column] = 1;

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = new MazeModel(raw);
        s.activePadRow = 10;
        s.activePadColumn = 10;
        s.player.x = 10.5;
        s.player.z = 10.5;
        s.player.yaw = 0.0F;
        s.player.grounded = true;

        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        s.tick = 1;
        // Establish the previous objective while the player is on that pad.
        assertEquals(Action.IDLE, controller.nextAction(s, new Cell(10, 10), false));

        s.activePadRow = 20;
        s.activePadColumn = 20;
        s.tick = 2;

        Action action = controller.nextAction(s, new Cell(20, 20), false);

        assertTrue(action.forward() > 0.0 || Math.abs(action.yawDelta()) > 0.0,
                "a newly active pad must remain actionable even when only the old pad's current cell is canonical floor");
        assertFalse(controller.lastDecisionDetail().contains("No physical route"),
                controller.lastDecisionDetail());
        assertFalse(controller.lastDecisionDetail().contains("REACHED"),
                controller.lastDecisionDetail());
    }

    @Test
    void physicsDrivenRightTurnBrakesBeforeCornerAndReachesGoal() {
        CornerResult result = simulateCorner(
                2, 1, 2, 8, 8, 8,
                0.0F, 0.0D);

        assertTrue(result.reachedGoal,
                "right turn did not reach goal: " + result);
        assertFalse(result.leftPhysicalFloor,
                "right turn left physical floor: " + result);
        assertTrue(result.cornerPrepTicks > 0,
                "right turn never entered predictive corner staging: " + result);
        assertTrue(result.maxYawDelta <= 30.0F + 1.0E-6,
                "controller exceeded the 1.8 yaw limit: " + result);
    }

    @Test
    void physicsDrivenLeftTurnBrakesBeforeCornerAndReachesGoal() {
        CornerResult result = simulateCorner(
                8, 1, 8, 8, 2, 8,
                0.0F, 0.0D);

        assertTrue(result.reachedGoal,
                "left turn did not reach goal: " + result);
        assertFalse(result.leftPhysicalFloor,
                "left turn left physical floor: " + result);
        assertTrue(result.cornerPrepTicks > 0,
                "left turn never entered predictive corner staging: " + result);
        assertTrue(result.maxYawDelta <= 30.0F + 1.0E-6,
                "controller exceeded the 1.8 yaw limit: " + result);
    }


    @Test
    void advancesWhenPhysicsHasCrossedCornerByOnlyTwoCentimetres() {
        GameState s = cornerState(2, 1, 2, 8, 8, 8, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);

        s.tick = 1;
        controller.nextAction(s, new Cell(8, 8), false);

        // The waypoint centre is z=8.5. A real physics step can put the player
        // only a few centimetres beyond it; this must still count as crossing.
        s.player.z = 8.502D;
        s.player.vz = 0.01D;
        s.player.vx = 0.0D;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.tick = 2;

        controller.nextAction(s, new Cell(8, 8), false);

        assertTrue(controller.lastDecisionDetail().contains("dir=1,0"),
                controller.lastDecisionDetail());
    }

    @Test
    void highMomentumOvershootAtCornerTransitionsForwardInsteadOfBackingIntoOldSegment() {
        GameState s = cornerState(2, 1, 2, 8, 8, 8, 0.0F);
        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        LegacyMazePhysics physics = new LegacyMazePhysics();

        s.tick = 1;
        controller.nextAction(s, new Cell(8, 8), false);

        // Reproduce the actual failure mode: the player reaches the corner with
        // residual vanilla momentum before the next observation is processed.
        s.player.z = 8.62;
        s.player.vz = 0.25;
        s.player.vx = 0.0;
        s.player.yaw = 0.0F;
        s.player.grounded = true;
        s.player.y = 0.0;
        s.tick = 2;

        boolean sawOldDirectionInput = false;
        for (int tick = 0; tick < 80; tick++) {
            Action action = controller.nextAction(s, new Cell(8, 8), false);
            if (action.forward() < -1.0E-6 || action.strafe() < -1.0E-6) {
                sawOldDirectionInput = true;
            }
            physics.tick(s.player, action, s.maze, 0);

            if (s.player.y < -0.25 || !s.player.grounded && s.player.y < -0.75) break;
            s.tick++;
        }

        assertFalse(sawOldDirectionInput,
                "corner recovery emitted reverse input: " + controller.lastDecisionDetail());
        assertTrue(s.player.x > 2.0,
                "post-corner movement did not acquire the next +X segment: "
                        + s.player.x + "," + s.player.z);
        assertTrue(s.player.y >= -0.25,
                "overshoot recovery fell from the maze: "
                        + s.player.x + "," + s.player.z + " y=" + s.player.y);
    }

    private static CornerResult simulateCorner(
            int startRow, int startColumn,
            int cornerRow, int cornerColumn,
            int goalRow, int goalColumn,
            float initialYaw,
            double initialSpeed) {
        GameState s = cornerState(
                startRow, startColumn, cornerRow, cornerColumn,
                goalRow, goalColumn, initialYaw);
        s.player.vx = startRow == cornerRow ? 0.0D : initialSpeed * Integer.signum(cornerRow - startRow);
        s.player.vz = startColumn == cornerColumn ? 0.0D : initialSpeed * Integer.signum(cornerColumn - startColumn);

        StableLiveMovementController controller = new StableLiveMovementController(AiProfile.BASELINE, false);
        LegacyMazePhysics physics = new LegacyMazePhysics();
        CornerResult result = new CornerResult();

        for (int tick = 1; tick <= 360; tick++) {
            s.tick = tick;
            Action action = controller.nextAction(s, new Cell(goalRow, goalColumn), false);

            result.maxYawDelta = Math.max(result.maxYawDelta, Math.abs(action.yawDelta()));
            if (controller.lastDecisionDetail().contains("CORNER_PREP")
                    || controller.lastDecisionDetail().contains("CORNER_STAGE")) {
                result.cornerPrepTicks++;
            }

            physics.tick(s.player, action, s.maze, 0);

            if (!s.player.grounded && s.player.y < -0.25D) {
                result.leftPhysicalFloor = true;
                break;
            }

            if (Math.hypot(
                    s.player.x - (goalRow + 0.5D),
                    s.player.z - (goalColumn + 0.5D)) <= 0.55D) {
                result.reachedGoal = true;
                return result;
            }
        }

        return result;
    }

    private static GameState cornerState(
            int startRow, int startColumn,
            int cornerRow, int cornerColumn,
            int goalRow, int goalColumn,
            float yaw) {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];

        int rowStep = Integer.signum(cornerRow - startRow);
        int columnStep = Integer.signum(cornerColumn - startColumn);
        int row = startRow;
        int column = startColumn;
        raw[row][column] = 1;

        while (row != cornerRow) {
            row += rowStep;
            raw[row][column] = 1;
        }
        while (column != goalColumn) {
            column += columnStep;
            raw[row][column] = 1;
        }

        int goalRowStep = Integer.signum(goalRow - cornerRow);
        row = cornerRow;
        while (row != goalRow) {
            row += goalRowStep;
            raw[row][column] = 1;
        }

        GameState s = new GameState();
        s.inMonsterMaze = true;
        s.alive = true;
        s.maze = new MazeModel(raw);
        s.activePadRow = goalRow;
        s.activePadColumn = goalColumn;
        s.player.x = startRow + 0.5D;
        s.player.z = startColumn + 0.5D;
        s.player.y = 0.0D;
        s.player.yaw = yaw;
        s.player.grounded = true;
        return s;
    }

    private static final class CornerResult {
        boolean reachedGoal;
        boolean leftPhysicalFloor;
        int cornerPrepTicks;
        double maxYawDelta;

        @Override
        public String toString() {
            return "CornerResult{"
                    + "reachedGoal=" + reachedGoal
                    + ", leftPhysicalFloor=" + leftPhysicalFloor
                    + ", cornerPrepTicks=" + cornerPrepTicks
                    + ", maxYawDelta=" + maxYawDelta
                    + '}';
        }
    }

}
