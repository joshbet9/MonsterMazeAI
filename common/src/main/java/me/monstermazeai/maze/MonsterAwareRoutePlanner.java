package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.*;

/**
 * Selects the shortest physical route to the Safe Pad, with local monster
 * encounters evaluated as tactical interactions rather than global hazards.
 * Distant monsters do not distort the baseline route. When a monster is close
 * enough to matter, expected damage and expected knockback value can alter the
 * local decision.
 */
public final class MonsterAwareRoutePlanner {
    private static final double STEP_COST = 1.0;
    /** Monster Maze bump damage in the source game. */
    private static final double MONSTER_DAMAGE = 4.0;
    /** Only evaluate monster interactions when the player is genuinely close. */
    private static final double LOCAL_INTERACTION_RADIUS = 4.5;
    private static final double CONTACT_RADIUS = 1.05;
    /** Converts one point of expected damage into equivalent route-time cost. */
    private static final double HEALTH_COST_PER_POINT = 3.0;
    /** Approximate value of a useful source-game bump in route-time units. */
    private static final double USEFUL_KNOCKBACK_VALUE = 5.0;
    /** Avoiding a non-useful contact is preferred to absorbing its damage. */
    private static final double CONTACT_DISPLACEMENT_COST = 2.0;

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
                        + tacticalMobCost(state, next, goal, arrivalTick);
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

    private double tacticalMobCost(GameState state, Cell cell, Cell goal, double arrivalTick) {
        // The first route objective is always the shortest physical route.
        // Monsters only enter the cost function once the player is locally
        // exposed to an interaction.
        double px = state.player.x;
        double pz = state.player.z;
        double cost = 0.0;

        double goalX = goal.row() + 0.5;
        double goalZ = goal.column() + 0.5;
        double toGoalX = goalX - (cell.row() + 0.5);
        double toGoalZ = goalZ - (cell.column() + 0.5);
        double goalLength = Math.hypot(toGoalX, toGoalZ);

        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;

            double nowDistance = Math.hypot(px - monster.x, pz - monster.z);
            if (nowDistance > LOCAL_INTERACTION_RADIUS) continue;

            double ticks = Math.min(8.0, Math.max(0.0, arrivalTick));
            double mx = monster.x + monster.vx * ticks;
            double mz = monster.z + monster.vz * ticks;
            double distance = Math.hypot(cell.row() + 0.5 - mx, cell.column() + 0.5 - mz);

            if (distance > CONTACT_RADIUS) continue;

            // Source MonsterMaze applies velocity in the trajectory from the
            // monster to the player, then deals exactly 4 damage. A bump is
            // useful when that displacement points substantially toward the
            // Safe Pad; otherwise the health loss is treated as a real cost.
            double awayX = px - mx;
            double awayZ = pz - mz;
            double awayLength = Math.hypot(awayX, awayZ);
            if (awayLength < 1.0E-9) continue;

            double alignment = goalLength < 1.0E-9
                    ? 0.0
                    : (awayX * toGoalX + awayZ * toGoalZ) / (awayLength * goalLength);

            boolean hitCooldownActive = state.player.recentMobHitUntilTick > state.tick;
            double expectedDamage = hitCooldownActive ? 0.0 : MONSTER_DAMAGE;
            double healthCost = expectedDamage * HEALTH_COST_PER_POINT;

            if (alignment >= 0.65) {
                // Deliberate contact can be faster than walking around the mob.
                // Do not make it free: the 4-damage hit still matters.
                cost += healthCost - USEFUL_KNOCKBACK_VALUE;
            } else {
                cost += healthCost + CONTACT_DISPLACEMENT_COST;
            }
        }

        return cost;
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
