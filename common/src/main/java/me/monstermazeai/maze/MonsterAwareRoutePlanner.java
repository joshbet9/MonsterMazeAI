package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.*;

/**
 * Time-aware physical route planner.
 *
 * The baseline optimises expected completion time rather than blindly avoiding
 * monsters. Each edge is evaluated at its estimated arrival time using the
 * observed monster velocity, a small uncertainty envelope, expected damage,
 * and the probability that the monster occupies the player's swept corridor.
 * The route is rebuilt from the live state, so a newly dangerous corridor is
 * abandoned automatically.
 */
public final class MonsterAwareRoutePlanner {
    private static final double MONSTER_RADIUS = 0.85;
    private static final double UNCERTAINTY = 0.45;
    private static final double HIT_DAMAGE = 4.0;
    private static final double HIT_TICKS = 20.0;
    private static final double STEP_TIME = 1.0;
    private static final double RISK_WEIGHT = 4.0;
    private static final double DAMAGE_WEIGHT = 1.5;
    private static final double DEATH_WEIGHT = 30.0;

    public PlayerRoute route(GameState state, Cell start, Cell goal) {
        if (state == null || state.maze == null) throw new IllegalArgumentException("No maze");
        if (!state.maze.isPhysicalFloor(start.row(), start.column())
                || !state.maze.isPhysicalFloor(goal.row(), goal.column())) {
            throw new IllegalArgumentException("Start or goal is not physical floor");
        }
        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        Map<Cell, Double> cost = new HashMap<>();
        Map<Cell, Double> travelTime = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(
                Comparator.comparingDouble((Node n) -> n.cost)
                        .thenComparingInt(n -> n.cell.row())
                        .thenComparingInt(n -> n.cell.column()));

        cost.put(start, 0.0);
        travelTime.put(start, 0.0);
        queue.add(new Node(start, 0.0));

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            double known = cost.getOrDefault(current.cell, Double.POSITIVE_INFINITY);
            if (current.cost > known + 1.0E-9) continue;
            if (current.cell.equals(goal)) return reconstruct(previous, start, goal);

            for (Cell next : state.maze.physicalCardinalNeighbours(current.cell)) {
                double arrival = travelTime.get(current.cell) + STEP_TIME;
                double edge = STEP_TIME + riskCost(state, next, arrival);
                double nextCost = current.cost + edge;
                double old = cost.getOrDefault(next, Double.POSITIVE_INFINITY);
                if (nextCost < old - 1.0E-9) {
                    cost.put(next, nextCost);
                    travelTime.put(next, arrival);
                    previous.put(next, current.cell);
                    queue.add(new Node(next, nextCost));
                }
            }
        }

        List<Cell> fallback = new PlayerPathfinder().shortestPath(state.maze, start, goal);
        if (fallback.isEmpty()) throw new IllegalArgumentException("No player route exists");
        return new PlayerRoute(fallback);
    }

    private double riskCost(GameState state, Cell cell, double arrivalTick) {
        double x = cell.row() + 0.5;
        double z = cell.column() + 0.5;
        double risk = 0.0;

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double horizon = Math.max(0.0, Math.min(arrivalTick, 16.0));
            double mx = monster.x + monster.vx * horizon;
            double mz = monster.z + monster.vz * horizon;
            double distance = Math.hypot(x - mx, z - mz);

            double effectiveRadius = MONSTER_RADIUS + UNCERTAINTY
                    + Math.min(0.45, Math.hypot(monster.vx, monster.vz) * 2.0);
            if (distance >= effectiveRadius) continue;

            // Smooth probability proxy: 1 near the predicted centre, 0 at the
            // edge of the uncertainty envelope. Multiple nearby monsters add
            // risk independently and therefore naturally favour open corridors.
            double pHit = 1.0 - distance / effectiveRadius;
            pHit *= pHit;

            if (state.player.health <= HIT_DAMAGE && pHit >= 0.5) return 1000.0;

            double expectedDamage = pHit * HIT_DAMAGE;
            double deathProbability = state.player.health <= HIT_DAMAGE
                    ? pHit : 0.0;
            double cooldownPenalty = pHit * (HIT_TICKS / 20.0);

            risk += RISK_WEIGHT * pHit
                    + DAMAGE_WEIGHT * expectedDamage
                    + DEATH_WEIGHT * deathProbability
                    + 0.20 * cooldownPenalty;
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
