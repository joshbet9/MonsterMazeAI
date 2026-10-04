package me.monstermazeai.maze;

import java.util.*;

public final class PlayerPathfinder {
    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        return shortestPath(maze, start, goal, true);
    }

    /** Shortest physical route using only adjacent floor cells. */
    public List<Cell> shortestPathWithoutGaps(MazeModel maze, Cell start, Cell goal) {
        return shortestPath(maze, start, goal, false);
    }

    private List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal, boolean allowGaps) {
        if (!isPhysicalFloor(maze, start) || !isPhysicalFloor(maze, goal)) return List.of();
        SearchResult result = search(maze, start, goal, allowGaps, false, null, 0);
        return result.path();
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

        SearchResult result = search(maze, start, null, allowGaps, true, center, radius);
        return result.path();
    }

    /**
     * Lexicographic physical routing:
     *
     *   1. shortest executable edge count;
     *   2. among equal-length routes, fewest heading changes;
     *   3. deterministic cell ordering as the final tie-break.
     *
     * This matters because the maze contains many equal-length alternatives.
     * BFS insertion order can otherwise return a zig-zag route that has the same
     * topology length but costs substantially more real movement/turn time.
     *
     * Gap transitions remain one executable edge. Their finite Jumper charge
     * budget is enforced by MonsterAwareRoutePlanner after candidate generation.
     */
    private SearchResult search(MazeModel maze, Cell start, Cell exactGoal,
                                boolean allowGaps, boolean regionGoal,
                                Cell regionCenter, int regionRadius) {
        Comparator<Node> comparator = Comparator
                .comparingInt((Node n) -> n.edges)
                .thenComparingInt(n -> n.turns)
                .thenComparingInt(n -> n.cell.row())
                .thenComparingInt(n -> n.cell.column())
                .thenComparingInt(n -> n.direction);

        PriorityQueue<Node> open = new PriorityQueue<>(comparator);
        Map<StateKey, Cost> best = new HashMap<>();
        Map<StateKey, StateKey> previous = new HashMap<>();

        StateKey startKey = new StateKey(start, -1);
        best.put(startKey, new Cost(0, 0));
        previous.put(startKey, null);
        open.add(new Node(start, -1, 0, 0));

        Node bestGoal = null;
        while (!open.isEmpty()) {
            Node current = open.poll();
            StateKey currentKey = new StateKey(current.cell, current.direction);
            Cost known = best.get(currentKey);
            if (known == null
                    || current.edges != known.edges
                    || current.turns != known.turns) {
                continue;
            }

            if (exactGoal != null && current.cell.equals(exactGoal)) {
                bestGoal = current;
                break;
            }
            if (regionGoal && insideRegion(current.cell, regionCenter, regionRadius)) {
                if (bestGoal == null || compareGoal(current, bestGoal, regionCenter) < 0) {
                    bestGoal = current;
                }
                /*
                 * Once this edge-count layer is exhausted, a later node can only
                 * tie the current region goal or lose on turns. Continue processing
                 * the same lexicographic frontier so we deterministically choose
                 * the lowest-turn endpoint inside the region.
                 */
                if (!open.isEmpty() && open.peek().edges > current.edges) break;
                continue;
            }

            int r = current.cell.row();
            int c = current.cell.column();

            for (int direction = 0; direction < 4; direction++) {
                int nr;
                int nc;
                switch (direction) {
                    case 0 -> { nr = r - 1; nc = c; }
                    case 1 -> { nr = r + 1; nc = c; }
                    case 2 -> { nr = r; nc = c - 1; }
                    default -> { nr = r; nc = c + 1; }
                }
                relax(maze, current, new Cell(nr, nc), direction,
                        open, best, previous);
            }

            if (allowGaps) {
                int[] gapRows = {r - 2, r + 2, r, r};
                int[] gapColumns = {c, c, c - 2, c + 2};
                for (int i = 0; i < 4; i++) {
                    Cell next = new Cell(gapRows[i], gapColumns[i]);
                    int direction = i;
                    if (isGapEdge(maze, current.cell, next)) {
                        relax(maze, current, next, direction,
                                open, best, previous);
                    }
                }
            }
        }

        return bestGoal == null
                ? new SearchResult(List.of(), Integer.MAX_VALUE, Integer.MAX_VALUE)
                : reconstruct(previous, new StateKey(bestGoal.cell, bestGoal.direction),
                        bestGoal.edges, bestGoal.turns);
    }

    private static int compareGoal(Node a, Node b, Cell center) {
        if (a.edges != b.edges) return Integer.compare(a.edges, b.edges);
        if (a.turns != b.turns) return Integer.compare(a.turns, b.turns);
        int da = Math.abs(a.cell.row() - center.row()) + Math.abs(a.cell.column() - center.column());
        int db = Math.abs(b.cell.row() - center.row()) + Math.abs(b.cell.column() - center.column());
        if (da != db) return Integer.compare(da, db);
        if (a.cell.row() != b.cell.row()) return Integer.compare(a.cell.row(), b.cell.row());
        return Integer.compare(a.cell.column(), b.cell.column());
    }

    private void relax(MazeModel maze, Node current, Cell next, int direction,
                       PriorityQueue<Node> open, Map<StateKey, Cost> best,
                       Map<StateKey, StateKey> previous) {
        if (!isPhysicalFloor(maze, next)) return;

        int turns = current.turns;
        if (current.direction >= 0 && current.direction != direction) turns++;

        StateKey nextKey = new StateKey(next, direction);
        Cost candidate = new Cost(current.edges + 1, turns);
        Cost prior = best.get(nextKey);
        if (prior != null && compareCost(candidate, prior) >= 0) return;

        best.put(nextKey, candidate);
        previous.put(nextKey, new StateKey(current.cell, current.direction));
        open.add(new Node(next, direction, candidate.edges, candidate.turns));
    }

    private static int compareCost(Cost a, Cost b) {
        int edges = Integer.compare(a.edges, b.edges);
        return edges != 0 ? edges : Integer.compare(a.turns, b.turns);
    }

    private static boolean insideRegion(Cell cell, Cell center, int radius) {
        return Math.abs(cell.row() - center.row()) <= radius
                && Math.abs(cell.column() - center.column()) <= radius;
    }

    private boolean isPhysicalFloor(MazeModel maze, Cell cell) {
        return cell.row() >= 0 && cell.row() < MazeModel.SIZE
                && cell.column() >= 0 && cell.column() < MazeModel.SIZE
                && maze.isPhysicalFloor(cell.row(), cell.column());
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

    private SearchResult reconstruct(Map<StateKey, StateKey> previous, StateKey goal,
                                     int edges, int turns) {
        ArrayList<Cell> path = new ArrayList<>();
        for (StateKey at = goal; at != null; at = previous.get(at)) {
            path.add(at.cell);
        }
        Collections.reverse(path);
        return new SearchResult(path, edges, turns);
    }

    private record StateKey(Cell cell, int direction) {}
    private record Cost(int edges, int turns) {}
    private record Node(Cell cell, int direction, int edges, int turns) {}
    private record SearchResult(List<Cell> path, int edges, int turns) {}
}
