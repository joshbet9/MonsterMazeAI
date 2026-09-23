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
}
