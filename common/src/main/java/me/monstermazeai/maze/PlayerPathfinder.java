package me.monstermazeai.maze;

import java.util.*;

 /**
  * Shortest physical route for the player.
  *
  * Monster waypoint disabling is intentionally ignored here: the live game
  * disables maze waypoints around Safe Pads and during center decay, but those
  * cells remain physical floor that a player can stand and move across.
  */
public final class PlayerPathfinder {
    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        if (!maze.isRawPath(start.row(), start.column())
                || !maze.isRawPath(goal.row(), goal.column())) {
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
            add(maze, new Cell(r - 1, c), queue, previous);
            add(maze, new Cell(r + 1, c), queue, previous);
            add(maze, new Cell(r, c - 1), queue, previous);
            add(maze, new Cell(r, c + 1), queue, previous);
        }

        return List.of();
    }

    private void add(MazeModel maze, Cell next, ArrayDeque<Cell> queue,
                     Map<Cell, Cell> previous) {
        if (next.row() < 0 || next.row() >= MazeModel.SIZE
                || next.column() < 0 || next.column() >= MazeModel.SIZE) return;
        if (!maze.isRawPath(next.row(), next.column())
                || previous.containsKey(next)) return;
        previous.put(next, queue.peekLast());
        queue.addLast(next);
    }

    private List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }
}
