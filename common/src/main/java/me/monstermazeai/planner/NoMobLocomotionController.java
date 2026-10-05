package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

import java.util.List;

/**
 * Deterministic corridor motor for locomotion-only runs.
 *
 * With no monsters, the problem is static path following. Choose a
 * physics-aware route once per objective, then continuously steer along the
 * active cardinal edge. This deliberately has no tactical/threat branch.
 */
final class NoMobLocomotionController {
    private static final float MAX_TURN_PER_TICK = 30.0F;
    private static final double ROUTE_DRIFT_REPLAN = 0.90D;
    private static final double GAP_JUMP_PROGRESS = -0.80D;
    private static final double GAP_CONFIRM_PROGRESS = 1.10D;

    private final AiProfile profile;
    private final MonsterAwareRoutePlanner planner = new MonsterAwareRoutePlanner();

    private PlayerRoute route;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int goalRadius = -1;
    private long lastSpeedJumpTick = Long.MIN_VALUE;
    private String lastDecision = "UNSET";

    NoMobLocomotionController(AiProfile profile) {
        this.profile = profile;
    }

    Action nextAction(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || state.maze == null || goal == null) {
            lastDecision = "INVALID";
            return Action.IDLE;
        }

        if (regionRadius > 0 && PadModel.isOn(
                state.player,
                goal.row() + 0.5D,
                GameState.PAD_SURFACE_Y,
                goal.column() + 0.5D)) {
            route = null;
            lastDecision = "ON_PAD";
            return Action.IDLE;
        }

        boolean objectiveChanged = goal.row() != goalRow
                || goal.column() != goalColumn
                || regionRadius != goalRadius;
        if (objectiveChanged) {
            goalRow = goal.row();
            goalColumn = goal.column();
            goalRadius = regionRadius;
            route = null;
        }

        Cell start = resolveSupportedStart(state);
        if (start == null) {
            lastDecision = "NO_SUPPORT";
            return Action.IDLE;
        }

        if (route == null || routeBroken(state) || routeDistance(state) > ROUTE_DRIFT_REPLAN) {
            route = planner.routeToRegionFast(
                    state, start, goal, Math.max(0, regionRadius));
            lastDecision = "REPLAN start=" + start.row() + "," + start.column()
                    + " route=" + route.size();
        }

        if (route == null || route.size() <= 1) {
            lastDecision = "ROUTE_DONE";
            return Action.IDLE;
        }

        Edge edge = activeEdge(state);
        if (edge == null) {
            route = null;
            lastDecision = "NO_ACTIVE_EDGE";
            return Action.IDLE;
        }

        if (edge.gap) {
            Action gapAction = gapAction(state, edge, allowJump);
            if (gapAction != null) return gapAction;
        }

