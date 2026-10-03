package me.monstermazeai.sim;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.SourcePadSpawner;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.testdata.SourceMazeLayouts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "human.policy.model", matches = ".+")
class HumanPolicyV2ClosedLoopTest {
    private static final int FEATURE_COUNT = 96;
    private static final int MAX_TICKS = 20_000;
    private static final int REQUIRED_MATRIX_CELLS = 30;
    private static final int MAGIC = 0x48505632;
    private static final int VERSION = 1;

    @Test
    void runFullSourceMatrixWithHumanPolicy() throws Exception {
        String modelPath = System.getProperty("human.policy.model");
        HumanModel model = HumanModel.load(Path.of(modelPath));

        List<String> lines = new ArrayList<>();
        lines.add("mode\tpattern\tkit\tmaxStage\tticks\thealth\tcompleted\tfirstFallTick");
        int cells = 0;

        for (Mode mode : new Mode[]{Mode.SPEED, Mode.MODERN}) {
            for (int pattern = 0; pattern < 3; pattern++) {
                for (Kit kit : Kit.values()) {
                    RunResult result = run(pattern, kit, mode, model);
                    cells++;
                    String line = String.format(
                            Locale.ROOT,
                            "%s\t%d\t%s\t%d\t%d\t%.2f\t%s\t%d",
                            mode,
                            pattern + 1,
                            kit,
                            result.maxStage,
                            result.ticks,
                            result.health,
                            result.completed,
                            result.firstFallTick);
                    System.out.println("HUMAN_POLICY_V2_RESULT " + line);
                    lines.add(line);
                }
            }
        }

        assertTrue(cells == REQUIRED_MATRIX_CELLS);
        Path output = Path.of(System.getProperty(
                "human.policy.output",
                "ml-data/local/human-policy-v2-closed-loop-matrix.tsv"));
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, lines);

