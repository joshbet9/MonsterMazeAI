package me.monstermazeai.physics;

import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;
import me.monstermazeai.testdata.SourceMazeLayouts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replays recorded human actions from their observed tick state through the
 * actual LegacyMazePhysics implementation and measures the next-tick residual.
 *
 * The input is taken from the next recorded tick because HumanRunRecorder
 * captures keyboard state at ClientTick END, after Minecraft has already used
 * the state for that tick. yawDelta is likewise taken from the next movement
 * record because it is the observed change into that next tick.
 *
 * This is a calibration gate, not a performance benchmark.
 */
class HumanTracePhysicsCalibrationTest {
    private static final String HUMAN_RUNS = "human-runs";

    private static final RunSpec[] RUNS = {
            new RunSpec("20260930-174746-835", Kit.MAVERICK, 3),
            new RunSpec("20260930-181006-247", Kit.REPULSOR, 2),
            new RunSpec("20260930-195111-185", Kit.JUMPER, 1),
            new RunSpec("20260930-195904-165", Kit.SLOWBALLER, 3),
            new RunSpec("20260930-195916-359", Kit.SLOWBALLER, 1),
            new RunSpec("20260930-200313-358", Kit.BODY_BUILDER, 3)
    };

    private static final Pattern FIELD_LONG = Pattern.compile(
            "\"%s\":(-?\\d+)");
    private static final Pattern FIELD_DOUBLE = Pattern.compile(
            "\"%s\":(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");
    private static final Pattern FIELD_BOOLEAN = Pattern.compile(
            "\"%s\":(true|false)");
    private static final Pattern ACTIVE_PAD = Pattern.compile(
            "\"activePad\":\\{\"row\":(-?\\d+),\"column\":(-?\\d+)");
    private static final Pattern CENTER = Pattern.compile(
            "\"center\":\\{\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+)");

