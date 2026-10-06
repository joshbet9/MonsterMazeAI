package me.monstermazeai.maze;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MazeModelTest {
    private static MazeModel maze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++) {
            for (int c = 0; c < MazeModel.SIZE; c++) {
                raw[r][c] = 1;
            }
        }
        return new MazeModel(raw);
    }

    @Test
    void dynamicSignatureChangesOnlyWhenDynamicStateChanges() {
        MazeModel maze = maze();
        long initial = maze.dynamicSignature();

        maze.setDisabled(10, 11, true);
        long disabled = maze.dynamicSignature();
        assertNotEquals(initial, disabled);

        maze.setDisabled(10, 11, true);
        assertEquals(disabled, maze.dynamicSignature());

        maze.setDisabled(10, 11, false);
        assertEquals(initial, maze.dynamicSignature());
    }

    @Test
    void copiedMazePreservesDynamicSignature() {
        MazeModel maze = maze();
        maze.setPhysicalFloor(20, 21, false);
        maze.setDisabled(22, 23, true);

        MazeModel copy = maze.copy();

        assertEquals(maze.dynamicSignature(), copy.dynamicSignature());
        assertEquals(maze.isPhysicalFloor(20, 21), copy.isPhysicalFloor(20, 21));
        assertEquals(maze.isDisabled(22, 23), copy.isDisabled(22, 23));
    }
}
