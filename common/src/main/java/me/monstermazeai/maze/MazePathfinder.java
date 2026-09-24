package me.monstermazeai.maze;

import java.util.*;

/**
 * A* pathfinder over the logical maze graph.
 *
 * The maze is small and grid-shaped, so Manhattan distance is an admissible
 * heuristic for four-direction movement and keeps the implementation
 * deterministic while avoiding a full breadth-first traversal when the goal
 * is far away.
 */
public final class MazePathfinder {
    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        if (!maze.isTraversable(start.row(), start.column())
                || !maze.isTraversable(goal.row(), goal.column())) {
            return List.of();
        }
        if (start.equals(goal)) return List.of(start);

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingInt((Node n) -> n.f)
                        .thenComparingInt(n -> n.h)
                        .thenComparingInt(n -> n.cell.row())
                        .thenComparingInt(n -> n.cell.column()));

        Map<Cell, Integer> gScore = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        gScore.put(start, 0);
        previous.put(start, null);
        open.add(new Node(start, 0, heuristic(start, goal)));

        while (!open.isEmpty()) {
            Node current = open.remove();
            int knownG = gScore.getOrDefault(current.cell, Integer.MAX_VALUE);
            if (current.g != knownG) continue;

            if (current.cell.equals(goal)) {
                return reconstruct(previous, goal);
            }

            for (Cell next : maze.cardinalNeighbours(current.cell)) {
                int tentativeG = current.g + 1;
                if (tentativeG >= gScore.getOrDefault(next, Integer.MAX_VALUE)) continue;

                gScore.put(next, tentativeG);
                previous.put(next, current.cell);
                int h = heuristic(next, goal);
                open.add(new Node(next, tentativeG, h));
            }
        }

        return List.of();
    }

    private int heuristic(Cell a, Cell b) {
        return Math.abs(a.row() - b.row()) + Math.abs(a.column() - b.column());
    }

    private List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private record Node(Cell cell, int g, int h) {
        int f() { return g + h; }
    }
}