    @Test
    void humanTraceResidualsStayWithinCalibratedMovementTolerance() throws IOException {
        Path humanRuns = findHumanRunsRoot();
        Aggregate aggregate = new Aggregate();

        for (RunSpec spec : RUNS) {
            Path base = humanRuns.resolve("human-speed-run-20260930-" + spec.id);
            TreeMap<Long, Motion> motion = loadMotion(base.resolve("-movement.jsonl"));
            TreeMap<Long, Input> input = loadInput(base.resolve("-input.jsonl"));
            TreeMap<Long, World> world = loadWorld(base.resolve("-world.jsonl"));
            Set<Long> knockbackTicks = loadKnockbackTicks(base.resolve("-events.jsonl"));

            assertTrue(motion.size() > 100, spec.id + " movement trace unexpectedly small");
            assertTrue(input.size() > 100, spec.id + " input trace unexpectedly small");

            MazeModel maze = new MazeModel(SourceMazeLayouts.maze(spec.pattern - 1));
            Pad currentPad = null;
            Pad previousPad = null;
            int jumpTicks = 0;

            List<Long> ticks = new ArrayList<>(motion.keySet());
            for (int i = 0; i + 1 < ticks.size(); i++) {
                long tick = ticks.get(i);
                long nextTick = ticks.get(i + 1);
                Motion current = motion.get(tick);
                Motion next = motion.get(nextTick);
                Input actionInput = input.get(nextTick);
                World currentWorld = world.get(tick);

                if (current == null || next == null || actionInput == null || currentWorld == null) {
                    continue;
                }

                if (nextTick != tick + 1L) {
                    jumpTicks = 0;
                    continue;
                }

                Pad pad = currentWorld.pad;
                if (pad != null && (currentPad == null || !currentPad.equals(pad))) {
                    if (previousPad != null) {
                        setPadSurface(maze, previousPad, false);
                    }
                    setPadSurface(maze, pad, true);
                    previousPad = pad;
                    currentPad = pad;
                }

                PlayerState player = toLocalPlayer(current, currentWorld.center);
                player.jumpTicks = jumpTicks;

                Action action = new Action(
                        actionInput.forward,
                        actionInput.strafe,
                        actionInput.jump,
                        actionInput.sprint,
                        next.yawDelta,
                        false);

                boolean hasDamage = Math.abs(current.healthDelta) > 1.0e-9
                        || Math.abs(next.healthDelta) > 1.0e-9;
                boolean recentKnockback = nearby(knockbackTicks, tick, 6L);
                boolean collisionFlag = current.collision || current.horizontalCollision
                        || current.verticalCollision || next.collision
                        || next.horizontalCollision || next.verticalCollision;

                LegacyMazePhysics physics = new LegacyMazePhysics();
                physics.tick(player, action, maze, spec.kit == Kit.JUMPER ? 0 : -10);
                jumpTicks = player.jumpTicks;

                Residual residual = residual(player, next, currentWorld.center);
                aggregate.total.add(residual);

                if (!hasDamage && !recentKnockback) {
                    if (current.grounded && next.grounded && !action.jump()) {
                        aggregate.ground.add(residual);
                        aggregate.forKit(spec.kit).ground.add(residual);
                    }
                    if (spec.kit != Kit.JUMPER && current.grounded && next.grounded && action.jump()) {
                        if (input.get(tick) != null && input.get(tick).jump) {
                            aggregate.nonJumperJumpHold.add(residual);
                        } else {
                            aggregate.nonJumperJumpPress.add(residual);
                        }
                    }
                    if (spec.kit == Kit.JUMPER && current.grounded && !next.grounded && action.jump()) {
                        aggregate.jumperTakeoff.add(residual);
                    }
                }
                if (collisionFlag && !hasDamage && !recentKnockback) {
                    aggregate.collision.add(residual);
                }
            }
        }

        assertCalibrated("ground movement", aggregate.ground, 0.02D, 0.02D, 0.02D, 1000);
        assertCalibrated("non-Jumper jump press", aggregate.nonJumperJumpPress,
                0.02D, 0.02D, 0.02D, 100);
        assertCalibrated("non-Jumper jump hold", aggregate.nonJumperJumpHold,
                0.02D, 0.02D, 0.02D, 100);
        assertCalibrated("Jumper takeoff", aggregate.jumperTakeoff,
                0.02D, 0.02D, 0.02D, 20);

        System.out.println("HUMAN_TRACE_PHYSICS_CALIBRATION");
        aggregate.print("ground", aggregate.ground);
        aggregate.print("nonJumperJumpPress", aggregate.nonJumperJumpPress);
        aggregate.print("nonJumperJumpHold", aggregate.nonJumperJumpHold);
        aggregate.print("jumperTakeoff", aggregate.jumperTakeoff);
        aggregate.print("collisionFlagged", aggregate.collision);
        aggregate.print("all", aggregate.total);
    }

    private static void assertCalibrated(
            String label,
            List<Residual> values,
            double positionP95Limit,
            double velocityP95Limit,
            double verticalP95Limit,
            int minimumSamples) {
        assertTrue(values.size() >= minimumSamples,
                label + " calibration sample count too small: " + values.size());
        double positionP95 = percentile(values, 0, 0.95);
        double velocityP95 = percentile(values, 1, 0.95);
        double verticalP95 = percentile(values, 2, 0.95);
        assertTrue(positionP95 <= positionP95Limit,
                label + " position P95=" + positionP95);
        assertTrue(velocityP95 <= velocityP95Limit,
                label + " velocity P95=" + velocityP95);
        assertTrue(verticalP95 <= verticalP95Limit,
                label + " vertical-position P95=" + verticalP95);
    }

