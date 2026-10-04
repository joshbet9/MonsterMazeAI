package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.*;

/**
 * Low-cost local threat-aware path search used only for synchronous bootstrap
 * routing. It does not alter maze topology or monster mechanics; it biases the
 * cardinal path toward currently safer physical cells so the async tactical
 * simulator has time to take over.
 */
public final class ThreatAwarePathfinder {
    private static final double MONSTER_DANGER_RADIUS = 3.0D;
    private static final double MAX_DANGER_PENALTY = 8.0D;
    private static final double MOVING_TOWARD_PENALTY = 3.0D;

    public List<Cell> shortestPathToRegion(GameState state, Cell start, Cell center, int radius,
                                            boolean allowGaps) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(state.maze);
        if (start == null || center == null || radius < 0) return List.of();

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));
        Map<Cell, Double> best = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();

        best.put(start, 0.0D);
        previous.put(start, null);
        open.add(new Node(start, 0.0D));

        Cell bestGoal = null;
        double bestGoalCost = Double.POSITIVE_INFINITY;

        while (!open.isEmpty()) {
            Node node = open.poll();
            double known = best.getOrDefault(node.cell, Double.POSITIVE_INFINITY);
            if (node.cost > known + 1.0E-9D) continue;
            if (node.cost > bestGoalCost) continue;

            if (insideRegion(node.cell, center, radius)) {
                if (bestGoal == null || node.cost < bestGoalCost
                        || (Double.compare(node.cost, bestGoalCost) == 0
                        && compareRegionGoal(node.cell, bestGoal, center) < 0)) {
                    bestGoal = node.cell;
                    bestGoalCost = node.cost;
                }
                continue;
            }

            int r = node.cell.row();
            int c = node.cell.column();

            for (Cell next : List.of(
                    new Cell(r - 1, c), new Cell(r + 1, c),
                    new Cell(r, c - 1), new Cell(r, c + 1))) {
                relax(state, node.cell, next, 1.0D, node.cost, open, best, previous);
            }

            if (allowGaps) {
                for (Cell next : List.of(
                        new Cell(r - 2, c), new Cell(r + 2, c),
                        new Cell(r, c - 2), new Cell(r, c + 2))) {
                    if (isGapEdge(state, node.cell, next)) {
                        relax(state, node.cell, next, 1.35D, node.cost, open, best, previous);
                    }
                }
            }
        }

        if (bestGoal == null) return List.of();
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = bestGoal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private void relax(GameState state, Cell from, Cell to, double baseCost,
                        double currentCost, PriorityQueue<Node> open,
                        Map<Cell, Double> best, Map<Cell, Cell> previous) {
        if (!state.maze.isPhysicalFloor(to.row(), to.column())) return;

        double nextCost = currentCost + baseCost + dangerPenalty(state, to);
        double previousBest = best.getOrDefault(to, Double.POSITIVE_INFINITY);
        if (nextCost + 1.0E-9D >= previousBest) return;

        best.put(to, nextCost);
        previous.put(to, from);
        open.add(new Node(to, nextCost));
    }

    private double dangerPenalty(GameState state, Cell cell) {
        double x = cell.row() + 0.5D;
        double z = cell.column() + 0.5D;
        double penalty = 0.0D;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double dx = monster.x - x;
            double dz = monster.z - z;
            double distance = Math.hypot(dx, dz);
            if (distance >= MONSTER_DANGER_RADIUS) continue;

            double proximity = (MONSTER_DANGER_RADIUS - distance) / MONSTER_DANGER_RADIUS;
            penalty += MAX_DANGER_PENALTY * proximity * proximity;

            double speedSq = monster.vx * monster.vx + monster.vz * monster.vz;
            if (speedSq > 1.0E-6D) {
                double closing = (monster.vx * (x - monster.x)
                        + monster.vz * (z - monster.z)) / Math.max(distance, 1.0E-6D);
                if (closing > 0.0D) penalty += MOVING_TOWARD_PENALTY * proximity;
            }

            if (penalty >= 32.0D) return 32.0D;
        }
        return penalty;
    }

    private static boolean insideRegion(Cell cell, Cell center, int radius) {
        return Math.abs(cell.row() - center.row()) <= radius
                && Math.abs(cell.column() - center.column()) <= radius;
    }

    private static int compareRegionGoal(Cell a, Cell b, Cell center) {
        int da = Math.abs(a.row() - center.row()) + Math.abs(a.column() - center.column());
        int db = Math.abs(b.row() - center.row()) + Math.abs(b.column() - center.column());
        if (da != db) return Integer.compare(da, db);
        if (a.row() != b.row()) return Integer.compare(a.row(), b.row());
        return Integer.compare(a.column(), b.column());
    }

    private static boolean isGapEdge(GameState state, Cell from, Cell to) {
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0) || (Math.abs(dc) == 2 && dr == 0))) return false;
        int middleRow = from.row() + Integer.signum(dr);
        int middleColumn = from.column() + Integer.signum(dc);
        return state.maze.isPhysicalFloor(from.row(), from.column())
                && !state.maze.isPhysicalFloor(middleRow, middleColumn)
                && state.maze.isPhysicalFloor(to.row(), to.column());
    }

    private record Node(Cell cell, double cost) {}
}
