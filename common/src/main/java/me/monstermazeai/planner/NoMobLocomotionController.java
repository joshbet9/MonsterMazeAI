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
import me.monstermazeai.physics.LegacyMazePhysics;

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

        if (route != null && !routeContainsSupportedCell(start)) {
            /*
             * The player has physically left the committed route. Rebuild only
             * from the static PlayerPathfinder; never jump to an arbitrary
             * future edge based on AABB overlap.
             */
            clearRoute();
            lastDecision = "ROUTE_DESYNC supported="
                    + start.row() + "," + start.column();
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
                /*
                 * Phase 1 never calls the monster-aware planner. If the stable
                 * floor graph is disconnected, use only the source gap edges
                 * from PlayerPathfinder; gap selection is still static and
                 * deterministic, with no monster prediction or tactical scoring.
                 */
                List<Cell> staticWithGaps = regionRadius > 0
                        ? pathfinder.fastestPathToRegionWithGaps(
                                planningMaze, start, goal, regionRadius)
                        : pathfinder.fastestPathWithGaps(
                                planningMaze, start, goal);
                route = staticWithGaps.isEmpty()
                        ? null
                        : new PlayerRoute(staticWithGaps);
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

    private boolean routeContainsSupportedCell(Cell supported) {
        if (route == null || supported == null) return true;

        List<Cell> cells = route.cells();
        int current = Math.max(0, Math.min(routeEdgeIndex, cells.size() - 1));

        /*
         * Only exact route-cell identity may advance the logical route cursor.
         * Search forward from the committed edge, but never use an AABB overlap
         * with a distant future cell as evidence of route progress.
         */
        for (int i = current; i < cells.size(); i++) {
            if (cells.get(i).equals(supported)) {
                routeEdgeIndex = Math.max(routeEdgeIndex, i);
                return true;
            }
        }
        return false;
    }

    private void reanchorFromSupportedCell(GameState state) {
        /*
         * Retained as a compatibility hook for the existing call site. Route
         * progress is now reconciled exclusively against the exact supported
         * floor cell; no future-cell overlap heuristic remains.
         */
        Cell supported = resolveSupportedStart(state);
        if (supported != null) {
            routeContainsSupportedCell(supported);
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
         * Fast baseline: aim at the next floor-cell centre. This maximizes
         * travel speed through ordinary corridors; the safety layer below only
         * intervenes when the exact one-tick source model predicts a floor loss.
         */
        float yawError = headingErrorForDirection(state, worldX, worldZ);
        double speedAlong = state.player.vx * edge.dirX + state.player.vz * edge.dirZ;

        boolean jump = shouldSpeedJump(
                state, allowJump, speedAlong, remaining,
                false);

        /*
         * Charged Jumper jumps are safe only while the player is well aligned
         * with the committed floor corridor. After the three charges are gone,
         * the same Jump input is the source horizontal speed impulse and should
         * be available on every grounded tick.
         */
        double crossTrack = edgeLateral(
                state, edge.from, directionRow(edge), directionColumn(edge));
        if (state.kit == Kit.JUMPER
                && (Math.abs(crossTrack) > p3JumperCrossTrackLimit()
                    || Math.abs(yawError) > p3JumperYawLimit()
                    || hasTurnWithinCells(p3JumperTurnLookahead()))) {
            jump = false;
        }

        if (Math.abs(yawError) > 75.0F) {
            jump = false;
        }

        lastDecision = "CELL_DRIVE edge=" + edge.index
                + " target=" + target.row() + "," + target.column()
                + " remaining=" + format(remaining)
                + " speed=" + format(speedAlong)
                + " yawError=" + format(yawError)
                + " jump=" + jump;

        Action proposed = driveVector(state, worldX, worldZ, 1.0, true, jump);
        if (projectedFloorSafe(state, proposed)) {
            return proposed;
        }

        /*
         * Corner safety fallback. Do not replace the normal fast controller
         * every tick; only search alternatives when the chosen target-centre
         * vector would leave the physical floor on the very next source tick.
         */
        Action edgeAligned = driveVector(
                state, edge.dirX, edge.dirZ, 1.0, true, false);
        if (projectedFloorSafe(state, edgeAligned)) {
            lastDecision += "_FALLBACK_EDGE";
            return edgeAligned;
        }

        if (Math.abs(crossTrack) > 0.02D) {
            double correction = Math.max(
                    -0.35D, Math.min(0.35D, -crossTrack * 1.5D));
            double correctedX = edge.dirX;
            double correctedZ = edge.dirZ;
            if (edge.dirX != 0.0D) correctedZ += correction;
            else correctedX += correction;

            double len = Math.hypot(correctedX, correctedZ);
            if (len > 1.0E-9D) {
                correctedX /= len;
                correctedZ /= len;
                Action lane = driveVector(
                        state, correctedX, correctedZ, 1.0, true, false);
                if (projectedFloorSafe(state, lane)) {
                    lastDecision += "_FALLBACK_LANE";
                    return lane;
                }
            }
        }

        Action brake = brakeVelocity(state);
        if (projectedFloorSafe(state, brake)) {
            lastDecision += "_FALLBACK_BRAKE";
            return brake;
        }

        lastDecision += "_FALLBACK_IDLE";
        return Action.IDLE;
    }

    private boolean projectedFloorSafe(GameState state, Action action) {
        me.monstermazeai.player.PlayerState projected = state.player.copy();
        int jumpAmplifier =
                state.kit == Kit.JUMPER && state.ability.charges > 0 ? 0 : -10;

        new LegacyMazePhysics().tick(
                projected, action, state.maze, jumpAmplifier);

        if (projected.y < -0.01D) return false;

        boolean airborneJump =
                action.jump()
                        && state.kit == Kit.JUMPER
                        && state.ability.charges > 0
                        && projected.y > 0.01D;
        return airborneJump
                || physicalFloorUnderAabb(state, projected.x, projected.z);
    }

    private static boolean physicalFloorUnderAabb(
            GameState state, double x, double z) {
        final double halfWidth = 0.30D;
        int minRow = (int) Math.floor(x - halfWidth);
        int maxRow = (int) Math.floor(Math.nextDown(x + halfWidth));
        int minColumn = (int) Math.floor(z - halfWidth);
        int maxColumn = (int) Math.floor(Math.nextDown(z + halfWidth));

        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (state.maze.isPhysicalFloor(row, column)) return true;
            }
        }
        return false;
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

    private static double p3JumperCrossTrackLimit() {
        double value = Double.parseDouble(
                System.getProperty("p3JumperCrossTrackLimit", "0.30"));
        return Math.max(0.05D, Math.min(0.75D, value));
    }

    private static float p3JumperYawLimit() {
        float value = Float.parseFloat(
                System.getProperty("p3JumperYawLimit", "25.0"));
        return Math.max(5.0F, Math.min(75.0F, value));
    }

    private static int p3JumperTurnLookahead() {
        int value = Integer.getInteger("p3JumperTurnLookahead", 0);
        return Math.max(0, Math.min(10, value));
    }

    private boolean hasTurnWithinCells(int lookaheadCells) {
        if (route == null || route.size() < 3) return false;
        int end = Math.min(
                route.size() - 2,
                routeEdgeIndex + Math.max(1, lookaheadCells));
        for (int i = Math.max(0, routeEdgeIndex); i <= end; i++) {
            if (i + 2 >= route.size()) break;
            if (changesDirection(
                    route.cells().get(i),
                    route.cells().get(i + 1),
                    route.cells().get(i + 2))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSpeedJump(
            GameState state,
            boolean ignoredAllowJump,
            double speedAlong,
            double remaining,
            boolean nextTurn) {
        if (!state.player.grounded) {
            return false;
        }
        if (nextTurn && remaining <= 1.20D) {
            return false;
        }

        /*
         * Jumper is special in the source game: after its three temporary
         * vertical charges are consumed, the same client Jump input becomes the
         * horizontal speed interaction. There is no source-side reason to
         * throttle those inputs to the generic non-Jumper cadence.
         *
         * Phase-1 survival therefore uses:
         *   - charged Jumper: request Jump whenever safe; the source AbilityModel
         *     enforces the 15-tick charge recharge;
         *   - uncharged Jumper: request Jump every grounded tick so the real
         *     horizontal impulse is fully available.
         *   - other kits: retain the profile's normal cadence.
         */
        if (state.kit == Kit.JUMPER) {
            return true;
        }

        if (speedAlong >= TARGET_SPEED) {
            return false;
        }

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
        /*
         * Human-like camera convergence: large route-heading errors should be
         * closed quickly while translation continues. The source client accepts
         * continuous mouse-look; this only changes the controller's yaw input,
         * not movement physics.
         */
        float yawDelta = clamp(
                yawError * 0.50F, -30.0F, 30.0F);
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