        System.out.println("========== HUMAN POLICY V2 CLOSED-LOOP COMPLETE ==========");
        System.out.println("cells=" + cells);
        System.out.println("output=" + output);
    }

    private RunResult run(int pattern, Kit kit, Mode mode, HumanModel model) {
        long seed = 0x4D4D4153494D0000L
                ^ ((long) pattern * 0x9E3779B97F4A7C15L)
                ^ ((long) kit.ordinal() * 0xBF58476D1CE4E5B9L);

        Random monsterRandom = new Random(seed ^ 0x6A09E667F3BCC909L);
        Random padRandom = new Random(seed ^ 0xBB67AE8584CAA73BL);

        MazeModel maze = new MazeModel(SourceMazeLayouts.maze(pattern));

        GameState state = new GameState();
        state.mode = mode;
        state.mazePattern = pattern;
        state.maze = maze;
        state.kit = kit;
        state.inMonsterMaze = true;
        state.alive = true;
        state.completed = false;
        state.player.x = 49.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 49.5;
        state.player.yaw = 0.0F;
        state.player.grounded = true;

        AbilityModel abilities = new AbilityModel();
        MonsterSimulator monsterSimulator = new MonsterSimulator(
                maze,
                monsterRandom,
                1.4,
                seed ^ 0x6A09E667F3BCC909L);
        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                monsterSimulator,
                new CollisionModel(),
                abilities);
        simulator.initialise(state);

        SourcePadSpawner pads = new SourcePadSpawner(maze, padRandom);
        activatePadSurface(state, pads.initialPad());

        int[] nextMonsterId = {1};
        state.pendingMonsterSpawns = initialMonsterCount(mode);
        int maxStage = 1;
        long firstFallTick = -1L;
        int lastStage = 1;

        for (int tick = 0; tick < MAX_TICKS && state.alive; tick++) {
            if (state.pendingMonsterSpawns > 0) {
                int batch = Math.min(25, state.pendingMonsterSpawns);
                int spawned = spawnInitialBatch(state, monsterRandom, nextMonsterId, batch);
                state.pendingMonsterSpawns -= spawned;
            }

            float[] features = buildObservation(state);
            Action action = model.predict(features);
            simulator.tick(state, action);

            if (state.previewPadRequested && state.previewPadRow < 0) {
                List<Cell> avoid = currentPadAvoidance(state);
                Cell preview = pads.nextPad(avoid);
                state.previewPadRow = preview.row();
                state.previewPadColumn = preview.column();
                syncPadSurfaces(state);
                removeMonstersOnPad(state, preview);
                state.previewPadRequested = false;
            }

            if (state.stage != lastStage) {
                int spawned = spawnAdditional(
                        state,
                        monsterRandom,
                        nextMonsterId,
                        additionalMonsterCount(mode));
                state.pendingMonsterSpawns -= spawned;
                if (state.pendingMonsterSpawns < 0) state.pendingMonsterSpawns = 0;

                if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
                    removeMonstersOnPad(
                            state,
                            new Cell(state.activePadRow, state.activePadColumn));
                    activatePadSurface(
                            state,
                            new Cell(state.activePadRow, state.activePadColumn));
                }
                lastStage = state.stage;
            }

            maxStage = Math.max(maxStage, state.stage);
            if (firstFallTick < 0L && state.player.y < GameState.PATH_Y - 0.05D) {
                firstFallTick = state.tick;
            }
        }

        return new RunResult(
                maxStage,
                state.tick,
                state.player.health,
                state.completed,
                firstFallTick);
    }

    private static float[] buildObservation(GameState state) {
        float[] f = new float[FEATURE_COUNT];
        double px = state.player.x;
        double py = state.player.y;
        double pz = state.player.z;
        double vx = state.player.vx;
        double vy = state.player.vy;
        double vz = state.player.vz;
        double yaw = state.player.yaw;

        f[0] = clamp((px - 49.0) / 64.0, -1.0, 1.0);
        f[1] = clamp((pz - 49.0) / 64.0, -1.0, 1.0);
        f[2] = clamp(py / 8.0, -1.0, 1.0);
        f[3] = clamp(vx / 1.0, -1.0, 1.0);
        f[4] = clamp(vz / 1.0, -1.0, 1.0);
        f[5] = clamp(vy / 1.0, -1.0, 1.0);
        f[6] = clamp(Math.hypot(vx, vz), 0.0, 1.0);

        double yawRad = Math.toRadians(yaw);
        f[7] = (float) Math.sin(yawRad);
        f[8] = (float) Math.cos(yawRad);

        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            double tx = state.activePadRow + 0.5;
            double tz = state.activePadColumn + 0.5;
            double dx = tx - px;
            double dz = tz - pz;
            double distance = Math.hypot(dx, dz);
            double targetAngle = Math.toDegrees(Math.atan2(-dx, dz));
            double bearing = wrapDegrees(targetAngle - yaw);

            f[9] = clamp(dx / 64.0, -1.0, 1.0);
            f[10] = clamp(dz / 64.0, -1.0, 1.0);
            f[11] = clamp(distance / 64.0, 0.0, 1.0);
            f[12] = (float) Math.sin(Math.toRadians(bearing));
            f[13] = (float) Math.cos(Math.toRadians(bearing));
            f[14] = clamp(bearing / 180.0, -1.0, 1.0);
        }

        TimerState timer = timerState(state);
        f[15] = clamp(state.stage / 100.0, 0.0, 1.0);
        f[16] = clamp(Math.max(0, state.phaseTicksRemaining / 20) / 60.0, 0.0, 1.0);
        f[17] = clamp(
                Math.max(0, state.phaseTicksRemaining / 20) / (double) Math.max(1, timer.phaseStartSeconds),
                0.0,
                1.0);
        f[18] = clamp(
                state.player.health / Math.max(20.0, state.player.maxHealth),
                0.0,
                1.0);
        f[19] = clamp(Math.max(20.0, state.player.maxHealth) / 30.0, 0.0, 1.0);
        f[20] = state.player.grounded ? 1.0F : 0.0F;
        f[21] = state.padReached ? 1.0F : 0.0F;
        f[22] = state.padReached ? 1.0F : 0.0F;
        f[23] = kitOrdinal(state.kit) / 4.0F;
        f[24] = clamp(state.player.jumpCharges / 5.0, 0.0, 1.0);
        f[25] = state.ability.charges > 0 ? 1.0F : 0.0F;
        f[26] = state.mode == Mode.SPEED ? 1.0F : 0.0F;
        f[27] = state.mode == Mode.MODERN ? 1.0F : 0.0F;
        int datasetPattern = state.mazePattern + 1;
        f[28] = datasetPattern == 1 ? 1.0F : 0.0F;
        f[29] = datasetPattern == 2 ? 1.0F : 0.0F;
        f[30] = datasetPattern == 3 ? 1.0F : 0.0F;

        double phaseElapsedRatio =
                (timer.phaseStartSeconds - Math.max(0, state.phaseTicksRemaining / 20.0))
                / Math.max(1.0, timer.phaseStartSeconds);
        f[31] = clamp((state.stage - 1.0 + phaseElapsedRatio) / 100.0, 0.0, 1.0);

        Cell cell = containingCell(state);
        int[][] topology = null;
        for (int i = 0; i < 9; i++) {
            int dr = new int[]{0, 0, 1, -1, 1, 1, -1, -1, 0}[i];
            int dc = new int[]{-1, 1, 0, 0, -1, 1, -1, 1, 0}[i];
            int r = cell.row() + dr;
            int c = cell.column() + dc;
            f[32 + i] = state.maze.isPhysicalFloor(r, c) ? 1.0F : 0.0F;
        }

        f[41] = clamp(1.0 / 8.0, 0.0, 1.0);
        f[42] = clamp(1.0 / 8.0, 0.0, 1.0);

        List<MonsterState> active = new ArrayList<>();
        for (MonsterState monster : state.monsters) {
            if (!monster.removed) active.add(monster);
        }
        active.sort(Comparator.comparingDouble(m -> distanceSq(m, state)));
        int within8 = 0;
        int slot = 0;
        for (MonsterState monster : active) {
            double dx = monster.x - px;
            double dy = monster.y - py;
            double dz = monster.z - pz;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (Math.hypot(dx, dz) <= 8.0) within8++;
            if (slot < 8) {
                int base = 44 + slot * 4;
                f[base] = clamp(dx / 32.0, -1.0, 1.0);
                f[base + 1] = clamp(dz / 32.0, -1.0, 1.0);
                f[base + 2] = clamp(dy / 4.0, -1.0, 1.0);
                f[base + 3] = clamp(distance / 32.0, 0.0, 1.0);
                slot++;
            }
        }
        f[43] = clamp(within8 / 8.0, 0.0, 1.0);

        return f;
    }

    private static TimerState timerState(GameState state) {
        int ticks = new me.monstermazeai.game.TimerModel().initialTicks(state.mode, state.stage);
        return new TimerState(Math.max(0, ticks / 20));
    }

    private static Cell containingCell(GameState state) {
        return new Cell(
                clampCell((int) Math.floor(state.player.x)),
                clampCell((int) Math.floor(state.player.z)));
    }

    private static int clampCell(int value) {
        return Math.max(0, Math.min(MazeModel.SIZE - 1, value));
    }

    private static int kitOrdinal(Kit kit) {
        switch (kit) {
            case JUMPER: return 0;
            case SLOWBALLER: return 1;
            case BODY_BUILDER: return 2;
            case REPULSOR: return 3;
            default: return 4;
        }
    }

    private static double distanceSq(Cell a, Cell b) {
        double dr = a.row() - b.row();
        double dc = a.column() - b.column();
        return dr * dr + dc * dc;
    }

    private static double distanceSq(MonsterState monster, GameState state) {
        double dx = monster.x - state.player.x;
        double dz = monster.z - state.player.z;
        double dy = monster.y - state.player.y;
        return dx * dx + dz * dz + dy * dy;
    }

    private static int additionalMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 30 : 15;
    }

    private static int spawnInitialBatch(
            GameState state, Random random, int[] nextId, int count) {
        List<Cell> paths = pathCells(state.maze);
        Cell center = new Cell(49, 49);
        int spawned = 0;
        int guard = 0;
        while (spawned < count && guard++ < count * 5) {
            Cell pos = paths.get(random.nextInt(paths.size()));
            if (distanceSq(pos, center) < 7.5 * 7.5) continue;
            state.monsters.add(new MonsterState(
                    nextId[0]++,
                    pos.row() + 0.5,
                    GameState.PATH_Y,
                    pos.column() + 0.5));
            spawned++;
        }
        return spawned;
    }

    private static int spawnAdditional(
            GameState state, Random random, int[] nextId, int count) {
        List<Cell> spawns = spawnCells(state.maze);
        if (spawns.isEmpty()) spawns = pathCells(state.maze);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Cell pos = spawns.get(random.nextInt(spawns.size()));
            state.monsters.add(new MonsterState(
                    nextId[0]++,
                    pos.row() + 0.5,
                    GameState.PATH_Y,
                    pos.column() + 0.5));
            spawned++;
        }
        return spawned;
    }

    private static void removeMonstersOnPad(GameState state, Cell pad) {
        for (MonsterState monster : state.monsters) {
            if (monster.removed) continue;
            double dx = monster.x - (pad.row() + 0.5);
            double dz = monster.z - (pad.column() + 0.5);
            double dy = monster.y - GameState.PAD_SURFACE_Y;
            if (dx > -2.5 && dx < 2.5
                    && dz > -2.5 && dz < 2.5
                    && dy > 0.0 && dy < 5.0) {
                monster.removed = true;
            }
        }
    }

    private static void activatePadSurface(GameState state, Cell center) {
        if (center == null) return;
        state.activePadRow = center.row();
        state.activePadColumn = center.column();
        syncPadSurfaces(state);
    }

    private static void syncPadSurfaces(GameState state) {
        new GameProgressionModel().syncPadSurfaces(state);
    }

    private static List<Cell> currentPadAvoidance(GameState state) {
        ArrayList<Cell> avoid = new ArrayList<>();
        avoid.addAll(state.oldPads);
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            avoid.add(new Cell(state.activePadRow, state.activePadColumn));
        }
        return avoid;
    }

    private static List<Cell> pathCells(MazeModel maze) {
        ArrayList<Cell> out = new ArrayList<>();
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                if (maze.isRawPath(r, c)) out.add(new Cell(r, c));
            }
        }
        return out;
    }

    private static List<Cell> spawnCells(MazeModel maze) {
        ArrayList<Cell> out = new ArrayList<>();
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                if (maze.raw(r, c) == 2) out.add(new Cell(r, c));
            }
        }
        return out;
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped > 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static float clamp(double value, double min, double max) {
        return (float) Math.max(min, Math.min(max, value));
    }

    private static int initialMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 225 : 150;
    }

    private record TimerState(int phaseStartSeconds) {}
    private record RunResult(
            int maxStage,
            long ticks,
            double health,
            boolean completed,
            long firstFallTick) {}

    private static final class HumanModel {
        private final double[] mean;
        private final double[] std;
        private final double[][] w1;
        private final double[] b1;
        private final double[][] w2;
        private final double[] b2;
        private final double[][] w3;
        private final double[] b3;

        private HumanModel(
                double[] mean, double[] std,
                double[][] w1, double[] b1,
                double[][] w2, double[] b2,
                double[][] w3, double[] b3) {
            this.mean = mean; this.std = std;
            this.w1 = w1; this.b1 = b1;
            this.w2 = w2; this.b2 = b2;
            this.w3 = w3; this.b3 = b3;
        }

        static HumanModel load(Path path) throws IOException {
            try (InputStream in = Files.newInputStream(path);
                 DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
                if (data.readInt() != MAGIC) throw new IOException("Invalid human policy model magic");
                if (data.readInt() != VERSION) throw new IOException("Unsupported human policy model version");
                int inputs = data.readInt();
                int hidden1 = data.readInt();
                int hidden2 = data.readInt();
                int outputs = data.readInt();
                if (inputs != 96 || hidden1 != 64 || hidden2 != 64 || outputs != 6) {
                    throw new IOException("Expected [96,64,64,6] model, got ["
                            + inputs + "," + hidden1 + "," + hidden2 + "," + outputs + "]");
                }
                double[] mean = readVector(data, 96);
                double[] std = readVector(data, 96);
                double[][] w1 = readMatrix(data, 96, 64);
                double[] b1 = readVector(data, 64);
                double[][] w2 = readMatrix(data, 64, 64);
                double[] b2 = readVector(data, 64);
                double[][] w3 = readMatrix(data, 64, 6);
                double[] b3 = readVector(data, 6);
                return new HumanModel(mean, std, w1, b1, w2, b2, w3, b3);
            }
        }

        Action predict(float[] features) {
            double[] x = new double[96];
            for (int i = 0; i < 96; i++) x[i] = (features[i] - mean[i]) / std[i];

            double[] a1 = new double[64];
            for (int h = 0; h < 64; h++) {
                double sum = b1[h];
                for (int i = 0; i < 96; i++) sum += w1[i][h] * x[i];
                a1[h] = Math.max(0.0, sum);
            }

            double[] a2 = new double[64];
            for (int h = 0; h < 64; h++) {
                double sum = b2[h];
                for (int i = 0; i < 64; i++) sum += w2[i][h] * a1[i];
                a2[h] = Math.max(0.0, sum);
            }

            double[] out = new double[6];
            for (int o = 0; o < 6; o++) {
                double sum = b3[o];
                for (int h = 0; h < 64; h++) sum += w3[h][o] * a2[h];
                out[o] = Math.tanh(sum);
            }

            return new Action(
                    out[0],
                    out[1],
                    out[2] >= 0.5,
                    out[3] >= 0.5,
                    (float) (out[4] * 30.0),
                    out[5] >= 0.5);
        }

        private static double[] readVector(DataInputStream data, int count) throws IOException {
            double[] values = new double[count];
            for (int i = 0; i < count; i++) values[i] = data.readDouble();
            return values;
        }

        private static double[][] readMatrix(
                DataInputStream data, int rows, int cols) throws IOException {
            double[][] values = new double[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) values[r][c] = data.readDouble();
            }
            return values;
        }
    }
}
