package me.monstermazeai.adapter;

import me.monstermazeai.kit.Kit;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Version-neutral binary protocol for the Java-8 Minecraft adapter and the
 * Java-17 AI runtime. Keeping the wire format here prevents either side from
 * depending on Minecraft or Java-17 classes.
 */
public final class LegacyProtocol {
    private static final int VERSION = 1;
    private static final int MAX_MAZE_SIZE = 99;
    private static final int MAX_MONSTERS = 256;

    private LegacyProtocol() {}

    public static void writeObservation(DataOutput out, LegacyWorldObservation o) throws IOException {
        out.writeInt(VERSION);
        out.writeLong(o.worldTick);
        out.writeBoolean(o.inMonsterMaze);
        out.writeBoolean(o.mazeDetected);
        out.writeInt(o.mazePattern);
        out.writeBoolean(o.alive);
        out.writeBoolean(o.completed);
        out.writeInt(o.stage);
        out.writeInt(o.safePadSeconds);
        out.writeInt(o.liveSeconds);

        writePlayer(out, o.player);
        out.writeInt(o.kit.ordinal());
        out.writeInt(o.jumpCharges);
        out.writeInt(o.abilityCharges);

        out.writeBoolean(o.center != null);
        if (o.center != null) {
            out.writeInt(o.center.x); out.writeInt(o.center.y); out.writeInt(o.center.z);
        }

        out.writeBoolean(o.pad != null);
        if (o.pad != null) {
            out.writeInt(o.pad.row); out.writeInt(o.pad.column);
            out.writeDouble(o.pad.distanceSq); out.writeBoolean(o.pad.reached);
        }

        writeMaze(out, o.maze);
        writeString(out, o.scoreboardTitle);
        out.writeInt(o.scoreboardLines.size());
        for (String line : o.scoreboardLines) writeString(out, line);

        out.writeInt(Math.min(MAX_MONSTERS, o.monsters.size()));
        for (int i = 0; i < o.monsters.size() && i < MAX_MONSTERS; i++) {
            LegacyWorldObservation.Monster m = o.monsters.get(i);
            out.writeInt(m.id);
            writeString(out, m.gameplayType);
            writeString(out, m.visualType);
            out.writeDouble(m.x); out.writeDouble(m.y); out.writeDouble(m.z);
            out.writeDouble(m.vx); out.writeDouble(m.vy); out.writeDouble(m.vz);
            out.writeBoolean(m.removed);
        }
    }

    public static LegacyWorldObservation readObservation(DataInput in) throws IOException {
        if (in.readInt() != VERSION) throw new IOException("Unsupported protocol version");
        long tick = in.readLong();
        boolean inMaze = in.readBoolean();
        boolean detected = in.readBoolean();
        int pattern = in.readInt();
        boolean alive = in.readBoolean();
        boolean completed = in.readBoolean();
        int stage = in.readInt();
        int safe = in.readInt();
        int live = in.readInt();

        LegacyWorldObservation.Player player = readPlayer(in);
        int kitOrdinal = in.readInt();
        Kit[] kits = Kit.values();
        if (kitOrdinal < 0 || kitOrdinal >= kits.length) throw new IOException("Invalid kit");
        Kit kit = kits[kitOrdinal];
        int jumpCharges = in.readInt();
        int abilityCharges = in.readInt();

        LegacyWorldObservation.BlockPoint center = null;
        if (in.readBoolean()) center = new LegacyWorldObservation.BlockPoint(
                in.readInt(), in.readInt(), in.readInt());

        LegacyWorldObservation.Pad pad = null;
        if (in.readBoolean()) pad = new LegacyWorldObservation.Pad(
                in.readInt(), in.readInt(), in.readDouble(), in.readBoolean());

        int[][] maze = readMaze(in);
        String title = readString(in);
        int lineCount = readCount(in, 1000, "scoreboard lines");
        List<String> lines = new ArrayList<String>(lineCount);
        for (int i = 0; i < lineCount; i++) lines.add(readString(in));

        int monsterCount = readCount(in, MAX_MONSTERS, "monsters");
        List<LegacyWorldObservation.Monster> monsters = new ArrayList<LegacyWorldObservation.Monster>(monsterCount);
        for (int i = 0; i < monsterCount; i++) {
            int id = in.readInt();
            String gameplay = readString(in);
            String visual = readString(in);
            monsters.add(new LegacyWorldObservation.Monster(id, gameplay, visual,
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble(), in.readBoolean()));
        }

        return new LegacyWorldObservation(tick, inMaze, detected, pattern, alive, completed,
                stage, safe, live, player, kit, jumpCharges, abilityCharges, center, pad,
                maze, monsters, title, lines);
    }

    public static void writeAction(DataOutput out, LegacyAction action) throws IOException {
        LegacyAction a = action == null ? LegacyAction.IDLE : action;
        out.writeDouble(a.forward); out.writeDouble(a.strafe);
        out.writeBoolean(a.jump); out.writeBoolean(a.sprint);
        out.writeFloat(a.yawDelta); out.writeBoolean(a.useAbility);
    }

    public static LegacyAction readAction(DataInput in) throws IOException {
        return new LegacyAction(in.readDouble(), in.readDouble(),
                in.readBoolean(), in.readBoolean(), in.readFloat(), in.readBoolean());
    }

    private static void writePlayer(DataOutput out, LegacyWorldObservation.Player p) throws IOException {
        out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
        out.writeDouble(p.vx); out.writeDouble(p.vy); out.writeDouble(p.vz);
        out.writeFloat(p.yaw); out.writeFloat(p.pitch);
        out.writeBoolean(p.grounded);
        out.writeDouble(p.health); out.writeDouble(p.maxHealth);
    }

    private static LegacyWorldObservation.Player readPlayer(DataInput in) throws IOException {
        return new LegacyWorldObservation.Player(
                in.readDouble(), in.readDouble(), in.readDouble(),
                in.readDouble(), in.readDouble(), in.readDouble(),
                in.readFloat(), in.readFloat(), in.readBoolean(),
                in.readDouble(), in.readDouble());
    }

    private static void writeMaze(DataOutput out, int[][] maze) throws IOException {
        if (maze == null || maze.length > MAX_MAZE_SIZE) throw new IOException("Invalid maze");
        out.writeInt(maze.length);
        for (int[] row : maze) {
            if (row == null || row.length > MAX_MAZE_SIZE) throw new IOException("Invalid maze row");
            out.writeInt(row.length);
            for (int cell : row) out.writeInt(cell);
        }
    }

    private static int[][] readMaze(DataInput in) throws IOException {
        int rows = readCount(in, MAX_MAZE_SIZE, "maze rows");
        int[][] maze = new int[rows][];
        for (int r = 0; r < rows; r++) {
            int columns = readCount(in, MAX_MAZE_SIZE, "maze columns");
            maze[r] = new int[columns];
            for (int c = 0; c < columns; c++) maze[r][c] = in.readInt();
        }
        return maze;
    }

    private static int readCount(DataInput in, int max, String label) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("Invalid " + label + " count: " + count);
        return count;
    }

    private static void writeString(DataOutput out, String value) throws IOException {
        out.writeUTF(value == null ? "" : value);
    }

    private static String readString(DataInput in) throws IOException {
        return in.readUTF();
    }
}
