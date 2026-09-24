package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;

import java.util.ArrayList;
import java.util.List;

public final class GameState {
    /**
     * Common-simulator Y origin.
     *
     * The real 1.8 maze path points are at the maze center Y. The player stands
     * with feet at that same Y, while SafePad's surface is one block below it.
     */
    public static final double PATH_Y = 0.0;
    public static final double PAD_SURFACE_Y = -1.0;

    public long tick;
    public Mode mode = Mode.MODERN;
    public int stage = 1;
    /** Source Monster Maze layout number (1-3), or -1 when not identified. */
    public int mazePattern = -1;
    /** Remaining phase time represented in simulation ticks (20 ticks = 1 second). */
    public int phaseTicksRemaining;
    /** Counts simulation ticks toward the server's once-per-second phase task. */
    public int phaseSecondAccumulatorTicks;
    /** Seconds elapsed since the live game began. */
    public int liveSeconds;
    /** Source center deterioration counter: starts at 11 and decrements after 20 live seconds. */
    public int centerSafeZoneDecay = 11;
    /** Set when the source reaches the 2-second preview-pad event. */
    public boolean previewPadRequested;
    /** Number of newly spawned monsters the adapter must create at the next wave event. */
    public int pendingMonsterSpawns;

    public MazeModel maze;
    public PlayerState player = new PlayerState();
    public Kit kit = Kit.JUMPER;
    public AbilityState ability = new AbilityState();
    public final List<MonsterState> monsters = new ArrayList<>();
    public int activePadRow = -1, activePadColumn = -1;
    public int previewPadRow = -1, previewPadColumn = -1;
    public boolean alive = true;
    public boolean completed = false;
    /** True when the live adapter positively identifies a Monster Maze match. */
    public boolean inMonsterMaze = false;
    public boolean padReached = false;

    public double targetPadX() {
        return activePadRow < 0 ? Double.NaN : activePadRow + 0.5;
    }

    public double targetPadZ() {
        return activePadColumn < 0 ? Double.NaN : activePadColumn + 0.5;
    }

    public GameState copy() {
        GameState s = new GameState();
        s.tick=tick;
        s.mode=mode;
        s.stage=stage;
        s.mazePattern=mazePattern;
        s.phaseTicksRemaining=phaseTicksRemaining;
        s.phaseSecondAccumulatorTicks=phaseSecondAccumulatorTicks;
        s.liveSeconds=liveSeconds;
        s.centerSafeZoneDecay=centerSafeZoneDecay;
        s.previewPadRequested=previewPadRequested;
        s.pendingMonsterSpawns=pendingMonsterSpawns;
        s.maze=maze == null ? null : maze.copy();
        s.player=player.copy();
        s.kit=kit;
        s.ability=ability.copy();
        s.activePadRow=activePadRow;
        s.activePadColumn=activePadColumn;
        s.previewPadRow=previewPadRow;
        s.previewPadColumn=previewPadColumn;
        s.alive=alive;
        s.completed=completed;
        s.inMonsterMaze=inMonsterMaze;
        s.padReached=padReached;
        for (MonsterState monster : monsters) s.monsters.add(monster.copy());
        return s;
    }
}
