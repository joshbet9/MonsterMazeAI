package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

import java.util.List;

/**
 * Stable, corridor-safe closed-loop movement controller for the flat Monster
 * Maze surface.
 *
 * The route planner produces a cardinal cell path. The motor layer must preserve
 * that topology: Minecraft movement is continuous, but the maze is effectively
 * a graph of one-block-wide floor cells. A continuously rotating forward vector
 * can cut a 90-degree corner diagonally across an air cell. That was the main
 * remaining failure mode after the authoritative MovementInput bridge fixed the
 * old keyboard-overwrite problem.
 *
 * The controller preserves the cardinal route topology while allowing the
 * Minecraft motor to steer and drive concurrently. Small/medium heading errors
 * are corrected with yaw input while forward movement continues, because that
 * is the natural player control model and avoids unnecessary stop-turn-go
 * cycles. Large corner turns and unsafe lane situations still fail closed so
 * the player cannot cut across an air cell.
 *
 * Consecutive collinear route cells are compressed into a single movement
 * segment, so the AI does not brake/turn at every cell and the resulting path
 * remains the shortest cardinal route selected by the planner.
 */
public final class StableLiveMovementController {
    private static final double WAYPOINT_ARRIVAL = 0.18;
    private static final double WAYPOINT_BRAKE = 0.70;
    private static final double ROUTE_DEVIATION = 0.55;
    /**
     * Every fresh observation is eligible for route replanning. Computational
     * optimisation belongs inside the planner, never in an artificial cadence
     * that discards newer world state.
     */

    /** Minecraft 1.8 yaw is allowed to turn at most 12 degrees per tick. */
    private static final float MAX_TURN_PER_TICK = 12.0F;
    /** Once inside this error, forward + steering is safe for the corridor. */
    private static final float HEADING_TOLERANCE = 2.0F;
    /**
     * Maximum heading error for simultaneous forward movement and cursor
     * steering. Larger errors are reserved for in-place corner acquisition.
     */
    private static final float MAX_DRIVE_STEER_ERROR = 45.0F;
    /** Let vanilla friction kill lateral/forward momentum before a corner turn. */
    private static final double MAX_TURNING_SPEED = 0.035;
    /** Do not attempt lane recovery once the player is already near the cell edge. */
    private static final double MAX_SAFE_LANE_ERROR = 0.28;
    /*
     * Monster Maze SafePads are centred on integer block coordinates, while
     * PlayerRoute cells use half-block cell centres. The live player can
     * therefore legitimately enter the first route cell with a 0.5-block
     * cross-track offset (the observed 50.0,50.0 spawn is exactly this case).
     * Preserve that physical lane when a segment begins instead of treating
     * the pad-to-maze coordinate transition as a dangerous deviation.
     */
    private static final double MAX_INITIAL_LANE_OFFSET = 0.65;

    private final MonsterAwareRoutePlanner routePlanner = new MonsterAwareRoutePlanner();

    private PlayerRoute route;
    /** Index of the next turn/goal cell, not merely the next adjacent cell. */
    private int waypointIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int goalRadius = 0;
    private long lastRouteTick = Long.MIN_VALUE;
    private String lastDecisionDetail = "UNSET";
    private int anchoredSegmentIndex = -1;
    /** Coarse local threat state used to avoid re-running global route simulation on every tick. */
    private long lastThreatSignature = Long.MIN_VALUE;
    /** First route is deliberately bootstrapped from physical topology so movement starts immediately. */
    private boolean bootstrapRoutePending = true;
    /** True until the first source-faithful monster-aware route evaluation completes. */
    private boolean fullRouteEvaluationPending = true;
    /** Local threat state for which the expensive tactical branch was last evaluated. */
    private long lastTacticalSignature = Long.MIN_VALUE;
    private double laneAnchorX;
    private double laneAnchorZ;

    public Action nextAction(GameState state, Cell goal, boolean allowJump) {
        return nextAction(state, goal, allowJump, 0);
    }

    /** Live Safe Pad variant: goal identifies the beacon anchor, radius identifies its walkable surface. */
    public Action nextAction(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || state.maze == null || goal == null) {
            reset();
            lastDecisionDetail = "INVALID_INPUT";
            return Action.IDLE;
        }

