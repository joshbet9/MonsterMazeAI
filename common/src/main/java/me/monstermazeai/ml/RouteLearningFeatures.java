package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;

import java.util.Arrays;
import java.util.List;

/**
 * Fixed-size feature vector shared by the Java recorder/runtime and the Python
 * trainer. Features deliberately describe the current source state plus one
 * concrete physical route candidate; no learned value is included in the
 * inputs.
 */
public final class RouteLearningFeatures {
    public static final String[] NAMES = {
            "stage_norm",
            "mode_original",
            "mode_speed",
            "mode_modern",
            "kit_jumper",
            "kit_maverick",
            "kit_slowballer",
            "kit_repulsor",
            "kit_body_builder",
            "health_ratio",
            "horizontal_speed",
            "forward_speed",
            "lateral_speed",
            "first_heading_cos",
            "first_heading_sin",
            "grounded",
            "ability_charges_norm",
            "phase_ticks_norm",
            "goal_distance_norm",
            "route_size_norm",
            "route_distance_norm",
            "route_gap_count_norm",
            "route_turn_count_norm",
            "route_first_edge_norm",
            "route_alignment_cos",
            "route_deviation_norm",
            "monster_count_12_norm",
            "monster_count_20_norm",
            "nearest_monster_distance_norm",
            "nearest_monster_closing_norm",
            "nearest_monster_forward_norm",
            "nearest_monster_lateral_norm",
            "max_monster_closing_norm",
            "min_time_to_contact_norm"
    };

    private RouteLearningFeatures() {}

    public static double[] extract(GameState state, PlayerRoute route, Cell goal) {
        if (state == null || route == null || route.size() == 0 || goal == null) {
            throw new IllegalArgumentException("state, route and goal are required");
        }

        double[] f = new double[NAMES.length];
        f[0] = clamp01(state.stage / 100.0);
        f[1] = mode(state, "ORIGINAL");
        f[2] = mode(state, "SPEED");
        f[3] = mode(state, "MODERN");

        f[4] = kit(state, "JUMPER");
        f[5] = kit(state, "MAVERICK");
        f[6] = kit(state, "SLOWBALLER");
        f[7] = kit(state, "REPULSOR");
        f[8] = kit(state, "BODY_BUILDER");

        double healthMax = Math.max(1.0, state.player.maxHealth);
        f[9] = clamp01(state.player.health / healthMax);

        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        f[10] = clamp(horizontalSpeed / 0.60, 0.0, 3.0);

        double yawRad = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double strafeX = Math.cos(yawRad);
        double strafeZ = Math.sin(yawRad);
        double forwardSpeed = state.player.vx * forwardX + state.player.vz * forwardZ;
        double lateralSpeed = state.player.vx * strafeX + state.player.vz * strafeZ;
        f[11] = clamp(forwardSpeed / 0.60, -3.0, 3.0);
        f[12] = clamp(lateralSpeed / 0.60, -3.0, 3.0);

        int firstDirRow = 0;
        int firstDirColumn = 0;
        if (route.size() > 1) {
            Cell a = route.cells().get(0);
            Cell b = route.cells().get(1);
            firstDirRow = Integer.signum(b.row() - a.row());
            firstDirColumn = Integer.signum(b.column() - a.column());
        }

        double desiredYaw = cardinalYaw(firstDirRow, firstDirColumn);
        double headingErrorRad = Math.toRadians(normalise((float) (desiredYaw - state.player.yaw)));
        f[13] = Math.cos(headingErrorRad);
        f[14] = Math.sin(headingErrorRad);
        f[15] = state.player.grounded ? 1.0 : 0.0;
        f[16] = clamp((state.ability == null ? 0 : state.ability.charges) / 3.0, 0.0, 1.0);
        f[17] = clamp(state.phaseTicksRemaining / 400.0, 0.0, 2.0);

        double goalDx = goal.row() + 0.5 - state.player.x;
        double goalDz = goal.column() + 0.5 - state.player.z;
        f[18] = clamp(Math.hypot(goalDx, goalDz) / 100.0, 0.0, 3.0);

        int routeSize = Math.max(1, route.size() - 1);
        double routeDistance = 0.0;
        int gaps = 0;
        int turns = 0;
        int previousDirRow = 0;
        int previousDirColumn = 0;
        boolean previousSet = false;

        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            Cell a = cells.get(i);
            Cell b = cells.get(i + 1);
            int dr = b.row() - a.row();
            int dc = b.column() - a.column();
            int dirRow = Integer.signum(dr);
            int dirColumn = Integer.signum(dc);
            int edgeLength = Math.abs(dr) + Math.abs(dc);
            routeDistance += Math.hypot(dr, dc);
            if (edgeLength == 2) gaps++;
            if (previousSet && (dirRow != previousDirRow || dirColumn != previousDirColumn)) turns++;
            previousDirRow = dirRow;
            previousDirColumn = dirColumn;
            previousSet = true;
        }

