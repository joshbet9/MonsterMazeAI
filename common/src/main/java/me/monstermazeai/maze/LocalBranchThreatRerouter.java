package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.planner.TacticalRouteSimulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative local branch selector.
 *
 * It never scores the whole maze by monster danger. It only activates when a
 * moving or stationary monster is predicted to occupy the committed route
 * within the source interaction sphere. At that point it generates a handful of
 * physical branches which preserve the current first heading and uses the
 * source-faithful tactical simulator to compare continuing versus detouring to
 * the next safe rejoin point.
 */
public final class LocalBranchThreatRerouter {
    private static final int LOOKAHEAD_SEGMENTS = 8;
    private static final double INTERACTION_RADIUS = 20.0D;
    private static final double ROUTE_CONTACT_RADIUS = 1.45D;
    private static final double PREDICTED_CONTACT_RADIUS = 1.60D;
    private static final double MIN_PLAYER_SPEED = 0.12D;
    private static final int MAX_REJOIN_OFFSET = 5;
    private static final int MIN_REJOIN_AFTER_THREAT = 2;

    private final AlternativePhysicalRoutes alternatives = new AlternativePhysicalRoutes();
    private final TacticalRouteSimulator simulator = new TacticalRouteSimulator();

    public Choice choose(GameState state, PlayerRoute route, int waypointIndex, Cell currentCell) {
        if (state == null || route == null || currentCell == null
                || waypointIndex <= 0 || waypointIndex >= route.size()) {
            return null;
        }

        Cell from = route.cells().get(waypointIndex - 1);
        Cell to = route.cells().get(waypointIndex);
        if (isGapEdge(state, from, to)) return null;

        ThreatWindow threat = findThreatWindow(state, route, waypointIndex);
        if (threat == null) return null;

        int minimumRejoin = Math.min(
                route.size() - 1,
                Math.max(waypointIndex + MIN_REJOIN_AFTER_THREAT, threat.lastSegment + 2));
        int maximumRejoin = Math.min(
                route.size() - 1,
                minimumRejoin + MAX_REJOIN_OFFSET);

        Candidate baseline = buildCandidate(route, currentCell, minimumRejoin);
        if (baseline == null) return null;

        TacticalRouteSimulator.Result baselineResult =
                simulator.simulate(state, baseline.route, baseline.goal, false, 0);
        double baselineScore = score(baselineResult);

        Choice best = null;
        for (int rejoinIndex = minimumRejoin; rejoinIndex <= Math.min(maximumRejoin, minimumRejoin + 2); rejoinIndex++) {
            Cell rejoin = route.cells().get(rejoinIndex);
            List<PlayerRoute> branches = branchCandidates(
                    state, currentCell, rejoin, from, to, route, waypointIndex);
            for (PlayerRoute branch : branches) {
                if (branch.size() < 2) continue;
                if (sameAsOriginalPrefix(branch, route, currentCell, rejoinIndex)) continue;

                TacticalRouteSimulator.Result result =
                        simulator.simulate(state, branch, rejoin, false, 0);
                if (!result.reached()) continue;

                double branchScore = score(result);
                if (!strictlyImproves(branchScore, result, baselineScore, baselineResult)) {
                    continue;
                }

                PlayerRoute combined = appendSuffix(branch, route, rejoinIndex);
                if (combined == null || combined.size() <= branch.size()) continue;

                double extraCells = branch.size() - baseline.route.size();
                double totalScore = branchScore + Math.max(0.0D, extraCells) * 0.25D;
                if (best == null || totalScore < best.score) {
                    best = new Choice(combined, branch.size() - 1, totalScore,
                            threat.firstSegment, threat.lastSegment,
                            branchResultSummary(result), branchResultSummary(baselineResult));
                }
            }
        }
        return best;
    }

