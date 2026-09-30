package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.ability.AbilityDecision;
import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.TimerModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMovementModel;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.io.File;
import java.io.FileWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
 * - exercises the controller's one-block gap edges and legacy -10 speeding policy;
 * - requires every individual simulation to reach stage 10.
 *
 * No Minecraft client is launched. The harness uses the same MazeLayouts and
 * FirstPadSpeedrunController as the 1.8 adapter, and delegates movement to
 * common.LegacyMovementModel. The model is calibrated against the tick-level
 * v18 Minecraft observer traces collected during real runs; those traces are
 * empirical regression evidence for displacement, velocity, yaw and jump timing.
 * External packet descriptions are not used as a substitute for those traces.
 */
public final class MazePatternStage10SimulationTest {
    private static final int SIZE = 99;
    private static final int HALF = 49;
    private static final int TARGET_STAGE = 10;
    private static final int ENDURANCE_STAGE = 100;
    private static final int SEEDS_PER_PATTERN = 4;
    private static final Kit[] TEST_KITS = Kit.values();
    private static final int MAX_TICKS_PER_STAGE = 1200;
    // MonsterMaze SafePad starts with decayCount=11 and decays once per
    // second after the active-pad transition. 20 client ticks/second.
    private static final int OLD_PAD_LIFETIME_TICKS = 11 * 20;

    // Centered-world AABB footprint used by the real 1.8.9 player.
    private static final double PLAYER_HALF_WIDTH = 0.30D;
    // Movement is mirrored from the common 1.8.9 model used by the AI runtime.

    @Test(timeout = 180000)
    public void stageTenAllPatternsJumper() { runKitGate(Kit.JUMPER); }

    @Test(timeout = 180000)
    public void stageTenAllPatternsSlowballer() { runKitGate(Kit.SLOWBALLER); }

    @Test(timeout = 180000)
    public void stageTenAllPatternsBodyBuilder() { runKitGate(Kit.BODY_BUILDER); }

    @Test(timeout = 180000)
    public void stageTenAllPatternsRepulsor() { runKitGate(Kit.REPULSOR); }

    @Test(timeout = 180000)
    public void stageTenAllPatternsMaverick() { runKitGate(Kit.MAVERICK); }

    @Test(timeout = 300000)
    public void enduranceAllKitsAllPatterns() { runEnduranceMatrix(); }

    private static void runEnduranceMatrix() {
        StringBuilder report = new StringBuilder();
        int failures = 0;
        for (Kit kit : TEST_KITS) {
            for (int pattern = 0; pattern < 3; pattern++) {
                int minStage = Integer.MAX_VALUE;
                int maxStage = 0;
                for (int seed = 0; seed < SEEDS_PER_PATTERN; seed++) {
                    Result result = simulate(pattern, seed, kit, ENDURANCE_STAGE);
                    minStage = Math.min(minStage, result.stage);
                    maxStage = Math.max(maxStage, result.stage);
                    String line = "ENDURANCE Maze " + (pattern + 1)
                            + " kit=" + kit + " seed=" + seed
                            + " stage=" + result.stage + " pads=" + result.padsReached
                            + " ticks=" + result.ticks + " failure=" + result.failure;
                    System.err.println(line);
                    report.append(line).append('\n');
                    if (result.stage < ENDURANCE_STAGE) failures++;
                }
                report.append("ENDURANCE Maze ").append(pattern + 1)
                        .append(" kit=").append(kit)
                        .append(" MIN=").append(minStage)
                        .append(" MAX=").append(maxStage).append('\n');
            }
        }
        writeReport(report.toString(), "endurance");
        if (failures > 0) {
            throw new RuntimeException("Endurance simulation had " + failures
                    + " cases below stage " + ENDURANCE_STAGE + "\n" + report);
        }
    }