        f[19] = clamp(routeSize / 100.0, 0.0, 3.0);
        f[20] = clamp(routeDistance / 100.0, 0.0, 3.0);
        f[21] = clamp(gaps / 10.0, 0.0, 3.0);
        f[22] = clamp(turns / 20.0, 0.0, 3.0);

        double firstEdgeLength = route.size() > 1
                ? Math.hypot(
                        cells.get(1).row() - cells.get(0).row(),
                        cells.get(1).column() - cells.get(0).column())
                : 0.0;
        f[23] = clamp(firstEdgeLength / 2.0, 0.0, 2.0);

        double routeUnitX = firstDirRow;
        double routeUnitZ = firstDirColumn;
        double routeUnitLength = Math.hypot(routeUnitX, routeUnitZ);
        if (routeUnitLength > 1.0E-9) {
            routeUnitX /= routeUnitLength;
            routeUnitZ /= routeUnitLength;
        }

        double movementLength = Math.max(1.0E-9, horizontalSpeed);
        double movementCos = (state.player.vx * routeUnitX + state.player.vz * routeUnitZ) / movementLength;
        f[24] = clamp(movementCos, -1.0, 1.0);

        f[25] = clamp(distanceToRoute(state.player.x, state.player.z, cells) / 4.0, 0.0, 3.0);

