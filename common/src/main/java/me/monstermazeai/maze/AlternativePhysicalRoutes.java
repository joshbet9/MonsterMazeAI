package me.monstermazeai.maze;

import java.util.*;

/**
 * Deterministic bounded K-shortest physical-route generator.
 *
 * It uses iterative edge-deviation expansion: every accepted route contributes
 * each of its edges as a possible temporary exclusion, and BFS then finds the
 * shortest physical route under that exclusion. This is deliberately bounded
 * because tactical simulation is the expensive stage.
 */
public final class AlternativePhysicalRoutes {
    public List<PlayerRoute> generate(MazeModel maze, Cell start, Cell goal, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");

        PlayerRoute baseline = PlayerRoute.between(maze, start, goal);
        List<PlayerRoute> accepted = new ArrayList<>();
        accepted.add(baseline);

        Set<String> seen = new HashSet<>();
        seen.add(key(baseline.cells()));

        PriorityQueue<PlayerRoute> queue = new PriorityQueue<>(
                Comparator.comparingInt(PlayerRoute::size).thenComparing(r -> key(r.cells())));

        addDeviations(maze, start, goal, baseline, queue, seen);
        while (accepted.size() < limit && !queue.isEmpty()) {
            PlayerRoute candidate = queue.poll();
            if (!contains(accepted, candidate)) {
                accepted.add(candidate);
                addDeviations(maze, start, goal, candidate, queue, seen);
            }
        }
        return accepted;
    }

    private void addDeviations(MazeModel maze, Cell start, Cell goal, PlayerRoute route,
                                PriorityQueue<PlayerRoute> queue, Set<String> seen) {
        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            List<Cell> path = shortestAvoidingEdge(maze, start, goal, cells.get(i), cells.get(i + 1));
            if (path.isEmpty()) continue;
            String k = key(path);
            if (seen.add(k)) queue.add(new PlayerRoute(path));
        }
    }

    private static boolean contains(List<PlayerRoute> routes, PlayerRoute target) {
        String wanted = key(target.cells());
        for (PlayerRoute r : routes) if (key(r.cells()).equals(wanted)) return true;
        return false;
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
            for (Cell next : maze.physicalMovementNeighbours(current)) {
                if ((current.equals(blockedA) && next.equals(blockedB))
                        || (current.equals(blockedB) && next.equals(blockedA))) continue;
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
