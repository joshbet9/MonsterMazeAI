package me.monstermazeai.maze;

import java.util.*;

public final class PlayerPathfinder {

    /*
     * Estimated time model for a human-like runner. Cell-count BFS is useful
     * for topology, but it systematically prefers zig-zag routes over smoother
     * routes. On a one-block corridor, a 90-degree turn costs real momentum and
     * camera-control time, so route selection should optimize estimated travel
     * time rather than raw edge count.
     */
    private static final double STEP_COST = 1.0D;
    private static final double GAP_COST = 1.45D;
    private static final double TURN_90_COST = 1.75D;
    private static final double TURN_180_COST = 4.0D;

    /** Physics-aware route to a specific floor cell. */
    public List<Cell> fastestPath(MazeModel maze, Cell start, Cell goal) {
        return fastestPath(maze, start, goal, false);
    }

    /** Physics-aware route to a specific floor cell, optionally allowing source gap edges. */
    public List<Cell> fastestPathWithGaps(MazeModel maze, Cell start, Cell goal) {
        return fastestPath(maze, start, goal, true);
    }

    private List<Cell> fastestPath(MazeModel maze, Cell start, Cell goal, boolean allowGaps) {
        if (!isPhysicalFloor(maze, start) || !isPhysicalFloor(maze, goal)) return List.of();

        PriorityQueue<SearchNode> queue = new PriorityQueue<>(
                Comparator.comparingDouble(SearchNode::cost));
        Map<SearchKey, Double> best = new HashMap<>();
        Map<SearchKey, SearchKey> previous = new HashMap<>();

        SearchKey initial = new SearchKey(start, -1);
        queue.add(new SearchNode(initial, 0.0D));
        best.put(initial, 0.0D);
        SearchKey bestGoal = null;

        while (!queue.isEmpty()) {
            SearchNode current = queue.poll();
            double known = best.getOrDefault(current.key(), Double.POSITIVE_INFINITY);
            if (current.cost() > known + 1.0E-9D) continue;
            if (current.key().cell().equals(goal)) {
                bestGoal = current.key();
                break;
            }

            for (Cell next : maze.physicalMovementNeighbours(current.key().cell())) {
                boolean gap = isGapEdge(maze, current.key().cell(), next);
                if (gap && !allowGaps) continue;

                int dir = direction(current.key().cell(), next);
                double edgeCost = gap ? GAP_COST : STEP_COST;
                if (current.key().direction() >= 0 && dir != current.key().direction()) {
                    edgeCost += turnCost(current.key().direction(), dir);
                }

                SearchKey candidate = new SearchKey(next, dir);
                double newCost = current.cost() + edgeCost;
                if (newCost + 1.0E-9D < best.getOrDefault(candidate, Double.POSITIVE_INFINITY)) {
                    best.put(candidate, newCost);
                    previous.put(candidate, current.key());
                    queue.add(new SearchNode(candidate, newCost));
                }
            }
        }

        if (bestGoal == null) return List.of();
        return reconstructStates(previous, bestGoal);
    }

    /** Physics-aware route to any floor cell in the SafePad region. */
    public List<Cell> fastestPathToRegion(
            MazeModel maze, Cell start, Cell center, int radius) {
        return fastestPathToRegion(maze, start, center, radius, false);
    }

    /** Physics-aware route to a SafePad region, optionally allowing gap edges. */
    public List<Cell> fastestPathToRegionWithGaps(
            MazeModel maze, Cell start, Cell center, int radius) {
        return fastestPathToRegion(maze, start, center, radius, true);
    }

    private List<Cell> fastestPathToRegion(
            MazeModel maze, Cell start, Cell center, int radius, boolean allowGaps) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        if (!isPhysicalFloor(maze, start)) return List.of();

        PriorityQueue<SearchNode> queue = new PriorityQueue<>(
                Comparator.comparingDouble(SearchNode::cost));
        Map<SearchKey, Double> best = new HashMap<>();
        Map<SearchKey, SearchKey> previous = new HashMap<>();

        SearchKey initial = new SearchKey(start, -1);
        queue.add(new SearchNode(initial, 0.0D));
        best.put(initial, 0.0D);
        SearchKey bestGoal = null;

