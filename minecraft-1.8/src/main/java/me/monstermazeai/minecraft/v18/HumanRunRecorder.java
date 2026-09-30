package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyWorldObservation;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * Full-fidelity 1.8.9 human Monster Maze telemetry recorder.
 *
 * The recorder never drives input. It observes the same Minecraft18Observer
 * used by the AI and uses Minecraft18RunBoundary for game boundaries.
 *
 * F7 toggles recording. When enabled, each detected game is split into
 * focused files under <.minecraft>/human-runs:
 *
 *   manifest.json      run metadata and schema
 *   movement.jsonl     player kinematics and vanilla movement flags
 *   input.jsonl        aggregate + raw keyboard/mouse input
 *   world.jsonl        stage/timer/scoreboard/kit/charges/game state
 *   objectives.jsonl   active + preview SafePad beacon states near transitions
 *   navigation.jsonl   logical cell/pad geometry and derived movement facts
 *   monsters.jsonl     local monster state every tick
 *   maze.jsonl         logical + physical maze snapshots only when changed
 *   inventory.jsonl    inventory/hotbar snapshots only when changed
 *   collision.jsonl    local block collision context only when changed
 *   events.jsonl       sparse causal transitions and terminal reasons
 *
 * No player name, UUID, chat history, or other identity data is recorded.
 * Terminal chat is recorded only when it matches the shared AI run-end rule.
 */
public final class HumanRunRecorder implements Closeable {
    private static final String DIRECTORY = "human-runs";
    private static final int JSON_VERSION = 2;
    private static final double MONSTER_LOCAL_RADIUS = 20.0D;
    private static final double MONSTER_EVENT_RADIUS = 32.0D;
    private static final int COLLISION_RADIUS = 2;
    private static final int COLLISION_Y_BELOW = 1;
    private static final int COLLISION_Y_ABOVE = 2;

    private final Minecraft minecraft;
    private final Minecraft18Observer observer;

    private BufferedWriter manifestWriter;
    private BufferedWriter movementWriter;
    private BufferedWriter inputWriter;
    private BufferedWriter worldWriter;
    private BufferedWriter objectiveWriter;
    private BufferedWriter navigationWriter;
    private BufferedWriter monsterWriter;
    private BufferedWriter mazeWriter;
    private BufferedWriter inventoryWriter;
    private BufferedWriter collisionWriter;
    private BufferedWriter eventWriter;

    private Path currentManifest;
    private String runStamp;
    private boolean enabled;
    private boolean inRun;
    private String pendingEndReason;

    private float inputForward;
    private float inputStrafe;
    private boolean inputJump;
    private boolean inputSprint;
    private boolean rawForward;
    private boolean rawBack;
    private boolean rawLeft;
    private boolean rawRight;
    private boolean rawJump;
    private boolean rawSprint;
    private boolean rawSneak;
    private boolean rawAttack;
    private boolean rawUseItem;
    private boolean mouseLeftPulse;
    private boolean mouseRightPulse;
    private boolean previousMouseLeftDown;
    private boolean previousMouseRightDown;

    private float previousYaw = Float.NaN;
    private long previousWorldTick = Long.MIN_VALUE;
    private double previousX = Double.NaN;
    private double previousZ = Double.NaN;
    private double previousHealth = Double.NaN;
    private int previousStage = Integer.MIN_VALUE;
    private int previousPadRow = Integer.MIN_VALUE;
    private int previousPadColumn = Integer.MIN_VALUE;
    private boolean previousPadReached;
    private boolean previousGrounded;
    private int previousJumpCharges = Integer.MIN_VALUE;
    private int previousAbilityCharges = Integer.MIN_VALUE;
    private int previousSelectedSlot = Integer.MIN_VALUE;
    private String previousInventorySignature = "";
    private int previousCollisionHash;
    private int previousMazeHash;
    private int previousPhysicalFloorHash;
    private int previousMonsterIdsHash;
    private final Set<Integer> knownMonsterIds = new HashSet<Integer>();
    private boolean previousAlive = true;
    private String previousObjectiveSignature = "";

    private long records;

