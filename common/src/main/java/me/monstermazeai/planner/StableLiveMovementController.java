package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.monster.MobInteractionDecision;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;
import me.monstermazeai.physics.LegacyMovementModel;

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
    private final AiProfile profile;

    public StableLiveMovementController() {
        this(AiProfile.BASELINE);
    }

    public StableLiveMovementController(AiProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
    }

    public AiProfile profile() {
        return profile;
    }
    private static final double WAYPOINT_ARRIVAL = 0.18;
    private static final double WAYPOINT_BRAKE = 0.70;
    private static final double ROUTE_DEVIATION = 0.55;
    private static final double PHYSICS_SLIPPERINESS = 0.6D;
    private static final double PHYSICS_GROUND_FRICTION = 0.91D;
    private static final double PHYSICS_WALK_SPEED = 0.10D;
    private static final double PHYSICS_SPRINT_MULTIPLIER = 1.30D;
    private static final double PHYSICS_GROUND_FACTOR = 0.16277136D;
    /** One-tick safety horizon matches the live observe -> decide -> move cadence. */
    private static final int MIN_SUPPORT_LOOKAHEAD_TICKS = 1;
    private static final int MAX_SUPPORT_LOOKAHEAD_TICKS = 3;
    /**
     * Every fresh observation is eligible for route replanning. Computational
     * optimisation belongs inside the planner, never in an artificial cadence
     * that discards newer world state.
     */

    /** Monster Maze accepts up to 30 degrees of 1.8 camera/yaw change per tick. */
    private static final float MAX_TURN_PER_TICK = 30.0F;
    /** Same legacy player movement constants used by the physics predictor. */
    private static final double SLIPPERINESS = 0.6D;
    private static final double GROUND_FRICTION = 0.91D;
    private static final float WALK_SPEED = 0.1F;
    private static final float SPRINT_MULTIPLIER = 1.3F;
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
    private final LegacyMovementModel movementProjection = new LegacyMovementModel();
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
    /** Monotonic generation invalidates background routes when newer world state requests planning. */
    private long strategicPlanGeneration;

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
    /** Keep a non-Jumper grounded long enough to enter a source gap cleanly. */
    private static final double GAP_PRE_JUMP_RESERVE_DISTANCE = 1.80D;
    private long lastSpeedJumpInputTick = Long.MIN_VALUE;
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
    /** Require the player to be reasonably inside the pad's lateral lane before committing a directional crossing. */
    private static final double PAD_ENTRY_MAX_LATERAL_OFFSET = 1.15D;
    private static final long PAD_ENTRY_MAX_TICKS = 18L;
    private static final double GAP_JUMP_TRIGGER_DISTANCE = 0.10D;
    /** Press jump only once the player is at the actual source-block edge. */
    /** Strong players begin the jump before the source block centre so the vanilla arc lands inside the destination. */
    private static final double GAP_JUMP_PROGRESS = -0.80D;
    /** Begin the committed gap window slightly before the actual jump impulse. */
    private static final double GAP_COMMIT_PROGRESS = -1.80D;
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
        MonsterState intentionalBump = MobInteractionDecision.chooseIntentionalBump(
                state, profile.tendencies.positiveMobKnockback);
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
            Action recovery = unsupportedEdgeRecoveryAction(state, goal, allowJump);
            lastDecisionDetail = "NO_PHYSICAL_SUPPORT player=" + format(state.player.x) + "," + format(state.player.z)
                    + " y=" + format(state.player.y) + " goal=" + goal.row() + "," + goal.column()
                    + " recovery=" + recovery;
            return recovery;
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
            /*
             * Validate against the complete route corridor, not the segment
             * belonging to the next turn waypoint. waypointIndex is intentionally
             * several cells ahead on long straight runs, so checking that future
             * segment made an otherwise-valid player look off-route and caused
             * repeated FAST_RECOVERY_ROUTE resets.
             */
            Cell supportedCell = new Cell(startRow, startColumn);
            boolean supportedCellOnRoute = route.cells().contains(supportedCell);
            /*
             * Continuous Minecraft momentum can move the player's centre well
             * away from the exact route centreline while the AABB still overlaps
             * the intended route cell. Treat the supported logical cell as the
             * primary topology test; the physical lane/edge guards below remain
             * responsible for pulling the player back inside the corridor.
             *
             * Re-running a whole route from a 0.55-block global corridor miss was
             * a major source of FAST_RECOVERY_ROUTE churn in the matrix. It also
             * discarded useful momentum at the exact moments a high-skill player
             * should be carrying speed through a segment.
             */
            boolean currentSegmentMiss = !gapExecutionActive
                    && currentSegmentDeviationFromSupportedCell(state, route, supportedCell) > 1.10D;
            boolean routeInvalid = !gapExecutionActive
                    && (!supportedCellOnRoute
                        || distanceFromRouteCorridor(state, route, waypointIndex) > 1.10D
                        || currentSegmentMiss);

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
         * The route has now been selected against the freshest available
         * topology/threat snapshot. Ordinary monster avoidance is therefore a
         * motor refinement of that route, not a competing route planner.
         */
        Action mobAvoidance = avoidIncomingMonster(state, allowJump);
        if (mobAvoidance != null) return mobAvoidance;

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
                && (distanceToWaypoint(state, waypointIndex) <= WAYPOINT_ARRIVAL
                || hasPassedWaypointAlongSegment(state, waypointIndex))) {
            int previousWaypoint = waypointIndex;
            waypointIndex = nextTurnWaypoint(route, waypointIndex);
            if (waypointIndex != previousWaypoint) {
                anchoredSegmentIndex = -1;
            }
        }

        /*
         * A background/recovery route can be installed while the player is still
         * physically on an earlier cell of that same route. The waypoint index is
         * intentionally a turn/goal index, so distance-to-waypoint alone cannot
         * detect this case: the player may be two or three cells before the next
         * corner while still being perfectly on the route corridor. Rebase the
         * motor to the next turn from the actually supported route cell before
         * computing the lane anchor. This prevents a future perpendicular segment
         * from being mistaken for a lateral lane error and avoids the resulting
         * EDGE_GUARD zero-input loop.
         */
        int supportedRouteIndex = route.cells().indexOf(supportedCell);
        if (supportedRouteIndex >= 0) {
            int rebasedWaypoint = nextTurnWaypoint(route, supportedRouteIndex);
            if (rebasedWaypoint != waypointIndex) {
                waypointIndex = rebasedWaypoint;
                anchoredSegmentIndex = -1;
                lastDecisionDetail += " WAYPOINT_REBASE"
                        + " supportIndex=" + supportedRouteIndex
                        + " waypoint=" + waypointIndex;
            }
        }

        if (waypointIndex >= route.size()) {
            lastDecisionDetail = "REACHED routeSize=" + route.size();
            return Action.IDLE;
        }

        Action padEntry = maybeBeginPadEntryCommitment(state, goal, allowJump);
        if (padEntry != null) return padEntry;

        /*
         * Gap crossings are a motor-level source interaction. They must own the
         * command before tactical replanning can run, otherwise a local mob
         * branch can replace the edge-timed speeding/jump input for one tick.
         */
        if (waypointIndex > 0 && waypointIndex < route.size()) {
            Cell gapFrom = route.cells().get(waypointIndex - 1);
            Cell gapTo = route.cells().get(waypointIndex);
            if (isGapEdge(state, gapFrom.row(), gapFrom.column(), gapTo.row(), gapTo.column())) {
                Action gapAction = prepareOrStartGap(state,
                        Integer.signum(gapTo.row() - gapFrom.row()),
                        Integer.signum(gapTo.column() - gapFrom.column()),
                        allowJump);
                if (gapAction != null) return gapAction;
            }
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
             * Correct toward the actual anchored lane in world space, then
             * convert that correction into the player's current W/A/D frame.
             * The previous sign-only strafe could point away from the lane once
             * yaw had drifted, producing a repeatable edge-guard -> zero-input
             * deadlock even though the player still had supported floor nearby.
             */
            action = laneCorrectionAction(state, dirRow, dirColumn, 0.90D);
            if (action == null) {
                action = Action.IDLE;
            }
            lastDecisionDetail += " LANE_RECOVERY_WORLD crossTrack=" + format(crossTrack)
                    + " output=f=" + format(action.forward())
                    + ",s=" + format(action.strafe());
        } else if (Math.abs(crossTrack) > 0.18) {
            /*
             * Medium cross-track error is still a correction state, not a reason
             * to wait for momentum to decay. Keep a bounded lateral input while
             * the camera converges; the projected-support guard remains the final
             * authority near an actual edge.
             */
            action = laneCorrectionAction(state, dirRow, dirColumn, 0.60D);
            if (action == null) {
                action = Action.IDLE;
            }
            lastDecisionDetail += " LANE_FINE crossTrack=" + format(crossTrack)
                    + " output=f=" + format(action.forward())
                    + ",s=" + format(action.strafe());
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
            double turnGain = turnResponseGain();
            float turn = clamp((float) (yawError * turnGain),
                    -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            if (Math.abs(yawError) > HEADING_TOLERANCE && Math.abs(turn) < 1.0F) turn = yawError > 0 ? 1.0F : -1.0F;
            if (Math.abs(yawError) <= MAX_DRIVE_STEER_ERROR) {
                boolean brake = distance < waypointBrakeDistance()
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
                boolean sprint = forward > 0.05;
                // Non-Jumpers use the source Jump -10 + sprint-jump interaction
                // as their normal speed mechanic. Jumper vertical jumps remain
                // reserved for explicit terrain decisions.
                boolean jump = shouldSpeedJump(state, allowJump);
                action = new Action(forward, 0.0, jump, sprint, turn, false);
                lastDecisionDetail += " STEER_DRIVE";
            } else if (distance <= 4.50 && Math.abs(yawError) < 135.0F) {
                /*
                 * Near a cardinal corner, keep a bounded W+A/D vector active while
                 * the camera turns. The vector is derived from the actual
                 * heading error, so it rotates smoothly toward the next
                 * cardinal segment instead of waiting in place or strafing blindly.
                 */
                double errorRad = Math.toRadians(yawError);
                double forward = Math.cos(errorRad) * 0.65D;
                double strafe = -Math.sin(errorRad) * 0.65D;
                boolean jump = shouldSpeedJump(state, allowJump);
                boolean sprint = forward > 0.05;
                action = new Action(forward, strafe, jump, sprint, turn, false);
                lastDecisionDetail += " CORNER_VECTOR";
            } else {
                /*
                 * A large heading error far from the next corner is not itself
                 * a reason to stop. Human traces keep forward input active while
                 * re-acquiring the target bearing. The near-corner branch above
                 * remains the topology-safe 90-degree turn treatment.
                 *
                 * Only accept the far-turn drive if the same physical-support
                 * predictor confirms the current route corridor remains supported;
                 * this keeps the shared policy source-valid in every mode.
                 */
                float yawCommand = turn;
                if (distance > 4.50D) {
                    double forward = 0.35D;
                    boolean jump = shouldSpeedJump(state, allowJump);
                    Action farTurn = new Action(
                            forward,
                            0.0,
                            jump,
                            false,
                            yawCommand,
                            false);
                    action = hasPredictedPhysicalSupport(
                            state, farTurn, supportLookaheadTicks(state))
                            ? farTurn
                            : new Action(0.0, 0.0, false, false, yawCommand, false);
                    lastDecisionDetail += action == farTurn
                            ? " FAR_TURN_DRIVE"
                            : " FAR_TURN_SUPPORT_HOLD";
                } else {
                    /*
                     * When the desired heading is more than 135 degrees away,
                     * forward input points substantially away from the route.
                     * A short reverse-drive turn is often the source-equivalent
                     * way to keep translating toward the corridor while the
                     * camera catches up. This removes the artificial stop-turn-go
                     * burst without permitting a diagonal cut across the maze.
                     */
                    Action reverseTurn = new Action(
                            -0.35, 0.0, false, false,
                            yawCommand,
                            false);
                    if (hasPredictedPhysicalSupport(
                            state, reverseTurn, supportLookaheadTicks(state))) {
                        action = reverseTurn;
                        lastDecisionDetail += " REVERSE_TURN_DRIVE";
                    } else {
                        action = new Action(
                                0.0, 0.0, false, false,
                                yawCommand,
                                false);
                        lastDecisionDetail += " TURN_IN_PLACE";
                    }
                }
            }
        } else {
            boolean brake = distance < waypointBrakeDistance()
                    && closingSpeed(state, dx, dz) > 0.04;
            double forward = brake ? 0.0 : 1.0;
            boolean jump = shouldSpeedJump(state, allowJump);
            action = new Action(forward, 0.0, jump, forward > 0.0, 0.0F, false);
        }

        if (!gapExecutionActive
                && (Math.abs(crossTrack) > 0.20D
                || (speed > 0.04D
                && !hasPredictedPhysicalSupport(state, action, supportLookaheadTicks(state))))) {
            Action guarded = guardProjectedSupport(state, action, dirRow, dirColumn);
            if (guarded != action) {
                lastDecisionDetail += " EDGE_GUARD"
                        + " raw=f=" + format(action.forward())
                        + ",s=" + format(action.strafe())
                        + " guarded=f=" + format(guarded.forward())
                        + ",s=" + format(guarded.strafe());
                action = guarded;
            }
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
        strategicPlanGeneration++;
        lastSpeedJumpInputTick = Long.MIN_VALUE;
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
        long generation = ++strategicPlanGeneration;
        if (pendingRoutePlan != null && !pendingRoutePlan.isDone()) {
            // Do not apply the in-flight plan after a newer observation requested
            // replanning. The next live tick will submit a fresh snapshot when
            // the current worker becomes available.
            fullRouteEvaluationPending = true;
            return;
        }

        GameState snapshot = liveState.copyForSimulation();
        long requestedTick = liveState.tick;
        long topology = snapshot.maze.dynamicSignature();
        pendingRoutePlan = routePlanningExecutor.submit(() -> {
            try {
                PlayerRoute planned = regionRadius > 0
                        ? backgroundRoutePlanner.routeToRegion(snapshot, start, goal, regionRadius)
                        : backgroundRoutePlanner.route(snapshot, start, goal);
                if (planned == null || planned.cells().isEmpty()) {
                    throw new IllegalStateException("Strategic planner returned no route");
                }
                completedRoutePlan = new PlannedRoute(
                        planned, start.row(), start.column(), goal.row(), goal.column(), regionRadius,
                        requestedTick, topology, threatSignature(snapshot), generation);
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
        if (planned.generation != strategicPlanGeneration) {
            // A newer world observation has already requested a replacement.
            // Never let an older background snapshot steer the live motor.
            fullRouteEvaluationPending = true;
            return;
        }
        /*
         * Monster positions are intentionally dynamic. Requiring the exact
         * quantised threat signature from the planning snapshot made otherwise
         * useful routes expire before they could be applied, especially in dense
         * encounters where velocity changes every few ticks. The route's physical
         * topology, objective, and current first heading remain authoritative;
         * live tactical control handles whatever the monsters are doing now.
         */
        if (planned.route == null
                || planned.route.cells().isEmpty()
                || planned.startRow != startRow
                || planned.startColumn != startColumn
                || planned.goalRow != goal.row()
                || planned.goalColumn != goal.column()
                || planned.regionRadius != regionRadius
                || state.maze.dynamicSignature() != planned.topologySignature
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
                state, planned.route, startRow, startColumn)
                && !currentRouteThreatenedByMonster(state)) {
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
        final long generation;

        PlannedRoute(PlayerRoute route, int startRow, int startColumn,
                     int goalRow, int goalColumn, int regionRadius,
                     long requestedTick, long topologySignature, long threatSignature,
                     long generation) {
            this.route = route;
            this.startRow = startRow;
            this.startColumn = startColumn;
            this.goalRow = goalRow;
            this.goalColumn = goalColumn;
            this.regionRadius = regionRadius;
            this.requestedTick = requestedTick;
            this.topologySignature = topologySignature;
            this.threatSignature = threatSignature;
            this.generation = generation;
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

    private boolean shouldSpeedJump(GameState state, boolean allowJump) {
        if (!allowJump || state.kit == me.monstermazeai.kit.Kit.JUMPER || !state.player.grounded) {
            return false;
        }
        // The repeated Jump -10 + sprint-jump acceleration is a Speed-mode
        // mechanic. Modern non-Jumpers must stay on ordinary source movement.

        long cadenceTicks = profile.attributes.nonJumperJumpCadenceTicks();
        if (lastSpeedJumpInputTick != Long.MIN_VALUE
                && state.tick - lastSpeedJumpInputTick < cadenceTicks) {
            return false;
        }
        lastSpeedJumpInputTick = state.tick;
        return true;
    }

    private boolean isApproachingGap(GameState state) {
        if (route == null || route.size() < 2) return false;

        int start = Math.max(0, waypointIndex - 2);
        int end = Math.min(route.size() - 2, waypointIndex + 2);
        for (int i = start; i <= end; i++) {
            Cell from = route.cells().get(i);
            Cell to = route.cells().get(i + 1);
            if (!isGapEdge(state, from.row(), from.column(), to.row(), to.column())) continue;

            int dirRow = Integer.signum(to.row() - from.row());
            int dirColumn = Integer.signum(to.column() - from.column());
            double sourceCenterX = from.row() + 0.5D;
            double sourceCenterZ = from.column() + 0.5D;
            double edgeProgress;
            if (dirRow != 0) {
                edgeProgress = (state.player.x - sourceCenterX) * dirRow;
            } else {
                edgeProgress = (state.player.z - sourceCenterZ) * dirColumn;
            }

            // Only reserve the pre-gap window, not arbitrary nearby crossing
            // geometry elsewhere in the local route.
            double lateral = crossTrackError(
                    state.player.x, state.player.z,
                    sourceCenterX, sourceCenterZ,
                    dirRow, dirColumn);
            if (Math.abs(lateral) > 0.75D) continue;

            double distanceToTakeoff = GAP_JUMP_PROGRESS - edgeProgress;
            if (distanceToTakeoff >= -0.10D
                    && distanceToTakeoff <= GAP_PRE_JUMP_RESERVE_DISTANCE) {
                return true;
            }
        }
        return false;
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

    /**
     * Treat a waypoint as completed when the player has physically crossed the
     * segment endpoint. This is the closed-loop equivalent of PlayerRoute's
     * forward-waypoint handling: residual vanilla momentum can carry the player
     * past a corner before the next observation, and turning back toward the
     * old point is precisely the behaviour that caused the observed edge fall.
     *
     * Gap edges are excluded because their endpoint is not a normal standing
     * waypoint until the committed jump has confirmed the landing.
     */
    private boolean hasPassedWaypointAlongSegment(GameState state, int index) {
        if (route == null || index <= 0 || index >= route.size()) return false;

        int fromRow = route.cells().get(index - 1).row();
        int fromColumn = route.cells().get(index - 1).column();
        int toRow = route.cells().get(index).row();
        int toColumn = route.cells().get(index).column();

        if (isGapEdge(state, fromRow, fromColumn, toRow, toColumn)) return false;

        int dirRow = Integer.signum(toRow - fromRow);
        int dirColumn = Integer.signum(toColumn - fromColumn);
        if (Math.abs(dirRow) + Math.abs(dirColumn) != 1) return false;

        double progress;
        if (dirRow != 0) {
            progress = (state.player.x - (fromRow + 0.5D)) * dirRow;
        } else {
            progress = (state.player.z - (fromColumn + 0.5D)) * dirColumn;
        }

        double endpointProgress = Math.abs(toRow - fromRow) + Math.abs(toColumn - fromColumn);
        if (progress <= endpointProgress + 0.08D) return false;

        // Only advance when the player remains reasonably close to the route
        // corridor; a monster knockback far away must trigger a new route instead
        // of being mistaken for successful corner traversal.
        double lateral = crossTrackError(
                state.player.x, state.player.z,
                toRow + 0.5D, toColumn + 0.5D,
                dirRow, dirColumn);
        return Math.abs(lateral) <= 0.65D;
    }

    /**
     * Distance from the active route segment, unlike the global corridor metric.
     * A player can land near a later parallel segment after an edge recovery;
     * treating that unrelated segment as valid hides the fact that the current
     * waypoint is more than a block away and leaves the motor following stale
     * geometry.
     */
    private double currentSegmentDeviationFromSupportedCell(GameState state,
                                                        PlayerRoute route,
                                                        Cell supportedCell) {
        if (route == null || supportedCell == null) return 0.0D;
        int supportedIndex = route.cells().indexOf(supportedCell);
        if (supportedIndex < 0 || supportedIndex >= route.size() - 1) return 0.0D;

        int targetIndex = nextTurnWaypoint(route, supportedIndex);
        if (targetIndex <= supportedIndex || targetIndex >= route.size()) return 0.0D;
        return currentSegmentDeviation(state, route, targetIndex);
    }

    private double currentSegmentDeviation(GameState state, PlayerRoute route, int targetIndex) {
        if (route == null || route.size() < 2
                || targetIndex <= 0 || targetIndex >= route.size()) return 0.0D;

        List<Cell> cells = route.cells();
        Cell from = cells.get(targetIndex - 1);
        Cell to = cells.get(targetIndex);
        int rowDirection = Integer.signum(to.row() - from.row());
        int columnDirection = Integer.signum(to.column() - from.column());
        int segmentLength = Math.abs(to.row() - from.row())
                + Math.abs(to.column() - from.column());
        if (Math.abs(rowDirection) + Math.abs(columnDirection) != 1) {
            return Double.POSITIVE_INFINITY;
        }

        /*
         * waypointIndex is the endpoint of the current straight run, not the
         * single cell immediately before it. Measure against every one-block
         * edge in that run so a player can legitimately be several blocks away
         * from the corner without triggering FAST_RECOVERY_ROUTE.
         *
         * Gap edges have length two, so the length check also keeps the current
         * straight-run calculation from crossing a source-faithful gap boundary.
         */
        int runStart = targetIndex - 1;
        while (runStart > 0) {
            Cell previous = cells.get(runStart - 1);
            Cell current = cells.get(runStart);
            int previousRowDirection = Integer.signum(current.row() - previous.row());
            int previousColumnDirection = Integer.signum(current.column() - previous.column());
            int previousLength = Math.abs(current.row() - previous.row())
                    + Math.abs(current.column() - previous.column());
            if (previousRowDirection != rowDirection
                    || previousColumnDirection != columnDirection
                    || previousLength != segmentLength) {
                break;
            }
            runStart--;
        }

        double best = Double.POSITIVE_INFINITY;
        for (int i = runStart; i < targetIndex; i++) {
            Cell a = cells.get(i);
            Cell b = cells.get(i + 1);
            double ax = a.row() + 0.5D;
            double az = a.column() + 0.5D;
            double bx = b.row() + 0.5D;
            double bz = b.column() + 0.5D;
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSquared = dx * dx + dz * dz;
            if (lengthSquared <= 1.0E-9D) continue;

            double px = state.player.x - ax;
            double pz = state.player.z - az;
            double projection = (px * dx + pz * dz) / lengthSquared;
            projection = Math.max(0.0D, Math.min(1.0D, projection));
            double nearestX = ax + projection * dx;
            double nearestZ = az + projection * dz;
            best = Math.min(best, Math.hypot(
                    state.player.x - nearestX,
                    state.player.z - nearestZ));
        }
        return best == Double.POSITIVE_INFINITY ? 0.0D : best;
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
        boolean relevant = false;

        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed) continue;
            if (monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double distance = Math.sqrt(
                    sq(monster.x - state.player.x)
                    + sq(monster.y - state.player.y)
                    + sq(monster.z - state.player.z));
            double speed = Math.hypot(monster.vx, monster.vz);

            if (distance <= me.monstermazeai.monster.MonsterRelevance.INTERACTION_RADIUS) {
                relevant = true;
                h = mix(h, monster.id);
                h = mix(h, quantise(monster.x, 0.5D));
                h = mix(h, quantise(monster.y, 0.5D));
                h = mix(h, quantise(monster.z, 0.5D));
                h = mix(h, quantise(monster.vx, 0.05D));
                h = mix(h, quantise(monster.vz, 0.05D));
            } else if (distance <= 60.0D && speed >= 0.08D) {
                /*
                 * Strategic-only remote signature. The coarse buckets avoid
                 * re-planning for every fractional packet while still waking
                 * the background route planner as a moving mob approaches.
                 */
                relevant = true;
                h = mix(h, monster.id);
                h = mix(h, quantise(monster.x, 1.0D));
                h = mix(h, quantise(monster.z, 1.0D));
                h = mix(h, quantise(monster.vx, 0.10D));
                h = mix(h, quantise(monster.vz, 0.10D));
            }
        }

        return relevant ? h : 0L;
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
    /**
     * Predict three repeated motor ticks using the same 1.8 ground acceleration
     * and friction constants as LegacyMovementModel. This is deliberately only
     * an input guard: it never changes the simulator's collision/physics result.
     */
    private Action guardProjectedSupport(GameState state, Action action,
                                         int dirRow, int dirColumn) {
        if (state.maze == null || !state.player.grounded) return action;
        if (hasPredictedPhysicalSupport(state, action, supportLookaheadTicks(state))) return action;

        double lateralVelocity = routeLateralVelocity(state, dirRow, dirColumn);
        double counter = lateralVelocity > 0.0 ? -1.0 : lateralVelocity < 0.0 ? 1.0 : 0.0;

        double supportScale = 0.20D + (0.50D * profile.attributes.handling);
        Action[] alternatives = {
                    new Action(action.forward() * supportScale,
                            action.strafe() * supportScale,
                            action.jump(),
                            action.sprint(),
                            action.yawDelta(),
                            false),
                    new Action(0.0, 0.0, false, false, action.yawDelta(), false),
                    new Action(-0.8, 0.0, false, false, action.yawDelta(), false),
                    new Action(0.0, counter, false, false, action.yawDelta(), false),
                    new Action(0.0, -counter, false, false, action.yawDelta(), false)
                };

        Action best = null;
        double bestProgress = Double.NEGATIVE_INFINITY;
        for (Action candidate : alternatives) {
            if (!hasPredictedPhysicalSupport(state, candidate, supportLookaheadTicks(state))) continue;
            double progress = projectedRouteProgress(state, candidate, dirRow, dirColumn);
            if (progress > bestProgress) {
                bestProgress = progress;
                best = candidate;
            }
        }
        if (best != null) return best;

        float desired = cardinalYaw(dirRow, dirColumn);
        float correction = clamp(normalise(desired - state.player.yaw),
                -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        return new Action(0.0, 0.0, false, false, correction, false);
    }

    private double turnResponseGain() {
        return 0.25D + profile.attributes.agility * 0.50D;
    }

    private int supportLookaheadTicks(GameState state) {
        /*
         * Normal driving is re-evaluated every live observation, so a two-tick
         * support projection is enough to protect the next physical step without
         * repeatedly braking for a hypothetical future edge state. Committed gap
         * execution keeps the full three-tick horizon because that action is a
         * discrete source-specific takeoff/landing sequence.
         */
        return gapExecutionActive
                ? MAX_SUPPORT_LOOKAHEAD_TICKS
                : Math.min(2, MAX_SUPPORT_LOOKAHEAD_TICKS);
    }

    private boolean hasPredictedPhysicalSupport(GameState state, Action action, int ticks) {
        if (state.maze == null) return true;

        var projected = state.player.copy();
        int jumpAmplifier = state.kit == me.monstermazeai.kit.Kit.JUMPER
                && state.ability.charges > 0 ? 0 : -10;
        int steps = Math.max(1, ticks);

        for (int i = 0; i < steps; i++) {
            movementProjection.tick(projected, action, state.maze, jumpAmplifier);
            if (!projected.grounded && projected.y > 0.02D) {
                // A real jump may legitimately leave the floor between ticks.
                continue;
            }
            if (!hasPhysicalFloorFootprint(state.maze, projected.x, projected.z)) return false;
        }
        return true;
    }


    private double projectedRouteProgress(GameState state, Action action,
                                          int dirRow, int dirColumn) {
        double yaw = Math.toRadians(state.player.yaw + action.yawDelta());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double strafeX = Math.cos(yaw);
        double strafeZ = Math.sin(yaw);
        double movementX = forwardX * action.forward() + strafeX * action.strafe();
        double movementZ = forwardZ * action.forward() + strafeZ * action.strafe();
        double length = Math.hypot(movementX, movementZ);
        if (length < 1.0E-9) return 0.0;
        double routeX = dirRow;
        double routeZ = dirColumn;
        return (movementX / length) * routeX + (movementZ / length) * routeZ;
    }

    private double routeLateralVelocity(GameState state, int dirRow, int dirColumn) {
        if (dirRow == 0) return state.player.vx;
        return state.player.vz;
    }

    private static boolean hasPhysicalFloorFootprint(me.monstermazeai.maze.MazeModel maze,
                                                       double x, double z) {
        final double halfWidth = 0.30D;
        int minRow = (int) Math.floor(x - halfWidth);
        int maxRow = (int) Math.floor(Math.nextDown(x + halfWidth));
        int minColumn = (int) Math.floor(z - halfWidth);
        int maxColumn = (int) Math.floor(Math.nextDown(z + halfWidth));
        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (maze.isPhysicalFloor(row, column)) return true;
            }
        }
        return false;
    }

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

        // A struck player remains controllable in the air. Use the same W+A/D
        // vector a strong Minecraft player would use: rotate toward the landing
        // target while simultaneously applying the local movement component
        // that points at it. This is still ordinary Minecraft input; it is not
        // a physics shortcut.
        /*
         * Air control is weak in 1.8, while the Monster Maze source bump starts
         * the player with roughly 1 block/tick horizontal velocity. Pointing
         * only at the landing point therefore cannot cancel the knockback
         * quickly enough. Counter the measured source velocity, then bias the
         * remaining air-control vector toward the supported landing target.
         */
        double targetDx = target[0] - state.player.x;
        double targetDz = target[1] - state.player.z;
        double targetLen = Math.hypot(targetDx, targetDz);
        if (targetLen > 1.0E-9) {
            targetDx /= targetLen;
            targetDz /= targetLen;
        }
        double speedX = state.player.vx;
        double speedZ = state.player.vz;
        double horizontalSpeed = Math.hypot(speedX, speedZ);
        double desiredWorldX = targetDx;
        double desiredWorldZ = targetDz;
        if (horizontalSpeed > 0.05D) {
            double cancelWeight = Math.min(2.5D, 1.0D + horizontalSpeed);
            desiredWorldX -= (speedX / horizontalSpeed) * cancelWeight;
            desiredWorldZ -= (speedZ / horizontalSpeed) * cancelWeight;
        }
        double desiredLen = Math.hypot(desiredWorldX, desiredWorldZ);
        if (desiredLen < 1.0E-9) {
            desiredWorldX = targetDx;
            desiredWorldZ = targetDz;
            desiredLen = 1.0D;
        }
        desiredWorldX /= desiredLen;
        desiredWorldZ /= desiredLen;

        float postTurnYaw = state.player.yaw + yawDelta;
        double yawRad = Math.toRadians(postTurnYaw);
        double forwardWorldX = -Math.sin(yawRad);
        double forwardWorldZ = Math.cos(yawRad);
        double strafeWorldX = Math.cos(yawRad);
        double strafeWorldZ = Math.sin(yawRad);
        double forward = desiredWorldX * forwardWorldX + desiredWorldZ * forwardWorldZ;
        double strafe = desiredWorldX * strafeWorldX + desiredWorldZ * strafeWorldZ;
        double inputMagnitude = Math.hypot(forward, strafe);
        if (inputMagnitude > 1.0D) {
            forward /= inputMagnitude;
            strafe /= inputMagnitude;
        }
        boolean sprint = true;
        lastDecisionDetail = "MOB_HIT_AIRBORNE_RECOVERY"
                + " target=" + format(target[0]) + "," + format(target[1])
                + " yawError=" + format(yawError)
                + " yawDelta=" + format(yawDelta)
                + " f=" + format(forward)
                + " s=" + format(strafe)
                + " vy=" + format(state.player.vy);
        return new Action(forward, strafe, false, sprint, yawDelta, false);
    }

    private double[] findAirRecoveryTarget(GameState state, Cell goal) {
        /*
         * The source bump supplies the player with a large horizontal impulse.
         * Select the recovery surface from the point the same 1.8 movement model
         * predicts the player's feet will reach naturally, not merely the nearest
         * floor around the current position.
         */
        me.monstermazeai.player.PlayerState ballistic = state.player.copy();
        int landingTicks = 0;
        final int MAX_RECOVERY_TICKS = 20;
        while (landingTicks < MAX_RECOVERY_TICKS
                && !ballistic.grounded
                && ballistic.y > GameState.PATH_Y - 3.0D) {
            movementProjection.tick(
                    ballistic,
                    Action.IDLE,
                    state.maze,
                    0);
            landingTicks++;
        }

        double predictedX = ballistic.x;
        double predictedZ = ballistic.z;
        double goalX = goal.row() + 0.5D;
        double goalZ = goal.column() + 0.5D;

        int centreRow = (int) Math.floor(predictedX);
        int centreCol = (int) Math.floor(predictedZ);

        double bestX = state.player.x;
        double bestZ = state.player.z;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int row = Math.max(0, centreRow - 7);
             row <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreRow + 7); row++) {
            for (int col = Math.max(0, centreCol - 7);
                 col <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreCol + 7); col++) {
                if (!state.maze.isPhysicalFloor(row, col)) continue;

                double x = row + 0.5D;
                double z = col + 0.5D;
                double landingDistance = sq(x - predictedX) + sq(z - predictedZ);
                double goalDistance = sq(x - goalX) + sq(z - goalZ);

                /*
                 * Landing safety dominates strategic progress, but the goal gets
                 * a small bias so a choice among several equally reachable floor
                 * cells does not unnecessarily reverse the route.
                 */
                double score = landingDistance + 0.025D * goalDistance;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                }
            }
        }

        return new double[]{bestX, bestZ};
    }

    private Action laneCorrectionAction(
            GameState state, int dirRow, int dirColumn, double magnitude) {
        double targetX = state.player.x;
        double targetZ = state.player.z;
        if (dirRow == 0) {
            targetX = laneAnchorX;
        } else {
            targetZ = laneAnchorZ;
        }

        double worldX = targetX - state.player.x;
        double worldZ = targetZ - state.player.z;
        double length = Math.hypot(worldX, worldZ);
        if (length < 1.0E-6D) return null;
        worldX /= length;
        worldZ /= length;

        float desiredYaw = cardinalYaw(dirRow, dirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float gain = (float) turnResponseGain();
        float yawDelta = clamp(yawError * gain,
                -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        double postYaw = Math.toRadians(state.player.yaw + yawDelta);
        double forwardWorldX = -Math.sin(postYaw);
        double forwardWorldZ = Math.cos(postYaw);
        double strafeWorldX = Math.cos(postYaw);
        double strafeWorldZ = Math.sin(postYaw);

        double forward = worldX * forwardWorldX + worldZ * forwardWorldZ;
        double strafe = worldX * strafeWorldX + worldZ * strafeWorldZ;
        double inputLength = Math.hypot(forward, strafe);
        if (inputLength > 1.0D) {
            forward /= inputLength;
            strafe /= inputLength;
        }

        double correctionForward = forward * magnitude;
        double correctionStrafe = strafe * magnitude;
        boolean correctionSprint = correctionForward > 0.05;
        Action correction = new Action(
                correctionForward,
                correctionStrafe,
                false,
                correctionSprint,
                yawDelta,
                false);
        return guardLaneCorrectionSupport(state, correction);
    }

    /**
     * Lane correction is a local one-tick maneuver. The normal route guard
     * projects several ticks ahead and scores alternatives by forward route
     * progress; that is correct for route driving, but it can reject a lateral
     * correction whose purpose is specifically to regain the anchored lane.
     *
     * Keep the same physical-support test, but shrink the correction until the
     * immediate projected footprint remains supported. This preserves edge
     * protection without turning a valid A/D correction into a permanent stop.
     */
    private Action guardLaneCorrectionSupport(GameState state, Action correction) {
        if (state.maze == null || !state.player.grounded) return correction;
        if (hasPredictedPhysicalSupport(state, correction, 1)) return correction;

        final double[] scales = {0.70D, 0.45D, 0.25D, 0.10D};
        for (double scale : scales) {
            Action candidate = new Action(
                    correction.forward() * scale,
                    correction.strafe() * scale,
                    false,
                    correction.sprint(),
                    correction.yawDelta(),
                    false);
            if (hasPredictedPhysicalSupport(state, candidate, 1)) {
                return candidate;
            }
        }
        return new Action(
                0.0, 0.0, false, false,
                correction.yawDelta(), false);
    }

    private double waypointBrakeDistance() {
        // High handling lets a player carry more vanilla momentum through a
        // corner; low handling starts braking earlier. The baseline value remains
        // exactly the former 0.70-block threshold.
        return 0.15D + (1.0D - profile.attributes.handling) * 1.10D;
    }

    private static double sq(double value) {
        return value * value;
    }

    /**
     * Baseline high-skill behaviour: normal monster contact is not treated as
     * the default route. When a mob is entering the player's lane, use ordinary
     * Minecraft A/D or S input to create separation, but only toward a cell
     * which is physically supported. This keeps the behaviour source-valid and
     * leaves genuine unavoidable contacts to MonsterManager.bump().
     */
    private Action avoidIncomingMonster(GameState state, boolean allowJump) {
        if (!state.player.grounded || state.maze == null) return null;

        Cell supported = resolveSupportedStartCell(state);
        if (supported == null) return null;

        int routeDirRow = 0;
        int routeDirColumn = 0;
        if (route != null && waypointIndex > 0 && waypointIndex < route.size()) {
            Cell from = route.cells().get(waypointIndex - 1);
            Cell to = route.cells().get(waypointIndex);
            routeDirRow = Integer.signum(to.row() - from.row());
            routeDirColumn = Integer.signum(to.column() - from.column());
        }
        if (routeDirRow == 0 && routeDirColumn == 0) return null;

        MonsterState threat = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (MonsterState monster : state.monsters) {
            if (monster == null || monster.removed
                    || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

            double dx = monster.x - state.player.x;
            double dz = monster.z - state.player.z;
            double distance = Math.hypot(dx, dz);
            if (distance > 2.15D || distance < 0.05D) continue;

            double along = dx * routeDirRow + dz * routeDirColumn;
            if (along <= 0.0D || along > 2.15D) continue;

            double lateral = Math.abs(dx * routeDirColumn - dz * routeDirRow);
            if (lateral > 0.95D) continue;

            double closing = -(monster.vx * dx + monster.vz * dz) / distance;
            /*
             * A nearby but stationary/diverging monster is not automatically a
             * threat to the route. Human runs routinely pass such mobs with
             * forward input intact. Only engage the dodge layer when contact is
             * actually closing, or when the monster is already inside the final
             * close-contact envelope.
             */
            boolean genuinelyThreatening = closing > 0.03D || distance <= 1.10D;
            if (!genuinelyThreatening) continue;

            double score = distance - 0.20D * Math.max(0.0D, closing);
            if (score < bestScore) {
                bestScore = score;
                bestDistance = distance;
                threat = monster;
            }
        }

        if (threat == null) return null;

        int sideRow = routeDirColumn;
        int sideColumn = -routeDirRow;
        Cell left = new Cell(supported.row() + sideRow, supported.column() + sideColumn);
        Cell right = new Cell(supported.row() - sideRow, supported.column() - sideColumn);

        boolean leftFloor = inBounds(left.row(), left.column())
                && state.maze.isPhysicalFloor(left.row(), left.column());
        boolean rightFloor = inBounds(right.row(), right.column())
                && state.maze.isPhysicalFloor(right.row(), right.column());

        double monsterLateral = (threat.x - state.player.x) * routeDirColumn
                - (threat.z - state.player.z) * routeDirRow;

        /*
         * Jumper's real three-charge jump is the emergency "go over the mob"
         * option. Preserve a charge whenever ordinary A/D has a supported
         * escape, but spend one when the mob is already inside the final
         * contact window and the lane cannot safely be cleared laterally.
         */
        double closingSpeed = 0.0D;
        double threatDx = threat.x - state.player.x;
        double threatDz = threat.z - state.player.z;
        double threatDistance = Math.max(bestDistance, 1.0E-6D);
        closingSpeed = -(threat.vx * threatDx + threat.vz * threatDz) / threatDistance;
        /*
         * A strong Jumper can spend a charge to remove a monster from the
         * corridor instead of entering a prolonged yield/turn state. The
         * threshold is deliberately skill-aware: higher Jumper IQ commits the
         * emergency jump earlier, while the baseline behaviour remains unchanged.
         */
        double jumperJumpRange = 1.25D + 0.60D * profile.tendencies.jumperIq;
        boolean urgentJumperJump = allowJump
                && state.kit == me.monstermazeai.kit.Kit.JUMPER
                && state.ability.charges > 0
                && !gapExecutionActive
                && bestDistance <= jumperJumpRange
                && (closingSpeed > 0.03D || !(leftFloor || rightFloor));
        if (urgentJumperJump) {
            float desiredYaw = cardinalYaw(routeDirRow, routeDirColumn);
            float yawError = normalise(desiredYaw - state.player.yaw);
            float yawDelta = clamp(yawError, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
            Action jumpOver = new Action(
                    0.85, 0.0, true, true, yawDelta, false);
            lastDecisionDetail = "MOB_JUMP_OVER"
                    + " monster=" + threat.id
                    + " distance=" + format(bestDistance)
                    + " closing=" + format(closingSpeed)
                    + " yawDelta=" + format(yawDelta);
            return jumpOver;
        }

        double preferred = monsterLateral > 0.0D ? -1.0D : 1.0D;
        double worldSideX = preferred * routeDirColumn;
        double worldSideZ = -preferred * routeDirRow;

        float desiredYaw = cardinalYaw(routeDirRow, routeDirColumn);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float turn = clamp(yawError * (float) turnResponseGain(),
                -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        /*
         * Keep the route heading authoritative while adding only the minimum
         * lateral displacement needed to pass the monster. The dodge is expressed
         * in world space and converted back into W/A/D after the camera turn, so
         * a changed heading cannot silently turn the lateral escape into a stall.
         */
        double routeWeight = leftFloor || rightFloor ? 1.0D : 0.92D;
        double sideWeight = leftFloor || rightFloor ? 0.55D : 0.0D;
        double desiredWorldX = routeDirRow * routeWeight + worldSideX * sideWeight;
        double desiredWorldZ = routeDirColumn * routeWeight + worldSideZ * sideWeight;
        double desiredLength = Math.hypot(desiredWorldX, desiredWorldZ);
        desiredWorldX /= Math.max(desiredLength, 1.0E-9D);
        desiredWorldZ /= Math.max(desiredLength, 1.0E-9D);

        double postYaw = Math.toRadians(state.player.yaw + turn);
        double forwardWorldX = -Math.sin(postYaw);
        double forwardWorldZ = Math.cos(postYaw);
        double strafeWorldX = Math.cos(postYaw);
        double strafeWorldZ = Math.sin(postYaw);
        double forward = desiredWorldX * forwardWorldX + desiredWorldZ * forwardWorldZ;
        double strafe = desiredWorldX * strafeWorldX + desiredWorldZ * strafeWorldZ;

        /*
         * A Jumper can clear an unavoidable corridor blocker with its real charged
         * jump, but this is an emergency action. Otherwise every kit continues
         * through the current supported corridor instead of yielding backwards.
         */
        boolean jump = allowJump
                && state.kit == me.monstermazeai.kit.Kit.JUMPER
                && state.ability.charges > 0
                && bestDistance <= jumperJumpRange
                && closingSpeed > 0.03D
                && !(leftFloor || rightFloor);
        if (jump) {
            forward = routeDirRow * forwardWorldX + routeDirColumn * forwardWorldZ;
            strafe = routeDirRow * strafeWorldX + routeDirColumn * strafeWorldZ;
            double length = Math.hypot(forward, strafe);
            if (length > 1.0D) {
                forward /= length;
                strafe /= length;
            }
            Action jumpThrough = new Action(
                    forward * 0.90D,
                    strafe * 0.90D,
                    true,
                    true,
                    turn,
                    false);
            Action guarded = guardProjectedSupport(state, jumpThrough, routeDirRow, routeDirColumn);
            lastDecisionDetail = "MOB_CONTINUE_JUMP"
                    + " monster=" + threat.id
                    + " distance=" + format(bestDistance)
                    + " closing=" + format(closingSpeed)
                    + " yawError=" + format(yawError);
            return guarded;
        }

        Action continueThrough = new Action(
                forward * (leftFloor || rightFloor ? 0.95D : 0.85D),
                strafe * (leftFloor || rightFloor ? 0.95D : 0.85D),
                shouldSpeedJump(state, allowJump),
                true,
                turn,
                false);
        Action guarded = guardProjectedSupport(state, continueThrough, routeDirRow, routeDirColumn);
        lastDecisionDetail = leftFloor || rightFloor ? "MOB_DODGE_CONTINUE" : "MOB_CONTINUE_CORRIDOR";
        lastDecisionDetail += " monster=" + threat.id
                + " distance=" + format(bestDistance)
                + " yawError=" + format(yawError)
                + " output=f=" + format(guarded.forward())
                + ",s=" + format(guarded.strafe());
        return guarded;
    }

    /**
     * Number of route cells to inspect when deciding whether an asynchronously
     * planned route is allowed to change the current heading because a monster
     * may make the existing route unsafe. The source interaction sphere is 20
     * blocks; the historical value of eight segments made that lookahead much
     * shorter than the actual local observation horizon.
     *
     * The default remains the validated v14 behaviour. CI can sweep the larger
     * values without changing the production default until a candidate wins the
     * full 30-cell matrix without regressions.
     */
    private static int routeThreatLookaheadSegments() {
        final int defaultSegments = 8;
        final int maxSegments = 20;
        String raw = System.getProperty("monstermaze.routeThreatLookaheadSegments");
        if (raw == null || raw.isBlank()) return defaultSegments;
        try {
            return Math.max(1, Math.min(maxSegments, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return defaultSegments;
        }
    }

    private boolean currentRouteThreatenedByMonster(GameState state) {
        if (route == null || route.size() < 2
                || waypointIndex <= 0 || waypointIndex >= route.size()) return false;

        /*
         * The old guard only considered monsters already within ~2.6 blocks of
         * the player. That made the route lookahead mostly cosmetic: a monster
         * could sit several cells ahead on the chosen corridor, begin moving
         * toward it, and only become "threatening" after the player was already
         * committed to that corridor.
         *
         * Predict the encounter at the time the player is expected to reach each
         * inspected route segment. Nearby monsters use the source interaction
         * envelope; fast monsters farther away are also admitted through a
         * strategic 60-block horizon so an incoming crossing can authorize an
         * early route change before the mob reaches the player. This remains an
         * input/controller decision; MonsterState physics stays authoritative.
         */
        int firstSegment = Math.max(0, waypointIndex - 1);
        int lookaheadSegments = routeThreatLookaheadSegments();
        int lastSegment = Math.min(route.size() - 2, firstSegment + lookaheadSegments);
        double playerSpeed = Math.max(
                Math.hypot(state.player.vx, state.player.vz),
                0.12D);
        double routeDistance = 0.0D;

        for (int i = firstSegment; i <= lastSegment; i++) {
            Cell a = route.cells().get(i);
            Cell b = route.cells().get(i + 1);
            double ax = a.row() + 0.5D;
            double az = a.column() + 0.5D;
            double bx = b.row() + 0.5D;
            double bz = b.column() + 0.5D;
            double sx = bx - ax;
            double sz = bz - az;
            double length = Math.hypot(sx, sz);
            if (length <= 1.0E-9D) continue;

            /*
             * Approximate the point where the player reaches this segment by its
             * midpoint. Midpoint prediction is deliberately conservative: a mob
             * anywhere on the segment can make the route unsafe, while using the
             * exact nearest point would make a corner threat dependent on the
             * player's current lateral position.
             */
            double segmentMidX = (ax + bx) * 0.5D;
            double segmentMidZ = (az + bz) * 0.5D;
            double distanceToMidpoint = Math.hypot(
                    segmentMidX - state.player.x,
                    segmentMidZ - state.player.z);
            double timeToSegment = Math.max(
                    0.0D,
                    (routeDistance + Math.max(0.0D, distanceToMidpoint - 0.5D))
                            / playerSpeed);

            for (MonsterState monster : state.monsters) {
                if (monster == null || monster.removed
                        || monster.launched(state.tick) || monster.frozen(state.tick)) continue;

                double currentMonsterDistance = Math.hypot(
                        monster.x - state.player.x,
                        monster.z - state.player.z);
                double monsterSpeed = Math.hypot(monster.vx, monster.vz);
                boolean localMonster = currentMonsterDistance
                        <= me.monstermazeai.monster.MonsterRelevance.INTERACTION_RADIUS;
                boolean remoteMovingMonster = currentMonsterDistance <= 60.0D
                        && monsterSpeed >= 0.08D;
                if (!localMonster && !remoteMovingMonster) {
                    continue;
                }

                double predictedX = monster.x + monster.vx * timeToSegment;
                double predictedZ = monster.z + monster.vz * timeToSegment;

                double projection = ((predictedX - ax) * sx
                        + (predictedZ - az) * sz) / (length * length);
                projection = Math.max(0.0D, Math.min(1.0D, projection));
                double nearestX = ax + projection * sx;
                double nearestZ = az + projection * sz;
                double routeDistanceAtThreat = Math.hypot(
                        predictedX - nearestX,
                        predictedZ - nearestZ);

                if (routeDistanceAtThreat > 1.45D) continue;

                /*
                 * Require a meaningful future encounter rather than reacting to
                 * every monster merely occupying the observation sphere. A
                 * stationary mob on the route is still a valid obstacle; a mob
                 * already moving away is ignored unless the projected encounter
                 * remains inside the close-contact envelope.
                 */
                double predictedPlayerX = segmentMidX;
                double predictedPlayerZ = segmentMidZ;
                double predictedPlayerDistance = Math.hypot(
                        predictedX - predictedPlayerX,
                        predictedZ - predictedPlayerZ);

                double velocityTowardPlayer = 0.0D;
                double currentDx = monster.x - state.player.x;
                double currentDz = monster.z - state.player.z;
                double currentDistance = Math.max(
                        Math.hypot(currentDx, currentDz), 1.0E-6D);
                velocityTowardPlayer = -(
                        monster.vx * currentDx + monster.vz * currentDz)
                        / currentDistance;

                boolean projectedContact = predictedPlayerDistance <= 1.60D;
                boolean movingIntoRoute = velocityTowardPlayer > 0.02D
                        || monsterSpeed < 0.02D
                        || remoteMovingMonster;

                if (projectedContact && movingIntoRoute) return true;
            }

            routeDistance += length;
        }
        return false;
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
    /**
     * Last-chance ledge recovery. Minecraft does not instantly teleport a
     * player away when its AABB loses floor support; there is still horizontal
     * air control on the falling tick. Use that real control window rather than
     * returning IDLE and guaranteeing a straight vertical fall.
     */
    private Action unsupportedEdgeRecoveryAction(GameState state, Cell goal, boolean allowJump) {
        Cell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int centreRow = (int) Math.floor(state.player.x);
        int centreColumn = (int) Math.floor(state.player.z);

        for (int row = Math.max(0, centreRow - 4);
             row <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreRow + 4); row++) {
            for (int column = Math.max(0, centreColumn - 4);
                 column <= Math.min(me.monstermazeai.maze.MazeModel.SIZE - 1, centreColumn + 4); column++) {
                if (!state.maze.isPhysicalFloor(row, column)) continue;
                double supportDistance = sq((row + 0.5D) - state.player.x)
                        + sq((column + 0.5D) - state.player.z);
                double goalDistance = goal == null ? 0.0
                        : sq((row + 0.5D) - (goal.row() + 0.5D))
                        + sq((column + 0.5D) - (goal.column() + 0.5D));
                double score = supportDistance + 0.02D * goalDistance;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Cell(row, column);
                }
            }
        }

        if (best == null) return Action.IDLE;

        double dx = best.row() + 0.5D - state.player.x;
        double dz = best.column() + 0.5D - state.player.z;
        double length = Math.max(Math.hypot(dx, dz), 1.0E-9D);
        double ux = dx / length;
        double uz = dz / length;

        double yawRad = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double strafeX = Math.cos(yawRad);
        double strafeZ = Math.sin(yawRad);

        double forward = ux * forwardX + uz * forwardZ;
        double strafe = ux * strafeX + uz * strafeZ;
        double magnitude = Math.hypot(forward, strafe);
        if (magnitude > 1.0D) {
            forward /= magnitude;
            strafe /= magnitude;
        }

        boolean emergencyJump = allowJump
                && state.kit == me.monstermazeai.kit.Kit.JUMPER
                && state.ability.charges > 0
                && state.player.y > -0.05D;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawDelta = clamp(normalise(desiredYaw - state.player.yaw),
                -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);

        return new Action(forward, strafe, emergencyJump, forward > 0.75,
                yawDelta, false);
    }

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

    /**
     * Reject only a grounded input whose predicted next center leaves the
     * player's 0.6-wide AABB unsupported. This changes ordinary input, never
     * the physics outcome, and is therefore a legitimate closed-loop safety
     * policy for the Minecraft controller.
     */
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
        double lateral = Math.abs(-direction[1] * centerDx + direction[0] * centerDz);
        if (toward < 0.25 || lateral > PAD_ENTRY_MAX_LATERAL_OFFSET) return null;

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
        double centerDx = (goal.row() + 0.5) - state.player.x;
        double centerDz = (goal.column() + 0.5) - state.player.z;
        double toward = padEntryDirRow * centerDx + padEntryDirColumn * centerDz;
        double lateral = Math.abs(-padEntryDirColumn * centerDx + padEntryDirRow * centerDz);
        long elapsed = state.tick - padEntryStartTick;
        if (outside > PAD_ENTRY_RELEASE_DISTANCE
                || elapsed > PAD_ENTRY_MAX_TICKS
                || lateral > PAD_ENTRY_MAX_LATERAL_OFFSET
                || toward < -0.25) {
            lastDecisionDetail = "PAD_ENTRY_ABORT"
                    + " outside=" + format(outside)
                    + " lateral=" + format(lateral)
                    + " toward=" + format(toward)
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
        double distanceToTakeoff = GAP_JUMP_PROGRESS - progress;
        if (progress >= GAP_COMMIT_PROGRESS && progress <= 1.65D) {
            gapExecutionActive = true;
            // Commit early enough that a single-tick physics/replan boundary
            // cannot make us miss the jump input at the block edge.
            // Commitment starts before takeoff; the execute step owns the one-shot jump pulse.
            gapTakeoffStarted = false;
            gapExecutionRouteIndex = waypointIndex - 1;
            gapLandingConfirmTicks = 0;
            if (state.kit != me.monstermazeai.kit.Kit.JUMPER) {
                lastSpeedJumpInputTick = Long.MIN_VALUE;
            }
            return executeCommittedGap(state, allowJump);
        }
        if (distanceToTakeoff > GAP_JUMP_TRIGGER_DISTANCE) {
            lastDecisionDetail = "GAP_APPROACH edge=" + gapEdgeText() + " progress=" + format(progress);
            return new Action(1.0, 0.0, false, true, 0.0F, false);
        }
        if (distanceToTakeoff < -GAP_JUMP_LATE_TOLERANCE) {
            /*
             * Do not fall through into ordinary waypoint steering after a missed
             * edge. The route is now stale by definition: the player has passed
             * the committed takeoff window without entering the gap transition.
             * Stop for this one observation and let the next tick's
             * current-segment deviation check build a physical route from the
             * player's actual supported cell.
             */
            /*
             * The gap geometry is stale by this point. Do not hand a zero-input
             * command to RobustLiveController: that creates an artificial
             * stationary/stuck loop and its generic forced-jump recovery can fire
             * even though this is a route-bookkeeping failure, not a movement
             * failure. Preserve a small source-valid drive command and let the
             * next observation rebuild the route from the actual supported cell.
             */
            fullRouteEvaluationPending = true;
            lastDecisionDetail = "GAP_MISSED_REPLAN edge=" + gapEdgeText()
                    + " progress=" + format(progress);
            Action continueDrive = new Action(0.55, 0.0, false, true, 0.0F, false);
            return guardProjectedSupport(state, continueDrive, dirRow, dirColumn);
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
        boolean jumpThisTick = false;

        /*
         * Non-Jumper speeding is a deliberate repeated jump input while
         * remaining grounded. The source Jump -10 removes the vertical impulse,
         * but the sprint-jump routine still writes the horizontal 0.2 impulse.
         * Let Max Speed control this pre-gap cadence while the gap motor owns
         * the timing window, so tactical replanning cannot steal the inputs.
         */
        boolean nonJumperSpeedPulse = state.kit != me.monstermazeai.kit.Kit.JUMPER
                && state.player.grounded
                && progress < GAP_JUMP_PROGRESS
                && (lastSpeedJumpInputTick == Long.MIN_VALUE
                    || state.tick - lastSpeedJumpInputTick >= profile.attributes.nonJumperJumpCadenceTicks());
        if (nonJumperSpeedPulse) {
            jumpThisTick = true;
            lastSpeedJumpInputTick = state.tick;
            lastDecisionDetail = "GAP_SPEED_PULSE edge=" + gapEdgeText()
                    + " progress=" + format(progress)
                    + " cadence=" + profile.attributes.nonJumperJumpCadenceTicks();
        } else if (!gapTakeoffStarted && state.player.grounded && progress >= GAP_JUMP_PROGRESS) {
            jumpThisTick = true;
            gapTakeoffStarted = true;
            lastDecisionDetail = "GAP_TAKEOFF edge=" + gapEdgeText()
                    + " progress=" + format(progress);
        }
        if (gapTakeoffStarted && state.player.grounded && !jumpThisTick && progress > 0.90D
                && playerAabbOverlapsCell(state, toRow, toColumn)) {
            gapLandingConfirmTicks++;
            if (gapLandingConfirmTicks >= GAP_LANDING_CONFIRM_TICKS) {
                int completedIndex = waypointIndex;
                clearGapCommitment();
                waypointIndex = nextTurnWaypoint(route, completedIndex);
                anchoredSegmentIndex = -1;
                lastDecisionDetail = "GAP_LANDING_CONFIRMED edge=" + fromRow + "," + fromColumn + "->"
                        + toRow + "," + toColumn + " progress=" + format(progress);
                // Landing confirmation is not another jump opportunity.
                return new Action(1.0, 0.0, false, true, 0.0F, false);
            }
        } else {
            gapLandingConfirmTicks = 0;
        }
        // Never fail a committed jump solely because its centre passed the
        // endpoint: the player's 0.6-wide AABB can still overlap the destination
        // block while vanilla gravity is bringing the feet down onto it.
        if (state.player.y < GameState.PATH_Y - 3.0D) {
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
        boolean jumpInput = jumpThisTick;
        boolean sprintInput = !jumpInput || state.kit != me.monstermazeai.kit.Kit.JUMPER;
        lastDecisionDetail = "GAP_EXECUTE edge=" + gapEdgeText() + " progress=" + format(progress)
                + " takeoff=" + gapTakeoffStarted + " jumpInput=" + jumpInput
                + " sprint=" + sprintInput + " allowJump=" + allowJump;
        return new Action(1.0, 0.0, jumpInput, sprintInput, 0.0F, false);
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

    private boolean playerAabbOverlapsCell(GameState state, int row, int column) {
        final double halfWidth = 0.30D;
        double minX = state.player.x - halfWidth;
        double maxX = state.player.x + halfWidth;
        double minZ = state.player.z - halfWidth;
        double maxZ = state.player.z + halfWidth;
        double overlapX = Math.min(maxX, row + 1.0D) - Math.max(minX, row);
        double overlapZ = Math.min(maxZ, column + 1.0D) - Math.max(minZ, column);
        return overlapX > 0.05D && overlapZ > 0.05D;
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