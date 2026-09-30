package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyWorldObservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.MovementInput;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Optional human-run recorder for Minecraft 1.8.9.
 *
 * F7 toggles recording. Once enabled, a file is opened when a Monster Maze
 * round is detected and one JSON object is written per client tick at END.
 * Movement input is captured from Forge's InputUpdateEvent, which is the
 * point where vanilla has assembled the player's actual keyboard input.
 *
 * The recorder is deliberately independent of the AI runtime: recording a
 * human run must not install or alter movement input and must remain usable
 * with the AI disabled.
 */
public final class HumanRunRecorder implements Closeable {
    private static final String DIRECTORY = "human-runs";
    private static final int JSON_VERSION = 1;

    private final Minecraft minecraft;
    private BufferedWriter writer;
    private BufferedWriter movementWriter;
    private BufferedWriter worldWriter;
    private BufferedWriter monsterWriter;
    private BufferedWriter inputWriter;
    private BufferedWriter eventWriter;
    private Path currentPath;
    private boolean enabled;
    private boolean inRun;
    private boolean rightClickPulse;
    private float inputForward;
    private float inputStrafe;
    private boolean inputJump;
    private boolean inputSprint;
    private float previousYaw = Float.NaN;
    private long previousWorldTick = Long.MIN_VALUE;
    private long records;
    private int lastMazeHash;
    private int lastFloorHash;

    public HumanRunRecorder(Minecraft minecraft) {
        if (minecraft == null) throw new IllegalArgumentException("minecraft");
        this.minecraft = minecraft;
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
        return writer != null;
    }

    public Path currentPath() {
        return currentPath;
    }

    @SubscribeEvent
    public void onInputUpdate(InputUpdateEvent event) {
        if (!enabled || event == null || event.entityPlayer != minecraft.thePlayer) return;
        MovementInput input = event.movementInput;
        if (input == null) return;
        inputForward = input.moveForward;
        inputStrafe = input.moveStrafe;
        inputJump = input.jump;
        inputSprint = minecraft.gameSettings.keyBindSprint.isKeyDown();
    }

    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        if (!enabled || event == null) return;
        if (Mouse.getEventButton() == 1 && Mouse.getEventButtonState()) {
            rightClickPulse = true;
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

        LegacyWorldObservation state = new Minecraft18Observer().observe().state;
        if (!state.inMonsterMaze) {
            if (inRun) finish(state.completed ? "COMPLETED" : "LEFT_MAZE");
            resetInput();
            return;
        }

        if (!inRun) {
            begin(state);
        }
        write(state);
    }

