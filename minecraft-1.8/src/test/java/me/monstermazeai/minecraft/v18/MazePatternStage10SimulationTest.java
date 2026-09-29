package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.kit.Kit;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.io.FileWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Full deterministic pad-progression simulation for the real 1.8 movement
 * controller.
 *
 * This is deliberately stronger than the first-pad physics regression:
 * - runs every source maze pattern (Maze 1, 2 and 3);
 * - each simulation is a real pad transition, where one pad = one round;
 * - the active pad changes while the player is still physically standing on
 *   the previous pad, exercising the exact transition that previously latched
 *   the controller into IDLE;
 * - exercises the controller's one-block gap edges and continuous jump policy;
 * - requires every individual simulation to reach stage 10.
 *
 * No Minecraft client is launched. The harness uses the same MazeLayouts and
 * the same FirstPadSpeedrunController used by the 1.8 adapter. The physics
 * model is a deterministic 1.8-style sprint/jump model; its purpose is to
 * expose controller/state-machine failures before Forge is tested.
 */
public final class MazePatternStage10SimulationTest {
    private static final int SIZE = 99;
    private static final int HALF = 49;
    private static final int TARGET_STAGE = 10;
    private static final int SEEDS_PER_PATTERN = 4;
    private static final int MAX_TICKS_PER_STAGE = 900;
    private static final int TRANSITION_HOLD_TICKS = 6;

    private static final double PLAYER_HALF_WIDTH = 0.30D;
    private static final double GROUND_ACCEL = 0.13D;
    private static final double AIR_ACCEL = 0.02D;
    private static final double GROUND_DRAG = 0.546D;
    private static final double AIR_DRAG = 0.91D;
    private static final double JUMP_VELOCITY = 0.42D;
    private static final double GRAVITY = 0.08D;
    private static final double VERTICAL_DRAG = 0.98D;
    private static final double SPRINT_JUMP_BOOST = 0.20D;

    @Test(timeout = 180000)
    public void everyMazePatternReachesStageTenOnEverySimulation() {
        StringBuilder report = new StringBuilder();
        int total = 0;

        for (int pattern = 0; pattern < 3; pattern++) {
            int minStage = Integer.MAX_VALUE;
            int maxStage = 0;
            int passed = 0;

            for (int seed = 0; seed < SEEDS_PER_PATTERN; seed++) {
                total++;
                Result result = simulate(pattern, seed);
                minStage = Math.min(minStage, result.stage);
                maxStage = Math.max(maxStage, result.stage);
                if (result.stage >= TARGET_STAGE) {
                    passed++;
                }

                report.append("Maze ").append(pattern + 1)
                        .append(" seed=").append(seed)
                        .append(" stage=").append(result.stage)
                        .append(" pads=").append(result.padsReached)
                        .append(" ticks=").append(result.ticks)
                        .append(" jumpTicks=").append(result.jumpTicks)
                        .append(" movementTicks=").append(result.movementTicks)
                        .append(" gaps=").append(result.gapsSeen)
                        .append(" gapLandings=").append(result.gapLandings)
                        .append(" maxSpeed=").append(format(result.maxSpeed))
                        .append(" failure=").append(result.failure)
                        .append('\n');
            }

            report.append("Maze ").append(pattern + 1)
                    .append(" SUMMARY passed=").append(passed).append("/")
                    .append(SEEDS_PER_PATTERN)
                    .append(" minStage=").append(minStage)
                    .append(" maxStage=").append(maxStage)
                    .append('\n');

            System.err.println("\\n--- Maze " + (pattern + 1) + " diagnostics ---\\n" + report);
            writeReport(report.toString());

            if (passed != SEEDS_PER_PATTERN) {
                throw new RuntimeException("Maze " + (pattern + 1) + " failed simulation gate\n" + report);
            }
            if (minStage < TARGET_STAGE) {
                throw new RuntimeException("Maze " + (pattern + 1) + " minimum stage below 10\n" + report);
            }
        }

        System.out.println("\n========== MAZE PATTERN STAGE-10 SIMULATION ==========\n"
                + report
                + "TOTAL simulations=" + total + "\n"
                + "=======================================================\n");
    }

