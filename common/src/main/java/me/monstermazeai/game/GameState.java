package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.PlayerState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GameState {
    public static final double PATH_Y = 0.0;
    public static final double PAD_SURFACE_Y = -1.0;

    public long tick;
    public Mode mode = Mode.MODERN;
    public int stage = 1;
    public int mazePattern = -1;
    public int phaseTicksRemaining;
    public int phaseSecondAccumulatorTicks;
    public int liveSeconds;
    public int centerSafeZoneDecay = 11;
    public boolean previewPadRequested;
    public int pendingMonsterSpawns;

    public MazeModel maze;
    public PlayerState player = new PlayerState();
    public Kit kit = Kit.JUMPER;
    public AbilityState ability = new AbilityState();
    public final List<MonsterState> monsters = new ArrayList<>();
    public final List<Cell> oldPads = new ArrayList<>();
    /** Remaining source decay seconds for each inactive SafePad surface. */
    public final Map<Cell, Integer> oldPadDecaySeconds = new HashMap<>();
    public int activePadRow = -1, activePadColumn = -1;
    public int previewPadRow = -1, previewPadColumn = -1;
    public boolean alive = true;
    public boolean completed = false;
    public boolean inMonsterMaze = false;
    public boolean padReached = false;

    public double targetPadX() {
        return activePadRow < 0 ? Double.NaN : activePadRow + 0.5;
    }

    public boolean oldPadContains(PlayerState p) {
        for (Cell pad : oldPads) {
            if (PadModel.isOn(p, pad.row() + 0.5, PAD_SURFACE_Y, pad.column() + 0.5)) return true;
        }
        return false;
    }

    public double targetPadZ() {
        return activePadColumn < 0 ? Double.NaN : activePadColumn + 0.5;
    }

    public GameState copy() {
        GameState s = copyScalarsAndEntities();
        s.maze = maze == null ? null : maze.copy();
        return s;
    }

    /**
     * Simulation copy with the maze model shared.
     *
     * MazeModel is immutable for the AI simulation path: movement, bump and
     * ability simulation only read it. The live adapter must provide a stable
     * observation snapshot before handing the state to the planner. Sharing the
     * 99x99 model avoids copying nearly 20,000 primitive entries for every
     * tactical branch while preserving independent player/monster/ability state.
     */
    public GameState copyForSimulation() {
        GameState s = copyScalarsAndEntities();
        s.maze = maze;
        return s;
    }

    private GameState copyScalarsAndEntities() {
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
        s.oldPads.addAll(oldPads);
        s.oldPadDecaySeconds.putAll(oldPadDecaySeconds);
        for (MonsterState monster : monsters) s.monsters.add(monster.copy());
        return s;
    }
}
