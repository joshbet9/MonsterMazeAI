package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic controller/physics regression for the first-pad branch.
 *
 * This is intentionally a movement oracle, not a replacement for Minecraft.
 * The horizontal update mirrors the 1.8.x ground movement shape: input
 * acceleration is applied in the current yaw direction and horizontal motion
 * is then multiplied by the normal ground friction (0.6 * 0.91).
 *
 * The real Minecraft run remains the final hardware-in-the-loop check. This
 * test exists to catch controller regressions without launching Forge.
 */
public class FirstPadMovementPhysicsSimulationTest {
    private static final double GROUND_FRICTION = 0.6 * 0.91;
    private static final double PLAYER_WALK_SPEED = 0.13;
    private static final double SPRINT_ACCEL =
            PLAYER_WALK_SPEED / 2.0 + (PLAYER_WALK_SPEED / 2.0) * 0.30;
    private static final int MAX_TICKS = 1000;

    @Test
    public void reachesFirstPadAcrossInitialHeadingsWithoutLeavingFloor() {
        for (float initialYaw : new float[] {
                -180.0F, -135.0F, -90.0F, -45.0F,
                0.0F, 45.0F, 90.0F, 135.0F
        }) {
            SimulationResult result = simulate(Kit.REPULSOR, initialYaw);

            assertTrue(result.reachedPad,
                    "did not reach pad from yaw " + initialYaw
                            + " after " + result.ticks + " ticks");
            assertFalse(result.leftPhysicalFloor,
                    "left physical floor from yaw " + initialYaw
                            + " at " + result.x + "," + result.z);
            assertTrue(result.maxRouteDeviation < 1.21,
                    "route deviation exceeded controller corridor from yaw "
                            + initialYaw + ": " + result.maxRouteDeviation);
        }
    }

    @Test
    public void jumperMovementModeNeverPressesJumpOrConsumesCharge() {
        for (float initialYaw : new float[] {-180.0F, -90.0F, 0.0F, 90.0F}) {
            SimulationResult result = simulate(Kit.JUMPER, initialYaw);

            assertTrue(result.reachedPad);
            assertFalse(result.leftPhysicalFloor);
            assertEquals(0, result.jumpInputs,
                    "movement-only Jumper must not consume a charged jump");
            assertEquals(2, result.remainingJumpCharges);
        }
    }

    private static SimulationResult simulate(Kit kit, float initialYaw) {
        GameState state = state(kit, initialYaw);
        FirstPadMovementController controller = new FirstPadMovementController();
        SimulationResult result = new SimulationResult();

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            state.tick = tick;
            Action action = controller.nextAction(state);

            if (action == Action.IDLE) {
                result.reachedPad = state.padReached
                        || isInsidePad(state.player.x, state.player.z,
                        state.activePadRow, state.activePadColumn);
                result.ticks = tick;
                result.x = state.player.x;
                result.z = state.player.z;
                result.remainingJumpCharges = state.player.jumpCharges;
                result.lastDecisionDetail = controller.lastDecisionDetail();
                return result;
            }

            if (action.jump()) result.jumpInputs++;
            if (action.jump() && kit == Kit.JUMPER) {
                state.player.jumpCharges--;
            }

            state.player.yaw = wrap(state.player.yaw + action.yawDelta());
            stepHorizontal(state, action);

            double deviation = routeDeviation(state.player.x, state.player.z,
                    controller);
            result.maxRouteDeviation = Math.max(result.maxRouteDeviation, deviation);

            if (!isPhysical(state.maze, state.player.x, state.player.z)) {
                result.leftPhysicalFloor = true;
                result.ticks = tick + 1;
                result.x = state.player.x;
                result.z = state.player.z;
                result.remainingJumpCharges = state.player.jumpCharges;
                result.lastDecisionDetail = controller.lastDecisionDetail();
                return result;
            }

            if (isInsidePad(state.player.x, state.player.z,
                    state.activePadRow, state.activePadColumn)) {
                state.padReached = true;
                result.reachedPad = true;
                result.ticks = tick + 1;
                result.x = state.player.x;
                result.z = state.player.z;
                result.remainingJumpCharges = state.player.jumpCharges;
                result.lastDecisionDetail = controller.lastDecisionDetail();
                return result;
            }
        }

        result.ticks = MAX_TICKS;
        result.x = state.player.x;
        result.z = state.player.z;
        return result;
    }

    private static void stepHorizontal(GameState state, Action action) {
        double inputMagnitude = Math.hypot(action.strafe(), action.forward());
        if (inputMagnitude > 1.0) inputMagnitude = 1.0;

        if (inputMagnitude > 1.0e-4) {
            double acceleration = SPRINT_ACCEL * inputMagnitude;
            double radians = Math.toRadians(state.player.yaw);

            // Minecraft's forward basis:
            // motionX = -sin(yaw), motionZ = cos(yaw).
            state.player.vx += -Math.sin(radians) * acceleration;
            state.player.vz += Math.cos(radians) * acceleration;
        }

        state.player.x += state.player.vx;
        state.player.z += state.player.vz;

        state.player.vx *= GROUND_FRICTION;
        state.player.vz *= GROUND_FRICTION;
    }

    private static double routeDeviation(
            double x, double z, FirstPadMovementController controller) {
        // The controller's detailed telemetry is intentionally the source of
        // truth for route following. A finite value here confirms the current
        // position remains inside its recoverable corridor.
        String detail = controller.lastDecisionDetail();
        int marker = detail.indexOf("deviation=");
        if (marker < 0) return 0.0;
        int start = marker + "deviation=".length();
        int end = detail.indexOf(' ', start);
        String value = end < 0 ? detail.substring(start) : detail.substring(start, end);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static boolean isPhysical(MazeModel maze, double x, double z) {
        int row = (int) Math.floor(x);
        int column = (int) Math.floor(z);
        return row >= 0 && row < MazeModel.SIZE
                && column >= 0 && column < MazeModel.SIZE
                && maze.isPhysicalFloor(row, column);
    }

    private static boolean isInsidePad(double x, double z, int row, int column) {
        return Math.abs(x - (row + 0.5)) <= 2.5
                && Math.abs(z - (column + 0.5)) <= 2.5;
    }

    private static GameState state(Kit kit, float yaw) {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];

        for (int row = 50; row <= 60; row++) raw[row][49] = 1;
        for (int column = 49; column <= 60; column++) raw[60][column] = 1;

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
        state.player.x = 50.5;
        state.player.z = 49.5;
        state.player.y = 0.0;
        state.player.yaw = yaw;
        state.player.grounded = true;
        state.player.jumpCharges = 2;
        return state;
    }

    private static float wrap(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    private static final class SimulationResult {
        boolean reachedPad;
        boolean leftPhysicalFloor;
        int ticks;
        int jumpInputs;
        int remainingJumpCharges = 2;
        double x;
        double z;
        double maxRouteDeviation;
        String lastDecisionDetail = "";
    }
}
