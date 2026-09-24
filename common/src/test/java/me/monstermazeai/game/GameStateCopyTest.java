package me.monstermazeai.game;

import me.monstermazeai.maze.MazeModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameStateCopyTest {
    @Test
    void copiedStateOwnsMazeDynamicFlags() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][11] = 1;

        GameState original = new GameState();
        original.maze = new MazeModel(raw);
        original.maze.setDisabled(10, 11, true);
        original.completed = true;

        GameState copy = original.copy();
        assertNotSame(original.maze, copy.maze);
        assertTrue(copy.maze.isDisabled(10, 11));
        assertTrue(copy.completed);

        copy.maze.setDisabled(10, 11, false);
        assertTrue(original.maze.isDisabled(10, 11));
    }
}
