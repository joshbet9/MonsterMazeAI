package me.monstermazeai.adapter;

import me.monstermazeai.kit.Kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Version-neutral observation DTO shared by legacy Minecraft adapters and the
 * modern AI stack. Deliberately limited to Java 8 language features.
 */
public final class LegacyWorldObservation {
    public final long worldTick;
    public final boolean inMonsterMaze;
    public final boolean mazeDetected;
    /** Source layout number (1-3), or -1 when not identified. */
    public final int mazePattern;
    public final boolean alive;
    public final boolean completed;
    public final int stage;
    public final int safePadSeconds;
    public final int liveSeconds;
    public final Player player;
    public final Kit kit;
    public final int jumpCharges;
    public final int abilityCharges;
    public final BlockPoint center;
    public final Pad pad;
    /** Next Safe Pad preview exposed by the source at the end of each phase. */
    public final Pad previewPad;
    public final int[][] maze;
    /** Physical player floor observed in the live world; separate from logical monster waypoints. */
    public final boolean[][] physicalFloor;
    public final List<Monster> monsters;
    public final String scoreboardTitle;
    public final List<String> scoreboardLines;

    public LegacyWorldObservation(long worldTick, boolean inMonsterMaze, boolean mazeDetected,
                            boolean alive, boolean completed, int stage, int safePadSeconds,
                            int liveSeconds, Player player, Kit kit, int jumpCharges,
                            int abilityCharges, BlockPoint center, Pad pad, int[][] maze,
                            List<Monster> monsters, String scoreboardTitle,
                            List<String> scoreboardLines) {
        this(worldTick, inMonsterMaze, mazeDetected, -1, alive, completed, stage,
                safePadSeconds, liveSeconds, player, kit, jumpCharges, abilityCharges,
                center, pad, null, maze, defaultPhysicalFloor(maze), monsters, scoreboardTitle, scoreboardLines);
    }

    public LegacyWorldObservation(long worldTick, boolean inMonsterMaze, boolean mazeDetected,
                            int mazePattern, boolean alive, boolean completed, int stage,
                            int safePadSeconds, int liveSeconds, Player player, Kit kit,
                            int jumpCharges, int abilityCharges, BlockPoint center, Pad pad,
                            int[][] maze, List<Monster> monsters, String scoreboardTitle,
                            List<String> scoreboardLines) {
        this(worldTick, inMonsterMaze, mazeDetected, mazePattern, alive, completed, stage,
                safePadSeconds, liveSeconds, player, kit, jumpCharges, abilityCharges,
                center, pad, null, maze, defaultPhysicalFloor(maze), monsters, scoreboardTitle, scoreboardLines);
    }

    public LegacyWorldObservation(long worldTick, boolean inMonsterMaze, boolean mazeDetected,
                            int mazePattern, boolean alive, boolean completed, int stage,
                            int safePadSeconds, int liveSeconds, Player player, Kit kit,
                            int jumpCharges, int abilityCharges, BlockPoint center, Pad pad,
                            Pad previewPad, int[][] maze, List<Monster> monsters,
                            String scoreboardTitle, List<String> scoreboardLines) {
        this(worldTick, inMonsterMaze, mazeDetected, mazePattern, alive, completed, stage,
                safePadSeconds, liveSeconds, player, kit, jumpCharges, abilityCharges,
                center, pad, previewPad, maze, defaultPhysicalFloor(maze), monsters,
                scoreboardTitle, scoreboardLines);
    }

    public LegacyWorldObservation(long worldTick, boolean inMonsterMaze, boolean mazeDetected,
                            int mazePattern, boolean alive, boolean completed, int stage,
                            int safePadSeconds, int liveSeconds, Player player, Kit kit,
                            int jumpCharges, int abilityCharges, BlockPoint center, Pad pad,
                            Pad previewPad, int[][] maze, boolean[][] physicalFloor, List<Monster> monsters,
                            String scoreboardTitle, List<String> scoreboardLines) {
        if (player == null || kit == null || maze == null || physicalFloor == null || monsters == null
                || scoreboardTitle == null || scoreboardLines == null) {
            throw new IllegalArgumentException("Observation fields must not be null");
        }
        this.worldTick = worldTick;
        this.inMonsterMaze = inMonsterMaze;
        this.mazeDetected = mazeDetected;
        this.mazePattern = mazePattern;
        this.alive = alive;
        this.completed = completed;
        this.stage = stage;
        this.safePadSeconds = safePadSeconds;
        this.liveSeconds = liveSeconds;
        this.player = player;
        this.kit = kit;
        this.jumpCharges = jumpCharges;
        this.abilityCharges = abilityCharges;
        this.center = center;
        this.pad = pad;
        this.previewPad = previewPad;
        this.maze = copyMaze(maze);
        this.physicalFloor = copyPhysicalFloor(physicalFloor);
        this.monsters = Collections.unmodifiableList(new ArrayList<Monster>(monsters));
        this.scoreboardTitle = scoreboardTitle;
        this.scoreboardLines = Collections.unmodifiableList(new ArrayList<String>(scoreboardLines));
    }

