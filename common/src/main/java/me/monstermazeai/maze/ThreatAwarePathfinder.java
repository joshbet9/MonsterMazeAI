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
    /** Additional route cost for where a monster is predicted to be on arrival. */
    private static final double FUTURE_DANGER_RADIUS = 4.0D;
    private static final double FUTURE_DANGER_PENALTY = 4.0D;
    /** Near-contact is disproportionately dangerous because one source bump costs four health. */
    private static final double FUTURE_CONTACT_RADIUS = 1.8D;
    private static final double FUTURE_CONTACT_PENALTY = 8.0D;
    /** Conservative live speed estimate used only for route-threat timing. */
    private static final double MIN_ROUTE_TRAVEL_SPEED = 0.18D;
    private static final double MAX_ROUTE_THREAT_TICKS = 100.0D;

    public List<Cell> shortestPathToRegion(GameState state, Cell start, Cell center, int radius,
                                            boolean allowGaps) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(state.maze);
        if (start == null || center == null || radius < 0) return List.of();
        if (!state.maze.isPhysicalFloor(start.row(), start.column())) return List.of();

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparingDouble((Node n) -> n.cost)
                        .thenComparingInt(n -> n.turns)
                        .thenComparingDouble(n -> n.travelDistance)
                        .thenComparingInt(n -> n.state.cell.row())
                        .thenComparingInt(n -> n.state.cell.column())
                        .thenComparingInt(n -> n.state.direction.ordinal()));

        Map<StateKey, Best> best = new HashMap<>();
        Map<StateKey, StateKey> previous = new HashMap<>();

        StateKey origin = new StateKey(start, Direction.NONE);
        best.put(origin, new Best(0.0D, 0, 0.0D));
        open.add(new Node(origin, 0.0D, 0, 0.0D));

        StateKey bestGoal = null;
        double bestGoalCost = Double.POSITIVE_INFINITY;
        int bestGoalTurns = Integer.MAX_VALUE;

        while (!open.isEmpty()) {
            Node node = open.poll();
            Best known = best.get(node.state);
            if (known == null
                    || Double.compare(node.cost, known.cost) != 0
                    || node.turns != known.turns
                    || Double.compare(node.travelDistance, known.travelDistance) != 0) continue;

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

        double edgeDistance = Math.hypot(
                to.row() - from.state.cell.row(),
                to.column() - from.state.cell.column());
        double nextTravelDistance = from.travelDistance + edgeDistance;
        double arrivalTicks = Math.min(
                MAX_ROUTE_THREAT_TICKS,
                nextTravelDistance / Math.max(
                        MIN_ROUTE_TRAVEL_SPEED,
                        Math.hypot(state.player.vx, state.player.vz)));
        double nextCost = from.cost + baseCost
                + dangerPenalty(state, to, arrivalTicks);
        int nextTurns = from.turns
                + (from.state.direction != Direction.NONE && from.state.direction != direction ? 1 : 0);
        StateKey next = new StateKey(to, direction);
        Best prior = best.get(next);

        boolean better = prior == null
                || nextCost < prior.cost - 1.0E-9D
                || (Math.abs(nextCost - prior.cost) <= 1.0E-9D && nextTurns < prior.turns)
                || (Math.abs(nextCost - prior.cost) <= 1.0E-9D
                && nextTurns == prior.turns
                && nextTravelDistance < prior.travelDistance - 1.0E-9D);
        if (!better) return;

        best.put(next, new Best(nextCost, nextTurns, nextTravelDistance));
        previous.put(next, from.state);
        open.add(new Node(next, nextCost, nextTurns, nextTravelDistance));
    }

    private double dangerPenalty(GameState state, Cell cell, double arrivalTicks) {
        double x = cell.row() + 0.5D;
        double z = cell.column() + 0.5D;
        double penalty = 0.0D;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            // Current-position risk keeps the bootstrap path responsive to an
            // already-near contact.
            double dx = monster.x - x;
            double dz = monster.z - z;
            double distance = Math.hypot(dx, dz);
            if (distance < MONSTER_DANGER_RADIUS) {
                double proximity = (MONSTER_DANGER_RADIUS - distance) / MONSTER_DANGER_RADIUS;
                penalty += MAX_DANGER_PENALTY * proximity * proximity;

                double speedSq = monster.vx * monster.vx + monster.vz * monster.vz;
                if (speedSq > 1.0E-6D) {
                    double closing = (monster.vx * (x - monster.x)
                            + monster.vz * (z - monster.z)) / Math.max(distance, 1.0E-6D);
                    if (closing > 0.0D) penalty += MOVING_TOWARD_PENALTY * proximity;
                }
            }

            // Temporal threat: score the monster at approximately the moment
            // the player reaches this route cell. This captures a slow mob
            // that is harmless now but crossing the chosen lane by arrival.
            double t = Math.max(0.0D, Math.min(MAX_ROUTE_THREAT_TICKS, arrivalTicks));
            double predictedX = monster.x + monster.vx * t;
            double predictedZ = monster.z + monster.vz * t;
            double predictedDistance = Math.hypot(predictedX - x, predictedZ - z);

            if (predictedDistance < FUTURE_DANGER_RADIUS) {
                double proximity = (FUTURE_DANGER_RADIUS - predictedDistance)
                        / FUTURE_DANGER_RADIUS;
                penalty += FUTURE_DANGER_PENALTY * proximity * proximity;
            }
            if (predictedDistance < FUTURE_CONTACT_RADIUS) {
                double contact = (FUTURE_CONTACT_RADIUS - predictedDistance)
                        / FUTURE_CONTACT_RADIUS;
                penalty += FUTURE_CONTACT_PENALTY * contact * contact;
            }

            // Also sample slightly before arrival so a monster crossing the cell
            // between two route observations is represented instead of being
            // invisible because it has already passed by the exact arrival tick.
            double previousT = Math.max(0.0D, t - 4.0D);
            double previousX = monster.x + monster.vx * previousT;
            double previousZ = monster.z + monster.vz * previousT;
            double previousDistance = Math.hypot(previousX - x, previousZ - z);
            if (previousDistance < FUTURE_CONTACT_RADIUS) {
                double contact = (FUTURE_CONTACT_RADIUS - previousDistance)
                        / FUTURE_CONTACT_RADIUS;
                penalty += FUTURE_CONTACT_PENALTY * 0.5D * contact * contact;
            }

            if (penalty >= 64.0D) return 64.0D;
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
    private record Best(double cost, int turns, double travelDistance) {}
    private record Node(StateKey state, double cost, int turns, double travelDistance) {}
}