    private static void writeReport(String report) {
        try {
            File file = new File("build/maze-simulator-report.txt");
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter writer = new FileWriter(file, false);
            writer.write(report);
            writer.close();
        } catch (Exception e) {
            System.err.println("Could not write simulator report: " + e);
        }
    }

    private static Result simulate(int pattern, int seed) {
        int[][] raw = copy(MazeLayouts.ALL_MAZES[pattern]);
        List<Cell> pads = buildPadSequence(raw, seed);

        Result result = new Result();
        result.failure = "MAX_STAGE_NOT_REACHED";

        SimPlayer player = new SimPlayer();
        // Real game starts on the central SafePad. The first route benchmark
        // historically used this same centre-side spawn coordinate.
        player.x = worldX(50);
        player.z = worldZ(49);
        player.y = 0.0D;
        player.grounded = true;
        player.yaw = 0.0F;

        FirstPadSpeedrunController controller = new FirstPadSpeedrunController();

        Cell activePad = pads.get(0);
        Cell oldPad = new Cell(50, 49);
        int stage = 1;
        int padIndex = 0;
        int transitionTicks = 0;
        int ticks = 0;

        while (stage <= TARGET_STAGE && ticks < TARGET_STAGE * MAX_TICKS_PER_STAGE) {
            boolean onActivePad = isOnPad(player, activePad);

            if (onActivePad && transitionTicks == 0) {
                result.padsReached++;
                if (stage >= TARGET_STAGE) {
                    result.stage = stage;
                    result.ticks = ticks;
                    result.failure = "PASS";
                    return result;
                }

                // Force the exact problematic phase transition: the destination
                // becomes active while the player remains on the old pad.
                oldPad = activePad;
                padIndex++;
                activePad = pads.get(padIndex);
                stage++;
                transitionTicks = TRANSITION_HOLD_TICKS;
            }

            boolean[][] physical = physicalFloor(raw, activePad, oldPad, transitionTicks);
            LegacyWorldObservation observation = observation(
                    ticks, stage, pattern + 1, player, activePad, raw, physical);

            LegacyAction action = controller.next(observation);
            if (action == null) action = LegacyAction.IDLE;

            if (action.jump) result.jumpTicks++;
            if (action.forward > 0.01D) result.movementTicks++;

            step(player, action, physical, result);

            if (!player.alive) {
                result.stage = stage;
                result.ticks = ticks + 1;
                result.failure = "FALL stage=" + stage
                        + " pos=" + format(player.x) + "," + format(player.y)
                        + "," + format(player.z)
                        + " action=" + actionText(action);
                return result;
            }

            if (transitionTicks > 0) transitionTicks--;
            ticks++;

            if (ticks % MAX_TICKS_PER_STAGE == 0 && !isOnPad(player, activePad)) {
                result.stage = stage;
                result.ticks = ticks;
                result.failure = "STAGE_TIMEOUT stage=" + stage
                        + " pos=" + format(player.x) + "," + format(player.z)
                        + " action=" + actionText(action);
                return result;
            }
        }

        result.stage = stage;
        result.ticks = ticks;
        return result;
    }

    private static LegacyWorldObservation observation(
            long tick, int stage, int pattern, SimPlayer p, Cell pad,
            int[][] raw, boolean[][] physical) {
        return new LegacyWorldObservation(
                tick,
                true,
                true,
                pattern,
                p.alive,
                false,
                stage,
                30,
                (int) (tick / 20L),
                new LegacyWorldObservation.Player(
                        p.x, p.y, p.z,
                        p.vx, p.vy, p.vz,
                        p.yaw, 0.0F, p.grounded,
                        20.0D, 20.0D),
                Kit.REPULSOR,
                2,
                0,
                new LegacyWorldObservation.BlockPoint(0, 0, 0),
                new LegacyWorldObservation.Pad(
                        pad.row, pad.column,
                        distanceSqToPad(p, pad),
                        isOnPad(p, pad)),
                raw,
                physical,
                Collections.<LegacyWorldObservation.Monster>emptyList(),
                "Monster Maze",
                Collections.<String>emptyList());
    }