    private static void runKitGate(Kit kit) {
        StringBuilder report = new StringBuilder();
        int total = 0;
        int passed = 0;

        for (int pattern = 0; pattern < 3; pattern++) {
            int patternPassed = 0;

            for (int seed = 0; seed < SEEDS_PER_PATTERN; seed++) {
                total++;
                Result result = simulate(pattern, seed, kit);
                if (result.stage >= TARGET_STAGE) {
                    passed++;
                    patternPassed++;
                }

                String line = "Maze " + (pattern + 1)
                        + " kit=" + kit
                        + " seed=" + seed
                        + " stage=" + result.stage
                        + " pads=" + result.padsReached
                        + " ticks=" + result.ticks
                        + " jumpTicks=" + result.jumpTicks
                        + " movementTicks=" + result.movementTicks
                        + " gaps=" + result.gapsSeen
                        + " gapLandings=" + result.gapLandings
                        + " abilities=" + result.abilityUses
                        + " mobBumps=" + result.mobBumps
                        + " maxSpeed=" + format(result.maxSpeed)
                        + " failure=" + result.failure;
                System.err.println("SIM " + line);
                report.append(line).append('\n');
            }

            report.append("Maze ").append(pattern + 1)
                    .append(" kit=").append(kit)
                    .append(" SUMMARY passed=").append(patternPassed).append("/")
                    .append(SEEDS_PER_PATTERN)
                    .append('\n');
        }

        System.err.println(report.toString());
        writeReport(report.toString(), kit.name().toLowerCase());

        if (passed != total) {
            throw new RuntimeException(
                    "Stage-10 gate failed for kit=" + kit
                    + ": passed=" + passed + "/" + total + "\\n" + report);
        }
    }

    private static void writeReport(String report, String suffix) {
        try {
            File file = new File("build/maze-simulator-report-" + suffix + ".txt");
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter writer = new FileWriter(file, false);
            writer.write(report);
            writer.close();
        } catch (Exception e) {
            System.err.println("Could not write simulator report: " + e);
        }
    }

    private static Result simulate(int pattern, int seed, Kit kit) {
        return simulate(pattern, seed, kit, TARGET_STAGE);
    }