    private static boolean strictlyImproves(double candidateScore,
                                            TacticalRouteSimulator.Result candidate,
                                            double baselineScore,
                                            TacticalRouteSimulator.Result baseline) {
        if (!baseline.reached()) return true;
        if (candidateScore + 0.01D < baselineScore) return true;

        // Prefer a safer branch when timing is comparable. A normal bump is four
        // health and is deliberately treated as valuable, but not infinitely so.
        return candidate.remainingHealth() > baseline.remainingHealth()
                && candidate.arrivalTicks() <= baseline.arrivalTicks() + 18;
    }

    private static double score(TacticalRouteSimulator.Result result) {
        if (!result.reached()) return Double.POSITIVE_INFINITY;
        return result.arrivalTicks() + (result.damageTaken() * 1.50D);
    }

    private static String branchResultSummary(TacticalRouteSimulator.Result r) {
        return "reached=" + r.reached()
                + ",ticks=" + r.arrivalTicks()
                + ",health=" + r.remainingHealth()
                + ",damage=" + r.damageTaken();
    }

    private List<PlayerRoute> branchCandidates(GameState state, Cell start, Cell goal,
                                                 Cell currentFrom, Cell currentTo,
                                                 PlayerRoute original, int waypointIndex) {
        int dirRow = Integer.signum(currentTo.row() - currentFrom.row());
        int dirColumn = Integer.signum(currentTo.column() - currentFrom.column());

        ArrayList<PlayerRoute> generated = new ArrayList<>();
        addIfDistinct(generated, shortestWithFirstHeading(
                state, start, goal, dirRow, dirColumn, false));
        addIfDistinct(generated, shortestWithFirstHeading(
                state, start, goal, dirRow, dirColumn, true));

        for (PlayerRoute alt : alternatives.generate(state.maze, start, goal, 2)) {
            if (preservesFirstHeading(alt, start, dirRow, dirColumn)) {
                addIfDistinct(generated, alt);
            }
        }

        return generated;
    }

    private static boolean preservesFirstHeading(PlayerRoute route, Cell start,
                                                  int dirRow, int dirColumn) {
        if (route == null || route.size() < 2) return false;
        Cell next = route.cells().get(1);
        return Integer.signum(next.row() - start.row()) == dirRow
                && Integer.signum(next.column() - start.column()) == dirColumn;
    }

    private PlayerRoute shortestWithFirstHeading(GameState state, Cell start, Cell goal,
                                                  int dirRow, int dirColumn,
                                                  boolean allowGaps) {
        List<Cell> neighbors = new ArrayList<>(List.of(
                new Cell(start.row() - 1, start.column()),
                new Cell(start.row() + 1, start.column()),
                new Cell(start.row(), start.column() - 1),
                new Cell(start.row(), start.column() + 1)));

        for (Cell first : neighbors) {
            if (Integer.signum(first.row() - start.row()) != dirRow
                    || Integer.signum(first.column() - start.column()) != dirColumn) continue;
            if (!physicalStepAllowed(state.maze, start, first, allowGaps)) continue;

            List<Cell> suffix = allowGaps
                    ? shortest(state, first, goal, true, start)
                    : shortest(state, first, goal, false, start);
            if (suffix.isEmpty()) continue;

            ArrayList<Cell> cells = new ArrayList<>();
            cells.add(start);
            cells.addAll(suffix);
            return new PlayerRoute(cells);
        }
        return null;
    }

    private static List<Cell> shortest(GameState state, Cell start, Cell goal,
                                       boolean allowGaps, Cell originalStart) {
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Map<Cell, Cell> previous = new HashMap<>();
        queue.add(start);
        previous.put(start, null);

        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) return reconstruct(previous, goal);

