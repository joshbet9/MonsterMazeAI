package me.monstermazeai.maze;

import java.util.*;

/**
 * Physical-floor pathfinder that treats 90-degree turns as real execution cost.
 *
 * The live player is much faster when a route can be driven as long straight
 * segments, so route length alone is not an adequate proxy for execution time.
 * This search allows only a small number of extra cells over the ordinary
 * shortest route and uses incoming direction as part of the search state.
 */
public final class TurnAwarePathfinder {
    private static final double TURN_COST = 1.25D;
    private static final int MAX_EXTRA_STEPS = 6;

    public List<Cell> shortestPath(MazeModel maze, Cell start, Cell goal) {
        if (!valid(maze, start) || !valid(maze, goal)) return List.of();
        if (start.equals(goal)) return List.of(start);

        int baselineSteps = new PlayerPathfinder()
                .shortestPathWithoutGaps(maze, start, goal).size();
        if (baselineSteps == 0) return List.of();

        return search(maze, start, cell -> cell.equals(goal), baselineSteps + MAX_EXTRA_STEPS, goal);
    }

    public List<Cell> shortestPathToRegion(
            MazeModel maze, Cell start, Cell center, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        if (!valid(maze, start)) return List.of();

        List<Cell> baseline = new PlayerPathfinder()
                .shortestPathToRegionWithoutGaps(maze, start, center, radius);
        if (baseline.isEmpty()) return List.of();

        return search(
                maze,
                start,
                cell -> Math.abs(cell.row() - center.row()) <= radius
                        && Math.abs(cell.column() - center.column()) <= radius,
                baseline.size() + MAX_EXTRA_STEPS,
                center);
    }

    private List<Cell> search(
            MazeModel maze,
            Cell start,
            java.util.function.Predicate<Cell> goal,
            int maxSteps,
            Cell goalCenter) {
        Map<State, Double> bestCost = new HashMap<>();
        Map<State, State> previous = new HashMap<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(
                Comparator.comparingDouble((Node n) -> n.cost)
                        .thenComparingInt(n -> n.steps)
                        .thenComparingInt(n -> n.turns)
                        .thenComparingInt(n -> n.state.cell.row())
                        .thenComparingInt(n -> n.state.cell.column())
                        .thenComparingInt(n -> n.state.direction.ordinal()));

        State initial = new State(start, Direction.NONE);
        bestCost.put(initial, 0.0D);
        queue.add(new Node(initial, 0.0D, 0, 0));

        State bestGoal = null;
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            double known = bestCost.getOrDefault(node.state, Double.POSITIVE_INFINITY);
            if (node.cost > known + 1.0E-9D) continue;
            if (node.steps > maxSteps) continue;

            if (goal.test(node.state.cell)) {
                if (bestGoal == null || betterGoal(node, bestGoal, bestCost, goalCenter)) {
                    bestGoal = node.state;
                }
                /*
                 * The queue is ordered by total cost. Once the next node costs
                 * more than the current goal, no later route can improve it.
                 */
                if (!queue.isEmpty() && queue.peek().cost > node.cost + 1.0E-9D) break;
                continue;
            }

            int r = node.state.cell.row();
            int c = node.state.cell.column();

            expand(maze, node, new Cell(r - 1, c), Direction.NORTH,
                    maxSteps, bestCost, previous, queue);
            expand(maze, node, new Cell(r + 1, c), Direction.SOUTH,
                    maxSteps, bestCost, previous, queue);
            expand(maze, node, new Cell(r, c - 1), Direction.WEST,
                    maxSteps, bestCost, previous, queue);
            expand(maze, node, new Cell(r, c + 1), Direction.EAST,
                    maxSteps, bestCost, previous, queue);
        }

        if (bestGoal == null) return List.of();
        return reconstruct(previous, bestGoal);
    }

    private void expand(
            MazeModel maze,
            Node node,
            Cell nextCell,
            Direction nextDirection,
            int maxSteps,
            Map<State, Double> bestCost,
            Map<State, State> previous,
            PriorityQueue<Node> queue) {
        if (!valid(maze, nextCell) || node.steps + 1 > maxSteps) return;

        double addedTurnCost =
                node.state.direction == Direction.NONE
                        || node.state.direction == nextDirection
                ? 0.0D
                : TURN_COST;

        State next = new State(nextCell, nextDirection);
        double cost = node.cost + 1.0D + addedTurnCost;
        int turns = node.turns
                + (addedTurnCost > 0.0D ? 1 : 0);

        double prior = bestCost.getOrDefault(next, Double.POSITIVE_INFINITY);
        if (cost >= prior - 1.0E-9D) return;

        bestCost.put(next, cost);
        previous.put(next, node.state);
        queue.add(new Node(next, cost, node.steps + 1, turns));
    }

    private static boolean betterGoal(
            Node candidate,
            State incumbent,
            Map<State, Double> bestCost,
            Cell center) {
        double candidateCost = bestCost.get(candidate.state);
        double incumbentCost = bestCost.get(incumbent);
        if (candidateCost < incumbentCost - 1.0E-9D) return true;
        if (candidateCost > incumbentCost + 1.0E-9D) return false;

        int candidateDistance = manhattan(candidate.state.cell, center);
        int incumbentDistance = manhattan(incumbent.cell, center);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        return candidate.state.cell.row() < incumbent.cell.row()
                || (candidate.state.cell.row() == incumbent.cell.row()
                && candidate.state.cell.column() < incumbent.cell.column());
    }

    private static int manhattan(Cell a, Cell b) {
        return Math.abs(a.row() - b.row()) + Math.abs(a.column() - b.column());
    }

    private static boolean valid(MazeModel maze, Cell cell) {
        return maze != null && cell != null
                && maze.isPhysicalFloor(cell.row(), cell.column());
    }

    private static List<Cell> reconstruct(Map<State, State> previous, State goal) {
        ArrayList<Cell> cells = new ArrayList<>();
        State cursor = goal;
        cells.add(cursor.cell);
        while (previous.containsKey(cursor)) {
            cursor = previous.get(cursor);
            cells.add(cursor.cell);
        }
        Collections.reverse(cells);
        return cells;
    }

    private record Node(State state, double cost, int steps, int turns) {}
    private record State(Cell cell, Direction direction) {}

    private enum Direction {
        NONE, NORTH, SOUTH, WEST, EAST
    }
}
