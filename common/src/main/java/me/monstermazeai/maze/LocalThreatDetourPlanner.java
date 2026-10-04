package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cheap closed-loop detour planner for an already committed route.
 *
 * It reacts only when a monster is predicted to occupy the next few route
 * cells, preserves the current travel direction on the first edge, changes
 * lanes at a physical junction, then rejoins the original route.
 */
public final class LocalThreatDetourPlanner {
    private static final int LOOKAHEAD_CELLS = 10;
    private static final int MAX_REJOIN_EXTRA_CELLS = 6;
    private static final double PLAYER_TICKS_PER_CELL = 5.0D;
    private static final int MAX_FORECAST_TICKS = 24;
    private static final double HARD_THREAT_RADIUS = 1.05D;
    private static final double CLOSING_THREAT_RADIUS = 1.60D;
    private static final double MIN_CLOSING_SPEED = 0.05D;

    public PlayerRoute findDetour(GameState state, PlayerRoute route,
                                  int waypointIndex, Cell start) {
        if (state == null || state.maze == null || route == null
                || route.size() < 4 || start == null) {
            return null;
        }
        if (!isFloor(state, start) || waypointIndex <= 0
                || waypointIndex >= route.size()) return null;

        Cell currentFrom = route.cells().get(waypointIndex - 1);
        Cell currentTo = route.cells().get(waypointIndex);
        if (isGapEdge(state, currentFrom, currentTo)) return null;

        int dirRow = Integer.signum(currentTo.row() - currentFrom.row());
        int dirColumn = Integer.signum(currentTo.column() - currentFrom.column());
        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) return null;

        int end = Math.min(route.size() - 1, waypointIndex + LOOKAHEAD_CELLS);
        int firstBlocked = -1;
        int lastBlocked = -1;
        double arrivalTicks = 0.0D;

        for (int i = waypointIndex; i <= end; i++) {
            Cell from = route.cells().get(i - 1);
            Cell to = route.cells().get(i);
            arrivalTicks += edgeTicks(from, to);
            if (isThreatened(state, to, arrivalTicks)) {
                if (firstBlocked < 0) firstBlocked = i;
                lastBlocked = i;
            } else if (firstBlocked >= 0 && i > firstBlocked + 1) {
                break;
            }
        }

        if (firstBlocked < 0) return null;

        int rejoinStart = Math.min(
                route.size() - 1,
                Math.max(firstBlocked + 2, lastBlocked + 1));
        int rejoinEnd = Math.min(
                route.size() - 1,
                rejoinStart + MAX_REJOIN_EXTRA_CELLS);

        PlayerRoute best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int rejoinIndex = rejoinStart; rejoinIndex <= rejoinEnd; rejoinIndex++) {
            Cell rejoin = route.cells().get(rejoinIndex);
            if (isThreatened(state, rejoin,
                    PLAYER_TICKS_PER_CELL * Math.max(1, rejoinIndex - waypointIndex + 1))) {
                continue;
            }

            List<Cell> detour = shortestDetour(
                    state, start, rejoin, dirRow, dirColumn);
            if (detour.isEmpty() || !safePath(state, detour)) continue;

            ArrayList<Cell> combined = new ArrayList<>(detour);
            for (int i = rejoinIndex + 1; i < route.size(); i++) {
                combined.add(route.cells().get(i));
            }

            PlayerRoute candidate = new PlayerRoute(combined);
            double score = detour.size()
                    + 0.10D * (rejoinIndex - firstBlocked);
            if (best == null || score < bestScore
                    || (Double.compare(score, bestScore) == 0
                    && candidate.size() < best.size())) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private List<Cell> shortestDetour(GameState state, Cell start, Cell goal,
                                      int firstDirRow, int firstDirColumn) {
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Map<Cell, Integer> distance = new HashMap<>();
        Set<Cell> rejected = new HashSet<>();

        queue.add(start);
        previous.put(start, null);
        distance.put(start, 0);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            int depth = distance.get(current);
            if (current.equals(goal)) return reconstruct(previous, goal);

            int r = current.row();
            int c = current.column();
            for (Cell next : List.of(
                    new Cell(r - 1, c),
                    new Cell(r + 1, c),
                    new Cell(r, c - 1),
                    new Cell(r, c + 1))) {
                if (!isFloor(state, next) || previous.containsKey(next)
                        || rejected.contains(next)) continue;

                int nextDepth = depth + 1;
                if (depth == 0
                        && (Integer.signum(next.row() - current.row()) != firstDirRow
                        || Integer.signum(next.column() - current.column()) != firstDirColumn)) {
                    continue;
                }

                double arrivalTicks = nextDepth * PLAYER_TICKS_PER_CELL;
                if (!next.equals(goal) && isThreatened(state, next, arrivalTicks)) {
                    rejected.add(next);
                    continue;
                }

                previous.put(next, current);
                distance.put(next, nextDepth);
                queue.addLast(next);
            }
        }
        return List.of();
    }

    private boolean safePath(GameState state, List<Cell> path) {
        double arrivalTicks = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            arrivalTicks += edgeTicks(path.get(i - 1), path.get(i));
            if (isThreatened(state, path.get(i), arrivalTicks)) return false;
        }
        return true;
    }

    private boolean isThreatened(GameState state, Cell cell, double arrivalTicks) {
        double x = cell.row() + 0.5D;
        double z = cell.column() + 0.5D;
        int ticks = (int) Math.max(
                0.0D,
                Math.min(MAX_FORECAST_TICKS, Math.round(arrivalTicks)));

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) {
                continue;
            }

            double projectedX = monster.x + monster.vx * ticks;
            double projectedZ = monster.z + monster.vz * ticks;
            double dx = x - projectedX;
            double dz = z - projectedZ;
            double distance = Math.hypot(dx, dz);

            if (distance <= HARD_THREAT_RADIUS) return true;

            double speedSq = monster.vx * monster.vx + monster.vz * monster.vz;
            if (speedSq <= 1.0E-8D || distance > CLOSING_THREAT_RADIUS) continue;

            double closing = (monster.vx * dx + monster.vz * dz)
                    / Math.max(distance, 1.0E-6D);
            if (closing >= MIN_CLOSING_SPEED) return true;
        }
        return false;
    }

    private static double edgeTicks(Cell from, Cell to) {
        int dr = Math.abs(to.row() - from.row());
        int dc = Math.abs(to.column() - from.column());
        return (dr == 2 || dc == 2) ? 7.0D : PLAYER_TICKS_PER_CELL;
    }

    private static boolean isFloor(GameState state, Cell cell) {
        return cell.row() >= 0 && cell.row() < MazeModel.SIZE
                && cell.column() >= 0 && cell.column() < MazeModel.SIZE
                && state.maze.isPhysicalFloor(cell.row(), cell.column());
    }

    private static boolean isGapEdge(GameState state, Cell from, Cell to) {
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0)
                || (Math.abs(dc) == 2 && dr == 0))) return false;

        Cell middle = new Cell(
                from.row() + Integer.signum(dr),
                from.column() + Integer.signum(dc));
        return isFloor(state, from)
                && !isFloor(state, middle)
                && isFloor(state, to);
    }

    private static List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }
}