    public LegacyWorldObservation copy() {
        return new LegacyWorldObservation(worldTick, inMonsterMaze, mazeDetected, mazePattern, alive, completed,
                stage, safePadSeconds, liveSeconds, player.copy(), kit, jumpCharges,
                abilityCharges, center == null ? null : center.copy(),
                pad == null ? null : pad.copy(), previewPad == null ? null : previewPad.copy(), maze, physicalFloor, monsters, scoreboardTitle, scoreboardLines);
    }

    private static boolean[][] defaultPhysicalFloor(int[][] source) {
        boolean[][] floor = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            floor[i] = new boolean[source[i].length];
            for (int j = 0; j < source[i].length; j++) floor[i][j] = source[i][j] != 0;
        }
        return floor;
    }

    private static boolean[][] copyPhysicalFloor(boolean[][] source) {
        boolean[][] copy = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) copy[i] = source[i].clone();
        return copy;
    }

    private static int[][] copyMaze(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) copy[i] = source[i].clone();
        return copy;
    }

    public static final class Player {
        public final double x, y, z;
        public final double vx, vy, vz;
        public final float yaw, pitch;
        public final boolean grounded;
        public final double health, maxHealth;

        public Player(double x, double y, double z, double vx, double vy, double vz,
                      float yaw, float pitch, boolean grounded, double health, double maxHealth) {
            this.x=x; this.y=y; this.z=z;
            this.vx=vx; this.vy=vy; this.vz=vz;
            this.yaw=yaw; this.pitch=pitch; this.grounded=grounded;
            this.health=health; this.maxHealth=maxHealth;
        }

        public Player copy() { return new Player(x,y,z,vx,vy,vz,yaw,pitch,grounded,health,maxHealth); }
    }

    public static final class Monster {
        public final int id;
        /**
         * Stable gameplay abstraction: every Monster Maze ghost is the same
         * underlying maze monster regardless of its client-side skin.
         */
        public final String gameplayType;
        /** Source MobTypes id describing the client-visible visual skin. */
        public final String visualType;
        /** Backwards-compatible alias for visualType. */
        public final String type;
        public final double x, y, z;
        public final double vx, vy, vz;
        public final boolean removed;

        public Monster(int id, double x, double y, double z,
                       double vx, double vy, double vz, boolean removed) {
            this(id, "monster_maze_monster", "", x, y, z, vx, vy, vz, removed);
        }

        public Monster(int id, String type, double x, double y, double z,
                       double vx, double vy, double vz, boolean removed) {
            this(id, "monster_maze_monster", type, x, y, z, vx, vy, vz, removed);
        }

        public Monster(int id, String gameplayType, String visualType,
                       double x, double y, double z, double vx, double vy, double vz,
                       boolean removed) {
            if (gameplayType == null || visualType == null) {
                throw new IllegalArgumentException("Monster types must not be null");
            }
            this.id=id;
            this.gameplayType=gameplayType;
            this.visualType=visualType;
            this.type=visualType;
            this.x=x; this.y=y; this.z=z;
            this.vx=vx; this.vy=vy; this.vz=vz; this.removed=removed;
        }
    }

    public static final class BlockPoint {
        public final int x, y, z;
        public BlockPoint(int x, int y, int z) { this.x=x; this.y=y; this.z=z; }
        public BlockPoint copy() { return new BlockPoint(x,y,z); }
    }

    public static final class Pad {
        public final int row, column;
        public final double distanceSq;
        public final boolean reached;
        public Pad(int row, int column, double distanceSq, boolean reached) {
            this.row=row; this.column=column; this.distanceSq=distanceSq; this.reached=reached;
        }
        public Pad copy() { return new Pad(row,column,distanceSq,reached); }
    }
}
