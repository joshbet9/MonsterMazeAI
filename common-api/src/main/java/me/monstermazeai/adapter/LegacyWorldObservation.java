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
    public final int[][] maze;
    public final List<Monster> monsters;
    public final String scoreboardTitle;
    public final List<String> scoreboardLines;

    public LegacyWorldObservation(long worldTick, boolean inMonsterMaze, boolean mazeDetected,
                            boolean alive, boolean completed, int stage, int safePadSeconds,
                            int liveSeconds, Player player, Kit kit, int jumpCharges,
                            int abilityCharges, BlockPoint center, Pad pad, int[][] maze,
                            List<Monster> monsters, String scoreboardTitle,
                            List<String> scoreboardLines) {
        if (player == null || kit == null || maze == null || monsters == null
                || scoreboardTitle == null || scoreboardLines == null) {
            throw new IllegalArgumentException("Observation fields must not be null");
        }
        this.worldTick = worldTick;
        this.inMonsterMaze = inMonsterMaze;
        this.mazeDetected = mazeDetected;
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
        this.maze = copyMaze(maze);
        this.monsters = Collections.unmodifiableList(new ArrayList<Monster>(monsters));
        this.scoreboardTitle = scoreboardTitle;
        this.scoreboardLines = Collections.unmodifiableList(new ArrayList<String>(scoreboardLines));
    }

    public LegacyWorldObservation copy() {
        return new LegacyWorldObservation(worldTick, inMonsterMaze, mazeDetected, alive, completed,
                stage, safePadSeconds, liveSeconds, player.copy(), kit, jumpCharges,
                abilityCharges, center == null ? null : center.copy(),
                pad == null ? null : pad.copy(), maze, monsters, scoreboardTitle, scoreboardLines);
    }

    private static int[][] copyMaze(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
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

        public Player copy() {
            return new Player(x,y,z,vx,vy,vz,yaw,pitch,grounded,health,maxHealth);
        }
    }

    public static final class Monster {
        public final int id;
        public final double x, y, z;
        public final double vx, vy, vz;
        public final boolean removed;

        public Monster(int id, double x, double y, double z,
                       double vx, double vy, double vz, boolean removed) {
            this.id=id; this.x=x; this.y=y; this.z=z;
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