        while (!queue.isEmpty()) {
            SearchNode current = queue.poll();
            double known = best.getOrDefault(current.key(), Double.POSITIVE_INFINITY);
            if (current.cost() > known + 1.0E-9D) continue;

            if (insideRegion(current.key().cell(), center, radius)) {
                bestGoal = current.key();
                break;
            }

            for (Cell next : maze.physicalMovementNeighbours(current.key().cell())) {
                boolean gap = isGapEdge(maze, current.key().cell(), next);
                if (gap && !allowGaps) continue;

                int dir = direction(current.key().cell(), next);
                double edgeCost = gap ? GAP_COST : STEP_COST;
                if (current.key().direction() >= 0 && dir != current.key().direction()) {
                    edgeCost += turnCost(current.key().direction(), dir);
                }

                SearchKey candidate = new SearchKey(next, dir);
                double newCost = current.cost() + edgeCost;
                if (newCost + 1.0E-9D < best.getOrDefault(candidate, Double.POSITIVE_INFINITY)) {
                    best.put(candidate, newCost);
                    previous.put(candidate, current.key());
                    queue.add(new SearchNode(candidate, newCost));
                }
            }
        }

        if (bestGoal == null) return List.of();
        return reconstructStates(previous, bestGoal);
    }

    private static int direction(Cell from, Cell to) {
        int dr = Integer.signum(to.row() - from.row());
        int dc = Integer.signum(to.column() - from.column());
        if (dr < 0) return 0; // north
        if (dr > 0) return 1; // south
        if (dc < 0) return 2; // west
        if (dc > 0) return 3; // east
        throw new IllegalArgumentException("duplicate route cell");
    }

    private static double turnCost(int from, int to) {
        int delta = Math.abs(from - to);
        delta = Math.min(delta, 4 - delta);
        return delta == 2 ? TURN_180_COST : TURN_90_COST;
    }

    private static List<Cell> reconstructStates(
            Map<SearchKey, SearchKey> previous, SearchKey goal) {
        ArrayList<Cell> path = new ArrayList<>();
        SearchKey at = goal;
        while (at != null) {
            path.add(at.cell());
            at = previous.get(at);
        }
        Collections.reverse(path);
        return path;
    }

    private record SearchKey(Cell cell, int direction) {}
    private record SearchNode(SearchKey key, double cost) {}

    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        return shortestPath(maze, start, goal, true);
    }

    /** Shortest physical route using only adjacent floor cells. */
    public List<Cell> shortestPathWithoutGaps(MazeModel maze, Cell start, Cell goal) {
        return shortestPath(maze, start, goal, false);
    }

    private List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal, boolean allowGaps) {
        if (!isPhysicalFloor(maze, start) || !isPhysicalFloor(maze, goal)) return List.of();

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
            if (allowGaps) {
                addMovement(maze, current, new Cell(r - 2, c), queue, previous);
                addMovement(maze, current, new Cell(r + 2, c), queue, previous);
                addMovement(maze, current, new Cell(r, c - 2), queue, previous);
                addMovement(maze, current, new Cell(r, c + 2), queue, previous);
            }
        }
        return List.of();
    }

    public List<Cell> shortestPathToRegion(MazeModel maze, Cell start, Cell center, int radius) {
        return shortestPathToRegion(maze, start, center, radius, true);
    }

    /** Shortest physical route to a Safe Pad region without gap jumps. */
    public List<Cell> shortestPathToRegionWithoutGaps(MazeModel maze, Cell start, Cell center, int radius) {
        return shortestPathToRegion(maze, start, center, radius, false);
    }

    private List<Cell> shortestPathToRegion(MazeModel maze, Cell start, Cell center,
                                            int radius, boolean allowGaps) {
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
            if (allowGaps) {
                addMovement(maze, current, new Cell(r - 2, c), queue, previous, distance, currentDistance + 1);
                addMovement(maze, current, new Cell(r + 2, c), queue, previous, distance, currentDistance + 1);
                addMovement(maze, current, new Cell(r, c - 2), queue, previous, distance, currentDistance + 1);
                addMovement(maze, current, new Cell(r, c + 2), queue, previous, distance, currentDistance + 1);
            }
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
