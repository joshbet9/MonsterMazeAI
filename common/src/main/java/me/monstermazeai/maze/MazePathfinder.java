package me.monstermazeai.maze;

import java.util.*;

public final class MazePathfinder {
    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        if (!maze.isTraversable(start.row(), start.column()) || !maze.isTraversable(goal.row(), goal.column())) {
            return List.of();
        }
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) return reconstruct(previous, goal);
            for (Cell next : maze.cardinalNeighbours(current)) {
                if (!previous.containsKey(next)) {
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        return List.of();
    }

    private List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }
}