        if (regionRadius < 0) throw new IllegalArgumentException("regionRadius must be non-negative");

        if (goal.row() != goalRow || goal.column() != goalColumn || regionRadius != goalRadius) {
            clearRoute();
            goalRow = goal.row();
            goalColumn = goal.column();
            goalRadius = regionRadius;
        }

        int startRow = (int) Math.floor(state.player.x);
        int startColumn = (int) Math.floor(state.player.z);
        if (!inBounds(startRow, startColumn) || !inBounds(goal.row(), goal.column())) {
            lastDecisionDetail = "OUT_OF_BOUNDS start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
            return Action.IDLE;
        }

        // Entering any physical cell of the Safe Pad completes the movement
        // objective. Do not continue toward the beacon centre or re-route back
        // out of the pad after a monster-risk update.
        if (regionRadius > 0 && PadModel.isOn(state.player, goal.row() + 0.5,
                GameState.PAD_SURFACE_Y, goal.column() + 0.5)) {
            clearRoute();
            lastDecisionDetail = "REACHED_SAFE_PAD exact_source_geometry";
            return Action.IDLE;
        }

        if (route == null || shouldReplan(state, startRow, startColumn)) {
            boolean bootstrap = route == null && bootstrapRoutePending;
            route = regionRadius > 0
                    ? (bootstrap
                        ? routePlanner.routeToRegionFast(state, new Cell(startRow, startColumn), goal, regionRadius)
                        : routePlanner.routeToRegion(state, new Cell(startRow, startColumn), goal, regionRadius))
                    : (bootstrap
                        ? routePlanner.routeFast(state, new Cell(startRow, startColumn), goal)
                        : routePlanner.route(state, new Cell(startRow, startColumn), goal));
            waypointIndex = firstTurnWaypoint(route);
            lastRouteTick = state.tick;
            lastThreatSignature = threatSignature(state);
            lastDecisionDetail = (bootstrap ? "BOOTSTRAP_ROUTE" : "ROUTE_REPLAN")
                    + " size=" + route.size()
                    + " regionRadius=" + regionRadius
                    + " start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
            if (bootstrap) {
                bootstrapRoutePending = false;
                fullRouteEvaluationPending = true;
            } else {
                fullRouteEvaluationPending = false;
            }
            lastTacticalSignature = Long.MIN_VALUE;
        }

        if (route.size() == 1) {
            lastDecisionDetail = "REACHED routeSize=1";
            return Action.IDLE;
        }

        // A route waypoint is a turn cell. Once its centre is reached, switch
        // to the next segment. Never skip over a corner and then turn back.
        while (waypointIndex < route.size() - 1
                && distanceToWaypoint(state, waypointIndex) <= WAYPOINT_ARRIVAL) {
            int previousWaypoint = waypointIndex;
            waypointIndex = nextTurnWaypoint(route, waypointIndex);
            if (waypointIndex != previousWaypoint) {
                anchoredSegmentIndex = -1;
            }
        }

        if (waypointIndex >= route.size()) {
            lastDecisionDetail = "REACHED routeSize=" + route.size();
            return Action.IDLE;
        }

        // When a source interaction is close enough to matter this tick, hand
        // control to the same tactical simulator used during route selection.
        // This is what makes deliberate contact and ability use real live actions,
        // rather than merely simulated route preferences.
        long currentThreatSignature = threatSignature(state);
        if (routePlanner.shouldUseTacticalAction(state)
                && currentThreatSignature != lastTacticalSignature) {
            /*
             * Tactical search is a receding-horizon event, not a held command.
             * Only its first action is returned. The next observation falls back
             * to the live steering motor unless the local threat state materially
             * changes, preventing stale yaw/ability pulses from being replayed.
             */
            Action tactical = routePlanner.tacticalAction(
                    state, route, goal, regionRadius);
            lastTacticalSignature = currentThreatSignature;
            if (tactical != null) {
                lastDecisionDetail += " TACTICAL=" + tactical;
                return tactical;
            }
        }