    private static Result simulate(int pattern, int seed, Kit kit, int targetStage) {
        int[][] raw = copy(MazeLayouts.ALL_MAZES[pattern]);
        List<Cell> pads = buildPadSequence(raw, seed, targetStage);

        Result result = new Result();
        result.failure = "MAX_STAGE_NOT_REACHED";

        SimPlayer player = new SimPlayer();
        player.kit = kit;
        player.jumpCharges = kit == Kit.JUMPER ? 3 : 0;
        player.x = worldX(50);
        player.z = worldZ(49);
        player.y = 0.0D;
        player.grounded = true;
        player.yaw = 0.0F;

        FirstPadSpeedrunController controller = new FirstPadSpeedrunController();

        /*
         * Keep a real GameState alongside the adapter-facing centered-world
         * player. GameState uses the source Monster Maze coordinate frame
         * (0..98, cell centres at row+0.5); the controller/1.8 observer uses
         * centered Minecraft coordinates. This bridge is intentionally explicit
         * so we never "fix" physics by changing the Minecraft coordinate frame.
         */
        MazeModel monsterMaze = new MazeModel(raw);
        GameState tacticalState = new GameState();
        tacticalState.mode = Mode.MODERN;
        tacticalState.maze = monsterMaze;
        tacticalState.kit = kit;
        tacticalState.alive = true;
        tacticalState.inMonsterMaze = true;
        tacticalState.player.x = player.x + HALF;
        tacticalState.player.y = player.y;
        tacticalState.player.z = player.z + HALF;
        tacticalState.player.grounded = player.grounded;
        tacticalState.player.health = 20.0;
        tacticalState.player.maxHealth = 20.0;
        tacticalState.activePadRow = -1;
        tacticalState.activePadColumn = -1;
        AbilityModel abilities = new AbilityModel();
        abilities.initialiseForMode(tacticalState);
        MonsterSimulator monsterSimulator = new MonsterSimulator(monsterMaze, new Random(0x4D4D0000L + seed * 1009L), 0.18D);
        spawnInitialMonsters(tacticalState, raw, seed);

        Cell activePad = pads.get(0);
        setPadDisabled(monsterMaze, activePad, true);
        Cell oldPad = null;
        Cell previewPad = null;
        int stage = 1;
        int padIndex = 0;
        int oldPadTicksRemaining = 0;
        int stageTicksRemaining = stageTimeTicks(stage);
        boolean targetCaptured = false;
        int ticks = 0;

        while (stage <= targetStage && ticks < targetStage * MAX_TICKS_PER_STAGE) {
            /*
             * Source lifecycle:
             * - active pad is physical immediately;
             * - two seconds before phase expiry the preview pad is spawned and
             *   therefore also becomes physical;
             * - when the phase expires, the active pad becomes an old pad with
             *   an 11-second decay lifetime and the preview becomes active.
             */
            if (stageTicksRemaining == 40 && previewPad == null && padIndex + 1 < pads.size()) {
                previewPad = pads.get(padIndex + 1);
            }

            boolean[][] physical = physicalFloor(
                    raw, activePad, oldPad, oldPadTicksRemaining, previewPad);
            syncTacticalState(tacticalState, player, activePad, stage, stageTicksRemaining, ticks);
            tacticalState.previewPadRow = previewPad == null ? -1 : previewPad.row;
            tacticalState.previewPadColumn = previewPad == null ? -1 : previewPad.column;
            tacticalState.tick = ticks;

            LegacyWorldObservation observation = observation(
                    ticks, stage, pattern + 1, kit, player, activePad, raw, physical, tacticalState, abilities);

            LegacyAction action;
            ByteArrayOutputStream controllerLog = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            System.setOut(new PrintStream(controllerLog));
            try {
                action = controller.next(observation);
            } finally {
                System.out.flush();
                System.setOut(originalOut);
            }
            result.controllerLog.append(controllerLog.toString());
            if (action == null) action = LegacyAction.IDLE;

            /*
             * Ability use is now a real source-model operation. The adapter
             * action only carries the pulse; AbilityModel owns the cooldown,
             * charge, freeze, launch and Body Rush state exactly as the live
             * common model does.
             */
            if (action.useAbility) {
                boolean activated = abilities.activate(tacticalState);
                if (activated) result.abilityUses++;
            }

            if (action.jump) result.jumpTicks++;
            result.trace.append("t=").append(ticks)
                    .append(" p=").append(format(player.x)).append(",")
                    .append(format(player.y)).append(",").append(format(player.z))
                    .append(" v=").append(format(player.vx)).append(",")
                    .append(format(player.vz)).append(" a=").append(actionText(action)).append("\n");
            if (action.forward > 0.01D) result.movementTicks++;

            step(player, action, physical, result);

            syncTacticalState(tacticalState, player, activePad, stage, stageTicksRemaining, ticks);
            monsterSimulator.tick(tacticalState);
            int bumps = MonsterMazeBumpModel.apply(tacticalState);
            if (bumps > 0) {
                player.x = tacticalState.player.x - HALF;
                player.y = tacticalState.player.y;
                player.z = tacticalState.player.z - HALF;
                player.vx = tacticalState.player.vx;
                player.vy = tacticalState.player.vy;
                player.vz = tacticalState.player.vz;
                player.grounded = tacticalState.player.grounded;
                player.health = tacticalState.player.health;
                player.maxHealth = tacticalState.player.maxHealth;
                player.alive = tacticalState.player.health > 0.0;
                result.mobBumps += bumps;
            }

            if (!player.alive) {
                result.stage = stage;
                result.ticks = ticks + 1;
                result.failure = "FALL stage=" + stage
                        + " pos=" + format(player.x) + "," + format(player.y)
                        + "," + format(player.z)
                        + " action=" + actionText(action)
                        + " targets=" + padSequenceText(pads)
                        + " trace=" + tail(result.trace.toString(), 6000).replace("\n", " | ")
                        + " controllerLog=" + tail(result.controllerLog.toString(), 6000);
                return result;
            }

            ticks++;
            stageTicksRemaining--;
            if (oldPadTicksRemaining > 0) oldPadTicksRemaining--;

            /*
             * SafePad.isOn is the authoritative capture predicate. Capture
             * shortens the current phase but does not immediately advance the
             * active pad. This is the real multi-round state machine rather
             * than the old synthetic "6 tick transition" shortcut.
             */
            if (!targetCaptured && isOnPad(player, activePad)) {
                targetCaptured = true;
                syncTacticalState(tacticalState, player, activePad, stage, stageTicksRemaining, ticks);
                // Delegate pad healing/recharge to the common source model.
                // Solo progression then shortens the phase to four seconds.
                abilities.onReachedPad(tacticalState, true);
                player.health = tacticalState.player.health;
                player.maxHealth = tacticalState.player.maxHealth;
                player.jumpCharges = tacticalState.ability.charges;
                result.padsReached++;
                stageTicksRemaining = Math.min(stageTicksRemaining, 4 * 20);

                if (stage >= targetStage) {
                    result.stage = stage;
                    result.ticks = ticks;
                    result.failure = "PASS";
                    return result;
                }
            }

            // Match common Simulator's post-progression Jumper charge handling:
            // an airborne successful jump consumes one charge subject to the
            // source recharge/grace rules, while pad capture happens first.
            if (player.y > GameState.PATH_Y && kit == Kit.JUMPER) {
                abilities.consumeJumperCharge(tacticalState);
                player.jumpCharges = tacticalState.ability.charges;
            }

            if (stageTicksRemaining <= 0) {
                if (!targetCaptured) {
                    result.stage = stage;
                    result.ticks = ticks;
                    result.failure = "STAGE_TIMEOUT stage=" + stage
                            + " pos=" + format(player.x) + "," + format(player.z)
                            + " action=" + actionText(action)
                            + " targets=" + padSequenceText(pads)
                            + " controllerLog=" + tail(result.controllerLog.toString(), 10000);
                    return result;
                }

                oldPad = activePad;
                oldPadTicksRemaining = OLD_PAD_LIFETIME_TICKS;
                padIndex++;
                if (padIndex >= pads.size()) {
                    result.stage = stage;
                    result.ticks = ticks;
                    result.failure = "PAD_SEQUENCE_EXHAUSTED";
                    return result;
                }

                setPadDisabled(monsterMaze, oldPad, false);
                activePad = previewPad != null ? previewPad : pads.get(padIndex);
                setPadDisabled(monsterMaze, activePad, true);
                previewPad = null;
                stage++;
                targetCaptured = false;
                tacticalState.stage = stage;
                tacticalState.activePadRow = activePad.row;
                tacticalState.activePadColumn = activePad.column;
                spawnStageMonsters(tacticalState, raw, seed, stage);
                stageTicksRemaining = stageTimeTicks(stage);
            }
        }

        result.stage = stage;
        result.ticks = ticks;
        return result;
    }

