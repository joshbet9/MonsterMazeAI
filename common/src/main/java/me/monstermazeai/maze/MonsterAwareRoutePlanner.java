package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterRelevance;
import me.monstermazeai.planner.TacticalRouteSimulator;
import me.monstermazeai.ml.RouteLearningRecorder;
import me.monstermazeai.ml.RouteValueModel;
import me.monstermazeai.player.Action;

import java.util.*;
import java.util.stream.IntStream;

public final class MonsterAwareRoutePlanner {
    private static final int MAX_ROUTE_CANDIDATES = 8;
    private static final int MAX_REGION_CANDIDATES = 12;

    /*
     * The learned model is a guarded candidate prefilter, not a replacement for
     * source-faithful tactical evaluation. Four learned candidates are retained
     * plus deterministic physical-route hedges. When the model cannot clearly
     * separate the candidates, the planner evaluates the complete candidate set.
     */
    private static final int ML_PREFILTER_TOP_K = 4;
    private static final double ML_PREFILTER_AMBIGUITY_RATIO = 0.08;

    private final AlternativePhysicalRoutes alternatives = new AlternativePhysicalRoutes();
    private final TacticalRouteSimulator simulator = new TacticalRouteSimulator();
    private final GapJumpPolicy gapJumpPolicy;
    private final RouteValueModel routeValueModel = RouteValueModel.loadFromProperty();
    private long mlShadowComparisons;
    private long mlShadowAgreements;
    private long mlPrefilterCalls;
    private long mlPrefilterFallbacks;
    private long mlPrefilterCandidatesSeen;
    private long mlPrefilterCandidatesSimulated;
    private boolean mlPrefilterLogged;

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
    private long cachedThreatSignature = Long.MIN_VALUE;

