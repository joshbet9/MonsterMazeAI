package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterRelevance;
import me.monstermazeai.planner.TacticalRouteSimulator;
import me.monstermazeai.player.Action;

import java.util.*;
import java.util.stream.IntStream;

public final class MonsterAwareRoutePlanner {
    private static final int MAX_ROUTE_CANDIDATES = 8;
    private static final int MAX_REGION_CANDIDATES = 12;

    private final AlternativePhysicalRoutes alternatives = new AlternativePhysicalRoutes();
    private final TacticalRouteSimulator simulator = new TacticalRouteSimulator();
    private final GapJumpPolicy gapJumpPolicy;

    public MonsterAwareRoutePlanner() {
        this(GapJumpPolicy.BASELINE);
    }

    public MonsterAwareRoutePlanner(GapJumpPolicy gapJumpPolicy) {
        if (gapJumpPolicy == null) throw new IllegalArgumentException("gapJumpPolicy");
        this.gapJumpPolicy = gapJumpPolicy;
    }

    public GapJumpPolicy gapJumpPolicy() {
        return gapJumpPolicy;
    }

    private List<PlayerRoute> cachedCandidates = List.of();
    private long cachedTopologySignature = Long.MIN_VALUE;
    private int cachedStartRow = Integer.MIN_VALUE;
    private int cachedStartColumn = Integer.MIN_VALUE;
    private int cachedGoalRow = Integer.MIN_VALUE;
    private int cachedGoalColumn = Integer.MIN_VALUE;
    private int cachedRegionRadius = Integer.MIN_VALUE;
    private boolean cachedRegionGoal;

    public PlayerRoute routeFast(GameState state, Cell start, Cell goal) {
        validate(state, start, goal);
        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        PlayerPathfinder pathfinder = new PlayerPathfinder();
        PlayerRoute chosen = chooseByGapRisk(
                state,
                toRoute(pathfinder.fastestPath(state.maze, start, goal)),
                toRoute(pathfinder.fastestPathWithGaps(state.maze, start, goal)));
        if (chosen == null) throw new IllegalArgumentException("No physical route from start to goal");
        return chosen;
    }

    public PlayerRoute routeToRegionFast(GameState state, Cell start, Cell regionCenter, int radius) {
        validate(state, start, regionCenter);
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");

        if (me.monstermazeai.game.PadModel.isOn(state.player,
                regionCenter.row() + 0.5, GameState.PAD_SURFACE_Y,
                regionCenter.column() + 0.5)) {
            return new PlayerRoute(List.of(start));
        }

        PlayerPathfinder pathfinder = new PlayerPathfinder();
        PlayerRoute chosen;
        if (hasRelevantMonster(state)) {
            ThreatAwarePathfinder threatAware = new ThreatAwarePathfinder();
            chosen = chooseByGapRisk(
                    state,
                    toRoute(threatAware.shortestPathToRegion(
                            state, start, regionCenter, radius, false)),
                    toRoute(threatAware.shortestPathToRegion(
                            state, start, regionCenter, radius, true)));
        } else {
            chosen = chooseByGapRisk(
                    state,
                    toRoute(pathfinder.fastestPathToRegion(
                            state.maze, start, regionCenter, radius)),
                    toRoute(pathfinder.fastestPathToRegionWithGaps(
                            state.maze, start, regionCenter, radius)));
        }
        if (chosen == null) throw new IllegalArgumentException("No physical route to Safe Pad region");
        return chosen;
    }

    private static boolean hasRelevantMonster(GameState state) {
        for (var monster : state.monsters) {
            if (MonsterRelevance.withinPlayerRadius(
                    monster, state.player, MonsterRelevance.INTERACTION_RADIUS)) return true;
        }
        return false;
    }

    private static List<Cell> toRoute(List<Cell> cells) {
        return cells;
    }

