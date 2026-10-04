package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.monster.MonsterRelevance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Time-aware strategic threat model for player routes.
 *
 * The old planner only handed monsters to the expensive tactical simulator
 * once they entered a 20-block sphere around the current player. That is useful
 * for immediate contact handling, but it means a monster which will cross the
 * chosen route several seconds in the future is invisible to route selection.
 *
 * This scorer predicts source-like monster movement independently of the player
 * (MonsterSimulator's movement does not depend on player position), builds a
 * per-tick danger field, and scores each candidate where the player is expected
 * to be at that same tick. It is intentionally a strategic pre-pass; the exact
 * tactical simulator remains authoritative for near-contact actions.
 */
public final class PredictiveMonsterThreatScorer {
    private static final int HORIZON_TICKS = 144;
    private static final int TIMING_WINDOW_TICKS = 2;
    private static final double DANGER_RADIUS = 2.75D;
    private static final double CONTACT_DISTANCE = 1.0D;
    private static final double CONTACT_Y_DISTANCE = 1.0D;
    private static final double MAX_PREDICTED_MONSTER_TRAVEL = 40.0D;
    private static final double TURN_PENALTY_TICKS = 1.25D;
    private static final int STRAIGHT_MOVE_TICKS = 5;
    private static final int GAP_MOVE_TICKS = 6;
    private static final int TURN_EXTRA_TICKS = 2;
    private static final int WAIT_TICKS = 1;
    private static final double WAIT_COST = 1.15D;

    /*
     * Exact source-derived steady-state estimate for a sprinting player:
     *
     * movementFactor =
     *   0.1 * 1.3 * (0.16277136 / (0.6 * 0.91)^3)
     * post-move friction = 0.6 * 0.91
     * terminal ~= acceleration / (1 - friction)
     *
     * We start slightly below terminal speed so acceleration and small steering
     * corrections do not make the prediction systematically early.
     */
    private static final double GROUND_FRICTION = 0.6D * 0.91D;
    private static final double SPRINT_ACCELERATION =
            0.1D * 1.3D * (0.16277136D / Math.pow(GROUND_FRICTION, 3.0D));
    private static final double SPRINT_TERMINAL_SPEED =
            SPRINT_ACCELERATION / (1.0D - GROUND_FRICTION);
    private static final double PREDICTED_BLOCKS_PER_TICK =
            SPRINT_TERMINAL_SPEED * 0.82D;

    private PredictiveMonsterThreatScorer() {}

    public record Score(PlayerRoute route, double travelTicks,
                        double nearRisk, int predictedContacts,
                        double compositeCost) {}

    /**
     * Direct time-aware route search. Unlike candidate ranking, this searches
     * the physical maze while carrying an estimated arrival tick through the
     * state. That means a corridor can be rejected because a monster is forecast
     * to occupy it at the time the player would actually arrive.
     *
     * This is deliberately a bounded receding-horizon search. The exact tactical
     * simulator remains responsible for immediate interactions; this method is
     * the strategic "what corridor should I be on?" layer.
     */
    public static PlayerRoute bestRoute(GameState state, Cell start, Cell goal,
                                        int regionRadius, double gapPenalty,
                                        int maxGaps) {
        if (state == null || state.maze == null || start == null || goal == null) {
            throw new IllegalArgumentException("state/start/goal");
        }
        if (regionRadius < 0 || gapPenalty < 0.0 || maxGaps < -1) {
            throw new IllegalArgumentException("invalid predictive route arguments");
        }

        if (insideRegion(start, goal, regionRadius)) {
            return new PlayerRoute(List.of(start));
        }

        ThreatField field = buildThreatField(state, List.of());
        SearchNode initial = new SearchNode(
                start, 0, 0, 0, 0, 0, heuristic(start, goal, regionRadius), false);

        java.util.PriorityQueue<SearchNode> open =
                new java.util.PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        java.util.Map<SearchKey, Double> best = new java.util.HashMap<>();
        java.util.Map<SearchKey, SearchKey> previous = new java.util.HashMap<>();
        java.util.Map<SearchKey, Cell> cells = new java.util.HashMap<>();

        SearchKey initialKey = new SearchKey(start.row(), start.column(), 0, 0, 0, 0);
        best.put(initialKey, 0.0D);
        cells.put(initialKey, start);
        open.add(initial);

        SearchNode bestGoal = null;
        int expanded = 0;
        final int maxExpanded = 120_000;

        while (!open.isEmpty() && expanded++ < maxExpanded) {
            SearchNode node = open.poll();
            SearchKey nodeKey = node.key();
            double known = best.getOrDefault(nodeKey, Double.POSITIVE_INFINITY);
            if (node.g > known + 1.0E-9D) continue;
            if (!floorAvailableAtArrival(state, node.cell, node.tick)) continue;

            if (insideRegion(node.cell, goal, regionRadius)) {
                bestGoal = node;
                break;
            }

            if (node.tick >= HORIZON_TICKS) continue;

            /*
             * Waiting is a legitimate source action. When the current cell is
             * supported, give the time-expanded search the option to let an
             * approaching monster pass instead of forcing an expensive spatial
             * detour. Deadline cost above prevents indefinite waiting.
             */
            int waitArrival = node.tick + WAIT_TICKS;
            if (waitArrival <= HORIZON_TICKS
                    && floorAvailableAtArrival(state, node.cell, node.tick)) {
                double waitRisk = cellRisk(field, waitArrival,
                        node.cell.row(), node.cell.column());
                double waitCost = WAIT_COST + waitRisk * 6.0D;
                if (state.phaseTicksRemaining > 0
                        && waitArrival > state.phaseTicksRemaining) {
                    waitCost += 2_500.0D
                            + (waitArrival - state.phaseTicksRemaining) * 100.0D;
                }

                SearchKey waitKey = new SearchKey(
                        node.cell.row(), node.cell.column(), waitArrival,
                        node.dirRow, node.dirColumn, node.gapsUsed);
                double oldWait = best.getOrDefault(
                        waitKey, Double.POSITIVE_INFINITY);
                double nextWaitG = node.g + waitCost;
                if (nextWaitG + 1.0E-9D < oldWait) {
                    best.put(waitKey, nextWaitG);
                    cells.put(waitKey, node.cell);
                    previous.put(waitKey, nodeKey);
                    open.add(new SearchNode(
                            node.cell, nextWaitG, waitArrival,
                            node.dirRow, node.dirColumn, node.gapsUsed,
                            nextWaitG + heuristic(node.cell, goal, regionRadius),
                            node.directionSet));
                }
            }

            for (Cell next : state.maze.physicalMovementNeighbours(node.cell)) {
                if (!floorAvailableAtArrival(state, next, node.tick)) continue;
                int dr = next.row() - node.cell.row();
                int dc = next.column() - node.cell.column();
                int dirRow = Integer.signum(dr);
                int dirColumn = Integer.signum(dc);
                boolean gap = Math.abs(dr) + Math.abs(dc) == 2;

                int gaps = node.gapsUsed + (gap ? 1 : 0);
                if (maxGaps >= 0 && gaps > maxGaps) continue;

                /*
                 * The source-faithful high-skill ground model settles around
                 * 0.286 blocks/tick at sprint terminal speed. Five ticks per
                 * ordinary block is therefore a conservative arrival bucket;
                 * corners add two ticks for the camera/velocity transition.
                 * A one-block void is given a shorter six-tick bucket because
                 * the source speeding jump contributes an additional horizontal
                 * impulse at takeoff.
                 */
                int movementTicks = gap ? GAP_MOVE_TICKS : STRAIGHT_MOVE_TICKS;
                if (node.directionSet
                        && (dirRow != node.dirRow || dirColumn != node.dirColumn)) {
                    movementTicks += TURN_EXTRA_TICKS;
                }

                int arrivalTick = Math.min(HORIZON_TICKS, node.tick + movementTicks);
                if (arrivalTick <= node.tick) continue;

                double risk = edgeRisk(field, node.cell, next, node.tick, arrivalTick);
                double stepCost = movementTicks + gapPenalty * (gap ? 1.0D : 0.0D);

                /*
                 * A predicted contact is intentionally very expensive. A human
                 * will take a few extra blocks to avoid being bumped off a maze
                 * rather than deliberately entering a forecast contact window.
                 */
                if (risk >= contactRiskThreshold()) {
                    stepCost += 1_000.0D + risk * 10.0D;
                } else {
                    stepCost += risk * 8.0D;
                }

                /*
                 * Reaching the active SafePad is the actual survival condition.
                 * When the remaining phase timer is short, late arrival is much
                 * worse than taking a modestly riskier route that arrives in time.
                 */
                if (state.phaseTicksRemaining > 0
                        && arrivalTick > state.phaseTicksRemaining) {
                    stepCost += 2_500.0D
                            + (arrivalTick - state.phaseTicksRemaining) * 100.0D;
                }

                double nextG = node.g + stepCost;
                SearchKey key = new SearchKey(
                        next.row(), next.column(), arrivalTick,
                        dirRow, dirColumn, gaps);
                double old = best.getOrDefault(key, Double.POSITIVE_INFINITY);
                if (nextG + 1.0E-9D >= old) continue;

                best.put(key, nextG);
                cells.put(key, next);
                previous.put(key, nodeKey);

                double h = heuristic(next, goal, regionRadius);
                open.add(new SearchNode(
                        next, nextG, arrivalTick,
                        dirRow, dirColumn, gaps,
                        nextG + h, true));
            }
        }

        if (bestGoal == null) {
            return shortestPhysicalFallback(state, start, goal, regionRadius, maxGaps);
        }

        ArrayList<Cell> path = new ArrayList<>();
        SearchKey cursor = bestGoal.key();
        while (cursor != null) {
            Cell cell = cells.get(cursor);
            if (cell == null) break;
            path.add(cell);
            cursor = previous.get(cursor);
        }
        java.util.Collections.reverse(path);

        if (path.isEmpty() || !path.get(0).equals(start)) {
            return shortestPhysicalFallback(state, start, goal, regionRadius, maxGaps);
        }
        return new PlayerRoute(path);
    }

    /**
     * Old SafePad surfaces are temporary physical floor. The source keeps each
     * inactive pad for 11 seconds, then restores the underlying maze. A route
     * that reaches such a cell after its decay deadline is not executable.
     */
    private static boolean floorAvailableAtArrival(GameState state, Cell cell, int departureTick) {
        if (!state.maze.hasPadSurface(cell.row(), cell.column())) return true;

        /*
         * Active/preview pads remain available. Only inactive pads in the live
         * decay map have a finite lifetime.
         */
        for (var entry : state.oldPadDecaySeconds.entrySet()) {
            Cell pad = entry.getKey();
            if (Math.abs(cell.row() - pad.row()) <= 2
                    && Math.abs(cell.column() - pad.column()) <= 2) {
                long expiryTick = Math.max(0L, (long) entry.getValue() * 20L);
                return departureTick + 4L < expiryTick;
            }
        }
        return true;
    }

    private static double heuristic(Cell cell, Cell goal, int radius) {
        int dr = Math.max(0, Math.abs(cell.row() - goal.row()) - radius);
        int dc = Math.max(0, Math.abs(cell.column() - goal.column()) - radius);
        return (dr + dc) * 3.0D;
    }

    private static boolean insideRegion(Cell cell, Cell center, int radius) {
        return Math.abs(cell.row() - center.row()) <= radius
                && Math.abs(cell.column() - center.column()) <= radius;
    }

    private static double edgeRisk(ThreatField field, Cell from, Cell to,
                                   int departureTick, int arrivalTick) {
        int midpoint = departureTick + Math.max(1, (arrivalTick - departureTick) / 2);

        /*
         * The threat field is already projected onto every physical cell. The
         * A* search only needs the occupancy risk of the three route positions
         * it crosses, plus the small timing window. Re-sampling a 3x3 spatial
         * neighborhood for every expanded edge was dominating the live search.
         */
        double fromRisk = cellRisk(field, Math.max(1, departureTick + 1),
                from.row(), from.column());
        double midRisk = cellRisk(field, midpoint,
                (from.row() + to.row()) / 2,
                (from.column() + to.column()) / 2);
        double toRisk = cellRisk(field, arrivalTick,
                to.row(), to.column());

        return Math.max(toRisk, Math.max(fromRisk * 0.5D, midRisk));
    }

    private static double cellRisk(ThreatField field, int tick, int row, int column) {
        if (row < 0 || row >= MazeModel.SIZE || column < 0 || column >= MazeModel.SIZE) {
            return 0.0D;
        }

        double best = 0.0D;
        for (int offset = -TIMING_WINDOW_TICKS;
             offset <= TIMING_WINDOW_TICKS; offset++) {
            int t = tick + offset;
            if (t < 1 || t > HORIZON_TICKS) continue;
            double weight = 1.0D / (1.0D + Math.abs(offset));
            int index = t * ThreatField.CELL_COUNT
                    + row * MazeModel.SIZE + column;
            best = Math.max(best, field.risk[index] * weight);
        }
        return best;
    }

    private static PlayerRoute shortestPhysicalFallback(GameState state, Cell start,
                                                        Cell goal, int radius,
                                                        int maxGaps) {
        java.util.ArrayDeque<Cell> queue = new java.util.ArrayDeque<>();
        java.util.Map<Cell, Cell> previous = new java.util.HashMap<>();
        java.util.Map<Cell, Integer> gaps = new java.util.HashMap<>();
        queue.add(start);
        previous.put(start, null);
        gaps.put(start, 0);

        Cell found = null;
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (insideRegion(current, goal, radius)) {
                found = current;
                break;
            }
            for (Cell next : state.maze.physicalMovementNeighbours(current)) {
                if (!floorAvailableAtArrival(state, next, 0)) continue;
                int used = gaps.get(current)
                        + (Math.abs(next.row() - current.row())
                        + Math.abs(next.column() - current.column()) == 2 ? 1 : 0);
                if (maxGaps >= 0 && used > maxGaps) continue;
                if (previous.containsKey(next)) continue;
                previous.put(next, current);
                gaps.put(next, used);
                queue.addLast(next);
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No physical route to predictive goal");
        }

        ArrayList<Cell> path = new ArrayList<>();
        for (Cell at = found; at != null; at = previous.get(at)) path.add(at);
        java.util.Collections.reverse(path);
        return new PlayerRoute(path);
    }

    private record SearchKey(int row, int column, int tick,
                             int dirRow, int dirColumn, int gapsUsed) {}

    private record SearchNode(Cell cell, double g, int tick,
                              int dirRow, int dirColumn, int gapsUsed,
                              double f, boolean directionSet) {
        SearchKey key() {
            return new SearchKey(cell.row(), cell.column(), tick,
                    dirRow, dirColumn, gapsUsed);
        }
    }

    /**
     * Rank routes against future monster occupancy. With no monsters present,
     * all routes receive zero risk and the planner can fall back to its normal
     * shortest-path comparator.
     */
    public static List<Score> rank(GameState state, List<PlayerRoute> routes) {
        if (routes == null || routes.isEmpty()) return List.of();

        if (state == null || state.maze == null || state.monsters.isEmpty()) {
            ArrayList<Score> out = new ArrayList<>(routes.size());
            for (PlayerRoute route : routes) {
                out.add(new Score(route, estimatedTravelTicks(route), 0.0D, 0,
                        estimatedTravelTicks(route)));
            }
            return List.copyOf(out);
        }

        ThreatField field = buildThreatField(state, routes);
        ArrayList<Score> scored = new ArrayList<>(routes.size());
        for (PlayerRoute route : routes) scored.add(scoreRoute(state, route, field));

        scored.sort(Comparator
                .comparingInt(Score::predictedContacts)
                .thenComparingDouble(Score::nearRisk)
                .thenComparingDouble(Score::travelTicks)
                .thenComparingDouble(Score::compositeCost));
        return List.copyOf(scored);
    }

    private static Score scoreRoute(GameState state, PlayerRoute route, ThreatField field) {
        double speed = PREDICTED_BLOCKS_PER_TICK;
        double distance = 0.0D;
        double elapsed = 0.0D;
        double nearRisk = 0.0D;
        int contacts = 0;

        List<Cell> cells = route.cells();
        if (cells.size() == 1) {
            double risk = sampleRisk(field, 1, route.targetX(0), route.targetZ(0));
            nearRisk += risk;
            if (risk >= contactRiskThreshold()) contacts++;
            double cost = nearRisk * 4.0D;
            return new Score(route, 0.0D, nearRisk, contacts, cost);
        }

        for (int i = 1; i < cells.size(); i++) {
            Cell from = cells.get(i - 1);
            Cell to = cells.get(i);

            double edgeLength = Math.hypot(
                    to.row() - from.row(),
                    to.column() - from.column());
            double segmentStartTicks = elapsed;

            if (i > 1) {
                Cell previousFrom = cells.get(i - 2);
                int oldRow = Integer.signum(from.row() - previousFrom.row());
                int oldColumn = Integer.signum(from.column() - previousFrom.column());
                int newRow = Integer.signum(to.row() - from.row());
                int newColumn = Integer.signum(to.column() - from.column());
                if (oldRow != newRow || oldColumn != newColumn) {
                    elapsed += TURN_PENALTY_TICKS;
                }
            }

            elapsed += edgeLength / speed;
            double segmentEndTicks = elapsed;
            int firstTick = Math.max(1, (int) Math.floor(segmentStartTicks));
            int lastTick = Math.min(HORIZON_TICKS, (int) Math.ceil(segmentEndTicks));

            for (int tick = firstTick; tick <= lastTick; tick++) {
                double alpha;
                if (segmentEndTicks <= segmentStartTicks + 1.0E-9D) {
                    alpha = 1.0D;
                } else {
                    alpha = (tick - segmentStartTicks)
                            / (segmentEndTicks - segmentStartTicks);
                    alpha = Math.max(0.0D, Math.min(1.0D, alpha));
                }

                double x = from.row() + 0.5D
                        + (to.row() - from.row()) * alpha;
                double z = from.column() + 0.5D
                        + (to.column() - from.column()) * alpha;

                double risk = sampleRisk(field, tick, x, z);
                nearRisk += risk;

                if (risk >= contactRiskThreshold()) contacts++;
            }
        }

        double travelTicks = elapsed;
        double compositeCost = travelTicks
                + contacts * 1_000.0D
                + nearRisk * 4.0D;
        return new Score(route, travelTicks, nearRisk, contacts, compositeCost);
    }

    private static double estimatedTravelTicks(PlayerRoute route) {
        if (route == null || route.size() <= 1) return 0.0D;

        double ticks = 0.0D;
        for (int i = 1; i < route.size(); i++) {
            Cell from = route.cells().get(i - 1);
            Cell to = route.cells().get(i);
            ticks += Math.hypot(
                    to.row() - from.row(),
                    to.column() - from.column()) / PREDICTED_BLOCKS_PER_TICK;

            if (i > 1) {
                Cell previousFrom = route.cells().get(i - 2);
                int oldRow = Integer.signum(from.row() - previousFrom.row());
                int oldColumn = Integer.signum(from.column() - previousFrom.column());
                int newRow = Integer.signum(to.row() - from.row());
                int newColumn = Integer.signum(to.column() - from.column());
                if (oldRow != newRow || oldColumn != newColumn) ticks += TURN_PENALTY_TICKS;
            }
        }
        return ticks;
    }

    private static double contactRiskThreshold() {
        return 12.0D;
    }

    private static double sampleRisk(ThreatField field, int tick, double x, double z) {
        double best = 0.0D;
        int centerRow = (int) Math.floor(x);
        int centerColumn = (int) Math.floor(z);

        for (int offsetTick = -TIMING_WINDOW_TICKS;
             offsetTick <= TIMING_WINDOW_TICKS; offsetTick++) {
            int t = tick + offsetTick;
            if (t < 1 || t > HORIZON_TICKS) continue;

            double timeWeight = 1.0D / (1.0D + Math.abs(offsetTick));
            int index = t * ThreatField.CELL_COUNT;

            for (int row = centerRow - 1; row <= centerRow + 1; row++) {
                if (row < 0 || row >= MazeModel.SIZE) continue;
                for (int column = centerColumn - 1; column <= centerColumn + 1; column++) {
                    if (column < 0 || column >= MazeModel.SIZE) continue;

                    double risk = field.risk[index + row * MazeModel.SIZE + column];
                    if (risk == 0.0D) continue;

                    double cellX = row + 0.5D;
                    double cellZ = column + 0.5D;
                    double distance = Math.hypot(x - cellX, z - cellZ);
                    double spatialWeight = Math.max(0.0D, 1.0D - distance / 1.6D);
                    best = Math.max(best, risk * timeWeight * Math.max(0.25D, spatialWeight));
                }
            }
        }

        return best;
    }

    private static ThreatField buildThreatField(GameState state, List<PlayerRoute> routes) {
        ThreatField field = new ThreatField(HORIZON_TICKS + 1);

        GameState prediction = state.copyForSimulation();
        // Forecast progression mutates pad surfaces; never mutate the caller's live maze.
        prediction.maze = state.maze == null ? null : state.maze.copy();
        if (routes == null || routes.isEmpty()) {
            prediction.monsters.removeIf(monster ->
                    !MonsterRelevance.withinPlayerRadius(
                            monster, prediction.player, 55.0D));
        } else {
            prediction.monsters.removeIf(monster ->
                    minDistanceToRoutes(monster, routes) > MAX_PREDICTED_MONSTER_TRAVEL);
        }

        long seed = 0x4D4D5A50524544L ^ state.tick;
        seed ^= ((long) state.stage << 32) ^ (state.mazePattern & 0xFFFF_FFFFL);
        for (MonsterState monster : prediction.monsters) {
            seed = seed * 31L + monster.id;
        }

        MonsterSimulator simulator = new MonsterSimulator(
                prediction.maze, new Random(seed), 1.4D);
        AbilityModel forecastAbilities = new AbilityModel();
        GameProgressionModel progression = new GameProgressionModel(forecastAbilities);

        for (int tick = 1; tick <= HORIZON_TICKS; tick++) {
            prediction.tick = state.tick + tick;
            simulator.tick(prediction);
            progression.tick(prediction);
            progression.syncPadSurfaces(prediction);
            int base = tick * ThreatField.CELL_COUNT;

            for (MonsterState monster : prediction.monsters) {
                if (monster.removed) continue;

                double verticalDistance = Math.abs(monster.y - GameState.PATH_Y);
                if (verticalDistance > CONTACT_Y_DISTANCE) continue;

                int centerRow = (int) Math.floor(monster.x);
                int centerColumn = (int) Math.floor(monster.z);
                int minRow = Math.max(0, centerRow - 3);
                int maxRow = Math.min(MazeModel.SIZE - 1, centerRow + 3);
                int minColumn = Math.max(0, centerColumn - 3);
                int maxColumn = Math.min(MazeModel.SIZE - 1, centerColumn + 3);

                for (int row = minRow; row <= maxRow; row++) {
                    for (int column = minColumn; column <= maxColumn; column++) {
                        if (!prediction.maze.isPhysicalFloor(row, column)) continue;

                        double dx = monster.x - (row + 0.5D);
                        double dz = monster.z - (column + 0.5D);
                        double horizontalDistance = Math.hypot(dx, dz);
                        if (horizontalDistance >= DANGER_RADIUS) continue;

                        double proximity =
                                (DANGER_RADIUS - horizontalDistance) / DANGER_RADIUS;
                        double risk = proximity * proximity;

                        double monsterSpeed = Math.hypot(monster.vx, monster.vz);
                        if (monsterSpeed > 1.0E-6D) {
                            double closing = (
                                    monster.vx * ((row + 0.5D) - monster.x)
                                            + monster.vz * ((column + 0.5D) - monster.z))
                                    / Math.max(horizontalDistance, 1.0E-6D);
                            if (closing > 0.0D) risk *= 1.35D;
                        }

                        if (horizontalDistance < CONTACT_DISTANCE
                                && verticalDistance < CONTACT_Y_DISTANCE) {
                            risk += 16.0D;
                        }

                        field.risk[base + row * MazeModel.SIZE + column] += risk;
                    }
                }
            }
        }

        return field;
    }

    private static double minDistanceToRoutes(MonsterState monster,
                                              List<PlayerRoute> routes) {
        double best = Double.POSITIVE_INFINITY;
        for (PlayerRoute route : routes) {
            if (route == null || route.size() == 0) continue;

            for (int i = 0; i < route.size() - 1; i++) {
                Cell from = route.cells().get(i);
                Cell to = route.cells().get(i + 1);

                double ax = from.row() + 0.5D;
                double az = from.column() + 0.5D;
                double bx = to.row() + 0.5D;
                double bz = to.column() + 0.5D;

                double dx = bx - ax;
                double dz = bz - az;
                double lengthSquared = dx * dx + dz * dz;
                double t = lengthSquared <= 1.0E-9D
                        ? 0.0D
                        : ((monster.x - ax) * dx + (monster.z - az) * dz) / lengthSquared;
                t = Math.max(0.0D, Math.min(1.0D, t));

                double nearestX = ax + t * dx;
                double nearestZ = az + t * dz;
                best = Math.min(best, Math.hypot(
                        monster.x - nearestX, monster.z - nearestZ));

                if (best <= DANGER_RADIUS) return best;
            }
        }
        return best;
    }

    private static final class ThreatField {
        static final int CELL_COUNT = MazeModel.SIZE * MazeModel.SIZE;
        final double[] risk;

        ThreatField(int ticks) {
            risk = new double[ticks * CELL_COUNT];
        }
    }
}
