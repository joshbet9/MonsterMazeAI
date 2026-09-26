package me.monstermazeai.maze;

import java.util.*;

/**
 * Generates deterministic alternatives around a shortest physical route.
 *
 * The first path is the true unweighted shortest path. Each alternative is
 * produced by temporarily forbidding one edge of the baseline/previous path
 * and recomputing the shortest physical path. This is intentionally small:
 * tactical simulation, not an enormous route catalogue, determines which
 * alternative is actually faster in the live monster field.
 */
public final class AlternativePhysicalRoutes {
    public List<PlayerRoute> generate(MazeModel maze, Cell start, Cell goal, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");

        PlayerRoute baseline = PlayerRoute.between(maze, start, goal);
        List<PlayerRoute> out = new ArrayList<>();
        out.add(baseline);
        if (limit == 1 || baseline.size() < 2) return out;

        Set<String> seen = new HashSet<>();
        seen.add(key(baseline.cells()));

        for (int i = 0; i < baseline.size() - 1 && out.size() < limit; i++) {
            Cell a = baseline.cells().get(i);
            Cell b = baseline.cells().get(i + 1);

            List<Cell> candidate = shortestAvoidingEdge(maze, start, goal, a, b);
            if (candidate.isEmpty()) continue;

            String key = key(candidate);
            if (seen.add(key)) out.add(new PlayerRoute(candidate));
        }

        return out;
    }

    private List<Cell> shortestAvoidingEdge(MazeModel maze, Cell start, Cell goal,
                                             Cell blockedA, Cell blockedB) {
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) return reconstruct(previous, goal);

            for (Cell next : maze.physicalCardinalNeighbours(current)) {
                if ((current.equals(blockedA) && next.equals(blockedB))
                        || (current.equals(blockedB) && next.equals(blockedA))) {
                    continue;
                }
                if (previous.containsKey(next)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }

        return List.of();
    }

    private static List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private static String key(List<Cell> cells) {
        StringBuilder out = new StringBuilder(cells.size() * 8);
        for (Cell c : cells) out.append(c.row()).append(':').append(c.column()).append(';');
        return out.toString();
    }
}
