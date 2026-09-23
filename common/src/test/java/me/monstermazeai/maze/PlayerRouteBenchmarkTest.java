package me.monstermazeai.maze;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PlayerRouteBenchmarkTest {
    @Test
    void evaluatesOneHundredDeterministicRoutes() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;

        MazeModel maze = new MazeModel(raw);
        PlayerPathfinder pathfinder = new PlayerPathfinder();
        Random random = new Random(20260923L);

        long totalCells = 0;
        for (int i = 0; i < 100; i++) {
            Cell start = new Cell(random.nextInt(99), random.nextInt(99));
            Cell goal = new Cell(random.nextInt(99), random.nextInt(99));
            var route = pathfinder.shortestPath(maze, start, goal);

            assertFalse(route.isEmpty());
            assertEquals(start, route.get(0));
            assertEquals(goal, route.get(route.size() - 1));
            totalCells += route.size();
        }

        assertTrue(totalCells > 100);
    }
}