    private static int stageTimeTicks(int stage) {
        /*
         * Modern Monster Maze timing comes from the common source-derived
         * TimerModel, not a simulator-specific approximation.
         */
        return new TimerModel().initialTicks(Mode.MODERN, stage);
    }

    private static LegacyWorldObservation observation(
            long tick, int stage, int pattern, Kit kit, SimPlayer p, Cell pad,
            int[][] raw, boolean[][] physical, GameState tacticalState,
            AbilityModel abilities) {
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
                        p.health, p.maxHealth),
                kit,
                tacticalState.ability.charges,
                kit == Kit.BODY_BUILDER
                        ? tacticalState.ability.activations
                        : tacticalState.ability.charges,
                new LegacyWorldObservation.BlockPoint(0, 0, 0),
                new LegacyWorldObservation.Pad(
                        pad.row, pad.column,
                        distanceSqToPad(p, pad),
                        isOnPad(p, pad)),
                raw,
                physical,
                monsterObservations(tacticalState),
                "Monster Maze",
                Collections.<String>emptyList());
    }

    private static void syncTacticalState(
            GameState state, SimPlayer player, Cell activePad, int stage,
            int phaseTicks, long tick) {
        state.tick = tick;
        state.stage = stage;
        state.activePadRow = activePad.row;
        state.activePadColumn = activePad.column;
        state.phaseTicksRemaining = Math.max(0, phaseTicks);
        state.player.x = player.x + HALF;
        state.player.y = player.y;
        state.player.z = player.z + HALF;
        state.player.vx = player.vx;
        state.player.vy = player.vy;
        state.player.vz = player.vz;
        state.player.yaw = player.yaw;
        state.player.grounded = player.grounded;
        state.player.health = player.health;
        state.player.maxHealth = player.maxHealth;
        state.alive = player.alive;
    }

    private static List<LegacyWorldObservation.Monster> monsterObservations(GameState state) {
        List<LegacyWorldObservation.Monster> out = new ArrayList<LegacyWorldObservation.Monster>();
        for (MonsterState m : state.monsters) {
            out.add(new LegacyWorldObservation.Monster(
                    m.id, "monster_maze_monster", "monster",
                    m.x - HALF, m.y, m.z - HALF,
                    m.vx, m.vy, m.vz, m.removed));
        }
        return out;
    }

    private static void spawnInitialMonsters(GameState state, int[][] raw, int seed) {
        Random random = new Random(0x6D4D0000L + seed * 31337L);
        spawnMonsters(state, raw, 225, random, 100000);
    }

    private static void spawnStageMonsters(GameState state, int[][] raw, int seed, int stage) {
        Random random = new Random(0x7D4D0000L + seed * 31337L + stage * 7919L);
        spawnMonsters(state, raw, 30, random, 100000 + stage * 1000);
    }

    private static void spawnMonsters(GameState state, int[][] raw, int count, Random random, int idBase) {
        List<Cell> spawns = new ArrayList<Cell>();
        for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++)
            if (raw[r][c] == 2 && state.maze != null && state.maze.isTraversable(r, c)) spawns.add(new Cell(r, c));
        if (spawns.isEmpty()) throw new AssertionError("Maze has no MonsterMaze spawn cells");
        for (int i = 0; i < count; i++) {
            Cell spawn = spawns.get(random.nextInt(spawns.size()));
            state.monsters.add(new MonsterState(
                    idBase + i,
                    spawn.row + 0.5,
                    0.0,
                    spawn.column + 0.5));
        }
    }

    private static void step(
            SimPlayer p, LegacyAction action, boolean[][] physical, Result result) {
        /*
         * The simulator gate deliberately delegates player movement to the same
         * common LegacyMovementModel used by the AI runtime. The only adapter
         * work here is converting the centered Minecraft coordinate frame to the
         * common Monster Maze frame and exposing the dynamic physical floor
         * (SafePads + source maze surface) for collision support.
         */
        PlayerState commonPlayer = new PlayerState();
        commonPlayer.x = p.x + HALF;
        commonPlayer.y = p.y;
        commonPlayer.z = p.z + HALF;
        commonPlayer.vx = p.vx;
        commonPlayer.vy = p.vy;
        commonPlayer.vz = p.vz;
        commonPlayer.yaw = p.yaw;
        commonPlayer.pitch = 0.0F;
        commonPlayer.grounded = p.grounded;
        commonPlayer.pendingAirborne = p.pendingAirborne;
        commonPlayer.health = p.health;
        commonPlayer.maxHealth = 20.0D;
        commonPlayer.jumpCharges = p.jumpCharges;
        commonPlayer.jumpTicks = p.jumpTicks;

        int[][] floorRaw = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int col = 0; col < SIZE; col++) {
                floorRaw[r][col] = physical[r][col] ? 1 : 0;
            }
        }
        MazeModel movementMaze = new MazeModel(floorRaw);

        Action commonAction = new Action(
                action.forward,
                action.strafe,
                action.jump,
                action.sprint,
                action.yawDelta,
                action.useAbility);
        new LegacyMovementModel().tick(commonPlayer, commonAction, movementMaze);

        p.x = commonPlayer.x - HALF;
        p.y = commonPlayer.y;
        p.z = commonPlayer.z - HALF;
        p.vx = commonPlayer.vx;
        p.vy = commonPlayer.vy;
        p.vz = commonPlayer.vz;
        p.yaw = commonPlayer.yaw;
        p.grounded = commonPlayer.grounded;
        p.pendingAirborne = commonPlayer.pendingAirborne;
        p.jumpTicks = commonPlayer.jumpTicks;
        p.health = commonPlayer.health;
        p.maxHealth = commonPlayer.maxHealth;
        result.maxSpeed = Math.max(result.maxSpeed, Math.hypot(p.vx, p.vz));
        if (p.y < -2.0D) p.alive = false;
    }

    private static boolean[][] physicalFloor(
            int[][] raw, Cell activePad, Cell oldPad,
            int oldPadTicksRemaining, Cell previewPad) {
        boolean[][] floor = new boolean[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                // Physical player floor follows MazeModel's physicalFloor
                // layer, which is intentionally distinct from isRawPath():
                // barrier/center surfaces are physical blocks even when they
                // are not part of the monster waypoint graph.
                floor[r][c] = raw[r][c] != 0;
            }
        }

        fillPad(floor, activePad);
        if (previewPad != null) fillPad(floor, previewPad);
        if (oldPad != null && oldPadTicksRemaining > 0) fillPad(floor, oldPad);
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

    private static List<Cell> buildPadSequence(int[][] raw, int seed, int targetStage) {
        // Exact 1.8 MonsterMaze MazeGenerator candidate construction.
        List<Cell> pathPoints = new ArrayList<Cell>();
        List<Cell> spawns = new ArrayList<Cell>();
        List<Cell> barriers = new ArrayList<Cell>();

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                int v = raw[r][c];
                if (v == 2) spawns.add(new Cell(r, c));
                if (v == 4 || v == 6) barriers.add(new Cell(r, c));
                if (v == 1 || v == 2 || v == 5 || v == 6) pathPoints.add(new Cell(r, c));
            }
        }

        List<Cell> filtered = new ArrayList<Cell>();
        for (Cell p : pathPoints) {
            if (nearAny(p, spawns, 10.0D)) continue;
            if (nearAny(p, barriers, 7.0D)) continue;
            filtered.add(p);
        }

        List<Cell> candidates = new ArrayList<Cell>(filtered);
        List<Cell> safeZones = new ArrayList<Cell>();
        Cell center = new Cell(HALF, HALF);
        for (int i = 0; i < 8 && !candidates.isEmpty(); i++) {
            List<Cell> away = new ArrayList<Cell>(safeZones);
            away.add(center);
            Cell zone = furthestFromAll(candidates, away);
            safeZones.add(zone);
            List<Cell> remove = new ArrayList<Cell>();
            for (Cell c : candidates) {
                if (distanceSq(zone, c) <= 36.0D) remove.add(c);
            }
            candidates.removeAll(remove);
        }

        List<Cell> valid = new ArrayList<Cell>();
        for (Cell p : filtered) {
            if (!nearAny(p, safeZones, 7.0D)) valid.add(p);
        }
        if (valid.isEmpty()) throw new AssertionError("No source-compatible SafePad candidates");

        Random random = new Random(0x4D4D0000L + seed * 1009L);
        List<Cell> pads = new ArrayList<Cell>();

        // Source first spawnSafePad(): no avoid list, therefore furthest from centre.
        pads.add(furthestFromCenterTiedRandom(valid, center, random));

        while (pads.size() < targetStage) {
            List<Cell> best = new ArrayList<Cell>();
            for (Cell candidate : valid) {
                boolean allowed = true;
                for (Cell avoid : pads) {
                    if (distanceSq(candidate, avoid) < 40.0D * 40.0D) {
                        allowed = false;
                        break;
                    }
                }
                if (allowed) best.add(candidate);
            }

            if (best.isEmpty()) {
                pads.add(furthestFromCenterTiedRandom(valid, center, random));
            } else {
                pads.add(best.get(random.nextInt(best.size())));
            }
        }
        return pads;
    }

    private static Cell furthestFromAll(List<Cell> locations, List<Cell> awayFrom) {
        Cell best = null;
        double bestDistance = -1.0D;
        for (Cell location : locations) {
            double closest = Double.POSITIVE_INFINITY;
            for (Cell away : awayFrom) closest = Math.min(closest, distanceSq(location, away));
            if (best == null || closest > bestDistance) {
                best = location;
                bestDistance = closest;
            }
        }
        return best;
    }

    private static Cell furthestFromCenterTiedRandom(List<Cell> locations, Cell center, Random random) {
        double bestDistance = -1.0D;
        List<Cell> best = new ArrayList<Cell>();
        for (Cell location : locations) {
            double d = distanceSq(location, center);
            if (d > bestDistance) {
                bestDistance = d;
                best.clear();
                best.add(location);
            } else if (d == bestDistance) {
                best.add(location);
            }
        }
        return best.get(random.nextInt(best.size()));
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

    private static void setPadDisabled(MazeModel maze, Cell pad, boolean disabled) {
        if (maze == null || pad == null) return;
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                int r = pad.row + dr;
                int c = pad.column + dc;
                if (inBounds(r, c)) maze.setDisabled(r, c, disabled);
            }
        }
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
                if (overlapX > 0.0D && overlapZ > 0.0D) return true;
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

    private static String padSequenceText(List<Cell> pads) {
        StringBuilder out = new StringBuilder();
        for (Cell pad : pads) {
            if (out.length() > 0) out.append(";");
            out.append(pad.row).append(",").append(pad.column);
        }
        return out.toString();
    }

    private static String actionText(LegacyAction action) {
        return "F=" + format(action.forward)
                + ",J=" + action.jump
                + ",SP=" + action.sprint
                + ",Y=" + format(action.yawDelta);
    }

    private static String tail(String value, int max) {
        return value.length() <= max ? value : value.substring(value.length() - max);
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
        boolean pendingAirborne;
        int jumpTicks;
        Kit kit;
        int jumpCharges;
        double health = 20.0D;
        double maxHealth = 20.0D;
    }

    private static final class Result {
        int stage = 1;
        int padsReached;
        int ticks;
        int jumpTicks;
        int movementTicks;
        int gapsSeen;
        int gapLandings;
        int abilityUses;
        int mobBumps;
        double maxSpeed;
        String failure;
        StringBuilder controllerLog = new StringBuilder();
        StringBuilder trace = new StringBuilder();
    }
}