        int count12 = 0;
        int count20 = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        double nearestClosing = 0.0;
        double nearestForward = 0.0;
        double nearestLateral = 0.0;
        double maxClosing = 0.0;
        double minTtc = Double.POSITIVE_INFINITY;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed) continue;
            double dx = monster.x - state.player.x;
            double dy = monster.y - state.player.y;
            double dz = monster.z - state.player.z;
            double horizontalDistance = Math.hypot(dx, dz);
            double fullDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (horizontalDistance <= 12.0) count12++;
            if (horizontalDistance <= 20.0) count20++;

            if (horizontalDistance <= 20.0) {
                double closing = 0.0;
                if (fullDistance > 1.0E-9) {
                    double rvx = monster.vx - state.player.vx;
                    double rvz = monster.vz - state.player.vz;
                    closing = (rvx * (state.player.x - monster.x)
                            + rvz * (state.player.z - monster.z)) / fullDistance;
                }
                maxClosing = Math.max(maxClosing, Math.max(0.0, closing));

                double forward = dx * routeUnitX + dz * routeUnitZ;
                double lateral = dx * (-routeUnitZ) + dz * routeUnitX;

                if (horizontalDistance < nearestDistance) {
                    nearestDistance = horizontalDistance;
                    nearestClosing = closing;
                    nearestForward = forward;
                    nearestLateral = lateral;
                }

                if (closing > 1.0E-6) {
                    minTtc = Math.min(minTtc, Math.max(0.0, fullDistance / closing));
                }
            }
        }

        f[26] = clamp(count12 / 6.0, 0.0, 3.0);
        f[27] = clamp(count20 / 10.0, 0.0, 3.0);
        f[28] = clamp((Double.isFinite(nearestDistance) ? nearestDistance : 20.0) / 20.0, 0.0, 1.5);
        f[29] = clamp(nearestClosing / 0.60, -3.0, 3.0);
        f[30] = clamp(nearestForward / 20.0, -2.0, 2.0);
        f[31] = clamp(Math.abs(nearestLateral) / 8.0, 0.0, 2.0);
        f[32] = clamp(maxClosing / 0.60, 0.0, 3.0);
        f[33] = clamp((Double.isFinite(minTtc) ? minTtc : 40.0) / 20.0, 0.0, 3.0);

        return f;
    }

    public static double target(GameState state, PlayerRoute route,
                                me.monstermazeai.planner.TacticalRouteSimulator.Result result) {
        int gapCount = gapCount(route);

        if (result.reached()) {
            return result.arrivalTicks()
                    + result.damageTaken() * 30.0
                    + gapCount * 2.0
                    + Math.max(0, route.size() - 1) * 0.05;
        }

        int remainingWaypoints = Math.max(0, route.size() - 1 - result.finalWaypoint());
        return 1000.0
                + remainingWaypoints * 25.0
                + result.damageTaken() * 30.0
                + gapCount * 2.0;
    }

    public static int gapCount(PlayerRoute route) {
        int count = 0;
        for (int i = 0; i + 1 < route.size(); i++) {
            int dr = Math.abs(route.cells().get(i + 1).row() - route.cells().get(i).row());
            int dc = Math.abs(route.cells().get(i + 1).column() - route.cells().get(i).column());
            if ((dr == 2 && dc == 0) || (dc == 2 && dr == 0)) count++;
        }
        return count;
    }

    public static int turnCount(PlayerRoute route) {
        if (route.size() < 3) return 0;
        int previousRow = Integer.signum(route.cells().get(1).row() - route.cells().get(0).row());
        int previousColumn = Integer.signum(route.cells().get(1).column() - route.cells().get(0).column());
        int turns = 0;
        for (int i = 1; i + 1 < route.size(); i++) {
            int row = Integer.signum(route.cells().get(i + 1).row() - route.cells().get(i).row());
            int column = Integer.signum(route.cells().get(i + 1).column() - route.cells().get(i).column());
            if (row != previousRow || column != previousColumn) turns++;
            previousRow = row;
            previousColumn = column;
        }
        return turns;
    }

    private static double distanceToRoute(double x, double z, List<Cell> cells) {
        if (cells.size() < 2) return 0.0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < cells.size(); i++) {
            double ax = cells.get(i).row() + 0.5;
            double az = cells.get(i).column() + 0.5;
            double bx = cells.get(i + 1).row() + 0.5;
            double bz = cells.get(i + 1).column() + 0.5;
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSq = dx * dx + dz * dz;
            if (lengthSq <= 1.0E-9) continue;
            double px = x - ax;
            double pz = z - az;
            double t = clamp((px * dx + pz * dz) / lengthSq, 0.0, 1.0);
            double nx = ax + t * dx;
            double nz = az + t * dz;
            best = Math.min(best, Math.hypot(x - nx, z - nz));
        }
        return Double.isFinite(best) ? best : 0.0;
    }

    private static double mode(GameState state, String name) {
        return name.equals(state.mode.name()) ? 1.0 : 0.0;
    }

    private static double kit(GameState state, String name) {
        return name.equals(state.kit.name()) ? 1.0 : 0.0;
    }

    private static double cardinalYaw(int rowDirection, int columnDirection) {
        if (rowDirection > 0) return -90.0;
        if (rowDirection < 0) return 90.0;
        if (columnDirection > 0) return 0.0;
        return 180.0;
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    public static String featureJson(double[] features) {
        if (features == null || features.length != NAMES.length) {
            throw new IllegalArgumentException("Unexpected feature vector length");
        }
        return Arrays.toString(features);
    }
}
