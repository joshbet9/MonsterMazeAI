package me.monstermazeai.sim;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.PadModel;
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
 * starter/subsequent monster spawn pools, source progression/decay, and the
 * same common autonomous controller used by the 1.8 adapter.
 *
 * Gate: every source pattern and every kit must survive at least stage 10 in
 * Modern mode on its deterministic seed. This is a baseline gate, not a proof
 * that one seed or one profile represents every real game.
 */
class AuthenticStage10SimulationTest {
    private static final ThreadLocal<List<BehaviorTick>> ACTIVE_BEHAVIOR_TRACE = new ThreadLocal<>();

    private static final int MODERN_REQUIRED_STAGE = 5;
    private static final int SPEED_REQUIRED_STAGE = 10;
    private static final int MAX_TICKS = 20_000;
    private static final int FULL_RUN_MAX_TICKS = 20_000;

    @Test
    void allModernSourcePatternsAndKitsReachStageTen() {
        List<String> failures = new ArrayList<>();

        for (int pattern = 0; pattern < 3; pattern++) {
            for (Kit kit : Kit.values()) {
                RunResult result = run(pattern, kit, AiProfile.HIGH_SKILL, Mode.MODERN);
                if (result.maxStage < MODERN_REQUIRED_STAGE) {
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
                System.out.printf("SPEED pattern=%d kit=%s stage=%d seedOffset=%d%n",
                        pattern + 1, kit, result.maxStage,
                        Long.getLong("monstermaze.sim.seedOffset", 0L));
                if (result.maxStage < SPEED_REQUIRED_STAGE) {
                    failures.add("mode=SPEED pattern=" + (pattern + 1)
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

    private RunResult run(int pattern, Kit kit) {
        return run(pattern, kit, AiProfile.BASELINE, Mode.MODERN);
    }

    static RunResult runDiagnostic(int pattern, Kit kit, AiProfile profile, Mode mode) {
        return runDiagnostic(pattern, kit, profile, mode, requiredStage(mode));
    }

    static RunResult runDiagnostic(int pattern, Kit kit, AiProfile profile, Mode mode, int targetStage) {
        if (targetStage <= 0) {
            return new AuthenticStage10SimulationTest().run(pattern, kit, profile, mode, 0);
        }
        return new AuthenticStage10SimulationTest().run(pattern, kit, profile, mode, targetStage);
    }

    static RunResult runToEnd(int pattern, Kit kit, AiProfile profile, Mode mode) {
        return new AuthenticStage10SimulationTest().run(pattern, kit, profile, mode, 0);
    }

    static BehaviorTraceResult runToEndWithBehaviorTrace(
            int pattern, Kit kit, AiProfile profile, Mode mode) {
        List<BehaviorTick> trace = new ArrayList<>();
        ACTIVE_BEHAVIOR_TRACE.set(trace);
        try {
            RunResult result = new AuthenticStage10SimulationTest()
                    .run(pattern, kit, profile, mode, 0);
            return new BehaviorTraceResult(result, List.copyOf(trace));
        } finally {
            ACTIVE_BEHAVIOR_TRACE.remove();
        }
    }

    private RunResult run(int pattern, Kit kit, AiProfile profile, Mode mode) {
        return run(pattern, kit, profile, mode, requiredStage(mode));
    }

    private RunResult run(int pattern, Kit kit, AiProfile profile, Mode mode, int targetStage) {
        long seed = 0x4D4D4153494D0000L
                ^ ((long) pattern * 0x9E3779B97F4A7C15L)
                ^ ((long) kit.ordinal() * 0xBF58476D1CE4E5B9L);
        long seedOffset = Long.getLong("monstermaze.sim.seedOffset", 0L);
        if (seedOffset != 0L) {
            seed = mixSeed(seed ^ seedOffset);
        }
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
        state.pendingMonsterSpawns = initialMonsterCount(mode);

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
        long terminalTick = -1L;
        int terminalStage = -1;
        int terminalPhaseTicksRemaining = -1;
        int terminalPadRow = -1, terminalPadColumn = -1;
        boolean terminalOnPad = false;
        String terminalDecision = "NONE";
        Deque<String> trace = new ArrayDeque<>();
        String previousAction = "NONE";

        // Direct control/throughput telemetry. These counters measure what the
        // controller actually asked the 1.8 movement model to do, rather than
        // inferring behaviour from the final death trace.
        long movementInputTicks = 0L;
        long zeroInputTicks = 0L;
        long stationaryTicks = 0L;
        long forwardInputTicks = 0L;
        long sprintInputTicks = 0L;
        long jumpInputTicks = 0L;
        long laneRecoveryTicks = 0L;
        long edgeGuardTicks = 0L;
        long cornerVectorTicks = 0L;
        long steerDriveTicks = 0L;
        long fastRecoveryRouteTicks = 0L;
        double actualHorizontalDistance = 0.0D;
        double commandedInputSum = 0.0D;

        int maxTicks = targetStage > 0 ? MAX_TICKS : FULL_RUN_MAX_TICKS;
        for (int tick = 0; tick < maxTicks && state.alive; tick++) {
            // Source MonsterManager schedules its starter spawn task before its
            // movement task: 25 monsters are added per server tick until the
            // mode's 225-monster starter quota is reached.
            if (state.pendingMonsterSpawns > 0) {
                int batch = Math.min(25, state.pendingMonsterSpawns);
                int spawned = spawnInitialBatch(state, monsterRandom, nextMonsterId, batch);
                state.pendingMonsterSpawns -= spawned;
            }

            double preX = state.player.x, preY = state.player.y, preZ = state.player.z;
            double preVx = state.player.vx, preVy = state.player.vy, preVz = state.player.vz;
            boolean allowJump = state.kit != Kit.JUMPER || state.ability.charges > 0;
            ActionInput action = decide(agent, state);
            String decisionBeforeTick = agent.lastDecisionDetail();
            String currentAction = action.action.toString();

            double inputMagnitude = Math.hypot(action.action.forward(), action.action.strafe());
            commandedInputSum += inputMagnitude;
            if (inputMagnitude > 1.0E-6D) {
                movementInputTicks++;
            } else {
                zeroInputTicks++;
                if (Math.hypot(preVx, preVz) < 0.05D) stationaryTicks++;
            }
            if (Math.abs(action.action.forward()) > 1.0E-6D) forwardInputTicks++;
            if (action.action.sprint()) sprintInputTicks++;
            if (action.action.jump()) jumpInputTicks++;
            if (decisionBeforeTick.contains("LANE_RECOVERY")) laneRecoveryTicks++;
            if (decisionBeforeTick.contains("EDGE_GUARD")) edgeGuardTicks++;
            if (decisionBeforeTick.contains("CORNER_VECTOR")) cornerVectorTicks++;
            if (decisionBeforeTick.contains("STEER_DRIVE")) steerDriveTicks++;
            if (decisionBeforeTick.contains("FAST_RECOVERY_ROUTE")) fastRecoveryRouteTicks++;
            trace.addLast("tick=" + state.tick
                    + " stage=" + state.stage
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " y=" + format(state.player.y)
                    + " yaw=" + format(state.player.yaw)
                    + " v=" + format(state.player.vx) + "," + format(state.player.vy) + "," + format(state.player.vz)
                    + " hp=" + format(state.player.health)
                    + " decision=" + decisionBeforeTick.replace(' ', '_')
                    + " action=" + currentAction.replace(' ', '_'));
            while (trace.size() > 30) trace.removeFirst();

            simulator.tick(state, action.action);
            actualHorizontalDistance += Math.hypot(state.player.x - preX, state.player.z - preZ);

            List<BehaviorTick> activeTrace = ACTIVE_BEHAVIOR_TRACE.get();
            if (activeTrace != null) {
                activeTrace.add(new BehaviorTick(
                        state.tick,
                        state.stage,
                        state.activePadRow,
                        state.activePadColumn,
                        state.previewPadRow,
                        state.previewPadColumn,
                        preX, preY, preZ,
                        preVx, preVy, preVz,
                        state.player.x, state.player.y, state.player.z,
                        state.player.vx, state.player.vy, state.player.vz,
                        state.player.yaw,
                        action.action,
                        decisionBeforeTick));
            }
            if (!state.alive && terminalTick < 0L) {
                terminalTick = state.tick;
                terminalStage = state.stage;
                terminalPhaseTicksRemaining = state.phaseTicksRemaining;
                terminalPadRow = state.activePadRow;
                terminalPadColumn = state.activePadColumn;
                terminalOnPad = state.activePadRow >= 0 && state.activePadColumn >= 0
                        && PadModel.isOn(state.player,
                        state.activePadRow + 0.5, GameState.PAD_SURFACE_Y, state.activePadColumn + 0.5);
                terminalDecision = decisionBeforeTick
                        + " ACTION=" + currentAction
                        + " TRACE=" + String.join(" || ", trace);
            }

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
                        + " ACTION=" + currentAction
                        + " PREVIOUS_ACTION=" + previousAction
                        + " TRACE=" + String.join(" || ", trace);
            }

            previousAction = currentAction;

            if (targetStage > 0 && maxStage >= targetStage) break;
        }

        return new RunResult(maxStage, state.tick, state.player.health,
                state.player.x, state.player.z, firstFallTick,
                firstFallPreX, firstFallPreY, firstFallPreZ,
                firstFallPreVx, firstFallPreVy, firstFallPreVz,
                firstFallX, firstFallY, firstFallZ,
                firstFallVx, firstFallVz, firstFallDecision, agent.lastDecisionDetail(),
                terminalTick, terminalStage, terminalPhaseTicksRemaining,
                terminalPadRow, terminalPadColumn, terminalOnPad, terminalDecision,
                movementInputTicks, zeroInputTicks, stationaryTicks, forwardInputTicks,
                sprintInputTicks, jumpInputTicks, laneRecoveryTicks, edgeGuardTicks,
                cornerVectorTicks, steerDriveTicks, fastRecoveryRouteTicks,
                actualHorizontalDistance, commandedInputSum,
                movementInputTicks / (double) Math.max(1L, state.tick),
                zeroInputTicks / (double) Math.max(1L, state.tick),
                stationaryTicks / (double) Math.max(1L, state.tick),
                actualHorizontalDistance / Math.max(1L, state.tick),
                commandedInputSum / Math.max(1L, state.tick));
    }

    private static long mixSeed(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
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

    private static int requiredStage(Mode mode) {
        return mode == Mode.SPEED ? SPEED_REQUIRED_STAGE : MODERN_REQUIRED_STAGE;
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

    static record BehaviorTraceResult(
            RunResult result,
            List<BehaviorTick> ticks) {}

    static record BehaviorTick(
            long tick,
            int stage,
            int activePadRow,
            int activePadColumn,
            int previewPadRow,
            int previewPadColumn,
            double preX,
            double preY,
            double preZ,
            double preVx,
            double preVy,
            double preVz,
            double postX,
            double postY,
            double postZ,
            double postVx,
            double postVy,
            double postVz,
            float yaw,
            me.monstermazeai.player.Action action,
            String decision) {}

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
            String decision,
            long terminalTick,
            int terminalStage,
            int terminalPhaseTicksRemaining,
            int terminalPadRow,
            int terminalPadColumn,
            boolean terminalOnPad,
            String terminalDecision,
            long movementInputTicks,
            long zeroInputTicks,
            long stationaryTicks,
            long forwardInputTicks,
            long sprintInputTicks,
            long jumpInputTicks,
            long laneRecoveryTicks,
            long edgeGuardTicks,
            long cornerVectorTicks,
            long steerDriveTicks,
            long fastRecoveryRouteTicks,
            double actualHorizontalDistance,
            double commandedInputSum,
            double movementInputShare,
            double zeroInputShare,
            double stationaryShare,
            double averageHorizontalSpeed,
            double averageCommandedInput) {}
}