    public HumanRunRecorder(Minecraft minecraft, Minecraft18Observer observer) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        if (observer == null) throw new IllegalArgumentException("observer");
        this.minecraft = minecraft;
        this.observer = observer;
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) finish("RECORDER_DISABLED");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isActive() {
        return inRun && manifestWriter != null;
    }

    public Path currentPath() {
        return currentManifest;
    }

    private void captureInput() {
        EntityPlayerSP player = minecraft.thePlayer;
        if (player == null) return;

        MovementInput input = player.movementInput;
        if (input != null) {
            inputForward = input.moveForward;
            inputStrafe = input.moveStrafe;
            inputJump = input.jump;
        }
        inputSprint = minecraft.gameSettings.keyBindSprint.isKeyDown();

        rawForward = minecraft.gameSettings.keyBindForward.isKeyDown();
        rawBack = minecraft.gameSettings.keyBindBack.isKeyDown();
        rawLeft = minecraft.gameSettings.keyBindLeft.isKeyDown();
        rawRight = minecraft.gameSettings.keyBindRight.isKeyDown();
        rawJump = minecraft.gameSettings.keyBindJump.isKeyDown();
        rawSprint = minecraft.gameSettings.keyBindSprint.isKeyDown();
        rawSneak = minecraft.gameSettings.keyBindSneak.isKeyDown();
        rawAttack = minecraft.gameSettings.keyBindAttack.isKeyDown();
        rawUseItem = minecraft.gameSettings.keyBindUseItem.isKeyDown();

        boolean leftDown = Mouse.isButtonDown(0);
        boolean rightDown = Mouse.isButtonDown(1);
        mouseLeftPulse = leftDown && !previousMouseLeftDown;
        mouseRightPulse = rightDown && !previousMouseRightDown;
        previousMouseLeftDown = leftDown;
        previousMouseRightDown = rightDown;
    }

    @SubscribeEvent
    public void onClientChat(ClientChatReceivedEvent event) {
        if (!enabled || event == null || event.message == null || !inRun) return;
        String text = event.message.getUnformattedText();
        if (!Minecraft18RunBoundary.isTerminalChat(text)) return;
        pendingEndReason = "CHAT:" + sanitizeTerminalChat(text);
        if (eventWriter != null && minecraft.theWorld != null) {
            try {
                writeEvent(minecraft.theWorld.getTotalWorldTime(), records, "TERMINAL_CHAT", sanitizeTerminalChat(text));
            } catch (IOException e) {
                System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER chat event write failed: " + e);
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled) return;

        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            finish("WORLD_LEFT");
            resetInput();
            return;
        }

        LegacyWorldObservation state = observer.observe().state;

        if (!inRun) {
            if (Minecraft18RunBoundary.isGameStart(state)) {
                if (!begin(state)) {
                    resetInput();
                    return;
                }
            } else {
                resetInput();
                return;
            }
        }

        try {
            captureInput();
        write(state);
        } catch (IOException e) {
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER write failed: " + e);
            finish("WRITE_ERROR");
            return;
        }

        if (pendingEndReason != null) {
            finish(pendingEndReason);
            pendingEndReason = null;
        } else if (Minecraft18RunBoundary.isGameEnd(state)) {
            String reason;
            if (state.completed) reason = "COMPLETED";
            else if (!state.alive) reason = "PLAYER_DEAD";
            else reason = "LEFT_MAZE";
            finish(reason);
        }
    }

    private boolean begin(LegacyWorldObservation state) {
        File directory = new File(minecraft.mcDataDir, DIRECTORY);
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER failed to create " + directory.getAbsolutePath());
            return false;
        }

        runStamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        currentManifest = new File(directory, "human-speed-run-" + runStamp + "-manifest.json").toPath();

        try {
            manifestWriter = open(currentManifest);
        movementWriter = open(new File(directory, "human-speed-run-" + runStamp + "-movement.jsonl").toPath());
        inputWriter = open(new File(directory, "human-speed-run-" + runStamp + "-input.jsonl").toPath());
        worldWriter = open(new File(directory, "human-speed-run-" + runStamp + "-world.jsonl").toPath());
        objectiveWriter = open(new File(directory, "human-speed-run-" + runStamp + "-objectives.jsonl").toPath());
        navigationWriter = open(new File(directory, "human-speed-run-" + runStamp + "-navigation.jsonl").toPath());
        monsterWriter = open(new File(directory, "human-speed-run-" + runStamp + "-monsters.jsonl").toPath());
        mazeWriter = open(new File(directory, "human-speed-run-" + runStamp + "-maze.jsonl").toPath());
        inventoryWriter = open(new File(directory, "human-speed-run-" + runStamp + "-inventory.jsonl").toPath());
        collisionWriter = open(new File(directory, "human-speed-run-" + runStamp + "-collision.jsonl").toPath());
        eventWriter = open(new File(directory, "human-speed-run-" + runStamp + "-events.jsonl").toPath());

        inRun = true;
        records = 0L;
        previousYaw = Float.NaN;
        previousWorldTick = Long.MIN_VALUE;
        previousX = Double.NaN;
        previousZ = Double.NaN;
        previousHealth = Double.NaN;
        previousStage = Integer.MIN_VALUE;
        previousPadRow = Integer.MIN_VALUE;
        previousPadColumn = Integer.MIN_VALUE;
        previousPadReached = false;
        previousGrounded = false;
        previousJumpCharges = Integer.MIN_VALUE;
        previousAbilityCharges = Integer.MIN_VALUE;
        previousSelectedSlot = Integer.MIN_VALUE;
        previousInventorySignature = "";
        previousCollisionHash = 0;
        previousMazeHash = 0;
        previousPhysicalFloorHash = 0;
        previousMonsterIdsHash = 0;
        knownMonsterIds.clear();
        previousAlive = true;
        previousObjectiveSignature = "";
        pendingEndReason = null;

        writeManifest(state, directory);
        writeHeader(movementWriter, "movement");
        writeHeader(inputWriter, "input");
        writeHeader(worldWriter, "world");
        writeHeader(objectiveWriter, "objectives");
        writeHeader(navigationWriter, "navigation");
        writeHeader(monsterWriter, "monsters");
        writeHeader(mazeWriter, "maze");
        writeHeader(inventoryWriter, "inventory");
        writeHeader(collisionWriter, "collision");
        writeHeader(eventWriter, "events");
        writeEvent(state.worldTick, 0L, "GAME_START", "");
        flushAll();
        System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER started: "
                + currentManifest.toAbsolutePath());
        return true;
        } catch (IOException e) {
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER failed to open: " + e);
            closeAllWriters();
            manifestWriter = null;
            movementWriter = null;
            inputWriter = null;
            worldWriter = null;
            objectiveWriter = null;
            navigationWriter = null;
            monsterWriter = null;
            mazeWriter = null;
            inventoryWriter = null;
            collisionWriter = null;
            eventWriter = null;
            currentManifest = null;
            runStamp = null;
            inRun = false;
            return false;
        }
    }

    private BufferedWriter open(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private void writeManifest(LegacyWorldObservation state, File directory) throws IOException {
        manifestWriter.write("{\"recordType\":\"manifest\",\"jsonVersion\":" + JSON_VERSION
                + ",\"minecraftVersion\":\"1.8.9\",\"worldTick\":" + state.worldTick
                + ",\"stage\":" + state.stage
                + ",\"files\":[\""
                + "human-speed-run-" + runStamp + "-movement.jsonl\",\""
                + "human-speed-run-" + runStamp + "-input.jsonl\",\""
                + "human-speed-run-" + runStamp + "-world.jsonl\",\""
                + "human-speed-run-" + runStamp + "-objectives.jsonl\",\""
                + "human-speed-run-" + runStamp + "-navigation.jsonl\",\""
                + "human-speed-run-" + runStamp + "-monsters.jsonl\",\""
                + "human-speed-run-" + runStamp + "-maze.jsonl\",\""
                + "human-speed-run-" + runStamp + "-inventory.jsonl\",\""
                + "human-speed-run-" + runStamp + "-collision.jsonl\",\""
                + "human-speed-run-" + runStamp + "-events.jsonl\"],"
                + "\"boundary\":\"Minecraft18RunBoundary + Minecraft18Observer\","
                + "\"privacy\":\"No player identity, UUID, or ordinary chat transcript\"}");
        manifestWriter.newLine();
    }

    private void writeHeader(BufferedWriter writer, String stream) throws IOException {
        writer.write("{\"recordType\":\"header\",\"jsonVersion\":" + JSON_VERSION
                + ",\"stream\":\"" + stream + "\"}");
        writer.newLine();
    }

    private void write(LegacyWorldObservation state) throws IOException {
        long dt = previousWorldTick == Long.MIN_VALUE ? 1L
                : Math.max(1L, state.worldTick - previousWorldTick);
        float yawDelta = Float.isNaN(previousYaw) ? 0.0F
                : wrapDegrees(state.player.yaw - previousYaw);
        double dx = Double.isNaN(previousX) ? 0.0D : state.player.x - previousX;
        double dz = Double.isNaN(previousZ) ? 0.0D : state.player.z - previousZ;
        double displacement = Math.hypot(dx, dz);
        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        double healthDelta = Double.isNaN(previousHealth) ? 0.0D
                : state.player.health - previousHealth;

        String prefix = "{\"tick\":" + state.worldTick
                + ",\"stage\":" + state.stage
                + ",\"recordIndex\":" + records;

        writeLine(movementWriter, prefix
                + ",\"x\":" + state.player.x
                + ",\"y\":" + state.player.y
                + ",\"z\":" + state.player.z
                + ",\"vx\":" + state.player.vx
                + ",\"vy\":" + state.player.vy
                + ",\"vz\":" + state.player.vz
                + ",\"yaw\":" + state.player.yaw
                + ",\"pitch\":" + state.player.pitch
                + ",\"yawDelta\":" + yawDelta
                + ",\"grounded\":" + state.player.grounded
                + ",\"fallDistance\":" + minecraft.thePlayer.fallDistance
                + ",\"horizontalCollision\":" + minecraft.thePlayer.isCollidedHorizontally
                + ",\"verticalCollision\":" + minecraft.thePlayer.isCollidedVertically
                + ",\"collision\":" + minecraft.thePlayer.isCollided
                + ",\"airborne\":" + minecraft.thePlayer.isAirBorne
                + ",\"sprinting\":" + minecraft.thePlayer.isSprinting()
                + ",\"sneaking\":" + minecraft.thePlayer.isSneaking()
                + ",\"stepHeight\":" + minecraft.thePlayer.stepHeight
                + ",\"jumpMovementFactor\":" + minecraft.thePlayer.jumpMovementFactor
                + ",\"walkDistance\":" + minecraft.thePlayer.distanceWalkedModified
                + ",\"dx\":" + dx
                + ",\"dz\":" + dz
                + ",\"displacement\":" + displacement
                + ",\"horizontalSpeed\":" + horizontalSpeed
                + ",\"healthDelta\":" + healthDelta
                + "}");

        writeLine(inputWriter, prefix
                + ",\"forward\":" + inputForward
                + ",\"strafe\":" + inputStrafe
                + ",\"jump\":" + inputJump
                + ",\"sprintKey\":" + inputSprint
                + ",\"rawForward\":" + rawForward
                + ",\"rawBack\":" + rawBack
                + ",\"rawLeft\":" + rawLeft
                + ",\"rawRight\":" + rawRight
                + ",\"rawJump\":" + rawJump
                + ",\"rawSprint\":" + rawSprint
                + ",\"rawSneak\":" + rawSneak
                + ",\"rawAttack\":" + rawAttack
                + ",\"rawUseItem\":" + rawUseItem
                + ",\"mouseLeftPulse\":" + mouseLeftPulse
                + ",\"mouseRightPulse\":" + mouseRightPulse
                + ",\"yawDelta\":" + yawDelta
                + ",\"yawDeltaWithin30\":" + (Math.abs(yawDelta) <= 30.0001F)
                + "}");

        writeWorld(state, prefix, dt, healthDelta);
        writeObjectivesIfRelevant(state);
        writeNavigation(state, prefix, dx, dz, displacement, horizontalSpeed);
        writeMonsters(state);
        writeMazeIfChanged(state);
        writeInventoryIfChanged(state);
        writeCollisionIfChanged(state);
        writeTransitions(state, prefix, healthDelta);

        records++;
        previousYaw = state.player.yaw;
        previousWorldTick = state.worldTick;
        previousX = state.player.x;
        previousZ = state.player.z;
        previousHealth = state.player.health;
        previousStage = state.stage;
        if (state.pad != null) {
            previousPadRow = state.pad.row;
            previousPadColumn = state.pad.column;
            previousPadReached = state.pad.reached;
        } else {
            previousPadRow = Integer.MIN_VALUE;
            previousPadColumn = Integer.MIN_VALUE;
            previousPadReached = false;
        }
        previousGrounded = state.player.grounded;
        previousJumpCharges = state.jumpCharges;
        previousAbilityCharges = state.abilityCharges;
        previousSelectedSlot = minecraft.thePlayer.inventory.currentItem;
        previousAlive = state.alive;
        mouseLeftPulse = false;
        mouseRightPulse = false;
        previousMouseLeftDown = false;
        previousMouseRightDown = false;

        if (records % 20L == 0L) flushAll();
    }

    private void writeWorld(LegacyWorldObservation state, String prefix,
                            long dt, double healthDelta) throws IOException {
        StringBuilder b = new StringBuilder(prefix);
        b.append(",\"dt\":").append(dt)
                .append(",\"phaseTimerSeconds\":").append(state.safePadSeconds)
                .append(",\"liveSeconds\":").append(state.liveSeconds)
                .append(",\"inMaze\":").append(state.inMonsterMaze)
                .append(",\"alive\":").append(state.alive)
                .append(",\"completed\":").append(state.completed)
                .append(",\"mazeDetected\":").append(state.mazeDetected)
                .append(",\"mazePattern\":").append(state.mazePattern)
                .append(",\"kit\":\"").append(escape(state.kit == null ? "" : state.kit.name())).append("\"")
                .append(",\"jumpCharges\":").append(state.jumpCharges)
                .append(",\"abilityCharges\":").append(state.abilityCharges)
                .append(",\"health\":").append(state.player.health)
                .append(",\"healthDelta\":").append(healthDelta)
                .append(",\"selectedHotbarSlot\":").append(minecraft.thePlayer.inventory.currentItem)
                .append(",\"scoreboardTitle\":\"").append(escape(state.scoreboardTitle)).append("\",\"scoreboardLines\":[");
        for (int i = 0; i < state.scoreboardLines.size(); i++) {
            if (i > 0) b.append(",");
            b.append("\"").append(escape(state.scoreboardLines.get(i))).append("\"");
        }
        b.append("]");
        if (state.center == null) {
            b.append(",\"center\":null");
        } else {
            b.append(",\"center\":{\"x\":").append(state.center.x)
                    .append(",\"y\":").append(state.center.y)
                    .append(",\"z\":").append(state.center.z).append("}");
        }
        b.append(",\"activePad\":").append(padJson(state))
                .append("}");
        writeLine(worldWriter, b.toString());
    }

    private void writeObjectivesIfRelevant(LegacyWorldObservation state) throws IOException {
        if (state.center == null || state.safePadSeconds < 0) return;
        if (state.safePadSeconds > 3
                && state.pad != null
                && state.pad.row == previousPadRow
                && state.pad.column == previousPadColumn) {
            return;
        }

        StringBuilder beacons = new StringBuilder("[");
        StringBuilder signature = new StringBuilder();
        boolean first = true;
        int surfaceY = state.center.y - 1;
        int minX = state.center.x - 49;
        int maxX = state.center.x + 49;
        int minZ = state.center.z - 49;
        int maxZ = state.center.z + 49;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (minecraft.theWorld.getBlockState(new BlockPos(x, surfaceY, z)).getBlock() != Blocks.beacon) {
                    continue;
                }
                int row = x - minX;
                int column = z - minZ;
                boolean active = state.pad != null
                        && state.pad.row == row
                        && state.pad.column == column;
                boolean preview = !active;
                if (!first) beacons.append(",");
                first = false;
                beacons.append("{\"row\":").append(row)
                        .append(",\"column\":").append(column)
                        .append(",\"x\":").append(x)
                        .append(",\"y\":").append(surfaceY)
                        .append(",\"z\":").append(z)
                        .append(",\"active\":").append(active)
                        .append(",\"previewCandidate\":").append(preview)
                        .append("}");
                signature.append(row).append(":").append(column).append(":").append(active).append("|");
            }
        }
        beacons.append("]");

        String next = state.safePadSeconds + "|" + signature.toString()
                + "|" + (state.pad == null ? "none" : state.pad.row + "," + state.pad.column);
        if (next.equals(previousObjectiveSignature)) return;

        writeLine(objectiveWriter, "{\"tick\":" + state.worldTick
                + ",\"stage\":" + state.stage
                + ",\"safePadSeconds\":" + state.safePadSeconds
                + ",\"activePad\":" + padJson(state)
                + ",\"beacons\":" + beacons
                + ",\"note\":\"Beacon scan is performed only near the preview/transition window.\"}");
        previousObjectiveSignature = next;
    }

    private void writeNavigation(LegacyWorldObservation state, String prefix,
                                 double dx, double dz, double displacement,
                                 double horizontalSpeed) throws IOException {
        int row = -1;
        int column = -1;
        if (state.center != null) {
            row = (int)Math.floor(state.player.x - (state.center.x - 49));
            column = (int)Math.floor(state.player.z - (state.center.z - 49));
        }
        double targetDx = Double.NaN;
        double targetDz = Double.NaN;
        double targetDistance = Double.NaN;
        double targetBearing = Double.NaN;
        if (state.pad != null && state.center != null && state.pad.row >= 0 && state.pad.column >= 0) {
            double targetX = state.center.x - 49 + state.pad.row + 0.5D;
            double targetZ = state.center.z - 49 + state.pad.column + 0.5D;
            targetDx = targetX - state.player.x;
            targetDz = targetZ - state.player.z;
            targetDistance = Math.hypot(targetDx, targetDz);
            targetBearing = Math.toDegrees(Math.atan2(-targetDx, targetDz));
        }
        double movementBearing = displacement < 1.0E-9 ? Double.NaN : Math.toDegrees(Math.atan2(-dx, dz));
        double velocityBearing = horizontalSpeed < 1.0E-9
                ? Double.NaN : Math.toDegrees(Math.atan2(-state.player.vx, state.player.vz));

        writeLine(navigationWriter, prefix
                + ",\"row\":" + row
                + ",\"column\":" + column
                + ",\"activePad\":" + padJson(state)
                + ",\"targetDx\":" + targetDx
                + ",\"targetDz\":" + targetDz
                + ",\"targetDistance\":" + targetDistance
                + ",\"targetBearing\":" + targetBearing
                + ",\"movementBearing\":" + movementBearing
                + ",\"velocityBearing\":" + velocityBearing
                + ",\"displacement\":" + displacement
                + ",\"horizontalSpeed\":" + horizontalSpeed
                + "}");
    }

    private void writeMonsters(LegacyWorldObservation state) throws IOException {
        /*
         * This is intentionally a compact numeric stream. Repeating JSON field
         * names and monster type strings for every entity on every tick made a
         * long Stage-60+ run hundreds of MB. The AI's tactical monster horizon
         * is ~20 blocks, so keep exact per-tick kinematics inside that horizon.
         *
         * Type metadata and wider 32-block encounter information are emitted
         * sparsely through events, so information needed to interpret a monster
         * is retained without multiplying the payload on every tick.
         */
        StringBuilder b = new StringBuilder();
        b.append("{\"tick\":").append(state.worldTick)
                .append(",\"stage\":").append(state.stage)
                .append(",\"recordIndex\":").append(records)
                .append(",\"radius\":").append(MONSTER_LOCAL_RADIUS)
                .append(",\"monsters\":[");
        int localCount = 0;
        int idsHash = 1;
        double radiusSquared = MONSTER_LOCAL_RADIUS * MONSTER_LOCAL_RADIUS;
        double eventRadiusSquared = MONSTER_EVENT_RADIUS * MONSTER_EVENT_RADIUS;

        for (LegacyWorldObservation.Monster m : state.monsters) {
            if (m.removed) continue;
            double dx = m.x - state.player.x;
            double dy = m.y - state.player.y;
            double dz = m.z - state.player.z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;

            if (distanceSquared <= eventRadiusSquared && !knownMonsterIds.contains(m.id)) {
                writeEvent(state.worldTick, records, "MONSTER_SEEN",
                        "id=" + m.id
                                + ",gameplayType=" + safeEventValue(m.gameplayType)
                                + ",visualType=" + safeEventValue(m.visualType));
                knownMonsterIds.add(m.id);
            }

            if (distanceSquared > radiusSquared) continue;

            if (localCount > 0) b.append(",");
            b.append("[")
                    .append(m.id).append(",")
                    .append(formatNumber(m.x)).append(",")
                    .append(formatNumber(m.y)).append(",")
                    .append(formatNumber(m.z)).append(",")
                    .append(formatNumber(m.vx)).append(",")
                    .append(formatNumber(m.vy)).append(",")
                    .append(formatNumber(m.vz))
                    .append("]");
            idsHash = 31 * idsHash + m.id;
            localCount++;
        }

        b.append("],\"count\":").append(localCount)
                .append(",\"observerCount\":").append(state.monsters.size())
                .append("}");
        writeLine(monsterWriter, b.toString());

        if (idsHash != previousMonsterIdsHash && previousMonsterIdsHash != 0) {
            writeEvent(state.worldTick, records, "MONSTER_SET_CHANGED",
                    "localCount=" + localCount
                            + ",previousHash=" + previousMonsterIdsHash
                            + ",hash=" + idsHash);
        }
        previousMonsterIdsHash = idsHash;
    }

    private void writeMazeIfChanged(LegacyWorldObservation state) throws IOException {
        int mazeHash = matrixHash(state.maze);
        int physicalFloorHash = matrixHash(state.physicalFloor);
        if (mazeHash == previousMazeHash && physicalFloorHash == previousPhysicalFloorHash) return;

        mazeWriter.write("{\"tick\":" + state.worldTick
                + ",\"stage\":" + state.stage
                + ",\"logicalHash\":" + mazeHash
                + ",\"physicalFloorHash\":" + physicalFloorHash
                + ",\"maze\":" + intRows(state.maze)
                + ",\"physicalFloor\":" + booleanRows(state.physicalFloor)
                + "}");
        mazeWriter.newLine();

        previousMazeHash = mazeHash;
        previousPhysicalFloorHash = physicalFloorHash;
    }

    private void writeInventoryIfChanged(LegacyWorldObservation state) throws IOException {
        EntityPlayerSP player = minecraft.thePlayer;
        StringBuilder signature = new StringBuilder();
        StringBuilder b = new StringBuilder();
        signature.append(player.inventory.currentItem).append("|");
        b.append("{\"tick\":").append(state.worldTick)
                .append(",\"stage\":").append(state.stage)
                .append(",\"selectedSlot\":").append(player.inventory.currentItem)
                .append(",\"items\":[");
        boolean first = true;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null) continue;
            Item item = stack.getItem();
            int itemId = Item.getIdFromItem(item);
            int metadata = stack.getMetadata();
            String display = stack.hasDisplayName() ? stack.getDisplayName() : "";
            signature.append(slot).append(":").append(itemId).append(":")
                    .append(metadata).append(":").append(stack.stackSize).append(":")
                    .append(display).append("|");
            if (!first) b.append(",");
            first = false;
            b.append("{\"slot\":").append(slot)
                    .append(",\"itemId\":").append(itemId)
                    .append(",\"metadata\":").append(metadata)
                    .append(",\"count\":").append(stack.stackSize)
                    .append(",\"displayName\":\"").append(escape(display)).append("\"}");
        }
        b.append("]}");

        String nextSignature = signature.toString();
        if (nextSignature.equals(previousInventorySignature)) return;
        inventoryWriter.write(b.toString());
        inventoryWriter.newLine();
        previousInventorySignature = nextSignature;
    }

    private void writeCollisionIfChanged(LegacyWorldObservation state) throws IOException {
        EntityPlayerSP player = minecraft.thePlayer;
        WorldSnapshot snapshot = buildCollisionSnapshot(player);
        if (snapshot.hash == previousCollisionHash) return;

        collisionWriter.write("{\"tick\":" + state.worldTick
                + ",\"stage\":" + state.stage
                + ",\"playerBlock\":{\"x\":" + snapshot.playerX
                + ",\"y\":" + snapshot.playerY
                + ",\"z\":" + snapshot.playerZ + "}"
                + ",\"blocks\":" + snapshot.json + "}");
        collisionWriter.newLine();
        previousCollisionHash = snapshot.hash;
    }

    private WorldSnapshot buildCollisionSnapshot(EntityPlayerSP player) {
        int baseX = (int)Math.floor(player.posX);
        int baseY = (int)Math.floor(player.posY);
        int baseZ = (int)Math.floor(player.posZ);
        StringBuilder b = new StringBuilder("[");
        int hash = 1;
        boolean first = true;

        for (int dy = -COLLISION_Y_BELOW; dy <= COLLISION_Y_ABOVE; dy++) {
            for (int dx = -COLLISION_RADIUS; dx <= COLLISION_RADIUS; dx++) {
                for (int dz = -COLLISION_RADIUS; dz <= COLLISION_RADIUS; dz++) {
                    int x = baseX + dx;
                    int y = baseY + dy;
                    int z = baseZ + dz;
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = minecraft.theWorld.getBlockState(pos).getBlock();
                    int id = Block.getIdFromBlock(block);
                    int meta = minecraft.theWorld.getBlockState(pos).getBlock().getMetaFromState(
                            minecraft.theWorld.getBlockState(pos));
                    hash = 31 * hash + id;
                    hash = 31 * hash + meta;
                    if (!first) b.append(",");
                    first = false;
                    b.append("{\"dx\":").append(dx)
                            .append(",\"dy\":").append(dy)
                            .append(",\"dz\":").append(dz)
                            .append(",\"id\":").append(id)
                            .append(",\"meta\":").append(meta)
                            .append("}");
                }
            }
        }
        b.append("]");
        return new WorldSnapshot(hash, baseX, baseY, baseZ, b.toString());
    }

    private void writeTransitions(LegacyWorldObservation state, String prefix,
                                   double healthDelta) throws IOException {
        if (state.stage != previousStage) {
            writeEvent(state.worldTick, records, previousStage == Integer.MIN_VALUE
                    ? "STAGE_START" : "STAGE_CHANGE",
                    "stage=" + state.stage + ",previousStage=" + previousStage);
        }

        if (state.pad != null && (state.pad.row != previousPadRow
                || state.pad.column != previousPadColumn)) {
            writeEvent(state.worldTick, records, "ACTIVE_PAD_CHANGED",
                    "row=" + state.pad.row + ",column=" + state.pad.column
                            + ",previousRow=" + previousPadRow + ",previousColumn=" + previousPadColumn);
        }

        if (state.pad != null && state.pad.reached && !previousPadReached) {
            writeEvent(state.worldTick, records, "PAD_REACHED",
                    "row=" + state.pad.row + ",column=" + state.pad.column);
        }

        if (state.player.grounded != previousGrounded) {
            writeEvent(state.worldTick, records,
                    state.player.grounded ? "LANDED_OR_GROUNDED" : "LEFT_GROUND",
                    "grounded=" + state.player.grounded);
        }

        if (!Double.isNaN(previousHealth) && healthDelta < -1.0E-6D) {
            writeEvent(state.worldTick, records, "HEALTH_LOSS",
                    "delta=" + healthDelta + ",health=" + state.player.health);
        }

        if (state.jumpCharges != previousJumpCharges) {
            writeEvent(state.worldTick, records, "JUMP_CHARGES_CHANGED",
                    "value=" + state.jumpCharges + ",previous=" + previousJumpCharges);
        }

        if (state.abilityCharges != previousAbilityCharges) {
            writeEvent(state.worldTick, records, "ABILITY_CHARGES_CHANGED",
                    "value=" + state.abilityCharges + ",previous=" + previousAbilityCharges);
        }

        int selected = minecraft.thePlayer.inventory.currentItem;
        if (selected != previousSelectedSlot) {
            writeEvent(state.worldTick, records, "HOTBAR_SLOT_CHANGED",
                    "slot=" + selected + ",previous=" + previousSelectedSlot);
        }

        if (mouseLeftPulse) writeEvent(state.worldTick, records, "MOUSE_LEFT", "");
        if (mouseRightPulse) writeEvent(state.worldTick, records, "MOUSE_RIGHT", "");
    }

    private void writeEvent(long tick, long recordIndex, String event, String detail) throws IOException {
        eventWriter.write("{\"tick\":" + tick
                + ",\"recordIndex\":" + recordIndex
                + ",\"event\":\"" + escape(event)
                + "\",\"detail\":\"" + escape(detail) + "\"}");
        eventWriter.newLine();
    }

    private void writeLine(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    private void flushAll() throws IOException {
        if (manifestWriter != null) manifestWriter.flush();
        if (movementWriter != null) movementWriter.flush();
        if (inputWriter != null) inputWriter.flush();
        if (worldWriter != null) worldWriter.flush();
        if (objectiveWriter != null) objectiveWriter.flush();
        if (navigationWriter != null) navigationWriter.flush();
        if (monsterWriter != null) monsterWriter.flush();
        if (mazeWriter != null) mazeWriter.flush();
        if (inventoryWriter != null) inventoryWriter.flush();
        if (collisionWriter != null) collisionWriter.flush();
        if (eventWriter != null) eventWriter.flush();
    }

    private String padJson(LegacyWorldObservation state) {
        if (state.pad == null) return "null";
        return "{\"row\":" + state.pad.row
                + ",\"column\":" + state.pad.column
                + ",\"distanceSq\":" + state.pad.distanceSq
                + ",\"reached\":" + state.pad.reached + "}";
    }

    private static String intRows(int[][] matrix) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < matrix.length; i++) {
            if (i > 0) b.append(",");
            b.append("\"").append(intRow(matrix[i])).append("\"");
        }
        return b.append("]").toString();
    }

    private static String booleanRows(boolean[][] matrix) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < matrix.length; i++) {
            if (i > 0) b.append(",");
            b.append("\"").append(booleanRow(matrix[i])).append("\"");
        }
        return b.append("]").toString();
    }

    private static String intRow(int[] row) {
        StringBuilder b = new StringBuilder(row.length);
        for (int value : row) b.append(value);
        return b.toString();
    }

    private static String booleanRow(boolean[] row) {
        StringBuilder b = new StringBuilder(row.length);
        for (boolean value : row) b.append(value ? '1' : '0');
        return b.toString();
    }

    private static int matrixHash(int[][] matrix) {
        int hash = 1;
        for (int[] row : matrix) for (int value : row) hash = 31 * hash + value;
        return hash;
    }

    private static int matrixHash(boolean[][] matrix) {
        int hash = 1;
        for (boolean[] row : matrix) for (boolean value : row) hash = 31 * hash + (value ? 1 : 0);
        return hash;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String safeEventValue(String value) {
        return value == null ? "" : value.replace(",", ";").replace("\n", " ").replace("\r", " ");
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String sanitizeTerminalChat(String value) {
        if (value == null) return "";
        String clean = value.replace("\n", " ").replace("\r", " ");
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    private static final class WorldSnapshot {
        final int hash;
        final int playerX;
        final int playerY;
        final int playerZ;
        final String json;

        WorldSnapshot(int hash, int playerX, int playerY, int playerZ, String json) {
            this.hash = hash;
            this.playerX = playerX;
            this.playerY = playerY;
            this.playerZ = playerZ;
            this.json = json;
        }
    }

    private void resetInput() {
        inputForward = 0.0F;
        inputStrafe = 0.0F;
        inputJump = false;
        inputSprint = false;
        rawForward = false;
        rawBack = false;
        rawLeft = false;
        rawRight = false;
        rawJump = false;
        rawSprint = false;
        rawSneak = false;
        rawAttack = false;
        rawUseItem = false;
        mouseLeftPulse = false;
        mouseRightPulse = false;
    }

    public void finish(String reason) {
        if (!inRun && manifestWriter == null) return;
        String finalReason = reason == null || reason.length() == 0 ? "UNKNOWN" : reason;
        try {
            long tick = minecraft.theWorld == null ? previousWorldTick : minecraft.theWorld.getTotalWorldTime();
            writeEvent(tick, records, "GAME_END", finalReason);
            if (manifestWriter != null) {
                manifestWriter.write("{\"recordType\":\"footer\",\"records\":" + records
                        + ",\"reason\":\"" + escape(finalReason) + "\"}");
                manifestWriter.newLine();
            }
            flushAll();
        } catch (IOException e) {
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER close failed: " + e);
        } finally {
            closeWriter(manifestWriter);
            closeWriter(movementWriter);
            closeWriter(inputWriter);
            closeWriter(worldWriter);
            closeWriter(objectiveWriter);
            closeWriter(navigationWriter);
            closeWriter(monsterWriter);
            closeWriter(mazeWriter);
            closeWriter(inventoryWriter);
            closeWriter(collisionWriter);
            closeWriter(eventWriter);

            System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER finished: "
                    + currentManifest + " records=" + records + " reason=" + finalReason);

            manifestWriter = null;
            movementWriter = null;
            inputWriter = null;
            worldWriter = null;
            objectiveWriter = null;
            navigationWriter = null;
            monsterWriter = null;
            mazeWriter = null;
            inventoryWriter = null;
            collisionWriter = null;
            eventWriter = null;
            currentManifest = null;
            runStamp = null;
            inRun = false;
            pendingEndReason = null;
            resetInput();
        }
    }

    private void closeAllWriters() {
        closeWriter(manifestWriter);
        closeWriter(movementWriter);
        closeWriter(inputWriter);
        closeWriter(worldWriter);
        closeWriter(objectiveWriter);
        closeWriter(navigationWriter);
        closeWriter(monsterWriter);
        closeWriter(mazeWriter);
        closeWriter(inventoryWriter);
        closeWriter(collisionWriter);
        closeWriter(eventWriter);
    }

    private static void closeWriter(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        finish("CLOSED");
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
