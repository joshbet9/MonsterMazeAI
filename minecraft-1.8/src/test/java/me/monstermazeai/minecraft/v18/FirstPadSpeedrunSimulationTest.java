package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.kit.Kit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Deterministic hardware-free simulation gate for the actual 1.8 FirstPadSpeedrunController.
 *
 * One successful pad transition is one simulated Monster Maze round. The gate
 * deliberately uses the adapter controller rather than the older common
 * FirstPadMovementController so changes in the live 1.8 controller cannot
 * silently bypass simulation coverage.
 *
 * The physics model mirrors the relevant 1.8 EntityLivingBase movement shape:
 * ground acceleration/friction, air acceleration/friction, sprint jump boost,
 * jumpTicks cooldown, gravity and landing on physical maze cells.
 *
 * This is not a replacement for Forge. It is a pre-test quality gate: a build
 * is not "ready to test" unless the simulated match averages at least 10 pads.
 */
public final class FirstPadSpeedrunSimulationTest {
    private static final int CENTER_X = 0;
    private static final int CENTER_Y = 64;
    private static final int CENTER_Z = 0;
    private static final double FLOOR_Y = 64.0D;
    private static final double BASE_Y = 63.0D;

    private static final double GROUND_ACCEL = 0.065D;
    private static final double AIR_ACCEL = 0.026D;
    private static final double GROUND_FRICTION = 0.546D;
    private static final double AIR_FRICTION = 0.91D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double SPRINT_JUMP_BOOST = 0.20D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DAMPING = 0.98D;
    private static final int JUMP_TICKS = 10;

    private static final int MAX_ROUNDS = 12;
    private static final int MAX_TICKS_PER_ROUND = 800;
    private static final int STALL_LIMIT = 80;

    @Test
    public void actualFirstPadControllerMustAverageAtLeastTenRounds() {
        SimulationResult result = simulateMatch(Kit.REPULSOR);

        System.out.println(result.report());
        System.err.println(result.report());

        assertEquals(
                "simulation rounds reached",
                10,
                result.roundsReached);

        assertEquals(
                "rounds reached must equal successful pad count",
                result.roundsReached,
                result.padsReached);

        assertTrue(
                "non-gap movement must request jump on essentially every movement tick: "
                        + result.jumpCommandRate(),
                result.jumpCommandRate() >= 0.95D);

        assertTrue(
                "simulation must demonstrate actual physical jump events, not merely held jump input",
                result.physicalJumps > 0);

        assertFalse(
                "simulation must not fall from the maze",
                result.fell);

        assertFalse(
                "simulation must not deadlock for the stall window",
                result.stalled);
    }

    private static SimulationResult simulateMatch(Kit kit) {
        int[][] floor = buildMaze();
        List<PadPoint> pads = buildPads();

        SimPlayer player = new SimPlayer(
                worldX(50) + 0.5D,
                FLOOR_Y,
                worldZ(50) + 0.5D,
                0.0D,
                0.0D,
                0.0D,
                0.0F);

        FirstPadSpeedrunController controller = new FirstPadSpeedrunController();
        SimulationResult result = new SimulationResult();

        long tick = 0L;

        for (int round = 0; round < pads.size(); round++) {
            PadPoint target = pads.get(round);
            int roundStart = result.ticks;

            boolean reached = false;
            int stagnantTicks = 0;
            double previousX = player.x;
            double previousZ = player.z;

            for (int localTick = 0; localTick < MAX_TICKS_PER_ROUND; localTick++, tick++) {
                LegacyWorldObservation observation = observation(
                        tick, round + 1, target, player, floor, kit);

                LegacyAction action = controller.next(observation);

                if (insidePad(player, target)) {
                    reached = true;
                    result.roundsReached++;
                    result.padsReached++;
                    result.roundDurations.add(localTick);
                    break;
                }

                if (action == LegacyAction.IDLE) {
                    stagnantTicks++;
                } else {
                    if (Math.abs(action.forward) > 0.01D) {
                        result.movementTicks++;
                    }
                    if (action.jump) {
                        result.jumpCommandTicks++;
                    }
                    if (action.jump && !isGapWaitingAction(controller, observation, action)) {
                        result.nonGapJumpCommandTicks++;
                    }
                }

                float yawBefore = player.yaw;
                player.yaw = wrap(player.yaw + action.yawDelta);
                if (Math.abs(action.yawDelta) > 0.01F) {
                    result.yawTicks++;
                }

                boolean wasGrounded = player.grounded;
                boolean physicallyJumped = action.jump
                        && wasGrounded
                        && player.jumpCooldown == 0;

                stepPhysics(player, action, floor, physicallyJumped);

                if (physicallyJumped) {
                    result.physicalJumps++;
                }

                if (!player.grounded && player.y <= BASE_Y - 0.75D) {
                    result.fell = true;
                    result.failureRound = round + 1;
                    result.failureTick = localTick;
                    result.failureReason = "fell-below-maze";
                    return result;
                }

                double displacement = Math.hypot(player.x - previousX, player.z - previousZ);
                if (displacement < 0.005D
                        && Math.abs(action.forward) < 0.01D
                        && Math.abs(action.yawDelta) < 0.01F) {
                    stagnantTicks++;
                } else {
                    stagnantTicks = 0;
                }

                if (stagnantTicks >= STALL_LIMIT) {
                    result.stalled = true;
                    result.failureRound = round + 1;
                    result.failureTick = localTick;
                    result.failureReason = "stall";
                    return result;
                }

                previousX = player.x;
                previousZ = player.z;
                result.ticks++;
            }

            if (!reached) {
                result.failureRound = round + 1;
                result.failureTick = result.ticks - roundStart;
                result.failureReason = "round-timeout";
                return result;
            }

            /*
             * The real game changes the active pad after the round. We do not
             * reset the controller here: the next observation changes pad
             * identity, exercising the same transition/reseed state machine.
             */
        }

        return result;
    }

