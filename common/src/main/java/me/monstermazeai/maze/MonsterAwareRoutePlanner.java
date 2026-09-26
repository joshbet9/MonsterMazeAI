package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.*;

/**
 * Selects a physical maze route while accounting for short-horizon monster
 * interception risk. The maze graph remains authoritative for walkability;
 * monster risk only changes the route cost.
 */
public final class MonsterAwareRoutePlanner {
    private static final double MONSTER_RISK_RADIUS = 3.0;
    private static final double RISK_WEIGHT = 20.0;
    private static final double STEP_COST = 1.0;

    public PlayerRoute route(GameState state, Cell start, Cell goal) {
        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        Map<Cell, Double> distance = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(
                Comparator.comparingDouble((Node n) -> n.cost)
                        .thenComparingInt(n -> n.cell.row())
                        .thenComparingInt(n -> n.cell.column()));

        distance.put(start, 0.0);
        queue.add(new Node(start, 0.0));

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            double known = distance.getOrDefault(current.cell, Double.POSITIVE_INFINITY);
            if (current.cost > known + 1.0E-9) continue;
            if (current.cell.equals(goal)) return reconstruct(previous, start, goal);

            for (Cell next : state.maze.physicalCardinalNeighbours(current.cell)) {
                double arrivalTick = current.cost + 1.0;
                double cost = current.cost + STEP_COST
                        + riskCost(state, next, arrivalTick);
                double old = distance.getOrDefault(next, Double.POSITIVE_INFINITY);
                if (cost < old - 1.0E-9) {
                    distance.put(next, cost);
                    previous.put(next, current.cell);
                    queue.add(new Node(next, cost));
                }
            }
        }

        return new PlayerRoute(new PlayerPathfinder().shortestPath(state.maze, start, goal));
    }

    private double riskCost(GameState state, Cell cell, double arrivalTick) {
        double x = cell.row() + 0.5;
        double z = cell.column() + 0.5;
        double risk = 0.0;

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double ticks = Math.min(12.0, Math.max(0.0, arrivalTick));
            double mx = monster.x + monster.vx * ticks;
            double mz = monster.z + monster.vz * ticks;
            double distance = Math.hypot(x - mx, z - mz);

            if (distance >= MONSTER_RISK_RADIUS) continue;

            double severity = (MONSTER_RISK_RADIUS - distance) / MONSTER_RISK_RADIUS;
            // Earlier interception is more important than a monster projected
            // far into the future, while deterministic costs keep route choice
            // reproducible.
            risk += severity * severity * RISK_WEIGHT;
        }
        return risk;
    }

    private PlayerRoute reconstruct(Map<Cell, Cell> previous, Cell start, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        Cell current = goal;
        while (current != null) {
            path.add(current);
            if (current.equals(start)) break;
            current = previous.get(current);
        }
        if (!path.get(path.size() - 1).equals(start)) {
            throw new IllegalArgumentException("No player route exists");
        }
        Collections.reverse(path);
        return new PlayerRoute(path);
    }

    private record Node(Cell cell, double cost) {}
}
