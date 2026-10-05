package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerPathfinder;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

import java.util.List;

/**
 * Deterministic locomotion controller used only for zero-monster experiments.
 *
 * The critical invariant is that route progress is monotonic. The controller
 * owns one route edge at a time and never searches for the nearest edge across
 * the whole route after momentum carries the player through a corner.
 *
 * Ordinary edges use conservative heading acquisition and velocity-aware corner
 * braking. Gap edges are committed and timed separately.
 */
final class NoMobLocomotionController {
    private static final float MAX_TURN_PER_TICK = 30.0F;

    private static final double HEADING_TOLERANCE = 5.0D;
    private static final double DRIVE_HEADING_LIMIT = 18.0D;
    private static final double LANE_TOLERANCE = 0.32D;

    private static final double TARGET_SPEED = 0.46D;
    private static final double CORNER_SPEED = 0.14D;
    private static final double CORNER_BRAKE_DISTANCE = 0.70D;

    private static final double GAP_JUMP_PROGRESS = -0.80D;
    private static final double GAP_LANDING_PROGRESS = 1.10D;
    private static final double GAP_LATERAL_TOLERANCE = 0.30D;

    private final AiProfile profile;
    private final MonsterAwareRoutePlanner planner = new MonsterAwareRoutePlanner();
    private final PlayerPathfinder pathfinder = new PlayerPathfinder();

    private PlayerRoute route;
    private int routeEdgeIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int goalRadius = -1;

    private long lastSpeedJumpTick = Long.MIN_VALUE;
    private String lastDecision = "UNSET";

    NoMobLocomotionController(AiProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
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
            clearRoute();
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
            clearRoute();
        }

        Cell start = resolveSupportedStart(state);
        if (start == null) {
            lastDecision = "NO_SUPPORT";
            return Action.IDLE;
        }

        if (route == null || routeBroken(state)) {
            /*
             * No monsters means there is no reason to spend a jump on a gap
             * unless the ordinary floor graph cannot reach the target region.
             * Establishing the no-gap path as the default also matches the
             * observed human behaviour: gap crossings are exceptional shortcuts,
             * not the normal routing primitive.
             */
            MazeModel planningMaze = planningMaze(state);
            List<Cell> noGap = regionRadius > 0
                    ? pathfinder.fastestPathToRegion(
                            planningMaze, start, goal, regionRadius)
                    : pathfinder.fastestPath(planningMaze, start, goal);
            if (!noGap.isEmpty()) {
                route = new PlayerRoute(noGap);
            } else {
                route = regionRadius > 0
                        ? planner.routeToRegionFast(
                                state, start, goal, Math.max(0, regionRadius))
                        : planner.routeFast(state, start, goal);
            }
            routeEdgeIndex = 0;
            lastDecision = "REPLAN start=" + start.row() + "," + start.column()
                    + " route=" + route.size()
                    + " gaps=" + gapCount(route);
        }

        if (route == null || route.size() <= 1) {
            lastDecision = "ROUTE_DONE";
            return Action.IDLE;
        }

        /*
         * The player can cross a corner between observations because vanilla
         * momentum is continuous. Before steering, reconcile the logical route
         * edge with the block that actually supports the player's AABB. This is
         * monotonic: only a later cell on the already-selected route can advance
         * the edge index, so this cannot jump to an unrelated future branch.
         */
        reanchorFromSupportedCell(state);
        advanceCompletedEdges(state);

        if (routeEdgeIndex >= route.size() - 1) {
            if (regionRadius > 0 && !PadModel.isOn(
                    state.player,
                    goal.row() + 0.5D,
                    GameState.PAD_SURFACE_Y,
                    goal.column() + 0.5D)) {
                /*
                 * The graph objective intentionally stops within the SafePad
                 * region, but the source completion check is a 5x5 geometric
                 * surface. Finish the last few blocks by driving directly onto
                 * the actual pad instead of idling at the edge of the region.
                 */
                double dx = goal.row() + 0.5D - state.player.x;
                double dz = goal.column() + 0.5D - state.player.z;
                double len = Math.hypot(dx, dz);
                if (len > 1.0E-9D) {
                    return driveVector(
                            state, dx / len, dz / len,
                            1.0, true, false);
                }
            }
            lastDecision = "ROUTE_DONE edge=" + routeEdgeIndex;
            return Action.IDLE;
        }

        Cell from = route.cells().get(routeEdgeIndex);
        Cell to = route.cells().get(routeEdgeIndex + 1);
        Edge edge = edge(from, to, routeEdgeIndex, state);

