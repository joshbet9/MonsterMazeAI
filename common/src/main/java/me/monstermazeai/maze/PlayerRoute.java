package me.monstermazeai.maze;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical player route derived from the maze's raw floor graph.
 *
 * A route is a sequence of cell centres. Disabled monster waypoints are not
 * removed: they are still physical floor for the player.
 */
public final class PlayerRoute {
    private final List<Cell> cells;

    public PlayerRoute(List<Cell> cells) {
        if (cells == null || cells.isEmpty()) {
            throw new IllegalArgumentException("Player route must not be empty");
        }
        this.cells = List.copyOf(cells);
    }

    public static PlayerRoute between(MazeModel maze, Cell start, Cell goal) {
        List<Cell> path = new PlayerPathfinder().shortestPath(maze, start, goal);
        if (path.isEmpty()) throw new IllegalArgumentException("No player route exists");
        return new PlayerRoute(path);
    }

    public List<Cell> cells() {
        return cells;
    }

    public int size() {
        return cells.size();
    }

    public double targetX(int index) {
        return cells.get(index).row() + 0.5;
    }

    public double targetZ(int index) {
        return cells.get(index).column() + 0.5;
    }

    /**
     * Returns the first waypoint that is not already within tolerance.
     * This deliberately advances through multiple waypoints if the player
     * overshoots a corner; there is no block collision to trap the player.
     */
    public int nextWaypoint(double x, double z, int currentIndex, double tolerance) {
        int index = Math.max(0, Math.min(currentIndex, cells.size() - 1));

        // If physics carried the player past a waypoint, the player may be
        // more than the normal arrival tolerance from the old waypoint.
        // Advance to the furthest nearby forward waypoint so corners do not
        // cause the controller to turn back toward a point it has already
        // passed.
        int furthestNearby = index;
        for (int i = index + 1; i < cells.size(); i++) {
            if (Math.hypot(x - targetX(i), z - targetZ(i)) <= 0.75) {
                furthestNearby = i;
            } else if (i > index + 1) {
                break;
            }
        }
        index = furthestNearby;

        while (index < cells.size() - 1
                && Math.hypot(x - targetX(index), z - targetZ(index)) <= tolerance) {
            index++;
        }
        return index;
    }

    public boolean reached(double x, double z, double tolerance) {
        int last = cells.size() - 1;
        return Math.hypot(x - targetX(last), z - targetZ(last)) <= tolerance;
    }

    public List<double[]> worldWaypoints() {
        List<double[]> out = new ArrayList<>(cells.size());
        for (Cell cell : cells) out.add(new double[]{cell.row() + 0.5, cell.column() + 0.5});
        return List.copyOf(out);
    }
}