    public PlayerRoute routeFast(GameState state, Cell start, Cell goal) {
        validate(state, start, goal);
        if (start.equals(goal)) return new PlayerRoute(List.of(start));

        PlayerPathfinder pathfinder = new PlayerPathfinder();
        if (hasRelevantMonster(state)) {
            ThreatAwarePathfinder threatAware = new ThreatAwarePathfinder();
            PlayerRoute chosen = chooseByGapRisk(
                    state,
                    toRoute(threatAware.shortestPathToRegion(
                            state, start, goal, 0, false)),
                    toRoute(threatAware.shortestPathToRegion(
                            state, start, goal, 0, true)));
            if (chosen == null) throw new IllegalArgumentException("No physical route from start to goal");
            return chosen;
        }

        PlayerRoute chosen = chooseByGapRisk(
                state,
                toRoute(pathfinder.shortestPathWithoutGaps(state.maze, start, goal)),
                toRoute(pathfinder.shortestPath(state.maze, start, goal)));
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
                    toRoute(pathfinder.shortestPathToRegionWithoutGaps(
                            state.maze, start, regionCenter, radius)),
                    toRoute(pathfinder.shortestPathToRegion(
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

    private static long threatSignature(GameState state) {
        long h = 1469598103934665603L;
        boolean relevant = false;
        for (var monster : state.monsters) {
            if (monster == null || monster.removed
                    || !MonsterRelevance.withinPlayerRadius(
                    monster, state.player, MonsterRelevance.INTERACTION_RADIUS)) continue;
            relevant = true;
            h = mix(h, monster.id);
            h = mix(h, Math.round(monster.x / 0.5D));
            h = mix(h, Math.round(monster.y / 0.5D));
            h = mix(h, Math.round(monster.z / 0.5D));
            h = mix(h, Math.round(monster.vx / 0.05D));
            h = mix(h, Math.round(monster.vz / 0.05D));
            h = mix(h, monster.launched(state.tick) ? 1L : 0L);
            h = mix(h, monster.frozen(state.tick) ? 1L : 0L);
        }
        return relevant ? h : 0L;
    }

    private static long mix(long h, long value) {
        h ^= value;
        return h * 1099511628211L;
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
        if (state == null || state.kit == null) return -1;
        if (state.kit == me.monstermazeai.kit.Kit.JUMPER) {
            return Math.max(0, state.ability.charges);
        }

        // Non-Jumper speeding is part of the enhanced non-Original gameplay
        // mechanics in both Speed and Modern. Treat the source two-cell gap edge
        // as executable in either environment.
        return -1;
    }

    private List<PlayerRoute> cachedCandidatesFor(GameState state, Cell start, Cell goal,
                                                    int regionRadius, int limit, boolean regionGoal) {
        long topology = state.maze.dynamicSignature();
        long threatSignature = threatSignature(state);
        if (topology == cachedTopologySignature
                && start.row() == cachedStartRow && start.column() == cachedStartColumn
                && goal.row() == cachedGoalRow && goal.column() == cachedGoalColumn
                && regionRadius == cachedRegionRadius
                && regionGoal == cachedRegionGoal
                && threatSignature == cachedThreatSignature
                && !cachedCandidates.isEmpty()) {
            return cachedCandidates;
        }

        List<PlayerRoute> candidates;
        if (!regionGoal) {
            ArrayList<PlayerRoute> generated = new ArrayList<>();
            PlayerPathfinder pathfinder = new PlayerPathfinder();

            /*
             * When monsters are live, put a fresh threat-aware physical route
             * into the candidate set before static alternatives. The tactical
             * simulator can then decide whether the safer detour is actually
             * worth its extra distance; the live motor is never forced to keep
             * following yesterday's threat-free geometry.
             */
            if (hasRelevantMonster(state)) {
                ThreatAwarePathfinder threatAware = new ThreatAwarePathfinder();
                List<Cell> threatNormal = threatAware.shortestPathToRegion(
                        state, start, goal, 0, false);
                List<Cell> threatGap = threatAware.shortestPathToRegion(
                        state, start, goal, 0, true);
                if (!threatNormal.isEmpty()) generated.add(new PlayerRoute(threatNormal));
                if (!threatGap.isEmpty()) generated.add(new PlayerRoute(threatGap));
            }

            List<Cell> normal = pathfinder.shortestPathWithoutGaps(state.maze, start, goal);
            if (!normal.isEmpty()) generated.add(new PlayerRoute(normal));
            List<Cell> gap = pathfinder.shortestPath(state.maze, start, goal);
            if (!gap.isEmpty()) generated.add(new PlayerRoute(gap));
            generated.addAll(alternatives.generate(state.maze, start, goal, limit));
            candidates = distinct(generated, limit * 3);
        } else {
            ArrayList<PlayerRoute> generated = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            PlayerPathfinder pathfinder = new PlayerPathfinder();

            /*
             * The region route is also dynamic. Static shortest candidates remain
             * available for throughput, but a fresh threat-aware route is included
             * whenever a monster can materially affect the approach.
             */
            if (hasRelevantMonster(state)) {
                ThreatAwarePathfinder threatAware = new ThreatAwarePathfinder();
                List<Cell> threatNormal = threatAware.shortestPathToRegion(
                        state, start, goal, regionRadius, false);
                List<Cell> threatGap = threatAware.shortestPathToRegion(
                        state, start, goal, regionRadius, true);
                if (!threatNormal.isEmpty()) addCandidate(
                        generated, seen, new PlayerRoute(threatNormal));
                if (!threatGap.isEmpty()) addCandidate(
                        generated, seen, new PlayerRoute(threatGap));
            }

            for (int r = goal.row() - regionRadius; r <= goal.row() + regionRadius; r++) {
                for (int c = goal.column() - regionRadius; c <= goal.column() + regionRadius; c++) {
                    Cell target = new Cell(r, c);
                    if (r < 0 || r >= MazeModel.SIZE || c < 0 || c >= MazeModel.SIZE
                            || !state.maze.isPhysicalFloor(r, c)) continue;

                    List<Cell> normalPath = pathfinder.shortestPathWithoutGaps(state.maze, start, target);
                    if (!normalPath.isEmpty()) addCandidate(generated, seen, new PlayerRoute(normalPath));

                    List<Cell> path = pathfinder.shortestPath(state.maze, start, target);
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
        cachedThreatSignature = threatSignature;
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

        int[] evaluationIndices = simulationCandidateIndices(state, candidates, goal);
        TacticalRouteSimulator.Result[] results = new TacticalRouteSimulator.Result[candidates.size()];

        IntStream.of(evaluationIndices).parallel().forEach(i -> {
            results[i] = simulator.simulate(
                    state, candidates.get(i), goal, regionGoal, regionRadius);
        });

        // Full shadow mode records every candidate. Prefilter mode deliberately
        // records only the candidates it actually sends through the simulator.
        for (int i : evaluationIndices) {
            RouteLearningRecorder.record(state, candidates.get(i), goal, results[i]);
        }

        PlayerRoute best = null;
        TacticalRouteSimulator.Result bestResult = null;
        int simulatorBestIndex = -1;
        for (int i : evaluationIndices) {
            PlayerRoute candidate = candidates.get(i);
            TacticalRouteSimulator.Result result = results[i];
            if (bestResult == null || better(result, candidate, bestResult, best)) {
                best = candidate;
                bestResult = result;
                simulatorBestIndex = i;
            }
        }

        if (routeValueModel != null && !candidates.isEmpty()) {
            int predictedBestIndex = predictedBestCandidate(state, candidates, goal);
            if (evaluationIndices.length == candidates.size()) {
                mlShadowComparisons++;
                if (predictedBestIndex == simulatorBestIndex) mlShadowAgreements++;

                if (mlShadowComparisons == 1 || mlShadowComparisons % 1000 == 0) {
                    double agreement = mlShadowAgreements / (double) mlShadowComparisons;
                    System.out.println("[MonsterMazeAI] ML_SHADOW"
                            + " comparisons=" + mlShadowComparisons
                            + " agreement=" + String.format(Locale.ROOT, "%.3f", agreement)
                            + " predictedIndex=" + predictedBestIndex
                            + " simulatorIndex=" + simulatorBestIndex
                            + " predictedCost=" + String.format(
                                    Locale.ROOT, "%.3f",
                                    routeValueModel.predict(state, candidates.get(predictedBestIndex), goal)));
                }
            }
        }

        return best;
    }

    private int[] simulationCandidateIndices(GameState state,
                                              List<PlayerRoute> candidates,
                                              Cell goal) {
        if (routeValueModel == null || !"prefilter".equalsIgnoreCase(
                System.getProperty("monstermaze.ml.mode", "shadow"))) {
            return IntStream.range(0, candidates.size()).toArray();
        }

        int candidateCount = candidates.size();
        if (candidateCount <= ML_PREFILTER_TOP_K) {
            return IntStream.range(0, candidateCount).toArray();
        }

        double[] predictions = new double[candidateCount];
        Integer[] order = new Integer[candidateCount];
        for (int i = 0; i < candidateCount; i++) {
            predictions[i] = routeValueModel.predict(state, candidates.get(i), goal);
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> predictions[i]));

        double bestPrediction = predictions[order[0]];
        double kthPrediction = predictions[order[Math.min(
                ML_PREFILTER_TOP_K - 1, candidateCount - 1)]];
        double separation = kthPrediction - bestPrediction;
        double scale = Math.max(1.0D, Math.abs(bestPrediction));
        boolean ambiguous = separation / scale < ML_PREFILTER_AMBIGUITY_RATIO;

        int shortestIndex = 0;
        for (int i = 1; i < candidateCount; i++) {
            if (compareByGapRisk(candidates.get(i), candidates.get(shortestIndex)) < 0) {
                shortestIndex = i;
            }
        }

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        if (ambiguous) {
            for (int i = 0; i < candidateCount; i++) selected.add(i);
            mlPrefilterFallbacks++;
        } else {
            for (int i = 0; i < Math.min(ML_PREFILTER_TOP_K, candidateCount); i++) {
                selected.add(order[i]);
            }

            /*
             * Deterministic hedges keep a learned prefilter from eliminating the
             * first physical candidate or the shortest gap-risk route entirely.
             * They also make prefilter behaviour robust during early training.
             */
            selected.add(0);
            selected.add(shortestIndex);
        }

        mlPrefilterCalls++;
        mlPrefilterCandidatesSeen += candidateCount;
        mlPrefilterCandidatesSimulated += selected.size();

        if (!mlPrefilterLogged) {
            mlPrefilterLogged = true;
            System.out.println("[MonsterMazeAI] ML_PREFILTER"
                    + " candidates=" + candidateCount
                    + " selected=" + selected.size()
                    + " topK=" + Math.min(ML_PREFILTER_TOP_K, candidateCount)
                    + " ambiguous=" + ambiguous
                    + " bestPrediction=" + String.format(
                            Locale.ROOT, "%.3f", bestPrediction)
                    + " kthPrediction=" + String.format(
                            Locale.ROOT, "%.3f", kthPrediction));
        }

        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    private int predictedBestCandidate(GameState state,
                                        List<PlayerRoute> candidates, Cell goal) {
        int best = 0;
        double bestPrediction = routeValueModel.predict(state, candidates.get(0), goal);
        for (int i = 1; i < candidates.size(); i++) {
            double prediction = routeValueModel.predict(state, candidates.get(i), goal);
            if (prediction < bestPrediction) {
                bestPrediction = prediction;
                best = i;
            }
        }
        return best;
    }

    public long mlShadowComparisons() {
        return mlShadowComparisons;
    }

    public long mlShadowAgreements() {
        return mlShadowAgreements;
    }

    public double mlShadowAgreementRate() {
        return mlShadowComparisons == 0
                ? Double.NaN
                : mlShadowAgreements / (double) mlShadowComparisons;
    }

    public long mlPrefilterCalls() {
        return mlPrefilterCalls;
    }

    public long mlPrefilterFallbacks() {
        return mlPrefilterFallbacks;
    }

    public long mlPrefilterCandidatesSeen() {
        return mlPrefilterCandidatesSeen;
    }

    public long mlPrefilterCandidatesSimulated() {
        return mlPrefilterCandidatesSimulated;
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
