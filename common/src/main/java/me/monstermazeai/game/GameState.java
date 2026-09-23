package me.monstermazeai.game;

import me.monstermazeai.ability.AbilityState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;

import java.util.ArrayList;
import java.util.List;

public final class GameState {
    public long tick;
    public Mode mode = Mode.MODERN;
    public int stage = 1;
    public int phaseTicksRemaining;
    public MazeModel maze;
    public PlayerState player = new PlayerState();
    public Kit kit = Kit.JUMPER;
    public AbilityState ability = new AbilityState();
    public final List<MonsterState> monsters = new ArrayList<>();
    public int activePadRow = -1, activePadColumn = -1;
    public int previewPadRow = -1, previewPadColumn = -1;
    public boolean alive = true;
    public boolean padReached = false;

    public double targetPadX() {
        return activePadRow < 0 ? Double.NaN : activePadRow + 0.5;
    }

    public double targetPadZ() {
        return activePadColumn < 0 ? Double.NaN : activePadColumn + 0.5;
    }

    public GameState copy() {
        GameState s = new GameState();
        s.tick=tick; s.mode=mode; s.stage=stage; s.phaseTicksRemaining=phaseTicksRemaining;
        s.maze=maze; s.player=player.copy(); s.kit=kit;
        s.ability=ability.copy();
        s.activePadRow=activePadRow; s.activePadColumn=activePadColumn;
        s.previewPadRow=previewPadRow; s.previewPadColumn=previewPadColumn;
        s.alive=alive; s.padReached=padReached;
        for (MonsterState monster : monsters) s.monsters.add(monster.copy());
        return s;
    }
}
