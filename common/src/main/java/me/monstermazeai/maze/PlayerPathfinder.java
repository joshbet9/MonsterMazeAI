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
            addMovement(maze, current, new Cell(r - 2, c), queue, previous);
            addMovement(maze, current, new Cell(r + 2, c), queue, previous);
            addMovement(maze, current, new Cell(r, c - 2), queue, previous);
            addMovement(maze, current, new Cell(r, c + 2), queue, previous);
        }

        return List.of();
    }


    /**
     * Shortest physical route to any cell in an axis-aligned goal region.
     *
     * This is a single BFS, not one BFS per goal cell. It is the correct
     * bootstrap primitive for Safe Pads because every cell in the 5x5 surface
     * is a terminal success state for the player.
     */
    public List<Cell> shortestPathToRegion(MazeModel maze, Cell start, Cell center, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        if (!isPhysicalFloor(maze, start)) return List.of();

        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Map<Cell, Integer> distance = new HashMap<>();
        queue.add(start);
        previous.put(start, null);
        distance.put(start, 0);

        int bestDistance = Integer.MAX_VALUE;
        Cell bestGoal = null;
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            int currentDistance = distance.get(current);
            if (currentDistance > bestDistance) break;

            if (insideRegion(current, center, radius)) {
                if (bestGoal == null || compareRegionGoal(current, bestGoal, center) < 0) {
                    bestGoal = current;
                    bestDistance = currentDistance;
                }
                continue;
            }

            int r = current.row(), c = current.column();
            add(maze, current, new Cell(r - 1, c), queue, previous, distance, currentDistance + 1);
            add(maze, current, new Cell(r + 1, c), queue, previous, distance, currentDistance + 1);
            add(maze, current, new Cell(r, c - 1), queue, previous, distance, currentDistance + 1);
            add(maze, current, new Cell(r, c + 1), queue, previous, distance, currentDistance + 1);
            addMovement(maze, current, new Cell(r - 2, c), queue, previous, distance, currentDistance + 1);
            addMovement(maze, current, new Cell(r + 2, c), queue, previous, distance, currentDistance + 1);
            addMovement(maze, current, new Cell(r, c - 2), queue, previous, distance, currentDistance + 1);
            addMovement(maze, current, new Cell(r, c + 2), queue, previous, distance, currentDistance + 1);
        }
        return bestGoal == null ? List.of() : reconstruct(previous, bestGoal);
    }

    private static int compareRegionGoal(Cell a, Cell b, Cell center) {
        int da = Math.abs(a.row() - center.row()) + Math.abs(a.column() - center.column());
        int db = Math.abs(b.row() - center.row()) + Math.abs(b.column() - center.column());
        if (da != db) return Integer.compare(da, db);
        if (a.row() != b.row()) return Integer.compare(a.row(), b.row());
        return Integer.compare(a.column(), b.column());
    }

    private static boolean insideRegion(Cell cell, Cell center, int radius) {
        return Math.abs(cell.row() - center.row()) <= radius
                && Math.abs(cell.column() - center.column()) <= radius;
    }

    private boolean isPhysicalFloor(MazeModel maze, Cell cell) {
        return maze.isPhysicalFloor(cell.row(), cell.column());
    }

    private void add(MazeModel maze, Cell current, Cell next, ArrayDeque<Cell> queue,
                     Map<Cell, Cell> previous) {
        if (!isPhysicalFloor(maze, next) || previous.containsKey(next)) return;
        previous.put(next, current);
        queue.addLast(next);
    }
    private void addMovement(MazeModel maze, Cell current, Cell next, ArrayDeque<Cell> queue,
                             Map<Cell, Cell> previous) {
        if (!isGapEdge(maze, current, next) || previous.containsKey(next)) return;
        previous.put(next, current);
        queue.addLast(next);
    }


    private void add(MazeModel maze, Cell current, Cell next, ArrayDeque<Cell> queue,
                     Map<Cell, Cell> previous, Map<Cell, Integer> distance, int nextDistance) {
        if (!isPhysicalFloor(maze, next) || previous.containsKey(next)) return;
        previous.put(next, current);
        distance.put(next, nextDistance);
        queue.addLast(next);
    }
    private void addMovement(MazeModel maze, Cell current, Cell next, ArrayDeque<Cell> queue,
                             Map<Cell, Cell> previous, Map<Cell, Integer> distance, int nextDistance) {
        if (!isGapEdge(maze, current, next) || previous.containsKey(next)) return;
        previous.put(next, current);
        distance.put(next, nextDistance);
        queue.addLast(next);
    }

    private boolean isGapEdge(MazeModel maze, Cell from, Cell to) {
        int dr = to.row() - from.row(), dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0) || (Math.abs(dc) == 2 && dr == 0))) return false;
        int middleRow = from.row() + Integer.signum(dr);
        int middleColumn = from.column() + Integer.signum(dc);
        return isPhysicalFloor(maze, from)
                && !isPhysicalFloor(maze, new Cell(middleRow, middleColumn))
                && isPhysicalFloor(maze, to);
    }

    private List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }
}
