package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.planner.TacticalRouteSimulator;
import me.monstermazeai.player.Action;

import java.util.*;

/**
 * Physical route planner for Monster Maze.
 *
 * The route graph is deliberately mob-agnostic. It generates a small set of
 * physically valid alternatives, then evaluates those alternatives by running
 * the source-faithful movement/contact simulator against the observed monster
 * state. A monster therefore matters only when the simulated trajectory
 * actually encounters it.
 *
 * Selection is lexicographic rather than a hand-tuned weighted risk formula:
 * 1. successful Safe Pad arrival;
 * 2. earliest simulated arrival tick;
 * 3. highest remaining health;
 * 4. lowest damage taken;
 * 5. shortest physical route as a deterministic tie-break.
 */
public final class MonsterAwareRoutePlanner {
    private static final int MAX_ROUTE_CANDIDATES = 8;
    private static final int MAX_REGION_CANDIDATES = 12;

    private final AlternativePhysicalRoutes alternatives = new AlternativePhysicalRoutes();
    private final TacticalRouteSimulator simulator = new TacticalRouteSimulator();

    public PlayerRoute route(GameState state, Cell start, Cell goal) {
        validate(state, start, goal);

        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        List<PlayerRoute> candidates = alternatives.generate(
                state.maze, start, goal, MAX_ROUTE_CANDIDATES);

        return choose(state, candidates, goal, false, 0);
    }

    /**
     * Finds a physical route to the first reachable cell of the Safe Pad
     * region, then evaluates alternate corridors against the live monster
     * field. The beacon is only the region anchor.
     */
    public PlayerRoute routeToRegion(GameState state, Cell start, Cell regionCenter, int radius) {
        validate(state, start, regionCenter);
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");

        if (me.monstermazeai.game.PadModel.isOn(state.player,
                regionCenter.row() + 0.5, GameState.PAD_SURFACE_Y,
                regionCenter.column() + 0.5)) {
            return new PlayerRoute(List.of(start));
        }

        List<PlayerRoute> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Evaluate the shortest physical route to every physical cell in the
        // 5x5 region (or the requested region), not just the beacon centre.
        for (int r = regionCenter.row() - radius; r <= regionCenter.row() + radius; r++) {
            for (int c = regionCenter.column() - radius; c <= regionCenter.column() + radius; c++) {
                Cell target = new Cell(r, c);
                if (!state.maze.isPhysicalFloor(r, c)) continue;

                List<Cell> path = new PlayerPathfinder().shortestPath(state.maze, start, target);
                if (path.isEmpty()) continue;

                addCandidate(candidates, seen, new PlayerRoute(path));

                if (candidates.size() < MAX_REGION_CANDIDATES) {
                    for (PlayerRoute alt : alternatives.generate(
                            state.maze, start, target, 3)) {
                        addCandidate(candidates, seen, alt);
                        if (candidates.size() >= MAX_REGION_CANDIDATES) break;
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No physical route to Safe Pad region");
        }

        candidates.sort(Comparator.comparingInt(PlayerRoute::size));
        if (candidates.size() > MAX_REGION_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_REGION_CANDIDATES));
        }

        return choose(state, candidates, regionCenter, true, radius);
    }

    public Action tacticalAction(GameState state, PlayerRoute route, Cell goal, int regionRadius) {
        return simulator.nextAction(state, route, goal, regionRadius > 0, regionRadius);
    }

    public boolean shouldUseTacticalAction(GameState state) {
        return simulator.shouldUseTacticalAction(state);
    }

    private PlayerRoute choose(GameState state, List<PlayerRoute> candidates,
                               Cell goal, boolean regionGoal, int regionRadius) {
        PlayerRoute best = null;
        TacticalRouteSimulator.Result bestResult = null;

        for (PlayerRoute candidate : candidates) {
            TacticalRouteSimulator.Result result =
                    simulator.simulate(state, candidate, goal, regionGoal, regionRadius);

            if (bestResult == null || better(result, candidate, bestResult, best)) {
                best = candidate;
                bestResult = result;
            }
        }

        return best;
    }

    private boolean better(TacticalRouteSimulator.Result candidate, PlayerRoute candidateRoute,
                           TacticalRouteSimulator.Result incumbent, PlayerRoute incumbentRoute) {
        if (candidate.reached() != incumbent.reached()) return candidate.reached();

        if (candidate.reached() && candidate.arrivalTicks() != incumbent.arrivalTicks()) {
            return candidate.arrivalTicks() < incumbent.arrivalTicks();
        }

        if (Double.compare(candidate.remainingHealth(), incumbent.remainingHealth()) != 0) {
            return candidate.remainingHealth() > incumbent.remainingHealth();
        }

        if (Double.compare(candidate.damageTaken(), incumbent.damageTaken()) != 0) {
            return candidate.damageTaken() < incumbent.damageTaken();
        }

        return candidateRoute.size() < incumbentRoute.size();
    }

    private static void addCandidate(List<PlayerRoute> candidates, Set<String> seen,
                                     PlayerRoute route) {
        StringBuilder key = new StringBuilder(route.size() * 8);
        for (Cell cell : route.cells()) {
            key.append(cell.row()).append(':').append(cell.column()).append(';');
        }
        if (seen.add(key.toString())) candidates.add(route);
    }

    private static boolean insideRegion(Cell cell, Cell center, int radius) {
        return Math.abs(cell.row() - center.row()) <= radius
                && Math.abs(cell.column() - center.column()) <= radius;
    }

    private static void validate(GameState state, Cell start, Cell goal) {
        if (state == null || state.maze == null) {
            throw new IllegalArgumentException("Maze state is required");
        }
        if (start == null || goal == null) {
            throw new IllegalArgumentException("Start and goal are required");
        }
        if (!state.maze.isPhysicalFloor(start.row(), start.column())) {
            throw new IllegalArgumentException("Start is not physical floor: " + start);
        }
        if (!state.maze.isPhysicalFloor(goal.row(), goal.column())) {
            // Region centres may be an anchor whose exact block is not the
            // player's required destination, so callers to routeToRegion can
            // still use it. Exact route() requires a real floor goal.
            throw new IllegalArgumentException("Goal is not physical floor: " + goal);
        }
    }
}
