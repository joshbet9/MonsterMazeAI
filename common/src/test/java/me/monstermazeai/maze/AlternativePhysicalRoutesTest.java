package me.monstermazeai.maze;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativePhysicalRoutesTest {
    @Test
    void disconnectedGoalReturnsNoAlternatives() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[0][0] = 1;
        raw[4][4] = 1;

        MazeModel maze = new MazeModel(raw);

        assertTrue(new AlternativePhysicalRoutes()
                        .generate(maze, new Cell(0, 0), new Cell(4, 4), 3)
                        .isEmpty());
    }
}
