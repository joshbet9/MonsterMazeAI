package me.monstermazeai.sim;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.SourcePadSpawner;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.runtime.AutonomousMonsterMazeAgent;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.testdata.SourceMazeLayouts;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authentic closed-loop acceptance gate.
 *
 * The test intentionally exercises the current MonsterMaze source contract:
 * exact 99x99 source layouts, source SafePad selection/avoidance, source
 * starter/subsequent monster spawn pools, source progression/decay, the
 * same common autonomous controller used by the 1.8 adapter, and the
 * one-tick observe/decide/apply cadence used by the live IPC bridge.
 *
 * Gate: every source pattern and every kit must survive at least stage 10 in
 * Modern mode on its deterministic seed. This is a baseline gate, not a proof
 * that one seed or one profile represents every real game.
 */
class AuthenticStage10SimulationTest {
    private static final int REQUIRED_STAGE = 10;
    private static final int MAX_TICKS = 20_000;
    private static final int NATURAL_MAX_TICKS = 60_000;

    @Test
    void allModernSourcePatternsAndKitsReachStageTen() {
        List<String> failures = new ArrayList<>();

        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                RunResult result = run(pattern, kit, AiProfile.HIGH_SKILL, Mode.MODERN);
                if (result.maxStage < REQUIRED_STAGE) {
                    failures.add("mode=MODERN pattern=" + (pattern + 1)
                            + " kit=" + kit
                            + " stage=" + result.maxStage
                            + " tick=" + result.ticks
                            + " health=" + result.health
                            + " pos=(" + result.x + "," + result.z + ")"
                            + " firstFallTick=" + result.firstFallTick
                            + " firstFallPrePos=" + result.firstFallPreX + "," + result.firstFallPreY + "," + result.firstFallPreZ
                            + " firstFallPreV=" + result.firstFallPreVx + "," + result.firstFallPreVy + "," + result.firstFallPreVz
                            + " firstFallPos=" + result.firstFallX + "," + result.firstFallY + "," + result.firstFallZ
                            + " firstFallV=" + result.firstFallVx + "," + result.firstFallVz
                            + " firstFallDecision=" + result.firstFallDecision
                            + " decision=" + result.decision);
                }
            }
        }

        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    @Test
    void allSpeedSourcePatternsAndKitsReachStageTen() {
        List<String> failures = new ArrayList<>();

        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                RunResult result = run(pattern, kit, AiProfile.HIGH_SKILL, Mode.SPEED);
                System.out.printf("SPEED pattern=%d kit=%s stage=%d%n",
                        pattern + 1, kit, result.maxStage);
                if (result.maxStage < REQUIRED_STAGE) {
                    failures.add("mode=SPEED pattern=" + (pattern + 1)
                            + " kit=" + kit
                            + " stage=" + result.maxStage
                            + " tick=" + result.ticks
                            + " health=" + result.health
                            + " pos=(" + result.x + "," + result.z + ")"
                            + " firstFallTick=" + result.firstFallTick
                            + " firstFallDecision=" + result.firstFallDecision
                            + " decision=" + result.decision);
                }
            }
        }

        assertTrue(failures.isEmpty(), String.join(System.lineSeparator(), failures));
    }

    private RunResult run(int pattern, Kit kit) {
        return run(pattern, kit, AiProfile.BASELINE, Mode.MODERN);
    }

    private RunResult run(int pattern, Kit kit, AiProfile profile, Mode mode) {
        return run(pattern, kit, profile, mode, REQUIRED_STAGE, MAX_TICKS);
    }

    /** Natural-end run used by the unrestricted 30-cell matrix. */
    static RunResult run(int pattern, Kit kit, AiProfile profile, Mode mode, int stopStage) {
        return run(pattern, kit, profile, mode, stopStage, stopStage > 0 ? MAX_TICKS : NATURAL_MAX_TICKS);
    }

    private static RunResult run(int pattern, Kit kit, AiProfile profile, Mode mode,
                                 int stopStage, int maxTicks) {
        long seed = 0x4D4D4153494D0000L
                ^ ((long) pattern * 0x9E3779B97F4A7C15L)
                ^ ((long) kit.ordinal() * 0xBF58476D1CE4E5B9L);
        Random monsterRandom = new Random(seed ^ 0x6A09E667F3BCC909L);
        Random padRandom = new Random(seed ^ 0xBB67AE8584CAA73BL);

        int[][] raw = SourceMazeLayouts.maze(pattern);
        MazeModel maze = new MazeModel(raw);

        GameState state = new GameState();
        state.mode = mode;
        state.mazePattern = pattern;
        state.maze = maze;
        state.kit = kit;
        state.inMonsterMaze = true;
        state.alive = true;
        state.completed = false;
        /*
         * Match MonsterMaze GameManager.startGame(): the real player is
         * teleported to center.clone().add(0.5, 1, 0.5).
         * MazeCoordinates maps that source location to logical cell-centre
         * coordinates (49.5, 49.5).
         */
        state.player.x = 49.5;
        state.player.y = GameState.PATH_Y;
        state.player.z = 49.5;
        state.player.yaw = 0.0F;
        state.player.grounded = true;

        AbilityModel abilities = new AbilityModel();
        MonsterSimulator monsterSimulator = new MonsterSimulator(maze, monsterRandom, 1.4, seed ^ 0x6A09E667F3BCC909L);
        Simulator simulator = new Simulator(
                new LegacyMazePhysics(),
                monsterSimulator,
                new CollisionModel(),
                abilities);

        simulator.initialise(state);

        SourcePadSpawner pads = new SourcePadSpawner(maze, padRandom);
        Cell initial = pads.initialPad();
        activatePadSurface(state, initial);

        int[] nextMonsterId = {1};
        /*
         * MonsterManager.start() begins its starter spawn task during the
         * three-second countdown. All 150/225 starter monsters therefore exist
         * before the first LIVE movement tick. Reproduce that pre-LIVE RNG
         * consumption once here rather than spawning monsters during live play.
         */
        int starter = initialMonsterCount(mode);
        while (starter > 0) {
            int batch = Math.min(25, starter);
            int spawned = spawnInitialBatch(state, monsterRandom, nextMonsterId, batch);
            starter -= spawned;
        }

        AutonomousMonsterMazeAgent agent = new AutonomousMonsterMazeAgent(
                new RobustLiveController(
                        new LiveObjectiveController(
                                new MazeAwareRecedingHorizonController(1, profile))));

        int maxStage = 1;
        int lastStage = 1;
        long firstFallTick = -1L;
        double firstFallX = Double.NaN, firstFallY = Double.NaN, firstFallZ = Double.NaN;
        double firstFallVx = Double.NaN, firstFallVz = Double.NaN;
        String firstFallDecision = "NONE";
        double firstFallPreX = Double.NaN, firstFallPreY = Double.NaN, firstFallPreZ = Double.NaN;
        double firstFallPreVx = Double.NaN, firstFallPreVy = Double.NaN, firstFallPreVz = Double.NaN;
        Deque<String> trace = new ArrayDeque<>();
        String previousAction = "NONE";

        /*
         * Forge fires ClientTickEvent.START before world.updateEntities(), so
         * the simulator evaluates the controller at the same pre-physics
         * boundary as the live adapter and consumes that Action immediately.
         */
        boolean diagnosticTrace = pattern == 1
                && ((mode == Mode.SPEED || mode == Mode.MODERN)
                && (kit == Kit.MAVERICK || kit == Kit.BODY_BUILDER));

        for (int tick = 0; tick < maxTicks && state.alive; tick++) {
            double preX = state.player.x, preY = state.player.y, preZ = state.player.z;
            double preVx = state.player.vx, preVy = state.player.vy, preVz = state.player.vz;
            ActionInput action = decide(agent, state);
            String decisionBeforeTick = agent.lastDecisionDetail();
            String currentAction = action.action.toString();

            if (diagnosticTrace || (pattern == 0 && kit == Kit.JUMPER)) {
                trace.addLast("tick=" + state.tick
                        + " stage=" + state.stage
                        + " pos=" + format(state.player.x) + "," + format(state.player.z)
                        + " y=" + format(state.player.y)
                        + " yaw=" + format(state.player.yaw)
                        + " v=" + format(state.player.vx) + "," + format(state.player.vz)
                        + " hp=" + format(state.player.health)
                        + " pad=" + state.activePadRow + "," + state.activePadColumn
                        + " action=" + currentAction.replace(' ', '_'));
                while (trace.size() > (diagnosticTrace ? 120 : 30)) trace.removeFirst();
            }

            simulator.tick(state, action.action);

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
                int spawned = spawnAdditional(state, monsterRandom, nextMonsterId, additionalMonsterCount(mode));
                state.pendingMonsterSpawns -= spawned;
                if (state.pendingMonsterSpawns < 0) state.pendingMonsterSpawns = 0;

                // Source removes monsters from the newly promoted active pad.
                if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
                    removeMonstersOnPad(state,
                            new Cell(state.activePadRow, state.activePadColumn));
                    activatePadSurface(state,
                            new Cell(state.activePadRow, state.activePadColumn));
                }
                lastStage = state.stage;
            }

            maxStage = Math.max(maxStage, state.stage);

            if (firstFallTick < 0L && state.player.y < GameState.PATH_Y - 0.05D) {
                firstFallTick = state.tick;
                firstFallPreX = preX; firstFallPreY = preY; firstFallPreZ = preZ;
                firstFallPreVx = preVx; firstFallPreVy = preVy; firstFallPreVz = preVz;
                firstFallX = state.player.x;
                firstFallY = state.player.y;
                firstFallZ = state.player.z;
                firstFallVx = state.player.vx;
                firstFallVz = state.player.vz;
                firstFallDecision = decisionBeforeTick
                        + " DESIRED_ACTION=" + desiredAction
                        + " APPLIED_ACTION=" + currentAction
                        + " PREVIOUS_APPLIED_ACTION=" + previousAction
                        + " TRACE=" + String.join(" || ", trace);
            }

            previousAction = currentAction;

            if (stopStage > 0 && maxStage >= stopStage) break;
        }

        if (diagnosticTrace) {
            System.out.println("OUTLIER_DIAGNOSTIC mode=" + mode
                    + " pattern=" + (pattern + 1)
                    + " kit=" + kit
                    + " maxStage=" + maxStage
                    + " ticks=" + state.tick
                    + " firstFallTick=" + firstFallTick
                    + " firstFallDecision=" + firstFallDecision
                    + " TRACE=" + String.join(" || ", trace));
        }

        return new RunResult(maxStage, state.tick, state.player.health,
                state.player.x, state.player.z, firstFallTick,
                firstFallPreX, firstFallPreY, firstFallPreZ,
                firstFallPreVx, firstFallPreVy, firstFallPreVz,
                firstFallX, firstFallY, firstFallZ,
                firstFallVx, firstFallVz, firstFallDecision, agent.lastDecisionDetail());
    }

    private static ActionInput decide(AutonomousMonsterMazeAgent agent, GameState state) {
        boolean allowJump = state.kit != Kit.JUMPER || state.ability.charges > 0;
        return new ActionInput(agent.decide(state, allowJump));
    }

    private static List<Cell> currentPadAvoidance(GameState state) {
        ArrayList<Cell> avoid = new ArrayList<>();
        for (Cell old : state.oldPads) avoid.add(old);
        if (state.activePadRow >= 0 && state.activePadColumn >= 0) {
            avoid.add(new Cell(state.activePadRow, state.activePadColumn));
        }
        return avoid;
    }

    private static void activatePadSurface(GameState state, Cell center) {
        if (center == null) return;
        state.activePadRow = center.row();
        state.activePadColumn = center.column();
        syncPadSurfaces(state);
    }

    private static void syncPadSurfaces(GameState state) {
        new me.monstermazeai.game.GameProgressionModel().syncPadSurfaces(state);
    }

    private static int initialMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 225 : 150;
    }

    private static int additionalMonsterCount(Mode mode) {
        return mode == Mode.MODERN || mode == Mode.CLASSIC ? 30 : 15;
    }

    private static int spawnInitialBatch(GameState state, Random random, int[] nextId, int count) {
        List<Cell> paths = pathCells(state.maze);
        Cell center = new Cell(49, 49);
        int spawned = 0;
        int guard = 0;
        while (spawned < count && guard++ < count * 5) {
            Cell pos = paths.get(random.nextInt(paths.size()));
            if (distanceSq(pos, center) < 7.5 * 7.5) continue;
            state.monsters.add(new MonsterState(
                    nextId[0]++, pos.row() + 0.5, GameState.PATH_Y, pos.column() + 0.5));
            spawned++;
        }
        assertTrue(spawned == count,
                "source starter spawn pool could not produce batch of " + count + " mobs");
        return spawned;
    }

    private static int spawnAdditional(GameState state, Random random, int[] nextId, int count) {
        List<Cell> spawns = spawnCells(state.maze);
        if (spawns.isEmpty()) spawns = pathCells(state.maze);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Cell pos = spawns.get(random.nextInt(spawns.size()));
            state.monsters.add(new MonsterState(
                    nextId[0]++, pos.row() + 0.5, GameState.PATH_Y, pos.column() + 0.5));
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

    private static double distanceSq(Cell a, Cell b) {
        double dr = a.row() - b.row();
        double dc = a.column() - b.column();
        return dr * dr + dc * dc;
    }

    private record ActionInput(me.monstermazeai.player.Action action) {}

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    static record RunResult(
            int maxStage,
            long ticks,
            double health,
            double x,
            double z,
            long firstFallTick,
            double firstFallPreX,
            double firstFallPreY,
            double firstFallPreZ,
            double firstFallPreVx,
            double firstFallPreVy,
            double firstFallPreVz,
            double firstFallX,
            double firstFallY,
            double firstFallZ,
            double firstFallVx,
            double firstFallVz,
            String firstFallDecision,
            String decision) {}
}