            for (Cell next : neighbors(state.maze, current, allowGaps)) {
                if (previous.containsKey(next)) continue;
                previous.put(next, current);
                queue.addLast(next);
            }
        }
        return List.of();
    }

    private static List<Cell> neighbors(MazeModel maze, Cell current, boolean allowGaps) {
        ArrayList<Cell> result = new ArrayList<>();
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
        for (int[] d : dirs) {
            Cell next = new Cell(current.row() + d[0], current.column() + d[1]);
            if (maze.isPhysicalFloor(next.row(), next.column())) result.add(next);
        }

        if (allowGaps) {
            for (int[] d : dirs) {
                Cell landing = new Cell(current.row() + d[0] * 2,
                        current.column() + d[1] * 2);
                Cell middle = new Cell(current.row() + d[0], current.column() + d[1]);
                if (inBounds(landing) && inBounds(middle)
                        && maze.isPhysicalFloor(current.row(), current.column())
                        && !maze.isPhysicalFloor(middle.row(), middle.column())
                        && maze.isPhysicalFloor(landing.row(), landing.column())) {
                    result.add(landing);
                }
            }
        }
        return result;
    }

    private static boolean physicalStepAllowed(MazeModel maze, Cell from, Cell to,
                                                boolean allowGaps) {
        if (inBounds(to) && maze.isPhysicalFloor(to.row(), to.column())) return true;
        if (!allowGaps) return false;

        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (Math.abs(dr) + Math.abs(dc) != 2) return false;

        Cell middle = new Cell(
                from.row() + Integer.signum(dr),
                from.column() + Integer.signum(dc));
        return inBounds(middle)
                && maze.isPhysicalFloor(from.row(), from.column())
                && !maze.isPhysicalFloor(middle.row(), middle.column())
                && inBounds(to)
                && maze.isPhysicalFloor(to.row(), to.column());
    }

    private ThreatWindow findThreatWindow(GameState state, PlayerRoute route, int waypointIndex) {
        int firstSegment = Math.max(0, waypointIndex - 1);
        int lastSegment = Math.min(route.size() - 2, firstSegment + LOOKAHEAD_SEGMENTS);
        double playerSpeed = Math.max(
                Math.hypot(state.player.vx, state.player.vz), MIN_PLAYER_SPEED);
        double routeDistance = 0.0D;

        int firstThreat = -1;
        int lastThreat = -1;

        for (int i = firstSegment; i <= lastSegment; i++) {
            Cell a = route.cells().get(i);
            Cell b = route.cells().get(i + 1);
            double ax = a.row() + 0.5D;
            double az = a.column() + 0.5D;
            double bx = b.row() + 0.5D;
            double bz = b.column() + 0.5D;
            double sx = bx - ax;
            double sz = bz - az;
            double length = Math.hypot(sx, sz);
            if (length <= 1.0E-9D) continue;

            double midX = (ax + bx) * 0.5D;
            double midZ = (az + bz) * 0.5D;
            double distanceToMid = Math.hypot(midX - state.player.x, midZ - state.player.z);
            double timeToSegment = Math.max(
                    0.0D, (routeDistance + Math.max(0.0D, distanceToMid - 0.5D))
                            / playerSpeed);

            for (MonsterState monster : state.monsters) {
                if (monster == null || monster.removed
                        || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

                double currentDistance = Math.hypot(
                        monster.x - state.player.x, monster.z - state.player.z);
                if (currentDistance > INTERACTION_RADIUS) continue;

                double predictedX = monster.x + monster.vx * timeToSegment;
                double predictedZ = monster.z + monster.vz * timeToSegment;

                double projection = ((predictedX - ax) * sx
                        + (predictedZ - az) * sz) / (length * length);
                projection = Math.max(0.0D, Math.min(1.0D, projection));
                double nearestX = ax + projection * sx;
                double nearestZ = az + projection * sz;
                double distanceToRoute = Math.hypot(
                        predictedX - nearestX, predictedZ - nearestZ);

                if (distanceToRoute > ROUTE_CONTACT_RADIUS) continue;

                double predictedPlayerDistance = Math.hypot(
                        predictedX - midX, predictedZ - midZ);
                double currentDx = monster.x - state.player.x;
                double currentDz = monster.z - state.player.z;
                double currentDistanceSafe = Math.max(currentDistance, 1.0E-6D);
                double velocityTowardPlayer = -(
                        monster.vx * currentDx + monster.vz * currentDz)
                        / currentDistanceSafe;

                boolean projectedContact = predictedPlayerDistance <= PREDICTED_CONTACT_RADIUS;
                boolean movingIntoRoute = velocityTowardPlayer > 0.02D
                        || Math.hypot(monster.vx, monster.vz) < 0.02D;

                if (projectedContact && movingIntoRoute) {
                    if (firstThreat < 0) firstThreat = i;
                    lastThreat = i;
                }
            }
            routeDistance += length;
        }

        return firstThreat < 0 ? null : new ThreatWindow(firstThreat, lastThreat);
    }

    private static Candidate buildCandidate(PlayerRoute route, Cell currentCell, int rejoinIndex) {
        int currentIndex = route.cells().indexOf(currentCell);
        if (currentIndex < 0 || currentIndex >= rejoinIndex) return null;

        ArrayList<Cell> cells = new ArrayList<>(
                route.cells().subList(currentIndex, rejoinIndex + 1));
        return new Candidate(new PlayerRoute(cells), route.cells().get(rejoinIndex));
    }

    private static PlayerRoute appendSuffix(PlayerRoute branch, PlayerRoute original, int rejoinIndex) {
        List<Cell> branchCells = branch.cells();
        if (branchCells.isEmpty()
                || !branchCells.get(branchCells.size() - 1)
                .equals(original.cells().get(rejoinIndex))) return null;

        ArrayList<Cell> combined = new ArrayList<>(branchCells);
        for (int i = rejoinIndex + 1; i < original.size(); i++) {
            combined.add(original.cells().get(i));
        }
        return new PlayerRoute(combined);
    }

    private static boolean sameAsOriginalPrefix(PlayerRoute branch, PlayerRoute original,
                                                Cell currentCell, int rejoinIndex) {
        Candidate baseline = buildCandidate(original, currentCell, rejoinIndex);
        return baseline != null && baseline.route.cells().equals(branch.cells());
    }

    private static List<Cell> reconstruct(Map<Cell, Cell> previous, Cell goal) {
        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = goal; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path);
        return path;
    }

    private static boolean isGapEdge(GameState state, Cell from, Cell to) {
        int dr = to.row() - from.row();
        int dc = to.column() - from.column();
        if (!((Math.abs(dr) == 2 && dc == 0)
                || (Math.abs(dc) == 2 && dr == 0))) return false;

        Cell middle = new Cell(
                from.row() + Integer.signum(dr),
                from.column() + Integer.signum(dc));
        return inBounds(from) && inBounds(middle) && inBounds(to)
                && state.maze.isPhysicalFloor(from.row(), from.column())
                && !state.maze.isPhysicalFloor(middle.row(), middle.column())
                && state.maze.isPhysicalFloor(to.row(), to.column());
    }

    private static boolean inBounds(Cell cell) {
        return inBounds(cell.row(), cell.column());
    }

    private static boolean inBounds(int row, int column) {
        return row >= 0 && row < MazeModel.SIZE
                && column >= 0 && column < MazeModel.SIZE;
    }

    public static final class Choice {
        public final PlayerRoute route;
        public final int rejoinIndex;
        public final double score;
        public final int firstThreatSegment;
        public final int lastThreatSegment;
        public final String selectedResult;
        public final String baselineResult;

        private Choice(PlayerRoute route, int rejoinIndex, double score,
                       int firstThreatSegment, int lastThreatSegment,
                       String selectedResult, String baselineResult) {
            this.route = route;
            this.rejoinIndex = rejoinIndex;
            this.score = score;
            this.firstThreatSegment = firstThreatSegment;
            this.lastThreatSegment = lastThreatSegment;
            this.selectedResult = selectedResult;
            this.baselineResult = baselineResult;
        }
    }

    private record ThreatWindow(int firstSegment, int lastSegment) {}

    private record Candidate(PlayerRoute route, Cell goal) {}
}
