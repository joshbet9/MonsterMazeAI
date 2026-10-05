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
    /**
     * Weak current-velocity forecast. Monster movement is stochastic at
     * junctions, so forecast risk is intentionally smaller than direct-contact
     * risk and is capped to a short horizon.
     */
    private static final int MAX_FORECAST_TICKS = 30;
    private static final double PLAYER_TICKS_PER_CELL = 5.0D;
    private static final double PLAYER_GAP_TICKS = 7.0D;
    private static final double FORECAST_DANGER_RADIUS = 3.5D;
    private static final double MAX_FORECAST_PENALTY = 4.0D;
    private static final double FORECAST_CLOSING_PENALTY = 1.5D;

    /**
     * Score an already-generated physical route against the currently observed
     * monster field. Unlike the path search this does not invent new topology;
     * it lets the caller compare several genuine route alternatives cheaply.
     */
    public double routeThreatCost(GameState state, PlayerRoute route) {
        if (state == null || state.maze == null || route == null || route.size() == 0) {
            return Double.POSITIVE_INFINITY;
        }

        double cost = 0.0D;
        double travelTicks = 0.0D;
        List<Cell> cells = route.cells();
        for (int i = 1; i < cells.size(); i++) {
            Cell from = cells.get(i - 1);
            Cell to = cells.get(i);
            int dr = Math.abs(to.row() - from.row());
            int dc = Math.abs(to.column() - from.column());
            boolean gap = (dr == 2 && dc == 0) || (dc == 2 && dr == 0);
            travelTicks += gap ? PLAYER_GAP_TICKS : PLAYER_TICKS_PER_CELL;
            cost += dangerPenalty(state, to, travelTicks);
        }
        return cost;
    }

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
        open.add(new Node(start, 0.0D, 0.0D));

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
                relax(state, node.cell, next, 1.0D, node.cost,
                        node.travelTicks + PLAYER_TICKS_PER_CELL,
                        open, best, previous);
            }

            if (allowGaps) {
                for (Cell next : List.of(
                        new Cell(r - 2, c), new Cell(r + 2, c),
                        new Cell(r, c - 2), new Cell(r, c + 2))) {
                    if (isGapEdge(state, node.cell, next)) {
                        relax(state, node.cell, next, 1.35D, node.cost,
                                node.travelTicks + PLAYER_GAP_TICKS,
                                open, best, previous);
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
                        double currentCost, double travelTicks,
                        PriorityQueue<Node> open,
                        Map<Cell, Double> best, Map<Cell, Cell> previous) {
        if (!state.maze.isPhysicalFloor(to.row(), to.column())) return;

        double nextCost = currentCost + baseCost
                + dangerPenalty(state, to, travelTicks);
        double previousBest = best.getOrDefault(to, Double.POSITIVE_INFINITY);
        if (nextCost + 1.0E-9D >= previousBest) return;

        best.put(to, nextCost);
        previous.put(to, from);
        open.add(new Node(to, nextCost, travelTicks));
    }

    private double dangerPenalty(GameState state, Cell cell, double travelTicks) {
        double x = cell.row() + 0.5D;
        double z = cell.column() + 0.5D;
        double penalty = 0.0D;
        int forecastTicks = (int) Math.max(
                0.0D,
                Math.min(MAX_FORECAST_TICKS, Math.round(travelTicks)));

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double dx = monster.x - x;
            double dz = monster.z - z;
            double distance = Math.hypot(dx, dz);
            if (distance < MONSTER_DANGER_RADIUS) {
                double proximity = (MONSTER_DANGER_RADIUS - distance) / MONSTER_DANGER_RADIUS;
                penalty += MAX_DANGER_PENALTY * proximity * proximity;

                double speedSq = monster.vx * monster.vx + monster.vz * monster.vz;
                if (speedSq > 1.0E-6D) {
                    double closing = (monster.vx * (x - monster.x)
                            + monster.vz * (z - monster.z))
                            / Math.max(distance, 1.0E-6D);
                    if (closing > 0.0D) {
                        penalty += MOVING_TOWARD_PENALTY * proximity;
                    }
                }
            }

            /*
             * Only add the forecast component when a monster has enough
             * observed velocity to make a meaningful displacement. The
             * forecast is evaluated at the player's approximate arrival time,
             * so a monster approaching a future junction can influence routing
             * before it enters the present three-block danger sphere.
             */
            if (forecastTicks > 0) {
                double projectedX = monster.x + monster.vx * forecastTicks;
                double projectedZ = monster.z + monster.vz * forecastTicks;
                double projectedDistance = Math.hypot(
                        projectedX - x, projectedZ - z);
                if (projectedDistance < FORECAST_DANGER_RADIUS) {
                    double proximity = (FORECAST_DANGER_RADIUS - projectedDistance)
                            / FORECAST_DANGER_RADIUS;
                    penalty += MAX_FORECAST_PENALTY * proximity * proximity;

                    double projectedDx = x - projectedX;
                    double projectedDz = z - projectedZ;
                    double projectedLength = Math.max(
                            Math.hypot(projectedDx, projectedDz), 1.0E-6D);
                    double projectedClosing =
                            (monster.vx * projectedDx + monster.vz * projectedDz)
                                    / projectedLength;
                    if (projectedClosing > 0.02D) {
                        penalty += FORECAST_CLOSING_PENALTY * proximity;
                    }
                }
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

    private record Node(Cell cell, double cost, double travelTicks) {}
}
