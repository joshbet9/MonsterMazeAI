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
    void equalLengthRoutesPreferFewerHeadingChanges() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];

        // Route A: E,E,E,E,S,S,S,S — 8 edges, 1 turn.
        for (int c = 10; c <= 14; c++) raw[10][c] = 1;
        for (int r = 10; r <= 14; r++) raw[r][14] = 1;

        // Route B: S,S,E,E,S,S,E,E — 8 edges, 3 turns.
        raw[11][10] = 1;
        raw[12][10] = 1;
        raw[12][11] = 1;
        raw[12][12] = 1;
        raw[13][12] = 1;
        raw[14][12] = 1;
        raw[14][13] = 1;

        MazeModel maze = new MazeModel(raw);
        List<Cell> path = new PlayerPathfinder().shortestPathWithoutGaps(
                maze, new Cell(10, 10), new Cell(14, 14));

        assertEquals(9, path.size());
        assertEquals(new Cell(10, 14), path.get(4));
        assertEquals(new Cell(14, 14), path.get(8));
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
    void playerCannotReachAirEvenWhenWaypointIsDisabled() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[49][48] = 1;
        raw[49][49] = 0;
        MazeModel maze = new MazeModel(raw);
        maze.setDisabled(49, 49, true);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(49, 48), new Cell(49, 49));

        assertTrue(path.isEmpty());
    }

    @Test
    void playerCanRouteAcrossExactlyOneMissingCellAsAJumpEdge() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][10] = 1;
        raw[10][12] = 1;
        MazeModel maze = new MazeModel(raw);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(10, 10), new Cell(10, 12));

        assertEquals(List.of(new Cell(10, 10), new Cell(10, 12)), path);
    }

    @Test
    void playerDoesNotRouteAcrossTwoMissingCells() {
        int[][] raw = new int[MazeModel.SIZE][MazeModel.SIZE];
        raw[10][10] = 1;
        raw[10][13] = 1;
        MazeModel maze = new MazeModel(raw);

        List<Cell> path = new PlayerPathfinder().shortestPath(
                maze, new Cell(10, 10), new Cell(10, 13));

        assertTrue(path.isEmpty());
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
