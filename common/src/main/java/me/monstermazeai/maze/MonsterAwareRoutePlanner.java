package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterRelevance;
import me.monstermazeai.planner.TacticalRouteSimulator;
import me.monstermazeai.player.Action;

import java.util.*;
import java.util.stream.IntStream;

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

    /*
     * Candidate topology is independent of monster positions. Cache it by the
     * physical start cell, objective region and the maze's compact dynamic
     * signature, then re-evaluate the cached corridors against every fresh
     * monster observation. This preserves continuous replanning without paying
     * for repeated A-star/BFS route generation when only monsters moved.
     */
    private List<PlayerRoute> cachedCandidates = List.of();
    private long cachedTopologySignature = Long.MIN_VALUE;
    private int cachedStartRow = Integer.MIN_VALUE;
    private int cachedStartColumn = Integer.MIN_VALUE;
    private int cachedGoalRow = Integer.MIN_VALUE;
    private int cachedGoalColumn = Integer.MIN_VALUE;
    private int cachedRegionRadius = Integer.MIN_VALUE;
    private boolean cachedRegionGoal;

    public PlayerRoute route(GameState state, Cell start, Cell goal) {
        validate(state, start, goal);

        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        List<PlayerRoute> candidates = cachedCandidatesFor(
                state, start, goal, 0, MAX_ROUTE_CANDIDATES, false);
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

        List<PlayerRoute> candidates = cachedCandidatesFor(
                state, start, regionCenter, radius, MAX_REGION_CANDIDATES, true);
        return choose(state, candidates, regionCenter, true, radius);
    }

    private List<PlayerRoute> cachedCandidatesFor(GameState state, Cell start, Cell goal,
                                                       int regionRadius, int limit,
                                                       boolean regionGoal) {
        long topology = state.maze.dynamicSignature();
        if (topology == cachedTopologySignature
                && start.row() == cachedStartRow && start.column() == cachedStartColumn
                && goal.row() == cachedGoalRow && goal.column() == cachedGoalColumn
                && regionRadius == cachedRegionRadius
                && regionGoal == cachedRegionGoal
                && !cachedCandidates.isEmpty()) {
            return cachedCandidates;
        }

        List<PlayerRoute> candidates;
        if (!regionGoal) {
            candidates = alternatives.generate(state.maze, start, goal, limit);
        } else {
            ArrayList<PlayerRoute> generated = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            PlayerPathfinder pathfinder = new PlayerPathfinder();

            // Evaluate the shortest physical route to every physical cell in
            // the Safe Pad region, not just the beacon centre.
            for (int r = goal.row() - regionRadius; r <= goal.row() + regionRadius; r++) {
                for (int c = goal.column() - regionRadius; c <= goal.column() + regionRadius; c++) {
                    Cell target = new Cell(r, c);
                    if (r < 0 || r >= MazeModel.SIZE || c < 0 || c >= MazeModel.SIZE
                            || !state.maze.isPhysicalFloor(r, c)) continue;

                    List<Cell> path = pathfinder.shortestPath(state.maze, start, target);
                    if (path.isEmpty()) continue;
                    addCandidate(generated, seen, new PlayerRoute(path));

                    if (generated.size() < limit) {
                        for (PlayerRoute alt : alternatives.generate(state.maze, start, target, 3)) {
                            addCandidate(generated, seen, alt);
                            if (generated.size() >= limit) break;
                        }
                    }
                }
            }

            if (generated.isEmpty()) {
                throw new IllegalArgumentException("No physical route to Safe Pad region");
            }
            generated.sort(Comparator.comparingInt(PlayerRoute::size));
            if (generated.size() > limit) {
                generated = new ArrayList<>(generated.subList(0, limit));
            }
            candidates = generated;
        }

        cachedTopologySignature = topology;
        cachedStartRow = start.row();
        cachedStartColumn = start.column();
        cachedGoalRow = goal.row();
        cachedGoalColumn = goal.column();
        cachedRegionRadius = regionRadius;
        cachedRegionGoal = regionGoal;
        cachedCandidates = List.copyOf(candidates);
        return cachedCandidates;
    }

    public Action tacticalAction(GameState state, PlayerRoute route, Cell goal, int regionRadius) {
        return simulator.nextAction(state, route, goal, regionRadius > 0, regionRadius);
    }

    public boolean shouldUseTacticalAction(GameState state) {
        return simulator.shouldUseTacticalAction(state);
    }

    private PlayerRoute choose(GameState state, List<PlayerRoute> candidates,
                               Cell goal, boolean regionGoal, int regionRadius) {
        /*
         * If no observed monster is inside the player's 20-block interaction
         * radius, source-faithful monster simulation cannot change the immediate
         * decision. Select the shortest physical candidate directly.
         *
         * This is a structural fast path, not a reduction in replanning
         * frequency: fresh observations still reach this method immediately,
         * and any route with a relevant future monster takes the full simulator.
         */
        boolean hasRelevantMonster = false;
        for (var monster : state.monsters) {
            if (MonsterRelevance.withinPlayerRadius(
                    monster, state.player, MonsterRelevance.INTERACTION_RADIUS)) {
                hasRelevantMonster = true;
                break;
            }
        }
        if (!hasRelevantMonster) {
            return candidates.stream()
                    .min(Comparator.comparingInt(PlayerRoute::size))
                    .orElseThrow(() -> new IllegalArgumentException("No route candidates"));
        }

        /*
         * Candidate routes are independent simulations. Evaluate them in parallel
         * so the AI can use the available CPU cores instead of serialising the
         * most expensive part of planning. Results are then selected in candidate
         * order so route choice remains deterministic.
         */
        TacticalRouteSimulator.Result[] results = new TacticalRouteSimulator.Result[candidates.size()];
        IntStream.range(0, candidates.size()).parallel().forEach(i -> {
            results[i] = simulator.simulate(
                    state, candidates.get(i), goal, regionGoal, regionRadius);
        });

        PlayerRoute best = null;
        TacticalRouteSimulator.Result bestResult = null;
        for (int i = 0; i < candidates.size(); i++) {
            PlayerRoute candidate = candidates.get(i);
            TacticalRouteSimulator.Result result = results[i];
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