        return drive(state, edge);
    }

    String lastDecisionDetail() {
        return lastDecision;
    }

    void reset() {
        route = null;
        goalRow = goalColumn = -1;
        goalRadius = -1;
        lastSpeedJumpTick = Long.MIN_VALUE;
        lastDecision = "RESET";
    }

    private boolean routeBroken(GameState state) {
        if (route == null || route.size() <= 1) return false;
        List<Cell> cells = route.cells();
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            if (!state.maze.isPhysicalFloor(c.row(), c.column())) return true;
            if (i + 1 < cells.size()) {
                Cell n = cells.get(i + 1);
                int dr = Math.abs(n.row() - c.row());
                int dc = Math.abs(n.column() - c.column());
                if (dr + dc == 2) {
                    int mr = (c.row() + n.row()) / 2;
                    int mc = (c.column() + n.column()) / 2;
                    if (state.maze.isPhysicalFloor(mr, mc)) return true;
                }
            }
        }
        return false;
    }

    private double routeDistance(GameState state) {
        if (route == null || route.size() <= 1) return Double.POSITIVE_INFINITY;
        double best = Double.POSITIVE_INFINITY;
        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            Cell a = cells.get(i);
            Cell b = cells.get(i + 1);
            double ax = a.row() + 0.5D, az = a.column() + 0.5D;
            double bx = b.row() + 0.5D, bz = b.column() + 0.5D;
            double dx = bx - ax, dz = bz - az;
            double len2 = dx * dx + dz * dz;
            if (len2 <= 1.0E-9D) continue;
            double t = ((state.player.x - ax) * dx
                    + (state.player.z - az) * dz) / len2;
            t = Math.max(0.0D, Math.min(1.0D, t));
            double nx = ax + t * dx, nz = az + t * dz;
            best = Math.min(best, Math.hypot(state.player.x - nx, state.player.z - nz));
        }
        return best;
    }

    private Edge activeEdge(GameState state) {
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestMomentum = Double.NEGATIVE_INFINITY;
        int bestIndex = -1;
        Edge best = null;

        double speed = Math.hypot(state.player.vx, state.player.vz);
        double vx = speed > 1.0E-9 ? state.player.vx / speed : 0.0D;
        double vz = speed > 1.0E-9 ? state.player.vz / speed : 0.0D;

        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            Cell from = cells.get(i);
            Cell to = cells.get(i + 1);
            double ax = from.row() + 0.5D, az = from.column() + 0.5D;
            double bx = to.row() + 0.5D, bz = to.column() + 0.5D;
            double dx = bx - ax, dz = bz - az;
            double len2 = dx * dx + dz * dz;
            if (len2 <= 1.0E-9D) continue;

            double t = ((state.player.x - ax) * dx
                    + (state.player.z - az) * dz) / len2;
            t = Math.max(0.0D, Math.min(1.0D, t));
            double nx = ax + t * dx, nz = az + t * dz;
            double distance = Math.hypot(state.player.x - nx, state.player.z - nz);

            double len = Math.sqrt(len2);
            double dirX = dx / len, dirZ = dz / len;
            double momentum = speed > 1.0E-9 ? vx * dirX + vz * dirZ : 0.0D;

            boolean better = distance < bestDistance - 0.10D;
            boolean tie = Math.abs(distance - bestDistance) <= 0.10D;
            if (better
                    || (tie && momentum > bestMomentum + 0.05D)
                    || (tie && Math.abs(momentum - bestMomentum) <= 0.05D && i > bestIndex)) {
                bestDistance = distance;
                bestMomentum = momentum;
                bestIndex = i;
                best = new Edge(
                        i, from, to, dirX, dirZ,
                        len2 > 1.5D && len2 < 6.0D,
                        ((state.player.x - ax) * dx + (state.player.z - az) * dz) / len);
            }
        }
        return best;
    }

    private Action gapAction(GameState state, Edge edge, boolean allowJump) {
        double progress = edge.progress;
        boolean jump = false;

        if (state.player.grounded) {
            if (state.kit == Kit.JUMPER) {
                jump = allowJump
                        && state.ability.charges > 0
                        && progress >= GAP_JUMP_PROGRESS;
            } else if (progress < GAP_JUMP_PROGRESS) {
                long cadence = profile.attributes.nonJumperJumpCadenceTicks();
                if (lastSpeedJumpTick == Long.MIN_VALUE
                        || state.tick - lastSpeedJumpTick >= cadence) {
                    jump = true;
                    lastSpeedJumpTick = state.tick;
                }
            } else {
                jump = true;
                lastSpeedJumpTick = state.tick;
            }
        }

        if (progress > GAP_CONFIRM_PROGRESS
                && state.player.grounded
                && edgeOverlapsDestination(state, edge)) {
            lastDecision = "GAP_LANDED edge=" + edge.index;
            return driveVector(state, edge.dirX, edge.dirZ, false);
        }

        lastDecision = "GAP edge=" + edge.index
                + " progress=" + format(progress)
                + " jump=" + jump;
        return driveVector(state, edge.dirX, edge.dirZ, jump);
    }

    private Action drive(GameState state, Edge edge) {
        boolean jump = false;
        if (state.player.grounded && state.kit != Kit.JUMPER) {
            long cadence = profile.attributes.nonJumperJumpCadenceTicks();
            if (lastSpeedJumpTick == Long.MIN_VALUE
                    || state.tick - lastSpeedJumpTick >= cadence) {
                jump = true;
                lastSpeedJumpTick = state.tick;
            }
        }
        return driveVector(state, edge.dirX, edge.dirZ, jump);
    }

    private Action driveVector(GameState state, double worldX, double worldZ, boolean jump) {
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-worldX, worldZ));
        float yawError = normalize(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        double yaw = Math.toRadians(state.player.yaw + yawDelta);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double strafeX = Math.cos(yaw);
        double strafeZ = Math.sin(yaw);

        double forward = worldX * forwardX + worldZ * forwardZ;
        double strafe = worldX * strafeX + worldZ * strafeZ;
        double magnitude = Math.hypot(forward, strafe);
        if (magnitude > 1.0E-9D) {
            forward /= magnitude;
            strafe /= magnitude;
        }

        lastDecision = "DRIVE yawError=" + format(yawError)
                + " dir=" + format(worldX) + "," + format(worldZ)
                + " jump=" + jump;
        return new Action(forward, strafe, jump, true, yawDelta, false);
    }

    private static boolean edgeOverlapsDestination(GameState state, Edge edge) {
        Cell destination = edge.to;
        final double halfWidth = 0.30D;
        double minX = state.player.x - halfWidth;
        double maxX = state.player.x + halfWidth;
        double minZ = state.player.z - halfWidth;
        double maxZ = state.player.z + halfWidth;
        double overlapX = Math.min(maxX, destination.row() + 1.0D)
                - Math.max(minX, destination.row());
        double overlapZ = Math.min(maxZ, destination.column() + 1.0D)
                - Math.max(minZ, destination.column());
        return overlapX > 0.05D && overlapZ > 0.05D;
    }

    private static Cell resolveSupportedStart(GameState state) {
        int baseRow = (int) Math.floor(state.player.x);
        int baseColumn = (int) Math.floor(state.player.z);
        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int row = baseRow - 1; row <= baseRow + 1; row++) {
            for (int column = baseColumn - 1; column <= baseColumn + 1; column++) {
                if (!state.maze.isPhysicalFloor(row, column)) continue;
                double dx = state.player.x - (row + 0.5D);
                double dz = state.player.z - (column + 0.5D);
                double distance = Math.hypot(dx, dz);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Cell(row, column);
                }
            }
        }
        return best;
    }

    private static float normalize(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(-MAX_TURN_PER_TICK, Math.min(MAX_TURN_PER_TICK, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record Edge(int index, Cell from, Cell to,
                        double dirX, double dirZ, boolean gap, double progress) {}
}