    private static double percentile(List<Residual> values, int field, double fraction) {
        List<Double> sorted = new ArrayList<>();
        for (Residual value : values) {
            sorted.add(field == 0 ? value.position : field == 1 ? value.velocity : value.verticalPosition);
        }
        sorted.sort(Double::compare);
        if (sorted.isEmpty()) return Double.NaN;
        int index = (int) Math.ceil(sorted.size() * fraction) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static Residual residual(PlayerState actual, Motion expected, Center center) {
        double expectedX = expected.x;
        double expectedY = expected.y;
        double expectedZ = expected.z;
        if (center != null) {
            expectedX = expected.x - (center.x - 49.0D);
            expectedY = expected.y - center.y;
            expectedZ = expected.z - (center.z - 49.0D);
        }

        double position = Math.hypot(actual.x - expectedX, actual.z - expectedZ);
        double velocity = Math.hypot(actual.vx - expected.vx, actual.vz - expected.vz);
        double verticalPosition = Math.abs(actual.y - expectedY);
        double verticalVelocity = Math.abs(actual.vy - expected.vy);
        double yaw = Math.abs(wrapDegrees(actual.yaw - expected.yaw));
        return new Residual(position, velocity, verticalPosition, verticalVelocity, yaw);
    }

    private static PlayerState toLocalPlayer(Motion motion, Center center) {
        PlayerState p = new PlayerState();
        p.x = motion.x;
        p.y = motion.y;
        p.z = motion.z;
        p.vx = motion.vx;
        p.vy = motion.vy;
        p.vz = motion.vz;
        p.yaw = motion.yaw;
        p.grounded = motion.grounded;
        if (center != null) {
            p.x -= center.x - 49.0D;
            p.y -= center.y;
            p.z -= center.z - 49.0D;
        }
        return p;
    }

    private static void setPadSurface(MazeModel maze, Pad pad, boolean value) {
        for (int row = pad.row - 2; row <= pad.row + 2; row++) {
            for (int col = pad.column - 2; col <= pad.column + 2; col++) {
                maze.setPadSurface(row, col, value);
            }
        }
    }

    private static Set<Long> loadKnockbackTicks(Path path) throws IOException {
        Set<Long> ticks = new HashSet<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.contains("\"KNOCKBACK_CANDIDATE\"")) continue;
            Long tick = longValue(line, "tick");
            if (tick != null) ticks.add(tick);
        }
        return ticks;
    }

    private static TreeMap<Long, Motion> loadMotion(Path path) throws IOException {
        TreeMap<Long, Motion> out = new TreeMap<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.startsWith("{\"tick\"")) continue;
            long tick = requiredLong(line, "tick");
            out.put(tick, new Motion(
                    tick,
                    requiredDouble(line, "x"),
                    requiredDouble(line, "y"),
                    requiredDouble(line, "z"),
                    requiredDouble(line, "vx"),
                    requiredDouble(line, "vy"),
                    requiredDouble(line, "vz"),
                    requiredDouble(line, "yaw"),
                    requiredDouble(line, "yawDelta"),
                    requiredBoolean(line, "grounded"),
                    doubleValue(line, "healthDelta", 0.0D),
                    requiredBoolean(line, "horizontalCollision"),
                    requiredBoolean(line, "verticalCollision"),
                    requiredBoolean(line, "collision")));
        }
        return out;
    }

    private static TreeMap<Long, Input> loadInput(Path path) throws IOException {
        TreeMap<Long, Input> out = new TreeMap<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.startsWith("{\"tick\"")) continue;
            long tick = requiredLong(line, "tick");
            out.put(tick, new Input(
                    tick,
                    requiredDouble(line, "forward"),
                    requiredDouble(line, "strafe"),
                    requiredBoolean(line, "jump"),
                    requiredBoolean(line, "sprintKey")));
        }
        return out;
    }

    private static TreeMap<Long, World> loadWorld(Path path) throws IOException {
        TreeMap<Long, World> out = new TreeMap<>();
        for (String line : Files.readAllLines(path)) {
            if (!line.startsWith("{\"tick\"")) continue;
            long tick = requiredLong(line, "tick");
            Matcher centerMatcher = CENTER.matcher(line);
            Center center = centerMatcher.find()
                    ? new Center(
                    Integer.parseInt(centerMatcher.group(1)),
                    Integer.parseInt(centerMatcher.group(2)),
                    Integer.parseInt(centerMatcher.group(3)))
                    : null;
            Matcher padMatcher = ACTIVE_PAD.matcher(line);
            Pad pad = padMatcher.find()
                    ? new Pad(Integer.parseInt(padMatcher.group(1)),
                    Integer.parseInt(padMatcher.group(2)))
                    : null;
            out.put(tick, new World(tick, center, pad));
        }
        return out;
    }

    private static Path findHumanRunsRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path configured = System.getProperty(HUMAN_RUNS) == null
                ? null : Path.of(System.getProperty(HUMAN_RUNS)).toAbsolutePath().normalize();
        if (configured != null && Files.isDirectory(configured)) return configured;
        Path[] candidates = {
                cwd.resolve(HUMAN_RUNS),
                cwd.resolve("../human-runs").normalize(),
                cwd.resolve("../../human-runs").normalize()
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot locate human-runs from " + cwd);
    }

    private static boolean nearby(Set<Long> values, long tick, long radius) {
        for (long delta = -radius; delta <= radius; delta++) {
            if (values.contains(tick + delta)) return true;
        }
        return false;
    }

    private static double wrapDegrees(double value) {
        double out = value;
        while (out >= 180.0D) out -= 360.0D;
        while (out < -180.0D) out += 360.0D;
        return Math.abs(out);
    }

    private static Long longValue(String line, String field) {
        Matcher matcher = Pattern.compile(String.format(FIELD_LONG.pattern(), field)).matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static long requiredLong(String line, String field) {
        Long value = longValue(line, field);
        if (value == null) throw new IllegalStateException("Missing " + field + " in " + line);
        return value;
    }

    private static double doubleValue(String line, String field, double fallback) {
        Matcher matcher = Pattern.compile(String.format(FIELD_DOUBLE.pattern(), field)).matcher(line);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    private static double requiredDouble(String line, String field) {
        Double value = null;
        Matcher matcher = Pattern.compile(String.format(FIELD_DOUBLE.pattern(), field)).matcher(line);
        if (matcher.find()) value = Double.parseDouble(matcher.group(1));
        if (value == null) throw new IllegalStateException("Missing " + field + " in " + line);
        return value;
    }

    private static boolean requiredBoolean(String line, String field) {
        Matcher matcher = Pattern.compile(String.format(FIELD_BOOLEAN.pattern(), field)).matcher(line);
        if (!matcher.find()) throw new IllegalStateException("Missing " + field + " in " + line);
        return Boolean.parseBoolean(matcher.group(1));
    }

    private record RunSpec(String id, Kit kit, int pattern) {}
    private record Center(int x, int y, int z) {}
    private record Pad(int row, int column) {}
    private record World(long tick, Center center, Pad pad) {}
    private record Input(long tick, double forward, double strafe, boolean jump, boolean sprint) {}
    private record Motion(
            long tick,
            double x, double y, double z,
            double vx, double vy, double vz,
            double yaw, double yawDelta,
            boolean grounded,
            double healthDelta,
            boolean horizontalCollision,
            boolean verticalCollision,
            boolean collision) {}
    private record Residual(double position, double velocity, double verticalPosition,
                            double verticalVelocity, double yaw) {}

    private static final class KitStats {
        private final List<Residual> ground = new ArrayList<>();
    }

    private static final class Aggregate {
        private final List<Residual> total = new ArrayList<>();
        private final List<Residual> ground = new ArrayList<>();
        private final List<Residual> nonJumperJumpPress = new ArrayList<>();
        private final List<Residual> nonJumperJumpHold = new ArrayList<>();
        private final List<Residual> jumperTakeoff = new ArrayList<>();
        private final List<Residual> collision = new ArrayList<>();
        private final Map<Kit, KitStats> byKit = new HashMap<>();

        private KitStats forKit(Kit kit) {
            return byKit.computeIfAbsent(kit, ignored -> new KitStats());
        }

        private void print(String label, List<Residual> values) {
            if (values.isEmpty()) {
                System.out.println(label + " n=0");
                return;
            }
            System.out.printf(
                    "%s n=%d posP50=%.6f posP95=%.6f velP50=%.6f velP95=%.6f yP95=%.6f vyP95=%.6f yawP95=%.6f%n",
                    label,
                    values.size(),
                    percentile(values, 0, 0.50),
                    percentile(values, 0, 0.95),
                    percentile(values, 1, 0.50),
                    percentile(values, 1, 0.95),
                    percentile(values, 2, 0.95),
                    percentile(values, 3, 0.95),
                    percentile(values, 4, 0.95));
        }
    }
}