    private void begin(LegacyWorldObservation state) {
        try {
            Path directory = Paths.get(DIRECTORY);
            Files.createDirectories(directory);
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            currentPath = directory.resolve("human-speed-run-" + timestamp + "-manifest.json");
            Path movementPath = directory.resolve("human-speed-run-" + timestamp + "-movement.jsonl");
            Path worldPath = directory.resolve("human-speed-run-" + timestamp + "-world.jsonl");
            Path monsterPath = directory.resolve("human-speed-run-" + timestamp + "-monsters.jsonl");
            Path inputPath = directory.resolve("human-speed-run-" + timestamp + "-input.jsonl");
            Path eventPath = directory.resolve("human-speed-run-" + timestamp + "-events.jsonl");
            writer = Files.newBufferedWriter(currentPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            movementWriter = open(directory, movementPath);
            worldWriter = open(directory, worldPath);
            monsterWriter = open(directory, monsterPath);
            inputWriter = open(directory, inputPath);
            eventWriter = open(directory, eventPath);
            inRun = true;
            records = 0L;
            lastMazeHash = 0;
            lastFloorHash = 0;
            previousYaw = Float.NaN;
            previousWorldTick = Long.MIN_VALUE;
            System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER started: " + currentPath.toAbsolutePath());
            writeHeader(state);
        } catch (IOException e) {
            writer = null; movementWriter = null; worldWriter = null; monsterWriter = null; inputWriter = null; eventWriter = null;
            currentPath = null;
            inRun = false;
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER failed to open: " + e);
        }
    }

    private void writeHeader(LegacyWorldObservation state) throws IOException {
        writer.write("{\"recordType\":\"manifest\",\"jsonVersion\":" + JSON_VERSION
                + ",\"minecraftVersion\":\"1.8.9\",\"mode\":\"human\",\"startedWorldTick\":"
                + state.worldTick + ",\"files\":[\"movement.jsonl\",\"world.jsonl\",\"monsters.jsonl\",\"input.jsonl\",\"events.jsonl\"],\"note\":\"No player identity or chat data is recorded.\"}");
        writer.newLine();
    }

    private void write(LegacyWorldObservation state) {
        try {
            float yawDelta = Float.isNaN(previousYaw) ? 0.0f : wrapDegrees(state.player.yaw - previousYaw);
            long dt = previousWorldTick == Long.MIN_VALUE ? 1L : Math.max(1L, state.worldTick - previousWorldTick);
            String prefix = "{\\"tick\\":" + state.worldTick + ",\\"stage\\":" + state.stage + ",\\"recordIndex\\":" + records;

            writeLine(movementWriter, prefix + ",\\"x\\":" + state.player.x + ",\\"y\\":" + state.player.y + ",\\"z\\":" + state.player.z + ",\\"vx\\":" + state.player.vx + ",\\"vy\\":" + state.player.vy + ",\\"vz\\":" + state.player.vz + ",\\"yaw\\":" + state.player.yaw + ",\\"pitch\\":" + state.player.pitch + ",\\"grounded\\":" + state.player.grounded + "}");
            writeLine(inputWriter, prefix + ",\\"forward\\":" + inputForward + ",\\"strafe\\":" + inputStrafe + ",\\"jump\\":" + inputJump + ",\\"sprintKey\\":" + inputSprint + ",\\"yawDelta\\":" + yawDelta + ",\\"yawDeltaWithin30\\":" + (Math.abs(yawDelta) <= 30.0001f) + ",\\"useAbility\\":" + rightClickPulse + "}");
            writeLine(worldWriter, prefix + ",\\"dt\\":" + dt + ",\\"phaseTimerSeconds\\":" + state.safePadSeconds + ",\\"liveSeconds\\":" + state.liveSeconds + ",\\"inMaze\\":" + state.inMonsterMaze + ",\\"alive\\":" + state.alive + ",\\"completed\\":" + state.completed + ",\\"mazeDetected\\":" + state.mazeDetected + ",\\"mazePattern\\":\\"" + escape(state.mazePattern) + "\\",\\"kit\\":\\"" + escape(state.kit == null ? "" : state.kit.name()) + "\\",\\"jumpCharges\\":" + state.jumpCharges + ",\\"abilityCharges\\":" + state.abilityCharges + ",\\"currentCell\\":" + cellFor(state) + ",\\"activePad\\":" + padJson(state) + "}");
            writeMonsters(state, prefix);
            if (state.pad != null && state.pad.reached) writeLine(eventWriter, prefix + ",\\"event\\":\\"PAD_REACHED\\",\\"padRow\\":" + state.pad.row + ",\\"padColumn\\":" + state.pad.column + "}");
            if (state.stage != 0 && (records == 0 || state.stage != lastStage)) writeLine(eventWriter, prefix + ",\\"event\\":\\"STAGE_CHANGED\\",\\"stage\\":" + state.stage + "}");
            if (records % 20L == 0L) flushAll();
            records++; previousYaw = state.player.yaw; previousWorldTick = state.worldTick; rightClickPulse = false; lastStage = state.stage;
        } catch (IOException e) { System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER write failed: " + e); finish("WRITE_ERROR"); }
    }

    private int lastStage = Integer.MIN_VALUE;
    private BufferedWriter open(Path directory, Path path) throws IOException { return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); }
    private static void writeLine(BufferedWriter w, String line) throws IOException { w.write(line); w.newLine(); }
    private void flushAll() throws IOException { writer.flush(); movementWriter.flush(); worldWriter.flush(); monsterWriter.flush(); inputWriter.flush(); eventWriter.flush(); }
    private String padJson(LegacyWorldObservation s) { if (s.pad == null) return "null"; return "{\\"row\\":"+s.pad.row+",\\"column\\":"+s.pad.column+",\\"distanceSq\\":"+s.pad.distanceSq+",\\"reached\\":"+s.pad.reached+"}"; }
    private void writeMonsters(LegacyWorldObservation s, String prefix) throws IOException { for (LegacyWorldObservation.Monster m : s.monsters) writeLine(monsterWriter, prefix + ",\\"id\\":"+m.id+",\\"gameplayType\\":\\""+escape(m.gameplayType)+"\\",\\"visualType\\":\\""+escape(m.visualType)+"\\",\\"x\\":"+m.x+",\\"y\\":"+m.y+",\\"z\\":"+m.z+",\\"vx\\":"+m.vx+",\\"vy\\":"+m.vy+",\\"vz\\":"+m.vz+",\\"removed\\":"+m.removed+"}"); }

    private String toJson(LegacyWorldObservation s, float yawDelta, long dt) {
        StringBuilder b = new StringBuilder(8192);
        b.append("{\"recordType\":\"tick\",\"jsonVersion\":").append(JSON_VERSION)
                .append(",\"tick\":").append(s.worldTick)
                .append(",\"dt\":").append(dt)
                .append(",\"stage\":").append(s.stage)
                .append(",\"phaseTimerSeconds\":").append(s.safePadSeconds)
                .append(",\"liveSeconds\":").append(s.liveSeconds)
                .append(",\"inMaze\":").append(s.inMonsterMaze)
                .append(",\"alive\":").append(s.alive)
                .append(",\"completed\":").append(s.completed)
                .append(",\"mazeDetected\":").append(s.mazeDetected)
                .append(",\"mazePattern\":").append(s.mazePattern);

        b.append(",\"player\":{\"x\":").append(s.player.x)
                .append(",\"y\":").append(s.player.y)
                .append(",\"z\":").append(s.player.z)
                .append(",\"vx\":").append(s.player.vx)
                .append(",\"vy\":").append(s.player.vy)
                .append(",\"vz\":").append(s.player.vz)
                .append(",\"yaw\":").append(s.player.yaw)
                .append(",\"pitch\":").append(s.player.pitch)
                .append(",\"grounded\":").append(s.player.grounded)
                .append(",\"health\":").append(s.player.health)
                .append(",\"maxHealth\":").append(s.player.maxHealth).append("}");

        b.append(",\"input\":{\"forward\":").append(inputForward)
                .append(",\"strafe\":").append(inputStrafe)
                .append(",\"jump\":").append(inputJump)
                .append(",\"sprint\":").append(inputSprint)
                .append(",\"yawDelta\":").append(yawDelta)
                .append(",\"yawDeltaWithinActionLimit\":").append(Math.abs(yawDelta) <= 30.0001f)
                .append(",\"useAbility\":").append(rightClickPulse).append("}");

        b.append(",\"kit\":\"").append(escape(s.kit == null ? "" : s.kit.name()))
                .append("\",\"jumpCharges\":").append(s.jumpCharges)
                .append(",\"abilityCharges\":").append(s.abilityCharges);

        if (s.center == null) {
            b.append(",\"center\":null");
        } else {
            b.append(",\"center\":{\"x\":").append(s.center.x)
                    .append(",\"y\":").append(s.center.y)
                    .append(",\"z\":").append(s.center.z).append("}");
        }

        if (s.pad == null) {
            b.append(",\"activePad\":null");
        } else {
            b.append(",\"activePad\":{\"row\":").append(s.pad.row)
                    .append(",\"column\":").append(s.pad.column)
                    .append(",\"distanceSq\":").append(s.pad.distanceSq)
                    .append(",\"reached\":").append(s.pad.reached).append("}");
        }

        b.append(",\"monsters\":[");
        for (int i = 0; i < s.monsters.size(); i++) {
            if (i > 0) b.append(",");
            LegacyWorldObservation.Monster m = s.monsters.get(i);
            b.append("{\"id\":").append(m.id)
                    .append(",\"gameplayType\":\"").append(escape(m.gameplayType))
                    .append("\",\"visualType\":\"").append(escape(m.visualType))
                    .append("\",\"x\":").append(m.x)
                    .append(",\"y\":").append(m.y)
                    .append(",\"z\":").append(m.z)
                    .append(",\"vx\":").append(m.vx)
                    .append(",\"vy\":").append(m.vy)
                    .append(",\"vz\":").append(m.vz)
                    .append(",\"removed\":").append(m.removed).append("}");
        }
        b.append("]");

        int mazeHash = matrixHash(s.maze);
        int floorHash = matrixHash(s.physicalFloor);
        if (records == 0L || mazeHash != lastMazeHash) {
            b.append(",\"maze\":").append(intMatrix(s.maze));
            lastMazeHash = mazeHash;
        }
        if (records == 0L || floorHash != lastFloorHash) {
            b.append(",\"physicalFloor\":").append(booleanMatrix(s.physicalFloor));
            lastFloorHash = floorHash;
        }

        b.append(",\"derived\":{\"currentCell\":")
                .append(cellFor(s))
                .append(",\"distanceToActivePad\":")
                .append(s.pad == null ? "null" : Math.sqrt(Math.max(0.0, s.pad.distanceSq)))
                .append(",\"recordIndex\":").append(records).append("}");

        b.append("}");
        return b.toString();
    }

    private String cellFor(LegacyWorldObservation s) {
        if (s.center == null) return "null";
        int row = (int)Math.floor(s.player.x - (s.center.x - 49));
        int col = (int)Math.floor(s.player.z - (s.center.z - 49));
        return "{\"row\":" + row + ",\"column\":" + col + "}";
    }

    private static String intMatrix(int[][] matrix) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < matrix.length; i++) {
            if (i > 0) b.append(",");
            b.append("[");
            for (int j = 0; j < matrix[i].length; j++) {
                if (j > 0) b.append(",");
                b.append(matrix[i][j]);
            }
            b.append("]");
        }
        return b.append("]").toString();
    }

    private static String booleanMatrix(boolean[][] matrix) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < matrix.length; i++) {
            if (i > 0) b.append(",");
            b.append("[");
            for (int j = 0; j < matrix[i].length; j++) {
                if (j > 0) b.append(",");
                b.append(matrix[i][j]);
            }
            b.append("]");
        }
        return b.append("]").toString();
    }

    private static int matrixHash(int[][] matrix) {
        int hash = 1;
        for (int[] row : matrix) {
            for (int value : row) hash = 31 * hash + value;
        }
        return hash;
    }

    private static int matrixHash(boolean[][] matrix) {
        int hash = 1;
        for (boolean[] row : matrix) {
            for (boolean value : row) hash = 31 * hash + (value ? 1 : 0);
        }
        return hash;
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void resetInput() {
        inputForward = 0.0f;
        inputStrafe = 0.0f;
        inputJump = false;
        inputSprint = false;
        rightClickPulse = false;
        previousYaw = Float.NaN;
        previousWorldTick = Long.MIN_VALUE;
    }

    public void finish(String reason) {
        if (writer == null) return;
        try {
            writer.write("{\"recordType\":\"footer\",\"reason\":\""
                    + escape(reason) + "\",\"records\":" + records + "}");
            writer.newLine();
            writer.flush();
            flushAll();
            writer.close(); movementWriter.close(); worldWriter.close(); monsterWriter.close(); inputWriter.close(); eventWriter.close();
        } catch (IOException e) {
            System.err.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER close failed: " + e);
        } finally {
            System.out.println("[MonsterMazeAI/1.8] HUMAN RUN RECORDER finished: "
                    + currentPath + " records=" + records + " reason=" + reason);
            writer = null;
            currentPath = null;
            inRun = false;
            resetInput();
        }
    }

    @Override
    public void close() {
        finish("CLOSED");
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
