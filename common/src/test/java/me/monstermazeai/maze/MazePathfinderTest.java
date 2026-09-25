package me.monstermazeai.maze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MazePathfinderTest {
    private MazeModel openMaze() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        for (int r = 0; r < MazeModel.SIZE; r++)
            for (int c = 0; c < MazeModel.SIZE; c++)
                raw[r][c] = 1;
        return new MazeModel(raw);
    }

    @Test
    void shortestCardinalPath() {
        MazeModel maze = openMaze();
        List<Cell> path = new MazePathfinder().shortestPath(
                maze, new Cell(0, 0), new Cell(2, 2));
        assertEquals(5, path.size());
    }

    @Test
    void disabledMonsterWaypointIsAvoided() {
        MazeModel maze = openMaze();
        maze.setDisabled(1, 1, true);
        List<Cell> path = new MazePathfinder().shortestPath(
                maze, new Cell(0, 0), new Cell(2, 2));
        assertFalse(path.contains(new Cell(1, 1)));
    }

    @Test
    void playerCanCrossDisabledPhysicalFloor() {
        MazeModel maze = openMaze();
        maze.setDisabled(1, 1, true);
        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(0, 1), new Cell(2, 1));
        assertEquals(3, path.size());
        assertEquals(new Cell(1, 1), path.get(1));
    }

    @Test
    void playerCanLeaveCentralSafeArea() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[49][49] = 3;
        raw[49][50] = 4;
        raw[49][51] = 5;
        MazeModel maze = new MazeModel(raw);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(49, 49), new Cell(49, 51));

        assertEquals(List.of(
                new Cell(49, 49), new Cell(49, 50), new Cell(49, 51)), path);
    }

    @Test
    void playerCanReachSafePadWhenUnderlyingLayoutIsAir() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[49][48] = 1;
        raw[49][49] = 0;
        MazeModel maze = new MazeModel(raw);
        maze.setDisabled(49, 49, true);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(49, 48), new Cell(49, 49));

        assertEquals(List.of(new Cell(49, 48), new Cell(49, 49)), path);
    }

    @Test
    void playerCanReachDisabledSafePadFloor() {
        MazeModel maze = openMaze();
        for (int r = 47; r <= 51; r++) {
            for (int c = 47; c <= 51; c++) {
                maze.setDisabled(r, c, true);
            }
        }

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(46, 49), new Cell(49, 49));

        assertEquals(4, path.size());
        assertEquals(new Cell(49, 49), path.get(path.size() - 1));
    }
}
