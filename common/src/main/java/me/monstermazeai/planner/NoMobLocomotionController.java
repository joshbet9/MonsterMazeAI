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
    private static final double CORNER_SPEED = 0.035D;
    private static final double CORNER_BRAKE_DISTANCE = 0.80D;

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
            /*
             * A zero-mob run can still be recoverable after an edge crossing:
             * the player may be below the floor plane for a few ticks and land
             * on a later corridor block. Do not surrender control just because
             * support is temporarily absent; keep steering along the committed
             * route so horizontal motion can reach the next surface.
             */
            if (route != null && routeEdgeIndex < route.size() - 1) {
                Cell from = route.cells().get(routeEdgeIndex);
                Cell to = route.cells().get(routeEdgeIndex + 1);
                Edge recovery = edge(from, to, routeEdgeIndex, state);
                lastDecision = "AIRBORNE_RECOVERY edge=" + recovery.index;
                return driveVector(
                        state, recovery.dirX, recovery.dirZ,
                        1.0, true, false);
            }
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

            Cell target = to;
            double targetX = target.row() + 0.5D;
            double targetZ = target.column() + 0.5D;
            double centerDistance = Math.hypot(
                    state.player.x - targetX,
                    state.player.z - targetZ);
            if (progress < 0.82D && centerDistance > 0.42D) return;
            if (!playerAabbOverlapsCell(state, target.row(), target.column())
                    && centerDistance > 0.55D) return;

            routeEdgeIndex++;
        }
    }

    private Action normalAction(GameState state, Edge edge, boolean allowJump) {
        Cell target = edge.to;
        double targetX = target.row() + 0.5D;
        double targetZ = target.column() + 0.5D;
        double worldX = targetX - state.player.x;
        double worldZ = targetZ - state.player.z;
        double remaining = Math.hypot(worldX, worldZ);

        if (remaining < 1.0E-9D) {
            worldX = edge.dirX;
            worldZ = edge.dirZ;
            remaining = 1.0D;
        } else {
            worldX /= remaining;
            worldZ /= remaining;
        }

        /*
         * No-gap baseline invariant: drive toward the next cell centre, not a
         * diagonally corrected lane. This preserves the actual one-cell-wide
         * path geometry and prevents the controller from cutting across a void
         * corner merely because its geometric cross-track error is large.
         */
        float yawError = headingErrorForDirection(state, worldX, worldZ);
        double speedAlong = state.player.vx * edge.dirX + state.player.vz * edge.dirZ;

        boolean jump = shouldSpeedJump(
                state, allowJump, speedAlong, remaining,
                false);

        /*
         * The Jump -10 horizontal impulse follows the player's facing. When the
         * requested cell is behind that facing, suppress the pulse until the
         * camera has converged; translation itself remains fully active.
         */
        if (Math.abs(yawError) > 75.0F) {
            jump = false;
        }

        lastDecision = "CELL_DRIVE edge=" + edge.index
                + " target=" + target.row() + "," + target.column()
                + " remaining=" + format(remaining)
                + " speed=" + format(speedAlong)
                + " yawError=" + format(yawError)
                + " jump=" + jump;

        return driveVector(state, worldX, worldZ, 1.0, true, jump);
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

        /*
         * Lane correction is still movement. Do not turn in place here; the
         * current 1.8 client can combine forward+strafe while facing away from
         * the route. Full sprint keeps the player moving through the correction
         * instead of donating deadline time to camera-only ticks.
         */
        boolean jump = shouldSpeedJump(
                state,
                true,
                state.player.vx * desiredWorldX + state.player.vz * desiredWorldZ,
                edge.length - edge.progress,
                false);
        /*
         * Lane correction is still ordinary floor travel. Preserve sprint and
         * the source -10 jump-spam technique rather than falling back to the
         * much slower no-jump correction speed.
         */
        lastDecision = "LANE edge=" + edge.index
                + " cross=" + format(crossTrack)
                + " jump=" + jump;
        return driveVector(
                state, desiredWorldX, desiredWorldZ,
                1.0, true, jump);
    }

    private boolean shouldSpeedJump(
            GameState state,
            boolean ignoredAllowJump,
            double speedAlong,
            double remaining,
            boolean nextTurn) {
        /*
         * In Monster Maze, Jump is always a valid client input. Whether it is
         * a vertical jump is decided by the source Jump effect: Jumper charges
         * temporarily remove the -10 lock; once charges are gone, -10 returns
         * and the same Jump input becomes the horizontal speeding mechanic.
         *
         * Therefore the no-mob motor must never disable Jump merely because
         * the observation layer says a Jumper has no vertical charge left.
         */
        if (!state.player.grounded) {
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
        double lateral = edgeLateral(
                state, edge.from, directionRow(edge), directionColumn(edge));
        if (Math.abs(lateral) > GAP_LATERAL_TOLERANCE) {
            Action correction = laneCorrection(state, edge, lateral);
            if (correction != null) return correction;
        }

        double progress = edge.progress;
        boolean jump = false;

        /*
         * A gap requires the jump impulse, so unlike ordinary floor edges we
         * first rotate in place when facing backwards. A small fixed turn step
         * converges without the old +/-24 degree oscillation.
         */
        if (Math.abs(yawError) > 75.0F) {
            return new Action(
                    0.0, 0.0, false, false,
                    clamp(yawError, -15.0F, 15.0F),
                    false);
        }

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

        /*
         * Translation is expressed directly in the player's current
         * forward/strafe basis. Keeping yawDelta at zero removes the unstable
         * rotate-stop-rotate loop seen in the diagnostic trace; the resulting
         * input is still equivalent to WASD steering in the 1.8 client.
         */
        float yawDelta = clamp(
                yawError * 0.20F, -10.0F, 10.0F);
        double yaw = Math.toRadians(state.player.yaw);
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

    private static double estimatedRouteCost(List<Cell> cells) {
        if (cells == null || cells.size() <= 1) return 0.0D;

        double cost = 0.0D;
        int previousDirection = -1;
        for (int i = 0; i + 1 < cells.size(); i++) {
            Cell from = cells.get(i);
            Cell to = cells.get(i + 1);
            int direction;
            int dr = Integer.signum(to.row() - from.row());
            int dc = Integer.signum(to.column() - from.column());
            if (dr < 0) direction = 0;
            else if (dr > 0) direction = 1;
            else if (dc < 0) direction = 2;
            else direction = 3;

            boolean gap = Math.abs(to.row() - from.row())
                    + Math.abs(to.column() - from.column()) == 2;
            cost += gap ? 1.45D : 1.0D;

            if (previousDirection >= 0 && previousDirection != direction) {
                int delta = Math.abs(previousDirection - direction);
                delta = Math.min(delta, 4 - delta);
                cost += delta == 2 ? 4.0D : 1.75D;
            }
            previousDirection = direction;
        }
        return cost;
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
