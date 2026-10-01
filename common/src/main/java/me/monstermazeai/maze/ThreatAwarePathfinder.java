package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.*;

/**
 * Low-cost threat-aware path search used for live/bootstrap routing.
 *
 * Threat cost remains the primary objective, but equal-cost physical routes are
 * resolved by the fewest heading changes. This prevents moving monsters from
 * turning an otherwise sensible detour into a stop/zig-zag route.
 */
public final class ThreatAwarePathfinder {
    private static final double MONSTER_DANGER_RADIUS = 3.0D;
    private static final double MAX_DANGER_PENALTY = 2.5D;
    private static final double MOVING_TOWARD_PENALTY = 0.75D;

    public List<Cell> shortestPathToRegion(GameState state, Cell start, Cell center, int radius,
                                            boolean allowGaps) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(state.maze);
        if (start == null || center == null || radius < 0) return List.of();
        if (!state.maze.isPhysicalFloor(start.row(), start.column())) return List.of();

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble((Node n) -> n.cost)
                        .thenComparingInt(n -> n.turns)
                        .thenComparingInt(n -> n.state.cell.row())
                        .thenComparingInt(n -> n.state.cell.column())
                        .thenComparingInt(n -> n.state.direction.ordinal()));

        Map<StateKey, Best> best = new HashMap<>();
        Map<StateKey, StateKey> previous = new HashMap<>();

        StateKey origin = new StateKey(start, Direction.NONE);
        best.put(origin, new Best(0.0D, 0));
        open.add(new Node(origin, 0.0D, 0));

        StateKey bestGoal = null;
        double bestGoalCost = Double.POSITIVE_INFINITY;
        int bestGoalTurns = Integer.MAX_VALUE;

        while (!open.isEmpty()) {
            Node node = open.poll();
            Best known = best.get(node.state);
            if (known == null
                    || Double.compare(node.cost, known.cost) != 0
                    || node.turns != known.turns) continue;

            if (node.cost > bestGoalCost + 1.0E-9D) break;

            if (insideRegion(node.state.cell, center, radius)) {
                if (bestGoal == null
                        || node.cost < bestGoalCost - 1.0E-9D
                        || (Math.abs(node.cost - bestGoalCost) <= 1.0E-9D
                        && (node.turns < bestGoalTurns
                        || (node.turns == bestGoalTurns
                        && compareRegionGoal(node.state.cell, bestGoal.cell, center) < 0)))) {
                    bestGoal = node.state;
                    bestGoalCost = node.cost;
                    bestGoalTurns = node.turns;
                }
                continue;
            }

            int row = node.state.cell.row();
            int column = node.state.cell.column();

            relax(state, node, new Cell(row - 1, column), Direction.NORTH,
                    1.0D, open, best, previous);
            relax(state, node, new Cell(row + 1, column), Direction.SOUTH,
                    1.0D, open, best, previous);
            relax(state, node, new Cell(row, column - 1), Direction.WEST,
                    1.0D, open, best, previous);
            relax(state, node, new Cell(row, column + 1), Direction.EAST,
                    1.0D, open, best, previous);

            if (allowGaps) {
                Cell[] gaps = {
                        new Cell(row - 2, column),
                        new Cell(row + 2, column),
                        new Cell(row, column - 2),
                        new Cell(row, column + 2)
                };
                Direction[] directions = {
                        Direction.NORTH, Direction.SOUTH,
                        Direction.WEST, Direction.EAST
                };
                for (int i = 0; i < gaps.length; i++) {
                    if (isGapEdge(state, node.state.cell, gaps[i])) {
                        relax(state, node, gaps[i], directions[i],
                                1.35D, open, best, previous);
                    }
                }
            }
        }

        if (bestGoal == null) return List.of();
        ArrayList<Cell> path = new ArrayList<>();
        for (StateKey at = bestGoal; at != null; at = previous.get(at)) {
            path.add(at.cell);
        }
        Collections.reverse(path);
        return path;
    }

    private void relax(GameState state, Node from, Cell to, Direction direction,
                       double baseCost, PriorityQueue<Node> open,
                       Map<StateKey, Best> best, Map<StateKey, StateKey> previous) {
        if (!state.maze.isPhysicalFloor(to.row(), to.column())) return;

        double nextCost = from.cost + baseCost + dangerPenalty(state, to);
        int nextTurns = from.turns
                + (from.state.direction != Direction.NONE && from.state.direction != direction ? 1 : 0);
        StateKey next = new StateKey(to, direction);
        Best prior = best.get(next);

        boolean better = prior == null
                || nextCost < prior.cost - 1.0E-9D
                || (Math.abs(nextCost - prior.cost) <= 1.0E-9D && nextTurns < prior.turns);
        if (!better) return;

        best.put(next, new Best(nextCost, nextTurns));
        previous.put(next, from.state);
        open.add(new Node(next, nextCost, nextTurns));
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

    private enum Direction {
        NONE, NORTH, SOUTH, WEST, EAST
    }

    private record StateKey(Cell cell, Direction direction) {}
    private record Best(double cost, int turns) {}
    private record Node(StateKey state, double cost, int turns) {}
}