        double targetX = route.targetX(waypointIndex);
        double targetZ = route.targetZ(waypointIndex);
        double dx = targetX - state.player.x;
        double dz = targetZ - state.player.z;
        double distance = Math.hypot(dx, dz);

        int startIndex = waypointIndex - 1;
        int startCellRow = route.cells().get(startIndex).row();
        int startCellColumn = route.cells().get(startIndex).column();
        int targetCellRow = route.cells().get(waypointIndex).row();
        int targetCellColumn = route.cells().get(waypointIndex).column();

        int dirRow = Integer.signum(targetCellRow - startCellRow);
        int dirColumn = Integer.signum(targetCellColumn - startCellColumn);

        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) {
            // Defensive failure: PlayerRoute must be cardinal. Never issue a
            // diagonal command if the route invariant is broken.
            lastDecisionDetail += " INVALID_SEGMENT="
                    + startCellRow + "," + startCellColumn + "->"
                    + targetCellRow + "," + targetCellColumn;
            return Action.IDLE;
        }

        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        double speed = Math.hypot(state.player.vx, state.player.vz);

        /*
         * Keep the player on the route's cell centreline. Normally this error
         * is tiny. If physics nudges the player sideways inside the current
         * floor cell, briefly correct toward that centre before resuming the
         * cardinal segment. The correction is only permitted while comfortably
         * inside the current cell; near an edge we stop rather than drive into
         * an unknown/air cell.
         */
        if (anchoredSegmentIndex != waypointIndex) {
            /*
             * Anchor the corridor to the player's actual cross-axis position
             * when a segment begins. This is important at a source-accurate
             * SafePad transition: SafePad.isOn() is centred on integer block
             * coordinates, whereas route cell centres are half-block positions.
             * Only accept a modest offset; larger deviations still fail closed.
             */
            double nominalLaneX = startCellRow + 0.5;
            double nominalLaneZ = startCellColumn + 0.5;
            if (dirRow == 0) {
                double offset = state.player.x - nominalLaneX;
                laneAnchorX = Math.abs(offset) <= MAX_INITIAL_LANE_OFFSET
                        ? state.player.x : nominalLaneX;
                laneAnchorZ = nominalLaneZ;
            } else {
                laneAnchorX = nominalLaneX;
                double offset = state.player.z - nominalLaneZ;
                laneAnchorZ = Math.abs(offset) <= MAX_INITIAL_LANE_OFFSET
                        ? state.player.z : nominalLaneZ;
            }
            anchoredSegmentIndex = waypointIndex;
        }

        double crossTrack = crossTrackError(
                state.player.x, state.player.z, laneAnchorX, laneAnchorZ,
                dirRow, dirColumn);

        Action action;

        if (Math.abs(crossTrack) > MAX_SAFE_LANE_ERROR) {
            action = new Action(0.0, 0.0, false, false, 0.0F, false);
            lastDecisionDetail += " SAFETY_STOP crossTrack=" + format(crossTrack);
        } else if (Math.abs(crossTrack) > 0.18) {
            double laneTargetX = dirRow == 0 ? laneAnchorX : state.player.x;
            double laneTargetZ = dirColumn == 0 ? laneAnchorZ : state.player.z;
            float correctionYaw = (float) Math.toDegrees(
                    Math.atan2(-(laneTargetX - state.player.x), laneTargetZ - state.player.z));
            float correctionError = normalise(correctionYaw - state.player.yaw);

            if (speed > MAX_TURNING_SPEED || Math.abs(correctionError) > HEADING_TOLERANCE) {
                action = new Action(
                        0.0, 0.0, false, false,
                        speed <= MAX_TURNING_SPEED
                                ? clamp(correctionError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
                                : 0.0F,
                        false);
            } else {
                action = new Action(1.0, 0.0, false, true, 0.0F, false);
            }
        } else if (Math.abs(yawError) > HEADING_TOLERANCE) {
            /*
             * Normal steering is concurrent with forward movement. This is
             * deliberately not a time/cadence throttle: every fresh
             * observation can adjust both axes immediately.
             *
             * For moderate errors, Minecraft receives forward input and a
             * bounded cursor/yaw correction in the same tick. This lets the
             * player naturally arc onto the cardinal corridor instead of
             * stopping for several ticks at every heading correction.
             *
             * A large error is different: a 90-degree corner cannot safely
             * be cut across a one-cell corridor, so acquire the heading first.
             */
            float turn = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            if (Math.abs(yawError) <= MAX_DRIVE_STEER_ERROR) {
                boolean brake = distance < WAYPOINT_BRAKE
                        && closingSpeed(state, dx, dz) > 0.04;
                double forward = brake ? 0.0 : 1.0;
                boolean jump = allowJump
                        && state.player.grounded
                        && forward > 0.0
                        && distance > WAYPOINT_ARRIVAL;
                action = new Action(forward, 0.0, jump, forward > 0.0, turn, false);
                lastDecisionDetail += " STEER_DRIVE";
            } else {
                action = new Action(
                        0.0, 0.0, false, false,
                        speed <= MAX_TURNING_SPEED ? turn : 0.0F,
                        false);
            }
        } else {
            boolean brake = distance < WAYPOINT_BRAKE
                    && closingSpeed(state, dx, dz) > 0.04;
            double forward = brake ? 0.0 : 1.0;
            boolean jump = allowJump
                    && state.player.grounded
                    && forward > 0.0
                    && distance > WAYPOINT_ARRIVAL;
            action = new Action(forward, 0.0, jump, forward > 0.0, 0.0F, false);
        }

        lastDecisionDetail += " waypoint=" + waypointIndex + "/" + (route.size() - 1)
                + " target=" + targetX + "," + targetZ
                + " dist=" + format(distance)
                + " dir=" + dirRow + "," + dirColumn
                + " yawError=" + format(yawError)
                + " crossTrack=" + format(crossTrack)
                + " speed=" + format(speed)
                + " output=f=" + action.forward()
                + ",s=" + action.strafe()
                + ",jump=" + action.jump()
                + ",yawDelta=" + action.yawDelta();
        return action;
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public void reset() {
        clearRoute();
        goalRow = -1;
        goalColumn = -1;
        goalRadius = 0;
        lastRouteTick = Long.MIN_VALUE;
        anchoredSegmentIndex = -1;
        lastThreatSignature = Long.MIN_VALUE;
        bootstrapRoutePending = true;
        fullRouteEvaluationPending = true;
        lastTacticalSignature = Long.MIN_VALUE;
        cachedTacticalAction = null;
        laneAnchorX = 0.0;
        laneAnchorZ = 0.0;
        lastDecisionDetail = "RESET";
    }

    private boolean shouldReplan(GameState state, int startRow, int startColumn) {
        if (route == null) return true;

        Cell current = new Cell(startRow, startColumn);
        if (!route.cells().contains(current)) return true;

        /*
         * A route is committed in the graph, but a continuous player can drift
         * outside its one-cell corridor without changing containingCell yet.
         * Treat that as a genuine deviation rather than continuing to drive
         * toward a stale corner.
         */
        if (distanceFromRouteCorridor(state, route, waypointIndex) > ROUTE_DEVIATION) {
            return true;
        }

        /*
         * Fresh observations still reach this controller every tick. What we
         * must not do is throw away a valid committed route and re-run the
         * source-faithful simulator merely because the player moved 0.05 blocks.
         *
         * The motor layer below continues to consume the newest player pose,
         * yaw and monster state immediately. Global route simulation is only
         * repeated when a material local threat change occurs (or the route
         * itself becomes invalid). This separates high-frequency control from
         * expensive strategic search without reducing the AI's tactical horizon.
         */
        long threat = threatSignature(state);
        if (fullRouteEvaluationPending) {
            return true;
        }
        return threat != lastThreatSignature;
    }

    private static int firstTurnWaypoint(PlayerRoute route) {
        if (route.size() <= 1) return route.size();
        return nextTurnWaypoint(route, 0);
    }

    private static int nextTurnWaypoint(PlayerRoute route, int fromIndex) {
        if (fromIndex >= route.size() - 1) return route.size();

        List<Cell> cells = route.cells();
        int previousRow = cells.get(fromIndex).row();
        int previousColumn = cells.get(fromIndex).column();

        int segmentRow = Integer.signum(cells.get(fromIndex + 1).row() - previousRow);
        int segmentColumn = Integer.signum(cells.get(fromIndex + 1).column() - previousColumn);

        for (int i = fromIndex + 1; i < cells.size() - 1; i++) {
            int nextRow = Integer.signum(cells.get(i + 1).row() - cells.get(i).row());
            int nextColumn = Integer.signum(cells.get(i + 1).column() - cells.get(i).column());
            if (nextRow != segmentRow || nextColumn != segmentColumn) {
                return i;
            }
        }
        return cells.size() - 1;
    }

    private static float cardinalYaw(int rowDirection, int columnDirection) {
        // Logical row is world X; logical column is world Z.
        if (rowDirection > 0) return -90.0F; // +X / east
        if (rowDirection < 0) return 90.0F;  // -X / west
        if (columnDirection > 0) return 0.0F; // +Z / south
        return 180.0F; // -Z / north
    }

    private static double crossTrackError(
            double x, double z, double laneX, double laneZ,
            int rowDirection, int columnDirection) {
        if (rowDirection == 0) return x - laneX;
        return z - laneZ;
    }

    private static double closingSpeed(GameState state, double dx, double dz) {
        double length = Math.max(Math.hypot(dx, dz), 1.0E-6);
        return (state.player.vx * dx + state.player.vz * dz) / length;
    }

    private double distanceToWaypoint(GameState state, int index) {
        return Math.hypot(
                state.player.x - route.targetX(index),
                state.player.z - route.targetZ(index));
    }

    private double distanceFromRouteCorridor(GameState state, PlayerRoute route, int targetIndex) {
        if (targetIndex <= 0 || targetIndex >= route.size()) return 0.0;

        Cell start = route.cells().get(targetIndex - 1);
        Cell target = route.cells().get(targetIndex);
        double startX = start.row() + 0.5;
        double startZ = start.column() + 0.5;
        double targetX = target.row() + 0.5;
        double targetZ = target.column() + 0.5;

        double dx = targetX - startX;
        double dz = targetZ - startZ;
        if (Math.abs(dx) > Math.abs(dz)) {
            return Math.abs(state.player.z - startZ);
        }
        return Math.abs(state.player.x - startX);
    }

    private static boolean insideRegion(int row, int column, Cell center, int radius) {
        return Math.abs(row - center.row()) <= radius
                && Math.abs(column - center.column()) <= radius;
    }

    private static boolean inBounds(int row, int column) {
        return row >= 0 && row < me.monstermazeai.maze.MazeModel.SIZE
                && column >= 0 && column < me.monstermazeai.maze.MazeModel.SIZE;
    }

    private static float normalise(float angle) {
        while (angle >= 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }


    /**
     * Quantised local threat signature. Sub-block monster motion does not force
     * a complete route search every client observation; crossing a one-block
     * spatial bucket, changing launch/freeze state, or entering/leaving the
     * 20-block interaction sphere does. Immediate tactical control still uses
     * the current observation when a contact search is required.
     */
    private static long threatSignature(GameState state) {
        long h = 1469598103934665603L;
        for (me.monstermazeai.monster.MonsterState monster : state.monsters) {
            if (!me.monstermazeai.monster.MonsterRelevance.withinPlayerRadius(
                    monster, state.player, me.monstermazeai.monster.MonsterRelevance.INTERACTION_RADIUS)) continue;
            h = mix(h, monster.id);
            h = mix(h, (long) Math.floor(monster.x));
            h = mix(h, (long) Math.floor(monster.y));
            h = mix(h, (long) Math.floor(monster.z));
            h = mix(h, monster.launched(state.tick) ? 1L : 0L);
            h = mix(h, monster.frozen(state.tick) ? 1L : 0L);
        }
        return h;
    }

    private static long mix(long h, long value) {
        h ^= value;
        return h * 1099511628211L;
    }

    private void clearRoute() {
        route = null;
        waypointIndex = 0;
        anchoredSegmentIndex = -1;
    }
}
