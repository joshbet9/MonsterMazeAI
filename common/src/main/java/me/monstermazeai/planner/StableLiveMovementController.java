package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.monster.MobInteractionDecision;
import me.monstermazeai.player.Action;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    /*
     * Strategic route simulation is deliberately isolated from the live motor.
     * The motor must never wait for source-faithful multi-candidate simulation:
     * a stale movement command can carry the player off a one-block platform.
     */
    private final MonsterAwareRoutePlanner backgroundRoutePlanner = new MonsterAwareRoutePlanner();
    private final ExecutorService routePlanningExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MonsterMaze-strategic-planner");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pendingRoutePlan;
    private volatile PlannedRoute completedRoutePlan;

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
    private long routePlanCount;
    private double laneAnchorX;
    private double laneAnchorZ;

    /*
     * A real Monster Maze bump is not just another route deviation. The source
     * applies a large velocity impulse and a four-health hit, then gives the
     * player a short grace window. If the live controller keeps executing the
     * pre-hit route while airborne, it can immediately steer/jump against the
     * server knockback and lose the maze edge. Keep a small source-faithful
     * recovery state in the live motor so the next grounded observation starts
     * from the actual post-bump position.
     */
    private static final long MOB_HIT_RECOVERY_TICKS = 40L;
    private long mobHitRecoveryUntilTick = Long.MIN_VALUE;
    private double previousHealth = Double.NaN;

    /**
     * Terminal SafePad transition commitment. The live observer exposes the
     * source's 5x5 pad as physical floor even where the canonical maze layout
     * was air. Near the outer edge that surface must be treated as a special
     * transition, not as an ordinary one-block corridor: align to the entry
     * direction, commit the crossing, and do not let a concurrent strategic
     * replan replace the motor command mid-transition.
     */
    private static final double PAD_ENTRY_COMMIT_DISTANCE = 1.25;
    private static final double PAD_ENTRY_RELEASE_DISTANCE = 2.75;
    private static final long PAD_ENTRY_MAX_TICKS = 18L;
    private static final double GAP_JUMP_TRIGGER_DISTANCE = 0.35D;
    private static final double GAP_JUMP_LATE_TOLERANCE = 0.08D;
    private static final double GAP_LANDING_PROGRESS = 1.20D;
    private static final float GAP_HEADING_TOLERANCE = 5.0F;
    private static final double GAP_LATERAL_SPEED_LIMIT = 0.12D;
    private static final int GAP_LANDING_CONFIRM_TICKS = 2;
    private boolean padEntryCommitment;
    private int padEntryRow = -1;
    private int padEntryColumn = -1;
    private int padEntryDirRow;
    private int padEntryDirColumn;
    private long padEntryStartTick = Long.MIN_VALUE;
    private boolean gapExecutionActive;
    private boolean gapTakeoffStarted;
    /*
     * When a new SafePad activates while the player is still standing on the
     * previous pad, acquire the first route segment's heading before allowing
     * forward input. This makes the pad transition look like a deliberate
     * turn-and-go instead of a last-second 90/180-degree head snap.
     */
    private boolean padTransitionFacing;
    private int padTransitionPreviousRow = -1;
    private int padTransitionPreviousColumn = -1;
    private int gapExecutionRouteIndex = -1;
    private int gapLandingConfirmTicks;


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

        boolean mobHit = detectLiveMobHit(state);
        if (mobHit) {
            mobHitRecoveryUntilTick = Math.max(
                    mobHitRecoveryUntilTick,
                    state.tick + MOB_HIT_RECOVERY_TICKS);
            clearRoute();
            clearPadEntryCommitment();
            clearGapCommitment();
            fullRouteEvaluationPending = true;
            lastThreatSignature = Long.MIN_VALUE;
            lastTacticalSignature = Long.MIN_VALUE;
            lastDecisionDetail = "MOB_HIT_RECOVERY"
                    + " health=" + format(state.player.health)
                    + " recoveryUntil=" + mobHitRecoveryUntilTick
                    + " grounded=" + state.player.grounded;
            /*
             * While airborne, the server's bump velocity is authoritative.
             * Do not inject a jump, strafe, or stale route turn into it.
             * Once grounded, route construction below uses the new position.
             */
            if (!state.player.grounded) {
                return airborneMobRecoveryAction(state, goal);
            }
        }

        if (state.tick <= mobHitRecoveryUntilTick && !state.player.grounded) {
            return airborneMobRecoveryAction(state, goal);
        }

        /*
         * Emergency contact is deliberately separate from ordinary tactical
         * avoidance. If the deadline is already unattainable by normal travel,
         * a nearby monster can be used as a source-faithful bump toward the
         * active pad. MobInteractionDecision refuses this at <= 2 hearts.
         */
        MonsterState intentionalBump = MobInteractionDecision.chooseIntentionalBump(state);
        if (intentionalBump != null) {
            Action bumpAction = steerIntoMonster(state, intentionalBump);
            if (bumpAction != null) return bumpAction;
        }

        int previousGoalRow = goalRow;
        int previousGoalColumn = goalColumn;
        boolean objectiveChanged = goal.row() != goalRow
                || goal.column() != goalColumn
                || regionRadius != goalRadius;
        if (objectiveChanged) {
            clearRoute();
            clearGapCommitment();
            padTransitionFacing = previousGoalRow >= 0
                    && previousGoalColumn >= 0
                    && PadModel.isOn(state.player, previousGoalRow + 0.5,
                            GameState.PAD_SURFACE_Y, previousGoalColumn + 0.5);
            if (padTransitionFacing) {
                padTransitionPreviousRow = previousGoalRow;
                padTransitionPreviousColumn = previousGoalColumn;
            } else {
                clearPadTransitionFacing();
            }
            goalRow = goal.row();
            goalColumn = goal.column();
            goalRadius = regionRadius;
        }

        // Once a pad-edge crossing is committed, a newer strategic route is
        // not allowed to replace it. The only authoritative exits are landing
        // on the pad, losing the edge, or a bounded timeout/recovery condition.
        if (padEntryCommitment) {
            Action committed = executePadEntryCommitment(state, goal, allowJump);
            if (committed != null) return committed;
        }
        if (gapExecutionActive) {
            Action committed = executeCommittedGap(state, allowJump);
            if (committed != null) return committed;
        }

        GameState routingState = transitionRoutingState(
                state, objectiveChanged ? previousGoalRow : -1, objectiveChanged ? previousGoalColumn : -1);
        Cell supportedStart = resolveSupportedStartCell(state);
        if (supportedStart == null || !inBounds(goal.row(), goal.column())) {
            lastDecisionDetail = "NO_PHYSICAL_SUPPORT player=" + format(state.player.x) + "," + format(state.player.z)
                    + " y=" + format(state.player.y) + " goal=" + goal.row() + "," + goal.column();
            return Action.IDLE;
        }
        int startRow = supportedStart.row();
        int startColumn = supportedStart.column();

        // Entering any physical cell of the Safe Pad completes the movement
        // objective. Do not continue toward the beacon centre or re-route back
        // out of the pad after a monster-risk update.
        if (regionRadius > 0 && PadModel.isOn(state.player, goal.row() + 0.5,
                GameState.PAD_SURFACE_Y, goal.column() + 0.5)) {
            clearRoute();
            lastDecisionDetail = "REACHED_SAFE_PAD exact_source_geometry";
            return Action.IDLE;
        }

        if (!padEntryCommitment && !gapExecutionActive) {
            applyCompletedRoutePlan(state, startRow, startColumn, goal, regionRadius);
        }

        if (route == null) {
            /*
             * The first physical route must be available synchronously, but it
             * is intentionally only a shortest-topology path. The expensive
             * source-faithful evaluation is submitted after this action is
             * constructed, never placed on the live-control critical path.
             */
            route = regionRadius > 0
                    ? routePlanner.routeToRegionFast(routingState, new Cell(startRow, startColumn), goal, regionRadius)
                    : routePlanner.routeFast(routingState, new Cell(startRow, startColumn), goal);
            waypointIndex = firstTurnWaypoint(route);
            lastRouteTick = state.tick;
            routePlanCount++;
            lastThreatSignature = threatSignature(state);
            lastDecisionDetail = "BOOTSTRAP_ROUTE"
                    + " size=" + route.size()
                    + " regionRadius=" + regionRadius
                    + " start=" + startRow + "," + startColumn
                    + " goal=" + goal.row() + "," + goal.column();
            bootstrapRoutePending = false;
            fullRouteEvaluationPending = true;
            lastTacticalSignature = Long.MIN_VALUE;
            scheduleStrategicRoute(routingState, new Cell(startRow, startColumn), goal, regionRadius);
        } else {
            long threat = threatSignature(state);
            boolean routeInvalid = (!gapExecutionActive && !route.cells().contains(new Cell(startRow, startColumn)))
                    || (!gapExecutionActive && distanceFromRouteCorridor(state, route, waypointIndex) > ROUTE_DEVIATION);

            if (routeInvalid) {
                /*
                 * Recover immediately with a cheap physical route, then let the
                 * background planner decide whether a different risk-aware route
                 * is preferable. Never block the motor waiting for that result.
                 */
                route = regionRadius > 0
                        ? routePlanner.routeToRegionFast(state, new Cell(startRow, startColumn), goal, regionRadius)
                        : routePlanner.routeFast(state, new Cell(startRow, startColumn), goal);
                waypointIndex = firstTurnWaypoint(route);
                anchoredSegmentIndex = -1;
                lastRouteTick = state.tick;
                routePlanCount++;
                lastDecisionDetail = "FAST_RECOVERY_ROUTE"
                        + " size=" + route.size()
                        + " regionRadius=" + regionRadius
                        + " start=" + startRow + "," + startColumn
                        + " goal=" + goal.row() + "," + goal.column();
                fullRouteEvaluationPending = true;
                lastTacticalSignature = Long.MIN_VALUE;
                lastThreatSignature = threat;
                scheduleStrategicRoute(state, new Cell(startRow, startColumn), goal, regionRadius);
            } else if (fullRouteEvaluationPending || threat != lastThreatSignature) {
                lastThreatSignature = threat;
                fullRouteEvaluationPending = false;
                scheduleStrategicRoute(state, new Cell(startRow, startColumn), goal, regionRadius);
            }
        }

        if (route.size() == 1) {
            lastDecisionDetail = "REACHED routeSize=1";
            return Action.IDLE;
        }

        /*
         * The next pad has now spawned and a route exists. While the player is
         * still on the old pad, spend the transition ticks rotating in place
         * toward the first route segment. Once aligned, normal movement resumes.
         */
        if (padTransitionFacing) {
            Action facing = executePadTransitionFacing(state);
            if (facing != null) return facing;
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

        Action padEntry = maybeBeginPadEntryCommitment(state, goal, allowJump);
        if (padEntry != null) return padEntry;

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
            if (tactical != null && isDiscreteTacticalAction(tactical, allowJump)) {
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

        boolean gapEdge = isGapEdge(state, startCellRow, startCellColumn, targetCellRow, targetCellColumn);
        if (gapEdge) {
            Action gapAction = prepareOrStartGap(state, dirRow, dirColumn, allowJump);
            if (gapAction != null) return gapAction;
        }

        if (!gapEdge && Math.abs(dirRow) + Math.abs(dirColumn) != 1) {
            // Defensive failure: PlayerRoute is normally cardinal, with the
            // sole exception of a two-cell orthogonal edge representing one
            // missing block. Diagonal movement remains forbidden.
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
            /*
             * A player can remain physically supported while the block
             * containing floor(x,z) is air. Stopping forever at a 0.3-0.5
             * lateral error is therefore not source-like: A/D correction is a
             * normal Minecraft input and is the safest way to recover the lane
             * without cutting the cardinal corridor.
             */
            int crossSign = crossTrack > 0.0 ? 1 : -1;
            double strafe = dirRow == 0
                    ? -crossSign * Math.signum(dirColumn)
                    : crossSign * Math.signum(dirRow);
            float correctionYaw = cardinalYaw(dirRow, dirColumn);
            float correctionError = normalise(correctionYaw - state.player.yaw);
            float yawDelta = speed <= MAX_TURNING_SPEED
                    ? clamp(correctionError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
                    : 0.0F;
            action = new Action(0.0, strafe, false, false, yawDelta, false);
            lastDecisionDetail += " LANE_RECOVERY crossTrack=" + format(crossTrack)
                    + " strafe=" + format(strafe);
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
            float turn = clamp(yawError * 0.5F, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            if (Math.abs(yawError) > HEADING_TOLERANCE && Math.abs(turn) < 1.0F) turn = yawError > 0 ? 1.0F : -1.0F;
            if (Math.abs(yawError) <= MAX_DRIVE_STEER_ERROR) {
                boolean brake = distance < WAYPOINT_BRAKE
                        && closingSpeed(state, dx, dz) > 0.04;
                /*
                 * Keep forward input concurrent with cursor movement, but do not
                 * carry full sprint acceleration through a sharp heading change.
                 * The player is on a floating one-cell corridor: preserving the
                 * route centreline is more important than squeezing maximum
                 * horizontal speed out of the first few steering ticks.
                 */
                double steeringForward;
                double absError = Math.abs(yawError);
                if (absError <= 20.0) steeringForward = 1.0;
                else if (absError <= 35.0) steeringForward = 0.80;
                else steeringForward = 0.50;
                double forward = brake ? 0.0 : steeringForward;
                boolean sprint = forward >= 0.95 && absError <= 15.0;
                boolean jump = allowJump
                        && state.player.grounded
                        && forward > 0.0
                        && distance > WAYPOINT_ARRIVAL
                        && absError <= 20.0;
                action = new Action(forward, 0.0, jump, sprint, turn, false);
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

    public long routePlanCount() { return routePlanCount; }

    public long lastRouteTick() { return lastRouteTick; }

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
        laneAnchorX = 0.0;
        laneAnchorZ = 0.0;
        mobHitRecoveryUntilTick = Long.MIN_VALUE;
        previousHealth = Double.NaN;
        clearPadEntryCommitment();
        clearGapCommitment();
        clearPadTransitionFacing();
        Future<?> pending = pendingRoutePlan;
        if (pending != null) pending.cancel(false);
        pendingRoutePlan = null;
        completedRoutePlan = null;
        lastDecisionDetail = "RESET";
    }

    /**
     * Safe Pad replacement can remove the previous pad's canonical maze floor
     * from the physical-floor model on the exact tick a new pad becomes active.
     * The player is still physically standing on that old pad, so treating the
     * current block as air makes a valid next-pad route look disconnected.
     *
     * Preserve only the old pad surface containing the player as a temporary
     * routing bridge. This is a transition bootstrap, not a permanent floor
     * mutation; subsequent observations remain authoritative.
     */
    private GameState transitionRoutingState(GameState state,
                                               int previousGoalRow, int previousGoalColumn) {
        int currentRow = (int) Math.floor(state.player.x);
        int currentColumn = (int) Math.floor(state.player.z);

        /*
         * Do not short-circuit merely because the player's current logical
         * cell is still marked physical floor. On the real SafePad transition
         * tick the observer can retain the exact cell under the player while
         * the rest of the previous 5x5 pad has already been removed from the
         * canonical maze. The route planner then sees an apparently valid
         * starting cell surrounded by disconnected topology.
         *
         * The previous objective is the authoritative transition bridge. If
         * the player is still on that old SafePad, always overlay the complete
         * source-accurate 5x5 surface for this routing decision.
         */
        Cell oldPad = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        /*
         * The live protocol does not carry the source plugin's historical
         * SafePad list. At the exact pad-transition tick, the observer removes
         * the previous 5x5 pad from physicalFloor and marks only the newly
         * active pad. The player is nevertheless still standing on the old pad,
         * so a raw-layout start cell can become "air" even though it is the
         * authoritative physical surface under the player.
         *
         * The controller already knows the previous objective. Use that
         * objective as the transition bridge when the player is still inside
         * its source-accurate 5x5 surface. This is strictly limited to an
         * objective change and therefore cannot turn arbitrary air into floor.
         */
        if (previousGoalRow >= 0 && previousGoalColumn >= 0
                && PadModel.isOn(state.player, previousGoalRow + 0.5,
                        GameState.PAD_SURFACE_Y, previousGoalColumn + 0.5)) {
            oldPad = new Cell(previousGoalRow, previousGoalColumn);
            bestDistance = sq(state.player.x - (previousGoalRow + 0.5))
                    + sq(state.player.z - (previousGoalColumn + 0.5));
        }

        for (Cell candidate : state.oldPads) {
            if (!PadModel.isOn(state.player, candidate.row() + 0.5,
                    GameState.PAD_SURFACE_Y, candidate.column() + 0.5)) continue;
            double dx = state.player.x - (candidate.row() + 0.5);
            double dz = state.player.z - (candidate.column() + 0.5);
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                oldPad = candidate;
            }
        }
        if (oldPad == null) return state;

        GameState routingState = state.copyForSimulation();
        routingState.maze = state.maze.copy();
        for (int row = Math.max(0, oldPad.row() - 2); row <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, oldPad.row() + 2); row++) {
            for (int column = Math.max(0, oldPad.column() - 2);
                 column <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, oldPad.column() + 2); column++) {
                routingState.maze.setPhysicalFloor(row, column, true);
            }
        }
        return routingState;
    }

    private void scheduleStrategicRoute(GameState liveState, Cell start, Cell goal, int regionRadius) {
        if (pendingRoutePlan != null && !pendingRoutePlan.isDone()) return;

        GameState snapshot = liveState.copyForSimulation();
        long requestedTick = liveState.tick;
        long topology = snapshot.maze.dynamicSignature();
        pendingRoutePlan = routePlanningExecutor.submit(() -> {
            try {
                PlayerRoute planned = regionRadius > 0
                        ? backgroundRoutePlanner.routeToRegion(snapshot, start, goal, regionRadius)
                        : backgroundRoutePlanner.route(snapshot, start, goal);
                completedRoutePlan = new PlannedRoute(
                        planned, start.row(), start.column(), goal.row(), goal.column(), regionRadius,
                        requestedTick, topology, threatSignature(snapshot));
            } catch (RuntimeException failure) {
                System.err.println("[MonsterMazeAI] background strategic route failed: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        });
    }

    private void applyCompletedRoutePlan(GameState state, int startRow, int startColumn,
                                         Cell goal, int regionRadius) {
        PlannedRoute planned = completedRoutePlan;
        if (planned == null) return;

        completedRoutePlan = null;
        long currentThreat = threatSignature(state);
        if (planned.startRow != startRow
                || planned.startColumn != startColumn
                || planned.goalRow != goal.row()
                || planned.goalColumn != goal.column()
                || planned.regionRadius != regionRadius
                || planned.route.cells().isEmpty()
                || state.maze.dynamicSignature() != planned.topologySignature
                || currentThreat != planned.threatSignature
                || state.tick - planned.requestedTick > 10L) {
            fullRouteEvaluationPending = true;
            return;
        }

        /*
         * A background tactical route may improve the long-term path, but it
         * must not reverse the motor's immediate cardinal segment while that
         * segment is still physically valid. Monster updates were otherwise
         * producing alternating first headings and left/right oscillation.
         */
        if (route != null && !strategicRoutePreservesCurrentHeading(
                state, planned.route, startRow, startColumn)) {
            fullRouteEvaluationPending = true;
            return;
        }

        route = planned.route;
        waypointIndex = firstTurnWaypoint(route);
        anchoredSegmentIndex = -1;
        lastRouteTick = planned.requestedTick;
        routePlanCount++;
        lastDecisionDetail = "ASYNC_ROUTE_APPLIED"
                + " size=" + route.size()
                + " regionRadius=" + regionRadius
                + " start=" + startRow + "," + startColumn
                + " goal=" + goal.row() + "," + goal.column()
                + " plannedTick=" + planned.requestedTick;
        fullRouteEvaluationPending = false;
        lastTacticalSignature = Long.MIN_VALUE;
    }

    private static final class PlannedRoute {
        final PlayerRoute route;
        final int startRow;
        final int startColumn;
        final int goalRow;
        final int goalColumn;
        final int regionRadius;
        final long requestedTick;
        final long topologySignature;
        final long threatSignature;

        PlannedRoute(PlayerRoute route, int startRow, int startColumn,
                     int goalRow, int goalColumn, int regionRadius,
                     long requestedTick, long topologySignature, long threatSignature) {
            this.route = route;
            this.startRow = startRow;
            this.startColumn = startColumn;
            this.goalRow = goalRow;
            this.goalColumn = goalColumn;
            this.regionRadius = regionRadius;
            this.requestedTick = requestedTick;
            this.topologySignature = topologySignature;
            this.threatSignature = threatSignature;
        }
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
        int segmentLength = Math.abs(cells.get(fromIndex + 1).row() - previousRow)
                + Math.abs(cells.get(fromIndex + 1).column() - previousColumn);

        for (int i = fromIndex + 1; i < cells.size() - 1; i++) {
            int nextRowDelta = cells.get(i + 1).row() - cells.get(i).row();
            int nextColumnDelta = cells.get(i + 1).column() - cells.get(i).column();
            int nextRow = Integer.signum(nextRowDelta);
            int nextColumn = Integer.signum(nextColumnDelta);
            int nextLength = Math.abs(nextRowDelta) + Math.abs(nextColumnDelta);
            /*
             * A one-block gap is encoded as a two-cell route edge in exactly
             * the same cardinal direction as the following floor edge. Direction
             * alone therefore cannot identify the boundary. Treat an edge-length
             * change as a waypoint boundary so the gap executor sees the
             * immediate gap edge instead of being handed the final straight-run
             * waypoint and walking past the gap.
             */
            if (nextRow != segmentRow || nextColumn != segmentColumn
                    || nextLength != segmentLength) {
                return i;
            }
        }
        return cells.size() - 1;
    }

    private Action executePadTransitionFacing(GameState state) {
        if (!padTransitionFacing) return null;

        if (!PadModel.isOn(state.player, padTransitionPreviousRow + 0.5,
                GameState.PAD_SURFACE_Y, padTransitionPreviousColumn + 0.5)) {
            clearPadTransitionFacing();
            return null;
        }

        if (route == null || route.size() < 2) {
            clearPadTransitionFacing();
            return null;
        }

        Cell from = route.cells().get(0);
        Cell to = route.cells().get(1);
        int dirRow = Integer.signum(to.row() - from.row());
        int dirColumn = Integer.signum(to.column() - from.column());
        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) {
            clearPadTransitionFacing();
            return null;
        }

        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        if (Math.abs(yawError) <= HEADING_TOLERANCE) {
            clearPadTransitionFacing();
            lastDecisionDetail = "PAD_TRANSITION_RELEASED"
                    + " goal=" + goalRow + "," + goalColumn;
            return null;
        }

        float turn = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        lastDecisionDetail = "PAD_TRANSITION_FACE"
                + " oldPad=" + padTransitionPreviousRow + "," + padTransitionPreviousColumn
                + " goal=" + goalRow + "," + goalColumn
                + " firstHeading=" + desiredYaw
                + " yawError=" + format(yawError)
                + " yawDelta=" + format(turn);
        return new Action(0.0, 0.0, false, false, turn, false);
    }

    private void clearPadTransitionFacing() {
        padTransitionFacing = false;
        padTransitionPreviousRow = -1;
        padTransitionPreviousColumn = -1;
    }

    private boolean strategicRoutePreservesCurrentHeading(
            GameState state, PlayerRoute planned, int startRow, int startColumn) {
        if (planned == null || planned.size() < 2 || route == null || route.size() < 2) {
            return true;
        }

        int currentTargetIndex = Math.max(1, Math.min(waypointIndex, route.size() - 1));
        Cell currentFrom = route.cells().get(currentTargetIndex - 1);
        Cell currentTo = route.cells().get(currentTargetIndex);
        int currentRowDirection = Integer.signum(currentTo.row() - currentFrom.row());
        int currentColumnDirection = Integer.signum(currentTo.column() - currentFrom.column());

        Cell plannedFrom = planned.cells().get(0);
        Cell plannedTo = planned.cells().get(1);
        if (plannedFrom.row() != startRow || plannedFrom.column() != startColumn) {
            return false;
        }

        int plannedRowDirection = Integer.signum(plannedTo.row() - plannedFrom.row());
        int plannedColumnDirection = Integer.signum(plannedTo.column() - plannedFrom.column());

        return currentRowDirection == plannedRowDirection
                && currentColumnDirection == plannedColumnDirection;
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
        if (route == null || route.size() < 2) return 0.0;

        /*
         * targetIndex is the next turn/goal waypoint, not necessarily the
         * segment the player is currently traversing. Using only
         * targetIndex-1 -> targetIndex made a fast player look "off route" while
         * still travelling along an earlier straight segment, which triggered
         * repeated FAST_RECOVERY_ROUTE calls and caused left/right oscillation.
         *
         * Measure against the complete cardinal route corridor instead. This
         * still detects a genuine lateral escape, but it is invariant to how far
         * ahead the next corner is.
         */
        double best = Double.POSITIVE_INFINITY;
        List<Cell> cells = route.cells();
        for (int i = 0; i < cells.size() - 1; i++) {
            Cell start = cells.get(i);
            Cell target = cells.get(i + 1);
            double startX = start.row() + 0.5;
            double startZ = start.column() + 0.5;
            double targetX = target.row() + 0.5;
            double targetZ = target.column() + 0.5;

            double segmentX = targetX - startX;
            double segmentZ = targetZ - startZ;
            double lengthSquared = segmentX * segmentX + segmentZ * segmentZ;
            if (lengthSquared <= 1.0E-9) continue;

            double playerX = state.player.x - startX;
            double playerZ = state.player.z - startZ;
            double projection = (playerX * segmentX + playerZ * segmentZ) / lengthSquared;
            projection = Math.max(0.0, Math.min(1.0, projection));

            double nearestX = startX + projection * segmentX;
            double nearestZ = startZ + projection * segmentZ;
            best = Math.min(best, Math.hypot(
                    state.player.x - nearestX,
                    state.player.z - nearestZ));
        }
        return best == Double.POSITIVE_INFINITY ? 0.0 : best;
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

            /*
             * Cell-only signatures were too coarse for live Monster Maze.
             * Monsters can move a substantial fraction of a block without
             * crossing a cell boundary, while their velocity changes the
             * source-faithful predicted contact. Quantise position to 0.5
             * blocks and velocity to 0.05 so tactical evaluation is refreshed
             * when the threat meaningfully changes, without forcing a full
             * simulation for every floating-point packet variation.
             */
            h = mix(h, monster.id);
            h = mix(h, quantise(monster.x, 0.5D));
            h = mix(h, quantise(monster.y, 0.5D));
            h = mix(h, quantise(monster.z, 0.5D));
            h = mix(h, quantise(monster.vx, 0.05D));
            h = mix(h, quantise(monster.vz, 0.05D));
            h = mix(h, monster.launched(state.tick) ? 1L : 0L);
            h = mix(h, monster.frozen(state.tick) ? 1L : 0L);
        }
        return h;
    }

    private static long quantise(double value, double quantum) {
        return Math.round(value / quantum);
    }

    private static long mix(long h, long value) {
        h ^= value;
        return h * 1099511628211L;
    }

    /**
     * Detect a source Monster Maze hit from the live observation stream.
     *
     * Normal monster contact deals exactly four damage. Using the health delta
     * as the primary live signal is more reliable than trying to catch the
     * single tick containing the velocity packet: an asynchronous sidecar may
     * legitimately finish a decision after that exact physics tick. The
     * recovery state therefore remains correct even when the first airborne
     * observation arrives a few ticks after the hit.
     */
    /*
     * Continuous cardinal movement belongs to StableLiveMovementController.
     * Tactical simulation may still request discrete source mechanics, but a
     * tactical yaw/forward command is not allowed to replace the motor's
     * corridor-safe steering every time a nearby mob changes position.
     */
    private static boolean isDiscreteTacticalAction(Action action, boolean allowJump) {
        return action.useAbility() || (allowJump && action.jump());
    }

    private boolean detectLiveMobHit(GameState state) {
        boolean hit = !Double.isNaN(previousHealth)
                && state.player.health < previousHealth - 0.5D;
        previousHealth = state.player.health;
        return hit;
    }

    /**
     * Airborne mob-hit recovery. The bump velocity is authoritative, but
     * Minecraft 1.8 still permits a small amount of air steering. Aim that
     * steering at a physical floor landing point, preferring the active pad
     * whenever its surface is plausibly reachable before the fall.
     */
    private Action airborneMobRecoveryAction(GameState state, Cell goal) {
        double[] target = findAirRecoveryTarget(state, goal);
        double dx = target[0] - state.player.x;
        double dz = target[1] - state.player.z;
        if (Math.hypot(dx, dz) < 0.20D) {
            lastDecisionDetail = "MOB_HIT_AIRBORNE_RECOVERY"
                    + " target=under-player"
                    + " vx=" + format(state.player.vx)
                    + " vz=" + format(state.player.vz);
            return new Action(1.0, 0.0, false, true, 0.0F, false);
        }

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        lastDecisionDetail = "MOB_HIT_AIRBORNE_RECOVERY"
                + " target=" + format(target[0]) + "," + format(target[1])
                + " yawError=" + format(yawError)
                + " yawDelta=" + format(yawDelta)
                + " vy=" + format(state.player.vy);
        return new Action(1.0, 0.0, false, true, yawDelta, false);
    }

    private double[] findAirRecoveryTarget(GameState state, Cell goal) {
        double vx = state.player.vx;
        double vz = state.player.vz;
        double predictedX = state.player.x;
        double predictedZ = state.player.z;
        double vy = state.player.vy;
        double predictedY = state.player.y;
        int landingTicks = 0;

        for (int i = 1; i <= 40; i++) {
            predictedX += vx;
            predictedY += vy;
            predictedZ += vz;
            vy = (vy - 0.08D) * 0.98D;
            if (predictedY <= 0.0D && vy <= 0.0D) {
                landingTicks = i;
                break;
            }
        }

        double padX = goal.row() + 0.5D;
        double padZ = goal.column() + 0.5D;
        double bestX = padX;
        double bestZ = padZ;
        double bestScore = Double.POSITIVE_INFINITY;

        if (landingTicks > 0) {
            int centreRow = (int) Math.floor(predictedX);
            int centreCol = (int) Math.floor(predictedZ);
            for (int row = Math.max(0, centreRow - 5); row <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreRow + 5); row++) {
                for (int col = Math.max(0, centreCol - 5); col <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreCol + 5); col++) {
                    if (!state.maze.isPhysicalFloor(row, col)) continue;
                    double x = row + 0.5D;
                    double z = col + 0.5D;
                    double landing = sq(x - predictedX) + sq(z - predictedZ);
                    double pad = sq(x - padX) + sq(z - padZ);
                    double score = landing + 0.10D * pad;
                    if (score < bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
        }

        return new double[]{bestX, bestZ};
    }

    private static double sq(double value) {
        return value * value;
    }

    private Action steerIntoMonster(GameState state, MonsterState monster) {
        double dx = monster.x - state.player.x;
        double dz = monster.z - state.player.z;
        if (Math.hypot(dx, dz) < 0.15D) return null;

        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        lastDecisionDetail = "MOB_INTENTIONAL_BUMP"
                + " monster=" + monster.id
                + " health=" + format(state.player.health)
                + " distance=" + format(Math.hypot(dx, dz))
                + " yawError=" + format(yawError);
        return new Action(1.0, 0.0, false, true, yawDelta, false);
    }

    /**
     * Detect the final approach to the active SafePad. This is deliberately
     * geometric rather than based on the raw maze cells because SafePad.build
     * replaces a 5x5 area, including cells that were air in the canonical maze.
     */
    /**
     * Resolve the player's logical route anchor from the same physical support
     * used by the movement model. Minecraft collision is AABB-based, so the
     * block containing floor(x,z) can be air while the player's 0.6-wide body
     * still overlaps a neighbouring solid cell.
     */
    private Cell resolveSupportedStartCell(GameState state) {
        int currentRow = (int) Math.floor(state.player.x);
        int currentColumn = (int) Math.floor(state.player.z);
        if (inBounds(currentRow, currentColumn)
                && state.maze.isPhysicalFloor(currentRow, currentColumn)) {
            return new Cell(currentRow, currentColumn);
        }

        final double halfWidth = 0.30D;
        int minRow = (int) Math.floor(state.player.x - halfWidth);
        int maxRow = (int) Math.floor(Math.nextDown(state.player.x + halfWidth));
        int minColumn = (int) Math.floor(state.player.z - halfWidth);
        int maxColumn = (int) Math.floor(Math.nextDown(state.player.z + halfWidth));

        Cell best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (!inBounds(row, column) || !state.maze.isPhysicalFloor(row, column)) continue;
                double dx = state.player.x - (row + 0.5D);
                double dz = state.player.z - (column + 0.5D);
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new Cell(row, column);
                }
            }
        }
        return best;
    }

    private Action maybeBeginPadEntryCommitment(GameState state, Cell goal, boolean allowJump) {
        if (route == null || route.size() < 2 || padEntryCommitment) return null;
        if (state.padReached || PadModel.isOn(state.player, goal.row() + 0.5,
                GameState.PAD_SURFACE_Y, goal.column() + 0.5)) return null;

        double outside = distanceOutsidePad(state, goal);
        if (outside > PAD_ENTRY_COMMIT_DISTANCE) return null;

        Cell current = resolveSupportedStartCell(state);
        if (current == null) return null;

        int[] direction = terminalRouteDirection();
        if (direction == null) return null;

        double centerDx = (goal.row() + 0.5) - state.player.x;
        double centerDz = (goal.column() + 0.5) - state.player.z;
        double toward = direction[0] * centerDx + direction[1] * centerDz;
        if (toward < 0.25) return null;

        padEntryCommitment = true;
        padEntryRow = goal.row();
        padEntryColumn = goal.column();
        padEntryDirRow = direction[0];
        padEntryDirColumn = direction[1];
        padEntryStartTick = state.tick;
        lastDecisionDetail = "PAD_ENTRY_COMMIT"
                + " pad=" + goal.row() + "," + goal.column()
                + " outside=" + format(outside)
                + " dir=" + direction[0] + "," + direction[1]
                + " allowJump=" + allowJump;
        return executePadEntryCommitment(state, goal, allowJump);
    }

    /** Execute a committed pad-edge crossing without allowing route churn. */
    private Action executePadEntryCommitment(GameState state, Cell goal, boolean allowJump) {
        if (!padEntryCommitment) return null;
        if (goal.row() != padEntryRow || goal.column() != padEntryColumn) {
            clearPadEntryCommitment();
            return null;
        }

        if (state.padReached || PadModel.isOn(state.player, goal.row() + 0.5,
                GameState.PAD_SURFACE_Y, goal.column() + 0.5)) {
            clearPadEntryCommitment();
            lastDecisionDetail = "PAD_ENTRY_LANDED";
            return Action.IDLE;
        }

        double outside = distanceOutsidePad(state, goal);
        long elapsed = state.tick - padEntryStartTick;
        if (outside > PAD_ENTRY_RELEASE_DISTANCE || elapsed > PAD_ENTRY_MAX_TICKS) {
            lastDecisionDetail = "PAD_ENTRY_ABORT"
                    + " outside=" + format(outside)
                    + " elapsed=" + elapsed;
            clearPadEntryCommitment();
            return null;
        }

        float desiredYaw = cardinalYaw(padEntryDirRow, padEntryDirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        if (Math.abs(yawError) > HEADING_TOLERANCE) {
            lastDecisionDetail = "PAD_ENTRY_ALIGN"
                    + " pad=" + goal.row() + "," + goal.column()
                    + " outside=" + format(outside)
                    + " yawError=" + format(yawError)
                    + " yawDelta=" + format(yawDelta);
            return new Action(0.0, 0.0, false, false, yawDelta, false);
        }

        // The source's non-Jumper "speeding" mechanic is jump-spam while
        // jump-locked; Jumpers use a real charged jump. The caller supplies
        // exactly that permission, so this commitment does not guess at kit
        // state or consume a charge outside the normal Action pipeline.
        boolean jump = allowJump && state.player.grounded;
        lastDecisionDetail = "PAD_ENTRY_CROSS"
                + " pad=" + goal.row() + "," + goal.column()
                + " outside=" + format(outside)
                + " dir=" + padEntryDirRow + "," + padEntryDirColumn
                + " jump=" + jump
                + " elapsed=" + elapsed;
        return new Action(1.0, 0.0, jump, true, yawDelta, false);
    }

    private int[] terminalRouteDirection() {
        if (route == null || route.size() < 2) return null;
        Cell a = route.cells().get(route.size() - 2);
        Cell b = route.cells().get(route.size() - 1);
        int dr = Integer.signum(b.row() - a.row());
        int dc = Integer.signum(b.column() - a.column());
        if (Math.abs(dr) + Math.abs(dc) != 1) return null;
        return new int[]{dr, dc};
    }

    private static double distanceOutsidePad(GameState state, Cell goal) {
        double centerX = goal.row() + 0.5;
        double centerZ = goal.column() + 0.5;
        double dx = Math.max(0.0, Math.abs(state.player.x - centerX) - 2.5);
        double dz = Math.max(0.0, Math.abs(state.player.z - centerZ) - 2.5);
        return Math.hypot(dx, dz);
    }

    private Action prepareOrStartGap(GameState state, int dirRow, int dirColumn, boolean allowJump) {
        if (gapExecutionActive && gapExecutionRouteIndex == waypointIndex - 1) {
            return executeCommittedGap(state, allowJump);
        }
        if (!state.player.grounded) return new Action(0.0, 0.0, false, false, 0.0F, false);
        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        if (Math.abs(yawError) > GAP_HEADING_TOLERANCE) {
            float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            lastDecisionDetail = "GAP_ALIGN edge=" + gapEdgeText() + " yawError=" + format(yawError);
            return new Action(0.0, 0.0, false, false, yawDelta, false);
        }
        double rad = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(rad), forwardZ = Math.cos(rad);
        double lateralVelocity = Math.abs(state.player.vx * forwardZ - state.player.vz * forwardX);
        if (lateralVelocity > GAP_LATERAL_SPEED_LIMIT) {
            lastDecisionDetail = "GAP_BRAKE edge=" + gapEdgeText() + " lateralSpeed=" + format(lateralVelocity);
            return new Action(0.0, 0.0, false, false, 0.0F, false);
        }
        int gapIndex = waypointIndex - 1;
        double progress = currentGapProgress(state, gapIndex);
        double distanceToTakeoff = 0.50D - progress;
        if (progress >= 0.15D && progress <= 1.65D) {
            gapExecutionActive = true;
            // Commit early enough that a single-tick physics/replan boundary
            // cannot make us miss the jump input at the block edge.
            gapTakeoffStarted = progress >= 0.35D;
            gapExecutionRouteIndex = waypointIndex - 1;
            gapLandingConfirmTicks = 0;
            return executeCommittedGap(state, allowJump);
        }
        if (distanceToTakeoff > GAP_JUMP_TRIGGER_DISTANCE) {
            lastDecisionDetail = "GAP_APPROACH edge=" + gapEdgeText() + " progress=" + format(progress);
            return new Action(1.0, 0.0, false, true, 0.0F, false);
        }
        if (distanceToTakeoff < -GAP_JUMP_LATE_TOLERANCE) {
            lastDecisionDetail = "GAP_MISSED edge=" + gapEdgeText() + " progress=" + format(progress);
            return null;
        }
        gapExecutionActive = true;
        gapTakeoffStarted = false;
        gapExecutionRouteIndex = waypointIndex - 1;
        gapLandingConfirmTicks = 0;
        return executeCommittedGap(state, allowJump);
    }

    private Action executeCommittedGap(GameState state, boolean allowJump) {
        if (!gapExecutionActive || gapExecutionRouteIndex != waypointIndex - 1 || waypointIndex >= route.size()) {
            clearGapCommitment();
            return null;
        }
        int fromRow = route.cells().get(gapExecutionRouteIndex).row();
        int fromColumn = route.cells().get(gapExecutionRouteIndex).column();
        int toRow = route.cells().get(waypointIndex).row();
        int toColumn = route.cells().get(waypointIndex).column();
        if (!isGapEdge(state, fromRow, fromColumn, toRow, toColumn)) {
            clearGapCommitment();
            return null;
        }
        double progress = currentGapProgress(state, gapExecutionRouteIndex);
        if (!gapTakeoffStarted && progress >= 0.35D) {
            gapTakeoffStarted = true;
            lastDecisionDetail = "GAP_TAKEOFF edge=" + gapEdgeText() + " progress=" + format(progress);
        }
        if (gapTakeoffStarted && state.player.grounded && progress > 0.90D
                && playerOverlapsCell(state, toRow, toColumn, 0.05D)) {
            gapLandingConfirmTicks++;
            if (gapLandingConfirmTicks >= GAP_LANDING_CONFIRM_TICKS) {
                int completedIndex = waypointIndex;
                clearGapCommitment();
                waypointIndex = nextTurnWaypoint(route, completedIndex);
                anchoredSegmentIndex = -1;
                lastDecisionDetail = "GAP_LANDING_CONFIRMED edge=" + fromRow + "," + fromColumn + "->"
                        + toRow + "," + toColumn + " progress=" + format(progress);
                return new Action(1.0, 0.0, allowJump, true, 0.0F, false);
            }
        } else {
            gapLandingConfirmTicks = 0;
        }
        if (progress > 1.65D || state.player.y < GameState.PATH_Y - 3.0D) {
            lastDecisionDetail = "GAP_LANDING_FAILED edge=" + gapEdgeText() + " progress=" + format(progress);
            clearGapCommitment();
            return null;
        }
        /*
         * The critical edge tick is the last grounded tick on the source
         * block. Do not make the jump input depend on a narrow exact progress
         * threshold or on whether the previous observation happened to mark
         * takeoff as started. Once committed, keep jump held/pulsed whenever
         * grounded until the landing is confirmed. This removes the observed
         * "ran off the end without pressing space" failure caused by a one-tick
         * observation boundary.
         *
         * allowJump means a charged/real jump is available. Non-Jumper
         * speeding still benefits from the jump input, so the motor input is
         * intentionally requested for the committed gap regardless of that
         * permission; the server-side jump lock suppresses the actual jump.
         */
        boolean jumpInput = state.player.grounded;
        lastDecisionDetail = "GAP_EXECUTE edge=" + gapEdgeText() + " progress=" + format(progress)
                + " takeoff=" + gapTakeoffStarted + " jumpInput=" + jumpInput
                + " allowJump=" + allowJump;
        return new Action(1.0, 0.0, jumpInput, true, 0.0F, false);
    }

    private boolean isGapEdge(GameState state, int fromRow, int fromColumn, int toRow, int toColumn) {
        int dr = toRow - fromRow, dc = toColumn - fromColumn;
        if (!((Math.abs(dr) == 2 && dc == 0) || (Math.abs(dc) == 2 && dr == 0))) return false;
        int middleRow = fromRow + Integer.signum(dr);
        int middleColumn = fromColumn + Integer.signum(dc);
        return state.maze.isPhysicalFloor(fromRow, fromColumn)
                && !state.maze.isPhysicalFloor(middleRow, middleColumn)
                && state.maze.isPhysicalFloor(toRow, toColumn);
    }

    private double currentGapProgress(GameState state, int gapIndex) {
        int fromRow = route.cells().get(gapIndex).row();
        int fromColumn = route.cells().get(gapIndex).column();
        int toRow = route.cells().get(waypointIndex).row();
        int toColumn = route.cells().get(waypointIndex).column();
        double fromX = fromRow + 0.5, fromZ = fromColumn + 0.5;
        double edgeX = toRow - fromRow, edgeZ = toColumn - fromColumn;
        double length = Math.hypot(edgeX, edgeZ);
        edgeX /= length; edgeZ /= length;
        return (state.player.x - fromX) * edgeX + (state.player.z - fromZ) * edgeZ;
    }

    private boolean playerOverlapsCell(GameState state, int row, int column, double tolerance) {
        return state.player.x > row + tolerance && state.player.x < row + 1.0 - tolerance
                && state.player.z > column + tolerance && state.player.z < column + 1.0 - tolerance;
    }

    private String gapEdgeText() {
        if (route == null || gapExecutionRouteIndex < 0 || waypointIndex >= route.size()) return "unknown";
        Cell from = route.cells().get(gapExecutionRouteIndex);
        Cell to = route.cells().get(waypointIndex);
        return from.row() + "," + from.column() + "->" + to.row() + "," + to.column();
    }

    private void clearGapCommitment() {
        gapExecutionActive = false;
        gapTakeoffStarted = false;
        gapExecutionRouteIndex = -1;
        gapLandingConfirmTicks = 0;
    }

    private void clearPadEntryCommitment() {
        padEntryCommitment = false;
        padEntryRow = -1;
        padEntryColumn = -1;
        padEntryDirRow = 0;
        padEntryDirColumn = 0;
        padEntryStartTick = Long.MIN_VALUE;
    }

    private void clearRoute() {
        route = null;
        waypointIndex = 0;
        anchoredSegmentIndex = -1;
    }
}