    public PlayerRoute route(GameState state, Cell start, Cell goal) {
        validate(state, start, goal);
        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        List<PlayerRoute> candidates = cachedCandidatesFor(
                state, start, goal, 0, MAX_ROUTE_CANDIDATES, false);
        return choose(state, restrictJumperGapBudget(state, candidates), goal, false, 0);
    }

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
        return choose(state, restrictJumperGapBudget(state, candidates),
                regionCenter, true, radius);
    }

    /**
     * Jumper's remaining charged jumps are a real finite resource. A route with
     * more gap edges than remaining charges cannot be executed under the source
     * jump lock. This filter is intentionally applied after candidate caching so
     * the cache remains topology-only and reacts immediately when a charge is
     * consumed.
     */
    private static List<PlayerRoute> restrictJumperGapBudget(GameState state,
                                                               List<PlayerRoute> candidates) {
        int budget = jumperGapBudget(state);
        if (candidates.isEmpty()) return candidates;

        ArrayList<PlayerRoute> executable = new ArrayList<>();
        for (PlayerRoute candidate : candidates) {
            if (budget < 0 || gapCount(candidate) <= budget) executable.add(candidate);
        }
        return List.copyOf(executable);
    }

    private static int jumperGapBudget(GameState state) {
        if (state == null || state.kit != me.monstermazeai.kit.Kit.JUMPER) return -1;
        return Math.max(0, state.ability.charges);
    }

    private List<PlayerRoute> cachedCandidatesFor(GameState state, Cell start, Cell goal,
                                                    int regionRadius, int limit, boolean regionGoal) {
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
            ArrayList<PlayerRoute> generated = new ArrayList<>();
            PlayerPathfinder pathfinder = new PlayerPathfinder();
            List<Cell> normal = pathfinder.fastestPath(state.maze, start, goal);
            if (!normal.isEmpty()) generated.add(new PlayerRoute(normal));
            List<Cell> gap = pathfinder.fastestPathWithGaps(state.maze, start, goal);
            if (!gap.isEmpty()) generated.add(new PlayerRoute(gap));
            generated.addAll(alternatives.generate(state.maze, start, goal, limit));
            candidates = distinct(generated, limit * 3);
        } else {
            ArrayList<PlayerRoute> generated = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            PlayerPathfinder pathfinder = new PlayerPathfinder();

            for (int r = goal.row() - regionRadius; r <= goal.row() + regionRadius; r++) {
                for (int c = goal.column() - regionRadius; c <= goal.column() + regionRadius; c++) {
                    Cell target = new Cell(r, c);
                    if (r < 0 || r >= MazeModel.SIZE || c < 0 || c >= MazeModel.SIZE
                            || !state.maze.isPhysicalFloor(r, c)) continue;

                    List<Cell> normalPath = pathfinder.fastestPath(state.maze, start, target);
                    if (!normalPath.isEmpty()) addCandidate(generated, seen, new PlayerRoute(normalPath));

                    List<Cell> path = pathfinder.fastestPathWithGaps(state.maze, start, target);
                    if (!path.isEmpty()) addCandidate(generated, seen, new PlayerRoute(path));

                    for (PlayerRoute alt : alternatives.generate(state.maze, start, target, 3)) {
                        addCandidate(generated, seen, alt);
                    }
                }
            }

            if (generated.isEmpty()) throw new IllegalArgumentException("No physical route to Safe Pad region");
            generated.sort(this::compareByGapRisk);
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
        boolean hasRelevantMonster = false;
        for (var monster : state.monsters) {
            if (MonsterRelevance.withinPlayerRadius(
                    monster, state.player, MonsterRelevance.INTERACTION_RADIUS)) {
                hasRelevantMonster = true;
                break;
            }
        }
        if (!hasRelevantMonster) return shortest(candidates);

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

        if (candidate.reached()) {
            double candidateTime = candidate.arrivalTicks()
                    + gapJumpPolicy.riskCostPerGap() * gapCount(candidateRoute);
            double incumbentTime = incumbent.arrivalTicks()
                    + gapJumpPolicy.riskCostPerGap() * gapCount(incumbentRoute);
            int timeCompare = Double.compare(candidateTime, incumbentTime);
            if (timeCompare != 0) return timeCompare < 0;
        }

        if (Double.compare(candidate.remainingHealth(), incumbent.remainingHealth()) != 0) {
            return candidate.remainingHealth() > incumbent.remainingHealth();
        }
        if (Double.compare(candidate.damageTaken(), incumbent.damageTaken()) != 0) {
            return candidate.damageTaken() < incumbent.damageTaken();
        }

        int gapCompare = Integer.compare(gapCount(candidateRoute), gapCount(incumbentRoute));
        if (gapCompare != 0) return gapCompare < 0;
        return candidateRoute.size() < incumbentRoute.size();
    }

    private PlayerRoute chooseByGapRisk(GameState state,
                                         List<Cell> normalPath, List<Cell> gapAwarePath) {
        PlayerRoute normal = normalPath.isEmpty() ? null : new PlayerRoute(normalPath);
        PlayerRoute gapAware = gapAwarePath.isEmpty() ? null : new PlayerRoute(gapAwarePath);
        int budget = jumperGapBudget(state);

        if (budget >= 0) {
            boolean normalAllowed = normal != null && gapCount(normal) <= budget;
            boolean gapAllowed = gapAware != null && gapCount(gapAware) <= budget;
            if (normalAllowed && !gapAllowed) return normal;
            if (gapAllowed && !normalAllowed) return gapAware;
            if (!normalAllowed && !gapAllowed) {
                // No executable candidate was produced by the fast search.
                // Preserve the physical route rather than returning null; the
                // full planner will have a chance to replace it on the next
                // dynamic observation.
                return normal != null ? normal : gapAware;
            }
        }

        if (normal == null) return gapAware;
        if (gapAware == null) return normal;
        return compareByGapRisk(normal, gapAware) <= 0 ? normal : gapAware;
    }

    private int compareByGapRisk(PlayerRoute a, PlayerRoute b) {
        int cost = Double.compare(routeCost(a), routeCost(b));
        if (cost != 0) return cost;
        int gaps = Integer.compare(gapCount(a), gapCount(b));
        if (gaps != 0) return gaps;
        return Integer.compare(a.size(), b.size());
    }

    private double routeCost(PlayerRoute route) {
        return gapJumpPolicy.routeCost(route.size(), gapCount(route));
    }

    private static int gapCount(PlayerRoute route) {
        int count = 0;
        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            int dr = Math.abs(cells.get(i + 1).row() - cells.get(i).row());
            int dc = Math.abs(cells.get(i + 1).column() - cells.get(i).column());
            if ((dr == 2 && dc == 0) || (dc == 2 && dr == 0)) count++;
        }
        return count;
    }

    private List<PlayerRoute> distinct(List<PlayerRoute> routes, int limit) {
        ArrayList<PlayerRoute> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PlayerRoute route : routes) {
            addCandidate(out, seen, route);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private PlayerRoute shortest(List<PlayerRoute> candidates) {
        return candidates.stream()
                .min(this::compareByGapRisk)
                .orElseThrow(() -> new IllegalArgumentException("No route candidates"));
    }

    private static void addCandidate(List<PlayerRoute> candidates, Set<String> seen,
                                     PlayerRoute route) {
        StringBuilder key = new StringBuilder(route.size() * 8);
        for (Cell cell : route.cells()) {
            key.append(cell.row()).append(':').append(cell.column()).append(';');
        }
        if (seen.add(key.toString())) candidates.add(route);
    }

    private static void validate(GameState state, Cell start, Cell goal) {
        if (state == null || state.maze == null) throw new IllegalArgumentException("Maze state is required");
        if (start == null || goal == null) throw new IllegalArgumentException("Start and goal are required");
        if (!state.maze.isPhysicalFloor(start.row(), start.column())) {
            throw new IllegalArgumentException("Start is not physical floor: " + start);
        }
        if (!state.maze.isPhysicalFloor(goal.row(), goal.column())) {
            throw new IllegalArgumentException("Goal is not physical floor: " + goal);
        }
    }
}
