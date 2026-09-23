package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.Action;

public interface WorldAdapter {
    WorldObservation observe();
    void execute(Action action);

    default void execute(Action[] actions) {
        for (Action action : actions) execute(action);
    }

    default boolean inMonsterMaze() {
        return observe().state().activePadRow >= 0;
    }

    default Cell activePad() {
        GameState state = observe().state();
        return state.activePadRow < 0 ? null
                : new Cell(state.activePadRow, state.activePadColumn);
    }
}