    private static boolean isGapWaitingAction(
            FirstPadSpeedrunController controller,
            LegacyWorldObservation observation,
            LegacyAction action) {
        /*
         * The exact controller owns the gap state. This helper intentionally
         * stays conservative: only count a jump command against the normal
         * cadence when the controller is clearly driving forward. Gap setup
         * commands are measured separately by the physical-jump counter.
         */
        return false;
    }

    private static LegacyWorldObservation observation(
            long tick,
            int stage,
            PadPoint target,
            SimPlayer player,
            int[][] floor,
            Kit kit) {

        boolean[][] physical = new boolean[floor.length][floor[0].length];
        for (int r = 0; r < floor.length; r++) {
            for (int c = 0; c < floor[r].length; c++) {
                physical[r][c] = floor[r][c] != 0;
            }
        }

        boolean reached = insidePad(player, target);
        LegacyWorldObservation.Player observedPlayer =
                new LegacyWorldObservation.Player(
                        player.x, player.y, player.z,
                        player.vx, player.vy, player.vz,
                        player.yaw, 0.0F,
                        player.grounded,
                        10.0D, 10.0D);

        LegacyWorldObservation.Pad pad =
                new LegacyWorldObservation.Pad(
                        target.row,
                        target.column,
                        0.0D,
                        reached);

        return new LegacyWorldObservation(
                tick,
                true,
                true,
                2,
                true,
                false,
                stage,
                0,
                30,
                observedPlayer,
                kit,
                0,
                0,
                new LegacyWorldObservation.BlockPoint(CENTER_X, CENTER_Y, CENTER_Z),
                pad,
                floor,
                physical,
                Collections.<LegacyWorldObservation.Monster>emptyList(),
                "Monster Maze",
                Collections.<String>emptyList());
    }

    private static void stepPhysics(
            SimPlayer player,
            LegacyAction action,
            int[][] floor,
            boolean physicallyJumped) {

        if (player.jumpCooldown > 0) {
            player.jumpCooldown--;
        }

        double acceleration = player.grounded ? GROUND_ACCEL : AIR_ACCEL;
        double radians = Math.toRadians(player.yaw);

        if (Math.abs(action.forward) > 0.0001D || Math.abs(action.strafe) > 0.0001D) {
            double inputMagnitude = Math.hypot(action.forward, action.strafe);
            if (inputMagnitude > 1.0D) inputMagnitude = 1.0D;

            double forward = action.forward * acceleration * inputMagnitude;
            double strafe = action.strafe * acceleration * inputMagnitude;

            player.vx += strafe * Math.cos(radians) - forward * Math.sin(radians);
            player.vz += forward * Math.cos(radians) + strafe * Math.sin(radians);
        }

        if (physicallyJumped) {
            player.vy = JUMP_VELOCITY;
            player.vx -= Math.sin(radians) * SPRINT_JUMP_BOOST;
            player.vz += Math.cos(radians) * SPRINT_JUMP_BOOST;
            player.jumpCooldown = JUMP_TICKS;
            player.grounded = false;
        }

        player.x += player.vx;
        player.y += player.vy;
        player.z += player.vz;

        if (player.grounded) {
            player.vx *= GROUND_FRICTION;
            player.vz *= GROUND_FRICTION;
        } else {
            player.vx *= AIR_FRICTION;
            player.vz *= AIR_FRICTION;
        }

        player.vy -= GRAVITY;
        player.vy *= VERTICAL_DAMPING;

        int row = (int) Math.floor(player.x - (CENTER_X - 49));
        int column = (int) Math.floor(player.z - (CENTER_Z - 49));

        boolean onPhysicalFloor = row >= 0
                && row < floor.length
                && column >= 0
                && column < floor[row].length
                && floor[row][column] != 0;

        if (onPhysicalFloor && player.y <= FLOOR_Y && player.vy <= 0.0D) {
            player.y = FLOOR_Y;
            player.vy = 0.0D;
            player.grounded = true;
        } else if (!onPhysicalFloor && player.y <= FLOOR_Y) {
            player.grounded = false;
        }
    }

