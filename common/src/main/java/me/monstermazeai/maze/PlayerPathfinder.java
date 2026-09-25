package me.monstermazeai.maze;

import java.util.*;

/**
 * Shortest physical route for the player.
 *
 * The live game has two different notions of walkability:
 * - monster waypoints use the logical maze topology (1/2/5/6);
 * - the player can physically stand on every non-air maze cell, including the
 *   central safe area (3/4) and temporarily disabled Safe Pad cells.
 *
 * Player routing must therefore ignore logical waypoint disabling and use the
 * physical floor represented by any non-zero source layout cell.
 */
public final class PlayerPathfinder {
    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        if (!isPhysicalFloor(maze, start) || !isPhysicalFloor(maze, goal)) {
            return List.of();
        }

        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) return reconstruct(previous, goal);

            int r = current.row(), c = current.column();
            add(maze, current, new Cell(r - 1, c), queue, previous);
            add(maze, current, new Cell(r + 1, c), queue, previous);
            add(maze, current, new Cell(r, c - 1), queue, previous);
            add(maze, current, new Cell(r, c + 1), queue, previous);
        }

        return List.of();
    }

    private boolean isPhysicalFloor(MazeModel maze, Cell cell) {
        return cell.row() >= 0 && cell.row() < MazeModel.SIZE
                && cell.column() >= 0 && cell.column() < MazeModel.SIZE
                && (maze.raw(cell.row(), cell.column()) != 0
                    || maze.isDisabled(cell.row(), cell.column()));
    }

    private void add(MazeModel maze, Cell current, Cell next, ArrayDeque<Cell> queue,
                     Map<Cell, Cell> previous) {
        if (!isPhysicalFloor(maze, next) || previous.containsKey(next)) return;
        previous.put(next, current);
        queue.addLast(next);
    }

    private List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }
}