        if (edge.gap) {
            return gapAction(state, edge, allowJump);
        }
        return normalAction(state, edge, allowJump);
    }

    String lastDecisionDetail() {
        return lastDecision;
    }

    void reset() {
        clearRoute();
        goalRow = -1;
        goalColumn = -1;
        goalRadius = -1;
        lastSpeedJumpTick = Long.MIN_VALUE;
        lastDecision = "RESET";
    }

    private void clearRoute() {
        route = null;
        routeEdgeIndex = 0;
    }

    private boolean routeBroken(GameState state) {
        if (route == null || route.size() <= 1) return false;

        List<Cell> cells = route.cells();
        for (int i = routeEdgeIndex; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (!state.maze.isPhysicalFloor(cell.row(), cell.column())
                    || projectedCenterCollapse(state, cell)) return true;
            if (i + 1 >= cells.size()) continue;

            Cell next = cells.get(i + 1);
            int dr = next.row() - cell.row();
            int dc = next.column() - cell.column();
            if (Math.abs(dr) + Math.abs(dc) == 2) {
                int mr = (cell.row() + next.row()) / 2;
                int mc = (cell.column() + next.column()) / 2;
                if (state.maze.isPhysicalFloor(mr, mc)) return true;
            }
        }
        return false;
    }

    private void reanchorFromSupportedCell(GameState state) {
        if (route == null) return;

        List<Cell> cells = route.cells();
        int current = Math.max(0, Math.min(routeEdgeIndex, cells.size() - 1));
        int bestIndex = -1;
        double bestOverlap = 0.0D;

        /*
         * Use the same 0.6-wide player AABB support semantics as the physics
         * model. At a 90-degree corner the AABB can overlap both cells, so
         * nearest-centre selection is ambiguous and can choose the wrong branch.
         * Only move the route index forward, and prefer the later cell with the
         * greatest actual support overlap.
         */
        for (int i = current + 1; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            double overlap = horizontalAabbOverlap(
                    state.player.x, state.player.z, cell);
            if (overlap > bestOverlap + 1.0E-6D) {
                bestOverlap = overlap;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0) {
            routeEdgeIndex = bestIndex;
        }
    }

    private void advanceCompletedEdges(GameState state) {
        while (routeEdgeIndex < route.size() - 1) {
            Cell from = route.cells().get(routeEdgeIndex);
            Cell to = route.cells().get(routeEdgeIndex + 1);
            int dr = to.row() - from.row();
            int dc = to.column() - from.column();
            boolean gap = Math.abs(dr) + Math.abs(dc) == 2;

            if (gap) {
                /*
                 * A gap edge is completed only by its dedicated landing branch.
                 * Never skip it merely because another route segment is nearby.
                 */
                return;
            }

            int dirRow = Integer.signum(dr);
            int dirColumn = Integer.signum(dc);
            double progress = edgeProgress(state, from, to);

            if (progress < 0.88D) return;

            double lateral = edgeLateral(state, from, dirRow, dirColumn);
            if (Math.abs(lateral) > LANE_TOLERANCE) return;

            routeEdgeIndex++;
        }
    }

    private Action normalAction(GameState state, Edge edge, boolean allowJump) {
        double speedAlong = state.player.vx * edge.dirX + state.player.vz * edge.dirZ;
        double progress = edge.progress;
        double remaining = edge.length - progress;

        float yawError = headingError(state, edge);

        boolean nextTurn = edge.index + 2 < route.size()
                && changesDirection(route.cells().get(edge.index),
                route.cells().get(edge.index + 1),
                route.cells().get(edge.index + 2));

        double horizontalSpeed = Math.hypot(
                state.player.vx, state.player.vz);

        /*
         * Minecraft keeps horizontal velocity across a yaw change. Simply
         * stopping W before a corner therefore does not actually stop the
         * player: the old velocity continues into the new corridor. Actively
         * counter-steer the measured velocity first, then acquire the new
         * heading once the residual motion is small.
         */
        if (nextTurn && remaining <= CORNER_BRAKE_DISTANCE
                && horizontalSpeed > CORNER_SPEED) {
            lastDecision = "CORNER_BRAKE edge=" + edge.index
                    + " remaining=" + format(remaining)
                    + " speed=" + format(horizontalSpeed);
            return brakeVelocity(state);
        }

        if (Math.abs(yawError) > DRIVE_HEADING_LIMIT) {
            if (horizontalSpeed > CORNER_SPEED) {
                lastDecision = "TURN_BRAKE edge=" + edge.index
                        + " yawError=" + format(yawError)
                        + " speed=" + format(horizontalSpeed);
                return brakeVelocity(state);
            }
            lastDecision = "TURN_EDGE edge=" + edge.index
                    + " yawError=" + format(yawError);
            return new Action(
                    0.0, 0.0, false, false,
                    clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                    false);
        }

        double crossTrack = edgeLateral(
                state, edge.from, directionRow(edge), directionColumn(edge));

        if (Math.abs(crossTrack) > LANE_TOLERANCE) {
            Action lane = laneCorrection(state, edge, crossTrack);
            if (lane != null) return lane;
        }

        boolean jump = shouldSpeedJump(state, allowJump, speedAlong, remaining, nextTurn);
        boolean sprint = speedAlong < TARGET_SPEED + 0.08D && Math.abs(yawError) <= HEADING_TOLERANCE;

        lastDecision = "DRIVE_EDGE edge=" + edge.index
                + " progress=" + format(progress)
                + " remaining=" + format(remaining)
                + " speed=" + format(speedAlong)
                + " yawError=" + format(yawError)
                + " jump=" + jump;

        return driveVector(state, edge.dirX, edge.dirZ, 1.0, sprint, jump);
    }

    private Action brakeVelocity(GameState state) {
        double speed = Math.hypot(state.player.vx, state.player.vz);
        if (speed < 1.0E-9D) {
            return Action.IDLE;
        }

        double worldX = -state.player.vx / speed;
        double worldZ = -state.player.vz / speed;
        float yawError = headingErrorForDirection(state, worldX, worldZ);
        float yawDelta = clamp(
                yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        /*
         * Keep the camera fixed while braking. Turning the camera at the same
         * time changes the meaning of the counter-input and makes deceleration
         * less predictable.
         */
        yawDelta = 0.0F;

        double yaw = Math.toRadians(state.player.yaw);
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

        return new Action(forward, strafe, false, false, yawDelta, false);
    }

    private Action laneCorrection(GameState state, Edge edge, double crossTrack) {
        if (Math.abs(crossTrack) < 0.38D && Math.hypot(
                state.player.vx, state.player.vz) > TARGET_SPEED) {
            return null;
        }

        double desiredWorldX = edge.dirX;
        double desiredWorldZ = edge.dirZ;

        double correction = Math.max(-0.45D, Math.min(0.45D, -crossTrack * 1.5D));
        if (edge.dirX != 0.0D) {
            desiredWorldZ += correction;
        } else {
            desiredWorldX += correction;
        }

        double length = Math.hypot(desiredWorldX, desiredWorldZ);
        if (length < 1.0E-9D) return null;
        desiredWorldX /= length;
        desiredWorldZ /= length;

        float yawError = headingErrorForDirection(
                state, desiredWorldX, desiredWorldZ);
        if (Math.abs(yawError) > DRIVE_HEADING_LIMIT) {
            return new Action(
                    0.0, 0.0, false, false,
                    clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                    false);
        }

        lastDecision = "LANE edge=" + edge.index
                + " cross=" + format(crossTrack);
        return driveVector(
                state, desiredWorldX, desiredWorldZ,
                0.65, false, false);
    }

    private boolean shouldSpeedJump(
            GameState state,
            boolean allowJump,
            double speedAlong,
            double remaining,
            boolean nextTurn) {
        if (!allowJump
                || state.kit == Kit.JUMPER
                || !state.player.grounded) {
            return false;
        }
        if (nextTurn && remaining <= 1.20D) return false;
        if (speedAlong >= TARGET_SPEED) return false;

        long cadence = profile.attributes.nonJumperJumpCadenceTicks();
        if (lastSpeedJumpTick != Long.MIN_VALUE
                && state.tick - lastSpeedJumpTick < cadence) {
            return false;
        }
        lastSpeedJumpTick = state.tick;
        return true;
    }

    private Action gapAction(GameState state, Edge edge, boolean allowJump) {
        float yawError = headingError(state, edge);
        if (Math.abs(yawError) > HEADING_TOLERANCE) {
            lastDecision = "GAP_ALIGN edge=" + edge.index
                    + " yawError=" + format(yawError);
            return new Action(
                    0.0, 0.0, false, false,
                    clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK),
                    false);
        }

        double lateral = edgeLateral(
                state, edge.from, directionRow(edge), directionColumn(edge));
        if (Math.abs(lateral) > GAP_LATERAL_TOLERANCE) {
            Action correction = laneCorrection(state, edge, lateral);
            if (correction != null) return correction;
        }

        double progress = edge.progress;
        boolean jump = false;

        if (state.player.grounded) {
            if (state.kit == Kit.JUMPER) {
                jump = allowJump
                        && state.ability.charges > 0
                        && progress >= GAP_JUMP_PROGRESS;
            } else if (progress >= GAP_JUMP_PROGRESS) {
                /*
                 * The source jump lock suppresses vertical lift, but the
                 * sprint-jump interaction supplies the horizontal impulse.
                 * Once the edge is committed, keep the jump input present on
                 * grounded ticks so observation boundaries cannot steal takeoff.
                 */
                jump = true;
                lastSpeedJumpTick = state.tick;
            } else {
                jump = shouldSpeedJump(state, allowJump, speedAlong(state, edge),
                        edge.length - progress, false);
            }
        }

        if (progress >= GAP_LANDING_PROGRESS
                && state.player.grounded
                && playerAabbOverlapsCell(state, edge.to.row(), edge.to.column())) {
            routeEdgeIndex++;
            lastDecision = "GAP_LANDED edge=" + edge.index
                    + " progress=" + format(progress);
            return normalActionAfterGap(state, allowJump);
        }

        lastDecision = "GAP edge=" + edge.index
                + " progress=" + format(progress)
                + " jump=" + jump;

        return driveVector(state, edge.dirX, edge.dirZ, 1.0, true, jump);
    }

    private Action normalActionAfterGap(GameState state, boolean allowJump) {
        if (routeEdgeIndex >= route.size() - 1) return Action.IDLE;
        Edge next = edge(
                route.cells().get(routeEdgeIndex),
                route.cells().get(routeEdgeIndex + 1),
                routeEdgeIndex,
                state);
        return normalAction(state, next, allowJump);
    }

    private Edge edge(Cell from, Cell to, int index, GameState state) {
        double ax = from.row() + 0.5D;
        double az = from.column() + 0.5D;
        double bx = to.row() + 0.5D;
        double bz = to.column() + 0.5D;
        double dx = bx - ax;
        double dz = bz - az;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-9D) throw new IllegalStateException("Duplicate route cell");

        boolean gap = length > 1.5D;
        double dirX = dx / length;
        double dirZ = dz / length;
        double progress = (state.player.x - ax) * dirX
                + (state.player.z - az) * dirZ;
        return new Edge(index, from, to, dirX, dirZ, length, progress, gap);
    }

    private static boolean changesDirection(Cell a, Cell b, Cell c) {
        return Integer.signum(b.row() - a.row()) != Integer.signum(c.row() - b.row())
                || Integer.signum(b.column() - a.column())
                != Integer.signum(c.column() - b.column());
    }

    private static int directionRow(Edge edge) {
        return edge.dirX > 0.5 ? 1 : edge.dirX < -0.5 ? -1 : 0;
    }

    private static int directionColumn(Edge edge) {
        return edge.dirZ > 0.5 ? 1 : edge.dirZ < -0.5 ? -1 : 0;
    }

    private double speedAlong(GameState state, Edge edge) {
        return state.player.vx * edge.dirX + state.player.vz * edge.dirZ;
    }

    private double edgeProgress(GameState state, Cell from, Cell to) {
        double dx = to.row() - from.row();
        double dz = to.column() - from.column();
        double length = Math.hypot(dx, dz);
        return (state.player.x - (from.row() + 0.5D)) * (dx / length)
                + (state.player.z - (from.column() + 0.5D)) * (dz / length);
    }

    private double edgeLateral(GameState state, Cell from, int dirRow, int dirColumn) {
        if (dirRow == 0) {
            return state.player.x - (from.row() + 0.5D);
        }
        return state.player.z - (from.column() + 0.5D);
    }

    private float headingError(GameState state, Edge edge) {
        return headingErrorForDirection(state, edge.dirX, edge.dirZ);
    }

    private float headingErrorForDirection(
            GameState state, double worldX, double worldZ) {
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-worldX, worldZ));
        return normalize(desiredYaw - state.player.yaw);
    }

    private Action driveVector(
            GameState state,
            double worldX,
            double worldZ,
            double forwardMagnitude,
            boolean sprint,
            boolean jump) {
        float yawError = headingErrorForDirection(state, worldX, worldZ);
        float yawDelta = clamp(
                yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        if (Math.abs(yawError) > DRIVE_HEADING_LIMIT) {
            // Never combine a speed/jump pulse with a large camera correction:
            // the resulting horizontal impulse would use the pre-alignment
            // heading and can throw the player sideways at a corner.
            return new Action(
                    0.0, 0.0, false, false, yawDelta, false);
        }

        double yaw = Math.toRadians(state.player.yaw + yawDelta);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double strafeX = Math.cos(yaw);
        double strafeZ = Math.sin(yaw);

        double forward = worldX * forwardX + worldZ * forwardZ;
        double strafe = worldX * strafeX + worldZ * strafeZ;
        double magnitude = Math.hypot(forward, strafe);
        if (magnitude > 1.0E-9D) {
            forward = forward / magnitude * forwardMagnitude;
            strafe = strafe / magnitude * forwardMagnitude;
        }

        return new Action(forward, strafe, jump, sprint, yawDelta, false);
    }

    private float normalize(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Before the final center-decay tick, treat decorative center cells (raw 3/4)
     * as future void so the route planner has time to move around them.
     */
    private static MazeModel planningMaze(GameState state) {
        if (state.centerSafeZoneDecay > 3) return state.maze;

        MazeModel copy = state.maze.copy();
        for (int row = 0; row < MazeModel.SIZE; row++) {
            for (int column = 0; column < MazeModel.SIZE; column++) {
                int raw = copy.raw(row, column);
                if (raw == 3 || raw == 4) {
                    copy.setPhysicalFloor(row, column, false);
                }
            }
        }
        return copy;
    }

    private static boolean projectedCenterCollapse(
            GameState state, Cell cell) {
        return state.centerSafeZoneDecay <= 3
                && (state.maze.raw(cell.row(), cell.column()) == 3
                || state.maze.raw(cell.row(), cell.column()) == 4);
    }

    private static int gapCount(PlayerRoute route) {
        if (route == null || route.size() < 2) return 0;
        int count = 0;
        List<Cell> cells = route.cells();
        for (int i = 0; i + 1 < cells.size(); i++) {
            int dr = Math.abs(cells.get(i + 1).row() - cells.get(i).row());
            int dc = Math.abs(cells.get(i + 1).column() - cells.get(i).column());
            if (dr + dc == 2) count++;
        }
        return count;
    }

    private static boolean playerAabbOverlapsCell(
            GameState state, int row, int column) {
        final double halfWidth = 0.30D;
        double minX = state.player.x - halfWidth;
        double maxX = state.player.x + halfWidth;
        double minZ = state.player.z - halfWidth;
        double maxZ = state.player.z + halfWidth;
        double overlapX = Math.min(maxX, row + 1.0D)
                - Math.max(minX, row);
        double overlapZ = Math.min(maxZ, column + 1.0D)
                - Math.max(minZ, column);
        return overlapX > 0.05D && overlapZ > 0.05D;
    }

    private static Cell resolveSupportedStart(GameState state) {
        int baseRow = (int) Math.floor(state.player.x);
        int baseColumn = (int) Math.floor(state.player.z);
        Cell best = null;
        double bestOverlap = 0.0D;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int row = baseRow - 1; row <= baseRow + 1; row++) {
            for (int column = baseColumn - 1; column <= baseColumn + 1; column++) {
                if (!state.maze.isPhysicalFloor(row, column)) continue;

                Cell cell = new Cell(row, column);
                double overlap = horizontalAabbOverlap(
                        state.player.x, state.player.z, cell);
                if (overlap <= 0.0D) continue;

                double dx = state.player.x - (row + 0.5D);
                double dz = state.player.z - (column + 0.5D);
                double distance = Math.hypot(dx, dz);

                if (overlap > bestOverlap + 1.0E-6D
                        || (Math.abs(overlap - bestOverlap) <= 1.0E-6D
                        && distance < bestDistance)) {
                    bestOverlap = overlap;
                    bestDistance = distance;
                    best = cell;
                }
            }
        }
        return best;
    }

    private static double horizontalAabbOverlap(double x, double z, Cell cell) {
        final double halfWidth = 0.30D;
        double overlapX = Math.min(x + halfWidth, cell.row() + 1.0D)
                - Math.max(x - halfWidth, cell.row());
        double overlapZ = Math.min(z + halfWidth, cell.column() + 1.0D)
                - Math.max(z - halfWidth, cell.column());
        if (overlapX <= 0.0D || overlapZ <= 0.0D) return 0.0D;
        return overlapX * overlapZ;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record Edge(
            int index,
            Cell from,
            Cell to,
            double dirX,
            double dirZ,
            double length,
            double progress,
            boolean gap) {}
}