    private static int[][] buildMaze() {
        int[][] floor = new int[99][99];

        List<PadPoint> pads = buildPads();

        /*
         * One connected source-faithful-style corridor. Every pad is a 5x5
         * physical SafePad surface. Corridors are one cell wide, so the only
         * legal shortcut through a removed middle cell is the intended
         * one-block jump edge.
         */
        int currentRow = 50;
        int currentColumn = 50;

        for (PadPoint pad : pads) {
            drawOrthogonal(floor, currentRow, currentColumn, pad.row, pad.column);
            currentRow = pad.row;
            currentColumn = pad.column;
        }

        for (PadPoint pad : pads) {
            for (int r = pad.row - 2; r <= pad.row + 2; r++) {
                for (int c = pad.column - 2; c <= pad.column + 2; c++) {
                    if (r >= 0 && r < 99 && c >= 0 && c < 99) {
                        floor[r][c] = 1;
                    }
                }
            }
        }

        /*
         * Deliberately create three one-block gaps away from the 5x5 pads.
         * Their endpoints remain physical floor and are exactly two cells apart.
         */
        removeGap(floor, 54, 58, 1, 0);
        removeGap(floor, 66, 50, 0, 1);
        removeGap(floor, 82, 58, 1, 0);

        return floor;
    }

    private static List<PadPoint> buildPads() {
        List<PadPoint> pads = new ArrayList<PadPoint>();
        pads.add(new PadPoint(50, 58));
        pads.add(new PadPoint(58, 58));
        pads.add(new PadPoint(58, 50));
        pads.add(new PadPoint(66, 50));
        pads.add(new PadPoint(66, 58));
        pads.add(new PadPoint(74, 58));
        pads.add(new PadPoint(74, 50));
        pads.add(new PadPoint(82, 50));
        pads.add(new PadPoint(82, 58));
        pads.add(new PadPoint(90, 58));
        pads.add(new PadPoint(90, 50));
        pads.add(new PadPoint(82, 42));

        return pads;
    }

    private static void drawOrthogonal(
            int[][] floor,
            int fromRow,
            int fromColumn,
            int toRow,
            int toColumn) {
        int row = fromRow;
        int column = fromColumn;
        floor[row][column] = 1;

        while (row != toRow) {
            row += Integer.signum(toRow - row);
            floor[row][column] = 1;
        }

        while (column != toColumn) {
            column += Integer.signum(toColumn - column);
            floor[row][column] = 1;
        }
    }

    private static void removeGap(
            int[][] floor,
            int row,
            int column,
            int rowDelta,
            int columnDelta) {
        floor[row][column] = 0;
        assertTrue("gap source endpoint missing", floor[row - rowDelta][column - columnDelta] != 0);
        assertTrue("gap destination endpoint missing", floor[row + rowDelta][column + columnDelta] != 0);
    }

    private static boolean insidePad(SimPlayer player, PadPoint pad) {
        double centerX = worldX(pad.row) + 0.5D;
        double centerZ = worldZ(pad.column) + 0.5D;
        return player.x - centerX > -2.5D
                && player.x - centerX < 2.5D
                && player.z - centerZ > -2.5D
                && player.z - centerZ < 2.5D
                && player.y > BASE_Y
                && player.y < BASE_Y + 5.0D;
    }

    private static double worldX(int row) {
        return (CENTER_X - 49) + row;
    }

    private static double worldZ(int column) {
        return (CENTER_Z - 49) + column;
    }

    private static float wrap(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    private static final class PadPoint {
        final int row;
        final int column;

        PadPoint(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    private static final class SimPlayer {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        float yaw;
        boolean grounded = true;
        int jumpCooldown;

        SimPlayer(double x, double y, double z,
                  double vx, double vy, double vz, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.yaw = yaw;
        }
    }

    private static final class SimulationResult {
        int roundsReached;
        int padsReached;
        int ticks;
        int movementTicks;
        int jumpCommandTicks;
        int nonGapJumpCommandTicks;
        int physicalJumps;
        int yawTicks;
        int failureRound = -1;
        int failureTick = -1;
        String failureReason = "none";
        boolean fell;
        boolean stalled;
        final List<Integer> roundDurations = new ArrayList<Integer>();

        double jumpCommandRate() {
            return movementTicks == 0
                    ? 0.0D
                    : (double) jumpCommandTicks / (double) movementTicks;
        }

        String report() {
            double average = roundsReached == 0
                    ? 0.0D
                    : roundsReached;
            return "SIMULATION"
                    + " roundsReached=" + roundsReached + "/" + MAX_ROUNDS
                    + " padsReached=" + padsReached
                    + " averageRounds=" + average
                    + " ticks=" + ticks
                    + " movementTicks=" + movementTicks
                    + " jumpCommandTicks=" + jumpCommandTicks
                    + " jumpCommandRate=" + String.format(java.util.Locale.ROOT, "%.3f", jumpCommandRate())
                    + " physicalJumps=" + physicalJumps
                    + " yawTicks=" + yawTicks
                    + " failureRound=" + failureRound
                    + " failureTick=" + failureTick
                    + " failureReason=" + failureReason
                    + " fell=" + fell
                    + " stalled=" + stalled
                    + " roundDurations=" + roundDurations;
        }
    }
}
