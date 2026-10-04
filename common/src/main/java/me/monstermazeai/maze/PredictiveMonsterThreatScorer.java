package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;

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
    private static final int HORIZON_TICKS = 96;
    private static final int TIMING_WINDOW_TICKS = 2;
    private static final double DANGER_RADIUS = 2.75D;
    private static final double CONTACT_DISTANCE = 1.0D;
    private static final double CONTACT_Y_DISTANCE = 1.0D;
    private static final double MAX_PREDICTED_MONSTER_TRAVEL = 30.0D;
    private static final double TURN_PENALTY_TICKS = 1.25D;

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
        prediction.monsters.removeIf(monster ->
                minDistanceToRoutes(monster, routes) > MAX_PREDICTED_MONSTER_TRAVEL);

        long seed = 0x4D4D5A50524544L ^ state.tick;
        seed ^= ((long) state.stage << 32) ^ (state.mazePattern & 0xFFFF_FFFFL);
        for (MonsterState monster : prediction.monsters) {
            seed = seed * 31L + monster.id;
        }

        MonsterSimulator simulator = new MonsterSimulator(
                prediction.maze, new Random(seed), 1.4D);

        for (int tick = 1; tick <= HORIZON_TICKS; tick++) {
            prediction.tick = state.tick + tick;
            simulator.tick(prediction);
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