    private static void step(
            SimPlayer p, LegacyAction action, boolean[][] physical, Result result) {
        float nextYaw = wrap(p.yaw + action.yawDelta);
        p.yaw = nextYaw;

        double radians = Math.toRadians(p.yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);

        double strafeX = Math.cos(radians);
        double strafeZ = Math.sin(radians);

        double inputForward = action.forward;
        double inputStrafe = action.strafe;
        double inputLength = Math.hypot(inputForward, inputStrafe);
        if (inputLength > 1.0D) {
            inputForward /= inputLength;
            inputStrafe /= inputLength;
        }

        boolean wasGrounded = p.grounded;
        if (wasGrounded && action.jump) {
            p.vy = JUMP_VELOCITY;
            if (action.sprint) {
                p.vx += forwardX * SPRINT_JUMP_BOOST;
                p.vz += forwardZ * SPRINT_JUMP_BOOST;
            }
        }

        double accel = wasGrounded ? GROUND_ACCEL : AIR_ACCEL;
        if (action.sprint) accel *= 1.30D;

        p.vx += (forwardX * inputForward + strafeX * inputStrafe) * accel;
        p.vz += (forwardZ * inputForward + strafeZ * inputStrafe) * accel;

        double drag = wasGrounded ? GROUND_DRAG : AIR_DRAG;
        p.vx *= drag;
        p.vz *= drag;

        double nextX = p.x + p.vx;
        double nextZ = p.z + p.vz;

        p.vy -= GRAVITY;
        p.vy *= VERTICAL_DRAG;
        double nextY = p.y + p.vy;

        int row = floorRow(nextX);
        int col = floorColumn(nextZ);
        boolean supported = inBounds(row, col) && physical[row][col];

        if (supported && nextY <= 0.0D && p.vy <= 0.0D) {
            p.x = nextX;
            p.z = nextZ;
            p.y = 0.0D;
            p.vy = 0.0D;
            p.grounded = true;
        } else {
            p.x = nextX;
            p.z = nextZ;
            p.y = nextY;
            p.grounded = false;
        }

        double speed = Math.hypot(p.vx, p.vz);
        result.maxSpeed = Math.max(result.maxSpeed, speed);

        if (!p.grounded && p.y < -2.0D) {
            p.alive = false;
        }
    }

    private static boolean[][] physicalFloor(
            int[][] raw, Cell activePad, Cell oldPad, int transitionTicks) {
        boolean[][] floor = new boolean[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                floor[r][c] = raw[r][c] != 0;
            }
        }

        // SafePad.captureAndBuild replaces a 5x5 surface with physical floor.
        fillPad(floor, activePad);

        // Old SafePads remain physical while their decay timer is running.
        // During the forced transition window we retain that physical support,
        // exactly matching the source's oldSafePads lifecycle.
        if (transitionTicks > 0) {
            fillPad(floor, oldPad);
        }

        return floor;
    }

    private static void fillPad(boolean[][] floor, Cell pad) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                int r = pad.row + dr;
                int c = pad.column + dc;
                if (inBounds(r, c)) floor[r][c] = true;
            }
        }
    }

    private static List<Cell> buildPadSequence(int[][] raw, int seed) {
        List<Cell> candidates = new ArrayList<Cell>();
        List<Cell> spawns = new ArrayList<Cell>();
        List<Cell> barriers = new ArrayList<Cell>();

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int v = raw[r][c];
                if (v == 2) spawns.add(new Cell(r, c));
                if (v == 4 || v == 6) barriers.add(new Cell(r, c));
            }
        }

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (raw[r][c] == 0 || raw[r][c] == 3 || raw[r][c] == 4
                        || raw[r][c] == 5 || raw[r][c] == 6) continue;

                Cell candidate = new Cell(r, c);
                if (distance(candidate, new Cell(49, 49)) < 12.0D) continue;
                if (nearAny(candidate, spawns, 10.0D)) continue;
                if (nearAny(candidate, barriers, 7.0D)) continue;
                candidates.add(candidate);
            }
        }

        Collections.shuffle(candidates, new Random(0x4D4D0000L + seed * 1009L));

        List<Cell> pads = new ArrayList<Cell>();
        Cell previous = new Cell(50, 49);
        while (pads.size() < TARGET_STAGE && !candidates.isEmpty()) {
            Cell chosen = null;
            double best = -1.0D;

            for (Cell candidate : candidates) {
                double distance = distance(candidate, previous);
                if (distance < 20.0D) continue;
                if (nearAny(candidate, pads, 15.0D)) continue;
                if (distance > best) {
                    best = distance;
                    chosen = candidate;
                }
            }

            if (chosen == null) {
                chosen = candidates.get(0);
            }

            pads.add(chosen);
            previous = chosen;
            candidates.remove(chosen);
        }

        if (pads.size() < TARGET_STAGE) {
            throw new AssertionError("Could not construct 10 source-compatible pads; got "
                    + pads.size());
        }

        return pads;
    }

    private static boolean nearAny(Cell candidate, List<Cell> cells, double radius) {
        return nearAny(candidate, cells, radius, false);
    }

    private static boolean nearAny(Cell candidate, List<Cell> cells,
                                   double radius, boolean ignored) {
        double radiusSq = radius * radius;
        for (Cell cell : cells) {
            if (distanceSq(candidate, cell) < radiusSq) return true;
        }
        return false;
    }

    private static double distance(Cell a, Cell b) {
        return Math.sqrt(distanceSq(a, b));
    }

    private static double distanceSq(Cell a, Cell b) {
        double dr = a.row - b.row;
        double dc = a.column - b.column;
        return dr * dr + dc * dc;
    }

    private static boolean isOnPad(SimPlayer p, Cell pad) {
        return Math.abs(p.x - worldX(pad.row)) < 2.5D
                && Math.abs(p.z - worldZ(pad.column)) < 2.5D
                && p.y > -1.0D
                && p.y < 4.0D;
    }

    private static double distanceSqToPad(SimPlayer p, Cell pad) {
        double dx = p.x - worldX(pad.row);
        double dz = p.z - worldZ(pad.column);
        return dx * dx + dz * dz;
    }

    private static boolean footprintSupported(double x, double z, boolean[][] physical) {
        double minX = x - PLAYER_HALF_WIDTH;
        double maxX = x + PLAYER_HALF_WIDTH;
        double minZ = z - PLAYER_HALF_WIDTH;
        double maxZ = z + PLAYER_HALF_WIDTH;
        int minRow = floorRow(minX);
        int maxRow = floorRow(maxX - 1.0E-9D);
        int minCol = floorColumn(minZ);
        int maxCol = floorColumn(maxZ - 1.0E-9D);
        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                if (!inBounds(r, c) || !physical[r][c]) continue;
                double cellMinX = r - HALF;
                double cellMaxX = cellMinX + 1.0D;
                double cellMinZ = c - HALF;
                double cellMaxZ = cellMinZ + 1.0D;
                double overlapX = Math.min(maxX, cellMaxX) - Math.max(minX, cellMinX);
                double overlapZ = Math.min(maxZ, cellMaxZ) - Math.max(minZ, cellMinZ);
                if (overlapX > 0.0D && overlapZ > 0.0D && overlapX * overlapZ >= 0.05D) return true;
            }
        }
        return false;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private static int floorRow(double x) {
        return (int) Math.floor(x + HALF);
    }

    private static int floorColumn(double z) {
        return (int) Math.floor(z + HALF);
    }

    private static double worldX(int row) {
        return row - HALF + 0.5D;
    }

    private static double worldZ(int column) {
        return column - HALF + 0.5D;
    }

    private static int[][] copy(int[][] source) {
        int[][] out = new int[source.length][];
        for (int i = 0; i < source.length; i++) out[i] = source[i].clone();
        return out;
    }

    private static float wrap(float yaw) {
        while (yaw >= 180.0F) yaw -= 360.0F;
        while (yaw < -180.0F) yaw += 360.0F;
        return yaw;
    }

    private static String actionText(LegacyAction action) {
        return "F=" + format(action.forward)
                + ",J=" + action.jump
                + ",SP=" + action.sprint
                + ",Y=" + format(action.yawDelta);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static final class Cell {
        final int row;
        final int column;

        Cell(int row, int column) {
            this.row = row;
            this.column = column;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Cell)) return false;
            Cell cell = (Cell) other;
            return row == cell.row && column == cell.column;
        }

        @Override
        public int hashCode() {
            return row * 131 + column;
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
        boolean grounded;
        boolean alive = true;
        int jumpCooldown;
    }

    private static final class Result {
        int stage = 1;
        int padsReached;
        int ticks;
        int jumpTicks;
        int movementTicks;
        int gapsSeen;
        int gapLandings;
        double maxSpeed;
        String failure;
    }
}
