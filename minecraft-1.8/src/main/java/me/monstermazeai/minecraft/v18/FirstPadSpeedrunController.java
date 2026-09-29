package me.monstermazeai.minecraft.v18;

import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.adapter.LegacyWorldObservation;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Isolated pad-to-pad speedrun benchmark:
 * maze + player + active pad + live monsters -> dynamic safe route -> W+sprint+jump.
 *
 * After reaching a pad, the controller deliberately waits in place while the
 * server countdown runs. When the active pad changes at the round transition,
 * it rebuilds the best currently safe route from the player's current position.
 *
 * Mobs are hard dynamic obstacles. If a live/predicted mob blocks the selected
 * route, the complete route is rebuilt. The movement controller never performs
 * a separate mob dodge or strafe; it only follows the currently selected route.
 *
 * The controller runs synchronously on the Minecraft client thread.
 */
public final class FirstPadSpeedrunController {
    private static final int SIZE = 99;
    private static final int PAD_RADIUS = 2;
    private static final float MAX_YAW_STEP = 30.0F;
    private static final float ALIGNMENT_TOLERANCE = 10.0F;
    private static final float MOVING_YAW_TOLERANCE = 12.0F;
    private static final int HEADING_STABLE_TICKS = 0;
    private static final double SAFETY_PROBE_DISTANCE = 0.48D;
    private static final double SAFETY_SWEEP_STEP = 0.10D;
    private static final double PLAYER_HALF_WIDTH = 0.30D;
    private static final double ROUTE_ADVANCE_PROGRESS = 0.80D;
    private static final double ROUTE_WAYPOINT_CAPTURE_RADIUS = 0.65D;
    private static final int MOB_REPLAN_RETRY_TICKS = 10;
    /*
     * Successful mob replans are deliberately rate-limited. Replanning on
     * every observation lets a moving monster alternately make two otherwise
     * valid routes look best, which can make the player turn back and forth
     * instead of committing to a clear route. A genuinely blocked immediate
     * edge still bypasses this cooldown.
     */
    private static final int MOB_REPLAN_MIN_INTERVAL_TICKS = 8;
    private static final int LOOKAHEAD_CELLS = 3;
    /*
     * Forward alignment is a route-direction safety check, not a logical-cell
     * boundary check. A player can legitimately be near any 1x1 cell boundary
     * while still standing on safe physical floor (especially on a 5x5 pad).
     */
    private static final double EDGE_FORWARD_DOT_MIN = 0.85D;
    /*
     * Mobs are hard dynamic obstacles. The planner predicts their short-term
     * position and rejects route cells whose estimated player arrival would
     * overlap the conservative collision envelope.
     *
     * Five client ticks per maze cell is deliberately conservative for the
     * W+sprint+jump speedrun. The planner is continuously replanned when the
     * currently selected route becomes unsafe, so this is an arrival estimate,
     * not a physics claim.
     */
    private static final double MOB_HAZARD_RADIUS = 1.25D;
    private static final int MOB_PREDICT_TICKS = 30;
    private static final double ESTIMATED_TICKS_PER_CELL = 5.0D;
    /*
     * A physical replan must respect the player's existing momentum. Without
     * this, A* can select a geometrically short first edge that points behind
     * the current velocity, forcing a 90-180 degree turn at a one-block
     * corridor corner and recreating the exact route-fighting failure.
     */
    private static final double REPLAN_HEADING_SPEED_THRESHOLD = 0.08D;
    private static final double REPLAN_HEADING_PENALTY_TICKS = 12.0D;
    /*
     * First-pad movement must not churn because of monsters far down the
     * route. Keep dynamic replanning local to the player's actual 20-block
     * threat envelope; the initial A* still accounts for the complete route.
     */
    private static final int MOB_ROUTE_LOOKAHEAD_CELLS = 5;
    private static final double MOB_REPLAN_PLAYER_RANGE = 20.0D;

    private int[] routeRows;
    private int[] routeColumns;
    private int routeLength;
    private int routeIndex;
    private int goalRow = -1;
    private int goalColumn = -1;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;
    private long lastLogTick = Long.MIN_VALUE;
    private boolean targetReached;
    private boolean aligningForStage;
    private boolean aligningForReplan;
    private int lastLoggedStage = -1;
    private long startedAtTick = Long.MIN_VALUE;
    private int headingStableTicks;
    private long lastFailedMobReplanTick = Long.MIN_VALUE;
    private long lastSuccessfulMobReplanTick = Long.MIN_VALUE;
    private String lastRouteBuildFailureReason = "unknown";

    /*
     * Active-pad transitions are tracked explicitly. targetReached is a
     * completion state for the current pad and is cleared as soon as the
     * active pad changes; therefore it cannot also be the transition signal.
     */
    private int lastActivePadRow = -1;
    private int lastActivePadColumn = -1;
    private boolean activePadTransitionPending;
    private boolean routeStartsOnPreviousPad;
    private int previousPadSeedRow = -1;
    private int previousPadSeedColumn = -1;
    private int previousPadCenterRow = -1;
    private int previousPadCenterColumn = -1;
    /*
     * The round starts on a physical 5x5 SafePad centered on the start cell.
     * That pad is not the same object as state.pad: state.pad is the current
     * active destination pad. Keep the initial pad geometry separately so a
     * player spawned on its boundary can leave it without a one-cell
     * logical-floor probe falsely declaring the movement unsafe.
     */
    private boolean initialStartPadAvailable;
    private int initialStartPadCenterRow = -1;
    private int initialStartPadCenterColumn = -1;
    /*
     * Recovery state is deliberately separate from normal route following.
     * A fast player can cross a logical-cell boundary before routeIndex is
     * advanced, so a safety hold must never become a permanent deadlock.
     * Recovery first returns the player to a known supported cell centre,
     * then rebuilds A* from the player's actual position.
     */
    private boolean recovering;
    private int recoveryRow = -1;
    private int recoveryColumn = -1;
    private long lastRecoveryLogTick = Long.MIN_VALUE;
    private double previousPlayerX = Double.NaN;
    private double previousPlayerZ = Double.NaN;
    private double previousPlayerVx = Double.NaN;
    private double previousPlayerVz = Double.NaN;
    private boolean knockbackRecoveryPending;
    private long lastKnockbackRecoveryTick = Long.MIN_VALUE;
    private GameRunSummaryRecorder telemetry;
    private static final double KNOCKBACK_HORIZONTAL_SPEED = 0.55D;
    private static final double KNOCKBACK_ACCELERATION = 0.22D;
    private static final double KNOCKBACK_TICK_DISPLACEMENT = 0.75D;
    private static final int KNOCKBACK_RECOVERY_COOLDOWN_TICKS = 8;
    /*
     * The player's 0.60m footprint can remain physically supported while its
     * centre is nearly 0.93 blocks from a one-block route centreline at a
     * diagonal/cell-boundary crossing. 0.85m was therefore below the actual
     * geometric support envelope and caused false POSITION REPLAN events on
     * otherwise valid grounded crossings.
     */
    private static final double ROUTE_EDGE_LATERAL_TOLERANCE = 1.35D;
    /*
     * At the simulator's capped sprint speed the player can cross a corner
     * before a single-cell waypoint capture is observed. Allow a bounded
     * forward re-index to the nearest future route cell instead of steering
     * back toward an already-passed corner.
     */
    private static final double ROUTE_NEAREST_CAPTURE_RADIUS = 1.85D;
    private static final int ROUTE_NEAREST_CAPTURE_LOOKAHEAD = 8;
    private static final double DIAGONAL_SUPPORT_MIN_AREA = 0.01D;
    /*
     * A two-cell route edge is a deliberate one-block jump, not a walk across
     * an unsupported cell. The jump must be initiated at the takeoff boundary
     * of the source block so the normal sprint speed carries the player over
     * the missing block. We remember the route edge that received the pulse
     * so the generic every-other-tick jump spam cannot replace the edge-timed
     * takeoff with an arbitrary jump phase.
     */
    private static final double GAP_JUMP_TRIGGER_DISTANCE = 0.35D;
    private static final double GAP_JUMP_LATE_TOLERANCE = 0.08D;
    private static final double GAP_LANDING_PROGRESS = 1.20D;
    private static final float GAP_HEADING_TOLERANCE = 5.0F;
    private static final double GAP_LATERAL_SPEED_LIMIT = 0.12D;
    /*
     * A gap jump is only committed after the player has demonstrably built
     * enough forward momentum in the gap's travel direction. This is a
     * physical qualification, not a timer: it survives observations at the
     * same speed and is invalidated by a meaningful turn or loss of forward
     * velocity. Three blocks is the baseline "running straight" requirement
     * and is intentionally exposed as a policy constant for future player/
     * difficulty tendencies.
     */
    private static final double GAP_MOMENTUM_DISTANCE_REQUIRED = 3.0D;
    private static final double GAP_MOMENTUM_MIN_FORWARD_SPEED = 0.22D;
    private static final float GAP_MOMENTUM_HEADING_TOLERANCE = 15.0F;
    private static final double GAP_MOMENTUM_LATERAL_SPEED_LIMIT = 0.12D;
    private static final float GAP_MOMENTUM_DIRECTION_TOLERANCE = 15.0F;
    private static final int GAP_MOMENTUM_LOG_INTERVAL_TICKS = 10;

    private static final int GAP_LANDING_CONFIRM_TICKS = 2;
    private int gapJumpTriggeredRouteIndex = -1;
    private boolean gapExecutionActive;
    private boolean gapTakeoffStarted;
    private int gapExecutionRouteIndex = -1;
    private int gapLandingConfirmTicks;
    private double gapQualifiedMomentumDistance;
    private double gapMomentumDirectionX = Double.NaN;
    private double gapMomentumDirectionZ = Double.NaN;
    private int gapMomentumRouteIndex = -1;

    private enum EdgeType {
        ORTHOGONAL,
        DIAGONAL,
        ONE_BLOCK_GAP
    }

    public void setTelemetry(GameRunSummaryRecorder telemetry) {
        this.telemetry = telemetry;
    }

    public boolean hasReachedTarget() {
        return targetReached;
    }

    private void log(long tick, String message) {
        System.out.println(message);
        if (telemetry != null) {
            telemetry.controllerEvent(tick, message);
        }
    }

    public LegacyAction next(LegacyWorldObservation state) {
        if (state == null || !state.inMonsterMaze || !state.mazeDetected
                || !state.alive || state.completed || state.center == null
                || state.pad == null || state.pad.row < 0 || state.pad.column < 0) {
            reset();
            return LegacyAction.IDLE;
        }

        /*
         * Completion must be decided from the same geometric predicate as the
         * authoritative MonsterMaze SafePad.isOn(Entity) implementation.
         * state.pad.reached is an observer convenience signal (distance to the
         * beacon) and must never be allowed to declare success by itself:
         * a stale/misaligned beacon observation can otherwise leave the AI idle
         * while the server still considers the player off the SafePad.
         */
        boolean atTarget = isInsidePad(state);
        boolean suddenHorizontalImpulse = detectSuddenHorizontalImpulse(state);

        /*
         * Active-pad identity is the authoritative phase-transition signal.
         * Capture the pad that was active on the previous observation before
         * changing targetReached or rebuilding the route. This preserves the
         * physical SafePad as a legitimate route seed during the transition.
         */
        boolean activePadChanged = lastActivePadRow >= 0
                && (state.pad.row != lastActivePadRow
                || state.pad.column != lastActivePadColumn);

        /*
         * The controller must treat the observed active-pad identity as the
         * source of truth even if the previous observation was lost/reset.
         * goalRow/goalColumn is therefore a second transition detector: if the
         * server has already advanced the active pad but lastActivePad* was
         * unavailable, we still must not leave targetReached latched.
         */
        boolean targetIdentityChanged = goalRow >= 0
                && (state.pad.row != goalRow || state.pad.column != goalColumn);

        if (activePadChanged || targetIdentityChanged) {
            int oldRow = activePadChanged
                    ? lastActivePadRow : goalRow;
            int oldColumn = activePadChanged
                    ? lastActivePadColumn : goalColumn;

            /*
             * Only a real previous active pad is a valid synthetic route seed.
             * If the controller lost its observation history, do not invent
             * one; buildRoute() will start from the player's actual position.
             */
            if (oldRow >= 0 && oldColumn >= 0
                    && (oldRow != state.pad.row || oldColumn != state.pad.column)) {
                previousPadCenterRow = oldRow;
                previousPadCenterColumn = oldColumn;
                routeStartsOnPreviousPad = true;
            }

            activePadTransitionPending = true;
            targetReached = false;
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;

            log(state.worldTick, "[MonsterMazeAI/1.8] PAD TRANSITION"
                    + " tick=" + state.worldTick
                    + " old=" + oldRow + "," + oldColumn
                    + " new=" + state.pad.row + "," + state.pad.column
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " previousPadSeed=" + routeStartsOnPreviousPad);
        }
        lastActivePadRow = state.pad.row;
        lastActivePadColumn = state.pad.column;

        /*
         * Being previously on a SafePad is never a permission to remain idle
         * after the player is no longer on it. This recovery is deliberately
         * unconditional with respect to activePadChanged: it makes targetReached
         * a self-healing state rather than a latch.
         */
        if (targetReached && !atTarget) {
            targetReached = false;
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;
            activePadTransitionPending = activePadTransitionPending
                    || (goalRow >= 0 && (state.pad.row != goalRow || state.pad.column != goalColumn));
            log(state.worldTick, "[MonsterMazeAI/1.8] PAD EXIT RECOVERY"
                    + " tick=" + state.worldTick
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " pad=" + state.pad.row + "," + state.pad.column
                    + " reason=left-active-pad");
        }

        if (atTarget) {
            if (!targetReached) {
                targetReached = true;
                long elapsed = startedAtTick == Long.MIN_VALUE
                        ? 0L : state.worldTick - startedAtTick;
                log(state.worldTick, "[MonsterMazeAI/1.8] PAD REACHED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " elapsedTicks=" + elapsed
                        + " routeLength=" + routeLength
                        + " geometry=authoritative-SafePad.isOn");
            }
            return LegacyAction.IDLE;
        }

        /*
         * A new stage is identified by a changed active pad. This is the
         * authoritative transition signal; the preview beacon is deliberately
         * ignored by Minecraft18Observer until it becomes the active pad.
         */
        if (centerX != state.center.x || centerZ != state.center.z
                || goalRow != state.pad.row || goalColumn != state.pad.column
                || routeLength == 0) {
            if (!buildRoute(state)) {
                /*
                 * An active-pad transition is a mandatory route-build event.
                 * Do not consume the transition by clearing its pending flag,
                 * and do not silently convert a failed build into a permanent
                 * IDLE state. buildRoute() records the exact dynamic/static
                 * failure reason; leave routeLength at zero so the next tick
                 * retries from the still-valid previous SafePad seed.
                 */
                if (activePadTransitionPending && state.worldTick % 5L == 0L) {
                    log(state.worldTick, "[MonsterMazeAI/1.8] PAD TRANSITION ROUTE FAILED"
                            + " tick=" + state.worldTick
                            + " old=" + previousPadCenterRow + "," + previousPadCenterColumn
                            + " new=" + state.pad.row + "," + state.pad.column
                            + " player=" + format(state.player.x) + "," + format(state.player.z)
                            + " buildFailure=" + lastRouteBuildFailureReason
                            + " action=RETRY_NEXT_TICK");
                }
                return LegacyAction.IDLE;
            }
            if (activePadTransitionPending) {
                log(state.worldTick, "[MonsterMazeAI/1.8] PAD TRANSITION ROUTE"
                        + " tick=" + state.worldTick
                        + " start=" + routeRows[0] + "," + routeColumns[0]
                        + " target=" + goalRow + "," + goalColumn
                        + " length=" + routeLength
                        + " firstEdge=" + (routeLength > 1
                        ? routeRows[1] + "," + routeColumns[1] : "none"));
                activePadTransitionPending = false;
            }
            if (startedAtTick == Long.MIN_VALUE) {
                startedAtTick = state.worldTick;
            }
        }

        /*
         * A new active pad can be a large heading change from the previous
         * stage. Do the turn while stationary before allowing any forward
         * movement. The next pad is intentionally unknown before the server
         * transition, so this is the earliest safe point at which the new
         * route can be used for pre-alignment.
         */
        if (aligningForStage || aligningForReplan) {
            int headingIndex = firstRouteHeadingIndex();
            int fromIndex = Math.max(0, headingIndex - 1);
            float desiredYaw = desiredYawForEdge(
                    routeRows[fromIndex], routeColumns[fromIndex],
                    routeRows[headingIndex], routeColumns[headingIndex]);
            float yawError = normalise(desiredYaw - state.player.yaw);
            float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

            if (Math.abs(yawError) <= ALIGNMENT_TOLERANCE) {
                boolean wasStageAlignment = aligningForStage;
                aligningForStage = false;
                aligningForReplan = false;
                log(state.worldTick, wasStageAlignment
                        ? "[MonsterMazeAI/1.8] PAD ALIGNED"
                        : "[MonsterMazeAI/1.8] REPLAN ALIGNED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " heading=" + routeRows[headingIndex] + "," + routeColumns[headingIndex]
                        + " yaw=" + format(state.player.yaw)
                        + " desiredYaw=" + format(desiredYaw));
            } else {
                if (state.worldTick % 2L == 0L) {
                    log(state.worldTick, "[MonsterMazeAI/1.8] PAD ALIGN"
                            + " stage=" + state.stage
                            + " tick=" + state.worldTick
                            + " heading=" + routeRows[headingIndex] + "," + routeColumns[headingIndex]
                            + " yaw=" + format(state.player.yaw)
                            + " desiredYaw=" + format(desiredYaw)
                            + " yawDelta=" + format(yawDelta));
                }
                return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
            }
        }

        if (targetReached || routeLength <= 1) {
            return LegacyAction.IDLE;
        }

        /*
         * Treat a sudden horizontal impulse as a movement-model invalidation.
         * Normal sprint/jump motion stays below these thresholds; mob bumps
         * can inject a substantially larger horizontal velocity or one-tick
         * displacement. Do not attempt to continue the old route through a
         * knockback event. Wait for the player to settle back onto the maze,
         * then re-anchor and rebuild from the observed position.
         */
        if (suddenHorizontalImpulse && !recovering
                && (lastKnockbackRecoveryTick == Long.MIN_VALUE
                || state.worldTick - lastKnockbackRecoveryTick >= KNOCKBACK_RECOVERY_COOLDOWN_TICKS)) {
            knockbackRecoveryPending = true;
            lastKnockbackRecoveryTick = state.worldTick;
            log(state.worldTick, "[MonsterMazeAI/1.8] KNOCKBACK DETECTED"
                    + " tick=" + state.worldTick
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " motion=" + format(state.player.vx) + "," + format(state.player.vz));
        }

        if (knockbackRecoveryPending) {
            if (!state.player.grounded) {
                log(state.worldTick, "[MonsterMazeAI/1.8] KNOCKBACK WAIT"
                        + " tick=" + state.worldTick
                        + " pos=" + format(state.player.x) + "," + format(state.player.z)
                        + " y=" + format(state.player.y)
                        + " vy=" + format(state.player.vy));
                return LegacyAction.IDLE;
            }
            knockbackRecoveryPending = false;
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;
            if (beginRecovery(state)) {
                log(state.worldTick, "[MonsterMazeAI/1.8] KNOCKBACK REANCHOR"
                        + " tick=" + state.worldTick
                        + " player=" + format(state.player.x) + "," + format(state.player.z));
                return recoveryAction(state);
            }
            if (buildRoute(state)) {
                log(state.worldTick, "[MonsterMazeAI/1.8] KNOCKBACK REPLAN"
                        + " tick=" + state.worldTick
                        + " start=" + routeRows[0] + "," + routeColumns[0]
                        + " target=" + goalRow + "," + goalColumn);
            } else {
                return LegacyAction.IDLE;
            }
        }

        advanceRouteIndex(state);

        /*
         * Route state is allowed to lag behind continuous player motion by a
         * fraction of a cell, but never far enough that the player is no
         * longer supported by the current route envelope. If that happens,
         * explicitly re-anchor instead of repeatedly returning IDLE from a
         * predicted-floor safety failure.
         */
        if (!gapExecutionActive && !recovering && routePositionNeedsRecovery(state)) {
            /*
             * The player is already at a physically observed position. Do not
             * send the recovery motor back toward a guessed cell centre: that
             * is precisely what caused the high-speed controller to fight its
             * own newly rebuilt route. Rebuild A* directly from the observed
             * position first. Recovery is only the grounded fallback when the
             * current position cannot seed a physical route at all.
             */
            routeLength = 0;
            routeIndex = 0;
            if (buildRoute(state)) {
                log(state.worldTick, "[MonsterMazeAI/1.8] POSITION REPLAN"
                        + " tick=" + state.worldTick
                        + " start=" + routeRows[0] + "," + routeColumns[0]
                        + " target=" + goalRow + "," + goalColumn);
            } else if (state.player.grounded && beginRecovery(state)) {
                return recoveryAction(state);
            } else {
                return LegacyAction.IDLE;
            }
        }

        if (recovering) {
            if (isRecoveryComplete(state)) {
                recovering = false;
                recoveryRow = -1;
                recoveryColumn = -1;
                if (buildRoute(state)) {
                    log(state.worldTick, "[MonsterMazeAI/1.8] RECOVERY REANCHORED"
                            + " tick=" + state.worldTick
                            + " start=" + routeRows[0] + "," + routeColumns[0]
                            + " target=" + goalRow + "," + goalColumn);
                }
            }
            if (recovering) return recoveryAction(state);
        }

        if (routeIndex >= routeLength - 1) {
            /*
             * Reaching the last route cell is NOT pad completion. The only
             * success condition was established at the top of next(): the
             * observed SafePad geometry must actually contain the player.
             *
             * If the route has legitimately brought us to the target cell,
             * perform a short final approach toward the real pad centre rather
             * than silently returning IDLE. If we are not close enough for a
             * final approach, discard the stale route and replan from the
             * player's actual position.
             */
            double padCenterX = (state.center.x - 49) + state.pad.row + 0.5D;
            double padCenterZ = (state.center.z - 49) + state.pad.column + 0.5D;
            double padDistance = Math.hypot(
                    state.player.x - padCenterX,
                    state.player.z - padCenterZ);

            if (padDistance <= 3.50D && physicalFloorSupportsFootprint(
                    state, state.player.x, state.player.z)) {
                return finalPadApproachAction(state, padCenterX, padCenterZ, padDistance);
            }

            log(state.worldTick, "[MonsterMazeAI/1.8] ROUTE END WITHOUT PAD"
                    + " tick=" + state.worldTick
                    + " routeIndex=" + routeIndex + "/" + (routeLength - 1)
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " pad=" + state.pad.row + "," + state.pad.column
                    + " padDistance=" + format(padDistance)
                    + " action=REPLAN");
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;
            if (!buildRoute(state)) {
                if (beginRecovery(state)) {
                    return recoveryAction(state);
                }
                return LegacyAction.IDLE;
            }
            if (routeLength <= 1) {
                return finalPadApproachAction(state, padCenterX, padCenterZ, padDistance);
            }
        }

        /*
         * The movement controller is edge-driven. The immediate next route
         * cell is authoritative for the direction we must travel; lookahead
         * is only used on a straight run after that edge is established.
         */
        int nextIndex = routeIndex + 1;
        int targetIndex = safeLookaheadIndex();

        /*
         * Heading is defined by the discrete route edge, not by the vector
         * from the player's current position to the next cell centre.
         *
         * The latter is subtly wrong when the player is near a logical cell
         * boundary: e.g. standing at x=1.0 while traversing 50,50 -> 49,50
         * points diagonally toward the next centre. That can disagree with
         * movementSafetyAllowsForward(), which correctly evaluates the actual
         * route edge, and can therefore create a permanent safety hold.
         */
        float desiredYaw = desiredYawForEdge(
                routeRows[routeIndex], routeColumns[routeIndex],
                routeRows[nextIndex], routeColumns[nextIndex]);
        /*
         * Route tracking is the sole grounded cursor-steering authority.
         * The separate anticipatory corner pulse can fight the current edge at
         * sprint speed and inject lateral velocity before waypoint capture.
         * AirborneCornerYaw remains responsible for a real in-flight turn.
         */
        desiredYaw = routeTrackingYaw(state, desiredYaw);
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

        if (gapExecutionActive) {
            LegacyAction gapAction = executeCommittedGap(state);
            if (gapAction != null) return gapAction;
        }

        /*
         * Airborne route continuity is a physics-critical state. A sprint jump
         * carries substantial horizontal momentum, so stopping to satisfy the
         * grounded corner/heading safety rules can turn a valid route corner
         * into a fall. While the player is airborne and still inside the
         * committed route envelope, keep W+sprint+Space active and steer toward
         * the current edge. Do not replan or recover in mid-flight; the next
         * grounded observation can safely validate the new edge.
         *
         * This is intentionally narrower than "always move while airborne":
         * sudden knockback and a genuinely lost route envelope are handled by
         * the recovery logic above.
         */
        if (!state.player.grounded
                && !gapExecutionActive
                && state.player.y > state.center.y - 2.00D
                && routeLength > 1) {
            /*
             * Sprint jumps deliberately chain while airborne. A route rebuild
             * from an airborne continuous position can select a different
             * corridor cell than the one whose horizontal momentum is already
             * carrying the player, which is exactly how the simulator produced
             * repeated mid-flight deaths. Grounded ticks are the safe point for
             * physical validation and replanning; airborne ticks preserve the
             * committed route and steer only.
             */
            float airborneDesiredYaw = desiredYaw;
            float airborneYawError = normalise(airborneDesiredYaw - state.player.yaw);
            float airYawDelta = clamp(airborneYawError, -MAX_YAW_STEP, MAX_YAW_STEP);
            if (state.worldTick % 10L == 0L) {
                log(state.worldTick, "[MonsterMazeAI/1.8] AIRBORNE ROUTE CONTINUE"
                        + " tick=" + state.worldTick
                        + " routeIndex=" + routeIndex
                        + " yawError=" + format(airborneYawError)
                        + " yawDelta=" + format(airYawDelta)
                        + " pos=" + format(state.player.x) + "," + format(state.player.y)
                        + "," + format(state.player.z));
            }
            return new LegacyAction(1.0f, 0.0f, true, true, airYawDelta, false);
        }

        /*
         * Mobs are part of route planning, never a movement override.
         *
         * If the selected route has become unsafe since it was planned, build
         * one new complete route from the player's current position. The
         * resulting route is then handled by the exact same movement controller
         * as a mob-free route. There is intentionally no strafe/dodge state.
         */
        boolean physicalRouteInvalid = routeNeedsPhysicalReplan(state);
        boolean mobBlocked = routeNeedsMobReplan(state);
        if (physicalRouteInvalid || mobBlocked) {
            /*
             * A committed gap is a physics-critical transaction. Dynamic mob
             * replanning is suspended until the landing is confirmed; changing
             * route arrays during the jump would invalidate the committed edge.
             */
            if (gapExecutionActive && gapExecutionRouteIndex == routeIndex) {
                return executeCommittedGap(state);
            }

            int oldLength = routeLength;
            int oldIndex = routeIndex;

            /*
             * Do not let a moving mob cause route oscillation every tick.
             * If the immediate edge itself is currently blocked, replan now;
             * otherwise wait for the short cooldown so the existing route can
             * actually make progress before another future-hazard prediction
             * changes the route.
             */
            boolean immediateMobBlocked = mobBlocked
                    && routeIndex < routeLength - 1
                    && isMobBlockedAlongEdge(
                    state,
                    routeRows[routeIndex],
                    routeColumns[routeIndex],
                    routeRows[routeIndex + 1],
                    routeColumns[routeIndex + 1],
                    0.0D);
            boolean mobCooldown = mobBlocked
                    && !immediateMobBlocked
                    && lastSuccessfulMobReplanTick != Long.MIN_VALUE
                    && state.worldTick - lastSuccessfulMobReplanTick < MOB_REPLAN_MIN_INTERVAL_TICKS;
            if (mobCooldown) {
                mobBlocked = false;
            }

            boolean retryAllowed = !mobBlocked
                    || lastFailedMobReplanTick == Long.MIN_VALUE
                    || state.worldTick - lastFailedMobReplanTick >= MOB_REPLAN_RETRY_TICKS;
            if (!retryAllowed) return LegacyAction.IDLE;
            if (!buildRoute(state)) {
                /*
                 * Never treat a failed replan as a terminal movement state.
                 * Re-anchor to the nearest known supported cell and try again;
                 * this is especially important after a fast boundary crossing
                 * or mob knockback has made the previous route stale.
                 */
                if (beginRecovery(state)) {
                    if (state.worldTick % 5L == 0L) {
                        log(state.worldTick, "[MonsterMazeAI/1.8] ROUTE REPLAN FAILED"
                                + " tick=" + state.worldTick
                                + " oldIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                                + " reason=" + lastRouteBuildFailureReason
                                + " action=RECOVER_TO_SAFE_CELL");
                    }
                    return recoveryAction(state);
                }
                if (mobBlocked) {
                    lastFailedMobReplanTick = state.worldTick;
                    if (state.worldTick % 5L == 0L) {
                        log(state.worldTick, "[MonsterMazeAI/1.8] MOB ROUTE BLOCKED"
                                + " tick=" + state.worldTick
                                + " routeIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                                + " buildFailure=" + lastRouteBuildFailureReason
                                + " action=RETRY_FROM_CURRENT_POSITION");
                    }
                } else if (state.worldTick % 5L == 0L) {
                    log(state.worldTick, "[MonsterMazeAI/1.8] PHYSICAL ROUTE REPLAN FAILED"
                            + " tick=" + state.worldTick
                            + " routeIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                            + " buildFailure=" + lastRouteBuildFailureReason
                            + " action=RETRY_FROM_CURRENT_POSITION");
                }
                return LegacyAction.IDLE;
            }
            lastFailedMobReplanTick = Long.MIN_VALUE;
            if (mobBlocked) {
                lastSuccessfulMobReplanTick = state.worldTick;
            }
            if (state.worldTick % 5L == 0L) {
                log(state.worldTick, "[MonsterMazeAI/1.8] MOB ROUTE REPLAN"
                        + " tick=" + state.worldTick
                        + " oldIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                        + " newLength=" + routeLength
                        + " newHeading=" + routeRows[Math.min(1, routeLength - 1)]
                        + "," + routeColumns[Math.min(1, routeLength - 1)]);
            }

            if (routeIndex >= routeLength - 1) {
                /*
                 * A dynamic replan can legitimately produce a one-cell route
                 * whose goal is inside the SafePad. Do not convert that into
                 * IDLE: the player may still be outside the actual 5x5 pad.
                 * Let the exact same final-pad approach used by the normal
                 * route-end path close the remaining distance.
                 */
                double padCenterX = (state.center.x - 49) + state.pad.row + 0.5D;
                double padCenterZ = (state.center.z - 49) + state.pad.column + 0.5D;
                double padDistance = Math.hypot(
                        state.player.x - padCenterX,
                        state.player.z - padCenterZ);
                if (padDistance <= 3.50D
                        && physicalFloorSupportsFootprint(
                        state, state.player.x, state.player.z)) {
                    return finalPadApproachAction(
                            state, padCenterX, padCenterZ, padDistance);
                }
                routeLength = 0;
                routeIndex = 0;
                if (!buildRoute(state)) {
                    return LegacyAction.IDLE;
                }
                if (routeIndex >= routeLength - 1) {
                    return finalPadApproachAction(
                            state, padCenterX, padCenterZ, padDistance);
                }
            }
            nextIndex = routeIndex + 1;
            targetIndex = safeLookaheadIndex();
            desiredYaw = desiredYawForEdge(
                    routeRows[routeIndex], routeColumns[routeIndex],
                    routeRows[nextIndex], routeColumns[nextIndex]);
            desiredYaw = cornerLeadYaw(state, desiredYaw);
            desiredYaw = routeTrackingYaw(state, desiredYaw);
            yawError = normalise(desiredYaw - state.player.yaw);
            yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
        }

        /*
         * Hard movement safety invariant. During testing, large heading errors         * are resolved with stationary yaw only. Forward input is permitted
         * only when the player is aligned with the immediate route edge and
         * the forward vector agrees with that edge.
         */
        float commandedYaw = normalise(state.player.yaw + yawDelta);
        String safetyReason = movementSafetyFailureReason(
                state, targetIndex, desiredYaw, yawError, commandedYaw);
        boolean safetyOk = safetyReason == null;
        if (!safetyOk) {
            /*
             * A safety hold is a real interruption to the straight-line run.
             * Do not carry previously accumulated gap momentum through a
             * rotation/hold and then allow the newly aligned edge to inherit
             * the old qualification. The previous implementation only
             * updated momentum after safety passed, which meant the accumulator
             * could survive exactly the "turn sideways -> turn back -> jump"
             * sequence this gate is intended to prevent.
             */
            resetGapMomentum();
            headingStableTicks = 0;
            if (state.worldTick % 5L == 0L) {
                log(state.worldTick, "[MonsterMazeAI/1.8] MOVEMENT SAFETY HOLD"
                        + " tick=" + state.worldTick
                        + " routeIndex=" + routeIndex
                        + " next=" + routeRows[nextIndex] + "," + routeColumns[nextIndex]
                        + " target=" + routeRows[targetIndex] + "," + routeColumns[targetIndex]
                        + " pos=" + format(state.player.x) + "," + format(state.player.z)
                        + " yawError=" + format(yawError)
                        + " commandedYaw=" + format(commandedYaw)
                        + " reason=" + safetyReason);
            }
            /*
             * Never classify a heading error itself as a movement failure.
             * movementSafetyFailureReason() has already evaluated the actual
             * post-turn heading. If that commanded heading is safe, the caller
             * below is allowed to turn and move in the same tick. If a genuine
             * floor/vertical/vector constraint failed, rotate in place rather
             * than advancing into the unsafe direction.
             */
            /*
             * If we are airborne but the route envelope has already been
             * invalidated, do not freeze the player in the air. Continue the
             * current heading for one control tick while the route/recovery
             * machinery catches up. A stationary airborne action has no useful
             * physical analogue and was a direct source of simulated falls.
             */
            if (!state.player.grounded
                    && state.player.y > state.center.y - 1.50D
                    && !suddenHorizontalImpulse) {
                float airborneDesiredYaw = desiredYaw;
                float airborneYawError = normalise(
                        airborneDesiredYaw - state.player.yaw);
                float airborneYawDelta = clamp(
                        airborneYawError, -MAX_YAW_STEP, MAX_YAW_STEP);
                return new LegacyAction(
                        1.0f, 0.0f, true, true, airborneYawDelta, false);
            }
            if (Math.abs(yawDelta) > 0.01F) {
                return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
            }
            return LegacyAction.IDLE;
        }

        if (headingStableTicks < HEADING_STABLE_TICKS) {
            headingStableTicks++;
            return new LegacyAction(0.0f, 0.0f, false, false, 0.0f, false);
        }

        /*
         * Maintain a physical momentum qualification before any gap commit.
         * The accumulator follows the current route direction, so a mob replan
         * that preserves the same heading does not throw away useful momentum,
         * while a meaningful turn immediately invalidates the straight-run
         * qualification.
         */
        updateGapMomentum(state, desiredYaw, yawError);

        /*
         * Normal speedrun movement uses jump spam. A one-block gap gets a
         * special edge-timed pulse: press jump while grounded just before the
         * source block's far edge. The pulse is only permitted once the player
         * has built the required straight-line momentum.
         */
        if (isCurrentEdgeGap(state)) {
            LegacyAction gapAction = prepareOrStartGap(state, desiredYaw, yawError);
            if (gapAction != null) return gapAction;
        }

        boolean jumpPulse = true;

        if (state.worldTick % 10L == 0L) {
            log(state.worldTick, "[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN"
                    + " tick=" + state.worldTick
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " route=" + routeIndex + "/" + (routeLength - 1)
                    + " aim=" + targetIndex
                    + " next=" + routeRows[nextIndex] + "," + routeColumns[nextIndex]
                    + " target=" + routeRows[targetIndex] + "," + routeColumns[targetIndex]
                    + " edge=" + edgeType(routeIndex)
                    + " yaw=" + format(state.player.yaw)
                    + " desiredYaw=" + format(desiredYaw)
                    + " yawDelta=" + format(yawDelta)
                    + " jump=" + jumpPulse);
        }

        return new LegacyAction(1.0f, 0.0f, jumpPulse, true, yawDelta, false);
    }

    public void reset() {
        routeRows = null;
        routeColumns = null;
        routeLength = 0;
        routeIndex = 0;
        goalRow = -1;
        goalColumn = -1;
        centerX = Integer.MIN_VALUE;
        centerZ = Integer.MIN_VALUE;
        startedAtTick = Long.MIN_VALUE;
        targetReached = false;
        aligningForStage = false;
        lastLoggedStage = -1;
        lastLogTick = Long.MIN_VALUE;
        headingStableTicks = 0;
        lastFailedMobReplanTick = Long.MIN_VALUE;
        lastSuccessfulMobReplanTick = Long.MIN_VALUE;
        lastRouteBuildFailureReason = "unknown";
        lastActivePadRow = -1;
        lastActivePadColumn = -1;
        activePadTransitionPending = false;
        recovering = false;
        recoveryRow = -1;
        recoveryColumn = -1;
        lastRecoveryLogTick = Long.MIN_VALUE;
        previousPlayerX = Double.NaN;
        previousPlayerZ = Double.NaN;
        previousPlayerVx = Double.NaN;
        previousPlayerVz = Double.NaN;
        knockbackRecoveryPending = false;
        lastKnockbackRecoveryTick = Long.MIN_VALUE;
        gapJumpTriggeredRouteIndex = -1;
        gapExecutionActive = false;
        gapTakeoffStarted = false;
        gapExecutionRouteIndex = -1;
        gapLandingConfirmTicks = 0;
        resetGapMomentum();
        routeStartsOnPreviousPad = false;
        previousPadSeedRow = -1;
        previousPadSeedColumn = -1;
        previousPadCenterRow = -1;
        previousPadCenterColumn = -1;
        initialStartPadAvailable = false;
        initialStartPadCenterRow = -1;
        initialStartPadCenterColumn = -1;
    }

    private boolean buildRoute(LegacyWorldObservation state) {
        lastRouteBuildFailureReason = "unknown";
        boolean firstRoute = startedAtTick == Long.MIN_VALUE;
        int nominalStartRow = row(state.player.x, state.center.x);
        int nominalStartColumn = row(state.player.z, state.center.z);

        int targetRow = state.pad.row;
        int targetColumn = state.pad.column;

        /*
         * At a phase transition the player is still physically standing on
         * the previous Safe Pad, while ObservationWorldModel has already
         * rebuilt physicalFloor around the NEW active pad. Preserve the old
         * pad as a legal route seed instead of forcing the player's centre
         * back into the old pad's vanished logical-floor representation.
         */
        boolean standingOnPreviousPad = isPreviousPadSeedAvailable()
                && Math.abs(nominalStartRow - previousPadCenterRow) <= PAD_RADIUS
                && Math.abs(nominalStartColumn - previousPadCenterColumn) <= PAD_RADIUS;

        /*
         * During a pad transition the player may already have crossed several
         * logical cells while still physically standing on the previous
         * SafePad. If that observed nominal cell is itself part of the old
         * pad's live physical surface, it is the correct launch state and
         * should seed A* directly. Re-anchoring to the old pad centre creates
         * a route behind the player's actual momentum, which can immediately
         * produce an overshoot/replan cycle during the stationary heading
         * alignment.
         *
         * Only fall back to the previous-pad centre when the observed nominal
         * cell is not represented by the transition's physical floor. That
         * preserves the synthetic previous-pad exception for the genuinely
         * missing logical representation without discarding valid continuous
         * player position.
         */
        int[] physicalStart;
        if (standingOnPreviousPad) {
            /*
             * The transition predicate already proves that the player is
             * inside the previous SafePad. Use the observed logical cell as
             * the graph seed even if the observer has already stopped exposing
             * that old pad through physicalFloor. routeCellSupported() treats
             * this first node as the legitimate previous-pad source.
             */
            physicalStart = new int[] {nominalStartRow, nominalStartColumn};
        } else {
            physicalStart = findNearestPhysicalStartCell(
                    state, nominalStartRow, nominalStartColumn, false);
        }
        if (physicalStart == null) {
            lastRouteBuildFailureReason = "no-physical-support-cell";
            log(state.worldTick, "[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + nominalStartRow + "," + nominalStartColumn
                    + " reason=" + lastRouteBuildFailureReason);
            return false;
        }
        int startRow = physicalStart[0];
        int startColumn = physicalStart[1];

        if (!state.physicalFloor[startRow][startColumn] && !standingOnPreviousPad) {
            lastRouteBuildFailureReason = "start-not-physical-floor";
            log(state.worldTick, "[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn
                    + " reason=" + lastRouteBuildFailureReason);
            return false;
        }

        /*
         * Dynamic A*: each node carries the estimated arrival time for that
         * cell. A neighbour is rejected when a live monster is predicted to
         * occupy its collision envelope when the player arrives.
         *
         * This is deliberately a hard constraint, not a penalty. The planner
         * therefore cannot choose a shorter route through a monster simply
         * because that route has fewer cells.
         */
        int total = SIZE * SIZE;
        double[] bestArrivalTicks = new double[total];
        Arrays.fill(bestArrivalTicks, Double.POSITIVE_INFINITY);
        int[] parent = new int[total];
        Arrays.fill(parent, -2);

        PriorityQueue<RouteNode> open = new PriorityQueue<RouteNode>(
                (a, b) -> Double.compare(a.fScore, b.fScore));

        int start = index(startRow, startColumn);
        bestArrivalTicks[start] = 0.0D;
        parent[start] = -1;
        open.add(new RouteNode(start, 0.0D,
                heuristicTicks(startRow, startColumn, targetRow, targetColumn)));

        int goal = -1;

        while (!open.isEmpty()) {
            RouteNode node = open.poll();
            if (node.gTicks > bestArrivalTicks[node.index] + 1.0E-6D) {
                continue;
            }

            int r = node.index / SIZE;
            int c = node.index % SIZE;

            if (Math.abs(r - targetRow) <= PAD_RADIUS
                    && Math.abs(c - targetColumn) <= PAD_RADIUS) {
                goal = node.index;
                break;
            }

            goal = expandDynamicNeighbour(state, node, r - 1, c, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 1, c, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c - 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c + 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);

            // Permit diagonal traversal where two floor cells touch at a corner.
            goal = expandDynamicNeighbour(state, node, r - 1, c - 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r - 1, c + 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 1, c - 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 1, c + 1, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);

            // Permit a two-cell orthogonal edge only when exactly one missing
            // floor cell lies between the two supported endpoint cells.
            goal = expandDynamicNeighbour(state, node, r - 2, c, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 2, c, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c - 2, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c + 2, r, c, targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
        }

        if (goal < 0) {
            /*
             * A dynamic-mob-safe route is preferred, but it must never turn
             * into a permanent "do nothing" state. Monster predictions are
             * deliberately conservative and can temporarily close every
             * predicted route even though the static floor graph is still
             * traversable. Fall back to the shortest static physical route;
             * the live movement controller will re-evaluate the committed edge
             * against fresh mob observations before advancing.
             */
            lastRouteBuildFailureReason = "dynamic-mob-block";
            log(state.worldTick, "[MonsterMazeAI/1.8] DYNAMIC ROUTE EXHAUSTED"
                    + " tick=" + state.worldTick
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " action=STATIC_FALLBACK");

            Arrays.fill(bestArrivalTicks, Double.POSITIVE_INFINITY);
            Arrays.fill(parent, -2);
            open.clear();
            bestArrivalTicks[start] = 0.0D;
            parent[start] = -1;
            open.add(new RouteNode(start, 0.0D,
                    heuristicTicks(startRow, startColumn, targetRow, targetColumn)));

            goal = -1;
            while (!open.isEmpty()) {
                RouteNode node = open.poll();
                if (node.gTicks > bestArrivalTicks[node.index] + 1.0E-6D) continue;

                int r = node.index / SIZE;
                int c = node.index % SIZE;
                if (Math.abs(r - targetRow) <= PAD_RADIUS
                        && Math.abs(c - targetColumn) <= PAD_RADIUS) {
                    goal = node.index;
                    break;
                }

                goal = expandStaticNeighbour(state, node, r - 1, c, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r + 1, c, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r, c - 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r, c + 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r - 1, c - 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r - 1, c + 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r + 1, c - 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r + 1, c + 1, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r - 2, c, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r + 2, c, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r, c - 2, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
                goal = expandStaticNeighbour(state, node, r, c + 2, r, c, targetRow, targetColumn,
                        bestArrivalTicks, parent, open, goal);
            }

            if (goal < 0) {
                lastRouteBuildFailureReason = "static-floor-disconnected";
                log(state.worldTick, "[MonsterMazeAI/1.8] STATIC FALLBACK FAILED"
                        + " tick=" + state.worldTick
                        + " start=" + startRow + "," + startColumn
                        + " pad=" + targetRow + "," + targetColumn
                        + " reason=" + lastRouteBuildFailureReason);
                return false;
            }
            lastRouteBuildFailureReason = "static-fallback";
            log(state.worldTick, "[MonsterMazeAI/1.8] STATIC FALLBACK SUCCESS"
                    + " tick=" + state.worldTick
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " goal=" + (goal / SIZE) + "," + (goal % SIZE));
        }

        int count = 0;
        for (int p = goal; p >= 0; p = parent[p]) {
            count++;
        }

        int[] newRouteRows = new int[count];
        int[] newRouteColumns = new int[count];
        int p = goal;
        for (int i = count - 1; i >= 0; i--) {
            newRouteRows[i] = p / SIZE;
            newRouteColumns[i] = p % SIZE;
            p = parent[p];
        }

        /*
         * activePadTransitionPending is the authoritative transition flag.
         * next() clears targetReached before rebuilding the new route, so using
         * targetReached alone here loses the stationary pre-alignment phase
         * exactly when the server activates the next pad.
         */
        boolean transitioningFromReachedPad = activePadTransitionPending
                || (targetReached && !routeStartsOnPreviousPad);
        boolean initialRouteAlignment = routeLength == 0
                && initialStartPadAvailable
                && state.player.grounded
                && !targetReached;
        boolean mobReplan = routeLength > 0
                && goalRow == targetRow
                && goalColumn == targetColumn
                && !transitioningFromReachedPad;

        if (transitioningFromReachedPad) {
            previousPadCenterRow = goalRow;
            previousPadCenterColumn = goalColumn;
        }
        routeStartsOnPreviousPad = standingOnPreviousPad || transitioningFromReachedPad;
        previousPadSeedRow = routeStartsOnPreviousPad ? startRow : -1;
        previousPadSeedColumn = routeStartsOnPreviousPad ? startColumn : -1;

        /*
         * The A* graph is intentionally conservative and may return a
         * one-cell stair-step around an open corner. At speedrun velocity that
         * creates a physically unnecessary 45 -> 0 -> 90 degree steering
         * sequence while airborne. Collapse only those kinks for which the
         * direct diagonal is independently proven traversable by the same
         * physical-floor rules as the planner. Gaps are never collapsed.
         */
        int smoothedCount = smoothRouteCorners(
                state, newRouteRows, newRouteColumns, count);
        int[] smoothedRows = new int[smoothedCount];
        int[] smoothedColumns = new int[smoothedCount];
        System.arraycopy(newRouteRows, 0, smoothedRows, 0, smoothedCount);
        System.arraycopy(newRouteColumns, 0, smoothedColumns, 0, smoothedCount);

        routeRows = smoothedRows;
        routeColumns = smoothedColumns;
        routeLength = smoothedCount;
        routeIndex = 0;

        /*
         * The initial spawn pad is a real 5x5 SafePad around the start cell.
         * Record its centre once, before any mob replan can change routeRows[0].
         * This is deliberately geometry-only support: it does not make the pad
         * the route target and does not bypass route-edge validation.
         */
        if (firstRoute) {
            initialStartPadAvailable = true;
            initialStartPadCenterRow = startRow;
            initialStartPadCenterColumn = startColumn;
        }
        gapJumpTriggeredRouteIndex = -1;
        gapExecutionActive = false;
        gapTakeoffStarted = false;
        gapExecutionRouteIndex = -1;
        gapLandingConfirmTicks = 0;
        /*
         * Preserve momentum through a mob replan when the selected route keeps
         * the same travel direction. updateGapMomentum() compares the new
         * route direction on the next observation and resets it automatically
         * if the replan introduced a meaningful turn. A genuine pad/stage
         * transition starts a new momentum run.
         */
        if (!mobReplan) {
            resetGapMomentum();
        }
        goalRow = targetRow;
        goalColumn = targetColumn;
        centerX = state.center.x;
        centerZ = state.center.z;
        targetReached = false;
        lastFailedMobReplanTick = Long.MIN_VALUE;

        /*
         * Only a genuine pad-to-pad transition gets stationary alignment.
         * Mob replans start from the player's current heading and therefore
         * must not introduce a competing alignment state.
         */
        aligningForStage = transitioningFromReachedPad || initialRouteAlignment;
        aligningForReplan = false;
        if (!transitioningFromReachedPad
                && state.player.grounded
                && routeLength > 1) {
            double speed = Math.hypot(state.player.vx, state.player.vz);
            if (speed >= 0.12D) {
                double edgeX = routeRows[1] - routeRows[0];
                double edgeZ = routeColumns[1] - routeColumns[0];
                double edgeLength = Math.hypot(edgeX, edgeZ);
                if (edgeLength > 1.0E-9D) {
                    edgeX /= edgeLength;
                    edgeZ /= edgeLength;
                    double velocityX = state.player.vx / speed;
                    double velocityZ = state.player.vz / speed;
                    double headingDot = velocityX * edgeX + velocityZ * edgeZ;
                    if (headingDot < 0.65D) {
                        aligningForReplan = true;
                    }
                }
            }
        }

        if (startedAtTick == Long.MIN_VALUE) {
            startedAtTick = state.worldTick;
        }

        if (lastLoggedStage != state.stage || mobReplan) {
            log(state.worldTick, "[MonsterMazeAI/1.8] PAD ROUTE"
                    + " stage=" + state.stage
                    + " tick=" + state.worldTick
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " length=" + routeLength
                    + " mode=dynamic-mob-safe-A*"
                    + " mobReplan=" + mobReplan);
            lastLoggedStage = state.stage;
        }

        return true;
    }

    private int smoothRouteCorners(
            LegacyWorldObservation state, int[] rows, int[] columns, int count) {
        if (count <= 2) return count;

        int write = 0;
        for (int read = 0; read < count; read++) {
            rows[write] = rows[read];
            columns[write] = columns[read];
            write++;

            while (write >= 3) {
                int a = write - 3;
                int b = write - 2;
                int c = write - 1;
                int dr = Math.abs(rows[c] - rows[a]);
                int dc = Math.abs(columns[c] - columns[a]);

                if (dr == 1 && dc == 1
                        && edgeTypeForCells(rows[a], columns[a], rows[b], columns[b])
                        != EdgeType.ONE_BLOCK_GAP
                        && edgeTypeForCells(rows[b], columns[b], rows[c], columns[c])
                        != EdgeType.ONE_BLOCK_GAP
                        && canTraverseEdge(state,
                                rows[a], columns[a], rows[c], columns[c])) {
                    rows[a + 1] = rows[c];
                    rows[b + 1] = rows[c];
                    write--;
                } else {
                    break;
                }
            }
        }
        return write;
    }

    private EdgeType edgeTypeForCells(int fromRow, int fromColumn,
                                      int toRow, int toColumn) {
        int dr = Math.abs(toRow - fromRow);
        int dc = Math.abs(toColumn - fromColumn);
        if (dr == 2 || dc == 2) return EdgeType.ONE_BLOCK_GAP;
        if (dr == 1 && dc == 1) return EdgeType.DIAGONAL;
        return EdgeType.ORTHOGONAL;
    }

    private int expandStaticNeighbour(LegacyWorldObservation state,
                                       RouteNode node, int r, int c,
                                       int fromRow, int fromColumn,
                                       int targetRow, int targetColumn,
                                       double[] bestArrivalTicks, int[] parent,
                                       PriorityQueue<RouteNode> open, int currentGoal) {
        if (!canTraverseEdge(state, fromRow, fromColumn, r, c)) return currentGoal;
        int next = index(r, c);
        double edgeDistance = Math.hypot(r - fromRow, c - fromColumn);
        double arrivalTicks = node.gTicks + edgeDistance * ESTIMATED_TICKS_PER_CELL;

        /*
         * Only the first edge of a replan is heading-sensitive. Once the
         * player has entered the new route, normal A* geometry takes over.
         * Penalize, rather than absolutely forbid, a first edge that points
         * against current velocity so a genuinely forced turn still remains
         * possible when the graph offers no compatible alternative.
         */
        if (node.gTicks <= 1.0E-9D) {
            double speed = Math.hypot(state.player.vx, state.player.vz);
            if (speed >= REPLAN_HEADING_SPEED_THRESHOLD) {
                double edgeX = (r - fromRow);
                double edgeZ = (c - fromColumn);
                double edgeLength = Math.hypot(edgeX, edgeZ);
                if (edgeLength > 1.0E-9D) {
                    edgeX /= edgeLength;
                    edgeZ /= edgeLength;
                    double velocityX = state.player.vx / speed;
                    double velocityZ = state.player.vz / speed;
                    double headingDot = velocityX * edgeX + velocityZ * edgeZ;
                    if (headingDot < 0.50D) {
                        arrivalTicks += (0.50D - headingDot)
                                * REPLAN_HEADING_PENALTY_TICKS;
                    }
                }
            }
        }
        if (arrivalTicks + 1.0E-6D >= bestArrivalTicks[next]) return currentGoal;
        bestArrivalTicks[next] = arrivalTicks;
        parent[next] = node.index;
        open.add(new RouteNode(next, arrivalTicks,
                arrivalTicks + heuristicTicks(r, c, targetRow, targetColumn)));
        return currentGoal;
    }

    private int expandDynamicNeighbour(LegacyWorldObservation state,
                                       RouteNode node,
                                       int r,
                                       int c,
                                       int fromRow,
                                       int fromColumn,
                                       int targetRow,
                                       int targetColumn,
                                       double[] bestArrivalTicks,
                                       int[] parent,
                                       PriorityQueue<RouteNode> open,
                                       int currentGoal) {
        if (!canTraverseEdge(state, fromRow, fromColumn, r, c)) {
            return currentGoal;
        }

        int next = index(r, c);
        double edgeDistance = Math.hypot(r - fromRow, c - fromColumn);
        double arrivalTicks = node.gTicks + edgeDistance * ESTIMATED_TICKS_PER_CELL;

        /*
         * The start cell may be on the previous Safe Pad during a phase
         * transition, but every newly entered cell must pass both static floor
         * and dynamic monster safety checks.
         */
        if (isMobBlockedAlongEdge(state, fromRow, fromColumn, r, c, node.gTicks)) {
            return currentGoal;
        }

        if (arrivalTicks + 1.0E-6D >= bestArrivalTicks[next]) {
            return currentGoal;        }

        bestArrivalTicks[next] = arrivalTicks;
        parent[next] = node.index;

        double heuristic = heuristicTicks(r, c, targetRow, targetColumn);
        open.add(new RouteNode(next, arrivalTicks, arrivalTicks + heuristic));
        return currentGoal;
    }

    private boolean isMobBlockedAlongEdge(LegacyWorldObservation state,
                                           int fromRow,
                                           int fromColumn,
                                           int toRow,
                                           int toColumn,
                                           double startTicks) {
        /*
         * Do not only test the cell centre at the estimated arrival time.
         * Player and monster can cross between two cell centres during the
         * same sprint. Sample the complete one-cell transition at one-tick
         * intervals so a crossing mob also invalidates the edge.
         */
        double fromX = worldX(fromRow, state.center.x);
        double fromZ = worldZ(fromColumn, state.center.z);
        double toX = worldX(toRow, state.center.x);
        double toZ = worldZ(toColumn, state.center.z);

        double edgeDistance = Math.hypot(toRow - fromRow, toColumn - fromColumn);
        int edgeTicks = Math.max(1, (int) Math.ceil(edgeDistance * ESTIMATED_TICKS_PER_CELL));
        for (int tick = 1; tick <= edgeTicks; tick++) {
            double fraction = tick / (edgeDistance * ESTIMATED_TICKS_PER_CELL);
            if (fraction > 1.0D) {
                fraction = 1.0D;
            }

            double playerX = fromX + (toX - fromX) * fraction;
            double playerZ = fromZ + (toZ - fromZ) * fraction;
            double arrivalTicks = startTicks + tick;

            if (isMobBlockedAtPosition(state, playerX, playerZ, arrivalTicks)) {
                return true;
            }
        }

        return false;
    }

    private boolean isMobBlockedAtPosition(LegacyWorldObservation state,
                                            double playerX,
                                            double playerZ,
                                            double arrivalTicks) {
        if (state.monsters == null || state.monsters.isEmpty()) {
            return false;
        }

        for (LegacyWorldObservation.Monster monster : state.monsters) {
            if (monster.removed) {
                continue;
            }

            /*
             * Only make a moving monster a hard prediction within the horizon
             * we can actually trust. Do not clamp a long route's arrival time
             * to t=30 and then treat that frozen position as the monster's
             * future position indefinitely.
             */
            if (arrivalTicks <= MOB_PREDICT_TICKS) {
                double predictedX = monster.x + monster.vx * arrivalTicks;
                double predictedZ = monster.z + monster.vz * arrivalTicks;

                double dx = predictedX - playerX;
                double dz = predictedZ - playerZ;
                double horizontalDistanceSquared = dx * dx + dz * dz;

                /*
                 * Contact is a hard failure for this benchmark. We intentionally
                 * do not rely on future jump height to declare a mob safe.
                 */
                if (horizontalDistanceSquared < MOB_HAZARD_RADIUS * MOB_HAZARD_RADIUS) {
                    return true;
                }
            } else {
                /*
                 * Beyond the trusted prediction horizon:
                 * - stationary mobs remain hard obstacles;
                 * - moving mobs are not projected indefinitely.
                 *
                 * Fresh observations will cause routeNeedsMobReplan() to
                 * reconsider the route as the player advances.
                 */
                double speed = Math.abs(monster.vx) + Math.abs(monster.vz);
                if (speed < 0.03D) {
                    double currentDx = monster.x - playerX;
                    double currentDz = monster.z - playerZ;
                    if (currentDx * currentDx + currentDz * currentDz
                            < MOB_HAZARD_RADIUS * MOB_HAZARD_RADIUS) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean routeNeedsMobReplan(LegacyWorldObservation state) {
        if (state.monsters == null || state.monsters.isEmpty()
                || routeRows == null || routeLength <= 1
                || routeIndex >= routeLength - 1) {
            return false;
        }

        boolean nearbyMonster = false;
        double rangeSquared = MOB_REPLAN_PLAYER_RANGE * MOB_REPLAN_PLAYER_RANGE;
        for (LegacyWorldObservation.Monster monster : state.monsters) {
            if (monster.removed) continue;
            double dx = monster.x - state.player.x;
            double dz = monster.z - state.player.z;
            if (dx * dx + dz * dz <= rangeSquared) {
                nearbyMonster = true;
                break;
            }
        }
        if (!nearbyMonster) return false;

        int end = Math.min(routeLength - 1,
                routeIndex + MOB_ROUTE_LOOKAHEAD_CELLS);

        /*
         * Only future route cells matter. The current cell may already be
         * inside a mob's envelope because of the previous tick's movement;
         * rebuilding from that cell cannot retroactively undo the collision.
         */
        for (int i = routeIndex + 1; i <= end; i++) {
            double startTicks = (i - routeIndex - 1) * ESTIMATED_TICKS_PER_CELL;
            if (isMobBlockedAlongEdge(state,
                    routeRows[i - 1], routeColumns[i - 1],
                    routeRows[i], routeColumns[i],
                    startTicks)) {
                return true;
            }
        }

        return false;
    }

    private static double heuristicTicks(int row, int column,
                                         int targetRow, int targetColumn) {
        return Math.hypot(row - targetRow, column - targetColumn)
                * ESTIMATED_TICKS_PER_CELL;
    }

    private static final class RouteNode {
        private final int index;
        private final double gTicks;
        private final double fScore;

        private RouteNode(int index, double gTicks, double fScore) {
            this.index = index;
            this.gTicks = gTicks;
            this.fScore = fScore;
        }
    }

    private int safeLookaheadIndex() {
        int target = routeIndex;
        int end = Math.min(routeLength - 1, routeIndex + LOOKAHEAD_CELLS);
        if (routeIndex >= routeLength - 1) return routeIndex;
        int dr0 = routeRows[routeIndex + 1] - routeRows[routeIndex];
        int dc0 = routeColumns[routeIndex + 1] - routeColumns[routeIndex];
        for (int i = routeIndex + 1; i <= end; i++) {
            int dr = routeRows[i] - routeRows[i - 1];
            int dc = routeColumns[i] - routeColumns[i - 1];
            if (dr != dr0 || dc != dc0) break;
            target = i;
        }
        return target;
    }

    private boolean routeNeedsPhysicalReplan(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1 || routeIndex >= routeLength - 1) return false;
        int next = routeIndex + 1;
        if (!routeCellSupported(state, routeIndex)) return true;
        if (!routeCellSupported(state, next)) return true;
        int end = Math.min(routeLength - 1, routeIndex + LOOKAHEAD_CELLS);
        for (int i = routeIndex; i <= end; i++) {
            if (!routeCellSupported(state, i)) return true;
            if (i > routeIndex && !canTraverseEdge(
                    state,
                    routeRows[i - 1], routeColumns[i - 1],
                    routeRows[i], routeColumns[i])) return true;
        }
        return false;
    }

    private int[] findNearestPhysicalStartCell(LegacyWorldObservation state,
                                                    int nominalRow,
                                                    int nominalColumn,
                                                    boolean allowPreviousPadSeed) {
        if (inBounds(nominalRow, nominalColumn)
                && (state.physicalFloor[nominalRow][nominalColumn] || allowPreviousPadSeed)) {
            return new int[] { nominalRow, nominalColumn };
        }

        int bestRow = -1, bestColumn = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        final int searchRadius = 3;
        for (int r = nominalRow - searchRadius; r <= nominalRow + searchRadius; r++) {
            for (int c = nominalColumn - searchRadius; c <= nominalColumn + searchRadius; c++) {
                if (!inBounds(r, c) || !state.physicalFloor[r][c]) continue;
                double dx = state.player.x - worldX(r, state.center.x);
                double dz = state.player.z - worldZ(c, state.center.z);
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance; bestRow = r; bestColumn = c;
                }
            }
        }
        return bestRow < 0 || bestDistance > 9.0D ? null : new int[] {bestRow, bestColumn};
    }

    /*
     * Detect a stale route using the player's continuous footprint rather
     * than the discrete routeIndex. This catches the exact case where the
     * player has crossed beyond the current waypoint before the index update.
     */
    private boolean detectSuddenHorizontalImpulse(LegacyWorldObservation state) {
        double dx = Double.isNaN(previousPlayerX) ? 0.0D : state.player.x - previousPlayerX;
        double dz = Double.isNaN(previousPlayerZ) ? 0.0D : state.player.z - previousPlayerZ;
        double previousVx = previousPlayerVx;
        double previousVz = previousPlayerVz;

        previousPlayerX = state.player.x;
        previousPlayerZ = state.player.z;
        previousPlayerVx = state.player.vx;
        previousPlayerVz = state.player.vz;

        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        double tickDisplacement = Math.hypot(dx, dz);
        if (Double.isNaN(previousVx) || Double.isNaN(previousVz)) return false;

        double acceleration = Math.hypot(
                state.player.vx - previousVx,
                state.player.vz - previousVz);
        double previousSpeed = Math.hypot(previousVx, previousVz);
        double directionDot = previousSpeed > 0.05D && horizontalSpeed > 0.05D
                ? (previousVx * state.player.vx + previousVz * state.player.vz)
                / (previousSpeed * horizontalSpeed)
                : 1.0D;

        /*
         * Ordinary speed-boost movement can legitimately displace ~0.55
         * blocks/tick. Knockback therefore requires an actual velocity
         * discontinuity, a substantially larger speed, or a large one-tick
         * displacement that is beyond the normal speedrun envelope.
         */
        return horizontalSpeed >= KNOCKBACK_HORIZONTAL_SPEED
                || (acceleration >= KNOCKBACK_ACCELERATION && horizontalSpeed >= 0.40D)
                || (tickDisplacement >= KNOCKBACK_TICK_DISPLACEMENT
                    && horizontalSpeed >= 0.40D)
                || (directionDot < -0.35D
                    && acceleration >= 0.18D
                    && previousSpeed >= 0.30D
                    && horizontalSpeed >= 0.30D);
    }

    private boolean routePositionNeedsRecovery(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1 || routeIndex >= routeLength - 1) {
            return false;
        }

        /*
         * Route-position recovery is a grounded re-anchoring operation. An
         * airborne player can legitimately be ahead of the discrete route
         * index and temporarily outside the footprint of the current logical
         * cell; re-anchoring at that instant sends the recovery motor back
         * toward a cell centre and destroys the jump trajectory.
         *
         * Let airborne control finish the current flight. If the player
         * actually leaves the route, the next grounded observation will
         * perform the physical recovery/replan from a valid position.
         */
        if (!state.player.grounded) {
            return false;
        }

        /*
         * While executing an intentional one-block jump, the player's
         * horizontal footprint is expected to be unsupported over the missing
         * middle cell. Do not mistake that airborne span for a recovery event.
         */
        if (isGapTraversalActive(state)) {
            return false;
        }

        /*
         * The player can enter the gap while already airborne because the
         * speed-boost technique chains jumps. In that case the gap need not
         * have been "committed" on a prior grounded tick; the current edge
         * geometry is sufficient to keep the jump transaction alive.
         */
        if (!state.player.grounded && isCurrentEdgeGap(state)) {
            return false;
        }

        if (routeSupportsFootprint(state, state.player.x, state.player.z,
                Math.min(routeIndex + 2, routeLength - 1))) {
            return false;
        }

        /*
         * The player may be physically ahead of routeIndex after a fast
         * diagonal/orthogonal crossing. Keep executing the committed route
         * while the current position is still close to one of its next edges.
         */
        if (routePositionOnCommittedEnvelope(state)) {
            return false;
        }

        /*
         * A grounded player can be physically supported at a cell boundary
         * while the route centreline is more than the airborne envelope
         * tolerance away. Do not discard the committed route in that state:
         * the player still has a real floor beneath the footprint and can
         * correct back onto the route. This is specifically a grounded
         * boundary-crossing case, not permission to continue unsupported.
         */
        if (state.player.grounded
                && physicalFloorSupportsFootprint(state, state.player.x, state.player.z)
                && groundedPositionNearCommittedRoute(state)) {
            return false;
        }

        return true;
    }

    private boolean groundedPositionNearCommittedRoute(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1) return false;

        int last = Math.min(routeLength - 2, routeIndex + 4);
        final double maximumLateralDistance = 1.20D;

        for (int i = routeIndex; i <= last; i++) {
            double ax = worldX(routeRows[i], state.center.x);
            double az = worldZ(routeColumns[i], state.center.z);
            double bx = worldX(routeRows[i + 1], state.center.x);
            double bz = worldZ(routeColumns[i + 1], state.center.z);
            double ex = bx - ax;
            double ez = bz - az;
            double lengthSquared = ex * ex + ez * ez;
            if (lengthSquared <= 1.0E-9D) continue;

            double px = state.player.x - ax;
            double pz = state.player.z - az;
            double progress = (px * ex + pz * ez) / lengthSquared;
            if (progress < -0.50D || progress > 1.50D) continue;

            double lateralX = px - ex * progress;
            double lateralZ = pz - ez * progress;
            if (Math.hypot(lateralX, lateralZ) <= maximumLateralDistance) {
                return true;
            }
        }

        return false;
    }

    private boolean beginRecovery(LegacyWorldObservation state) {
        int[] candidate = findRecoveryCell(state);
        if (candidate == null) {
            return false;
        }
        recoveryRow = candidate[0];
        recoveryColumn = candidate[1];
        recovering = true;
        if (state.worldTick - lastRecoveryLogTick >= 5L) {
            log(state.worldTick, "[MonsterMazeAI/1.8] RECOVERY START"
                    + " tick=" + state.worldTick
                    + " player=" + format(state.player.x) + "," + format(state.player.z)
                    + " cell=" + recoveryRow + "," + recoveryColumn
                    + " reason=route-position-stale");
            lastRecoveryLogTick = state.worldTick;
        }
        return true;
    }

    private int[] findRecoveryCell(LegacyWorldObservation state) {
        int nominalRow = row(state.player.x, state.center.x);
        int nominalColumn = row(state.player.z, state.center.z);
        int bestRow = -1;
        int bestColumn = -1;
        double bestScore = Double.POSITIVE_INFINITY;

        /* Prefer cells already belonging to the selected route, especially
           the cell behind the player that lets recovery move back to safety. */
        for (int i = routeIndex; i < Math.min(routeLength, routeIndex + 4); i++) {
            int r = routeRows[i], c = routeColumns[i];
            if (!routeCellSupported(state, i)) continue;
            double d = Math.hypot(state.player.x - worldX(r, state.center.x),
                    state.player.z - worldZ(c, state.center.z));
            if (d < bestScore) {
                bestScore = d;
                bestRow = r;
                bestColumn = c;
            }
        }

        if (bestRow >= 0) return new int[] {bestRow, bestColumn};

        /* If the route itself has disappeared, use the nearest currently
           observed physical-floor cell. A recovery cell is deliberately
           allowed to be several cells away; this is a recovery operation, not
           the speedrun planner. */
        final int radius = 4;
        for (int r = nominalRow - radius; r <= nominalRow + radius; r++) {
            for (int c = nominalColumn - radius; c <= nominalColumn + radius; c++) {
                if (!inBounds(r, c) || !state.physicalFloor[r][c]) continue;
                double d = Math.hypot(state.player.x - worldX(r, state.center.x),
                        state.player.z - worldZ(c, state.center.z));
                if (d < bestScore) {
                    bestScore = d;
                    bestRow = r;
                    bestColumn = c;
                }
            }
        }
        return bestRow < 0 ? null : new int[] {bestRow, bestColumn};
    }

    private boolean isRecoveryComplete(LegacyWorldObservation state) {
        if (recoveryRow < 0 || recoveryColumn < 0) return true;
        double distance = Math.hypot(state.player.x - worldX(recoveryRow, state.center.x),
                state.player.z - worldZ(recoveryColumn, state.center.z));
        return distance <= 0.30D && physicalFloorCell(state, recoveryRow, recoveryColumn);
    }

    private LegacyAction recoveryAction(LegacyWorldObservation state) {
        if (recoveryRow < 0 || recoveryColumn < 0) return LegacyAction.IDLE;
        float desiredYaw = desiredYawForEdge(
                row(state.player.x, state.center.x),
                row(state.player.z, state.center.z),
                recoveryRow, recoveryColumn);
        double dx = worldX(recoveryRow, state.center.x) - state.player.x;
        double dz = worldZ(recoveryColumn, state.center.z) - state.player.z;
        if (Math.hypot(dx, dz) <= 0.30D) {
            return LegacyAction.IDLE;
        }
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
        if (Math.abs(yawError) > MOVING_YAW_TOLERANCE) {
            return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
        }

        /* Recovery deliberately does not sprint or jump. It is a controlled
           return to a known floor centre before the speedrun resumes. */
        return new LegacyAction(1.0f, 0.0f, false, false, yawDelta, false);
    }

    private boolean physicalFloorCell(LegacyWorldObservation state, int r, int c) {
        return inBounds(r, c) && state.physicalFloor[r][c];
    }

    private boolean canTraverseEdge(LegacyWorldObservation state,
                                    int fromRow, int fromColumn,
                                    int toRow, int toColumn) {
        if (!inBounds(fromRow, fromColumn) || !inBounds(toRow, toColumn)
                || !state.physicalFloor[toRow][toColumn]) {
            return false;
        }

        /*
         * At an active-pad transition the player is still physically standing
         * on the previous SafePad, but ObservationWorldModel intentionally
         * represents the NEW active pad as physicalFloor and may already have
         * restored the old pad's 5x5 area to the underlying maze. The previous
         * pad is nevertheless a real physical launch surface for this route.
         *
         * Allow exactly that synthetic route seed as the source of the first
         * edge. Do not generalise this to arbitrary non-floor cells: every
         * subsequent route node must be ordinary physical floor (or a genuine
         * gap endpoint).
         */
        boolean previousPadSource = routeStartsOnPreviousPad
                && fromRow == previousPadCenterRow
                && fromColumn == previousPadCenterColumn;

        if (!state.physicalFloor[fromRow][fromColumn] && !previousPadSource) {
            return false;
        }

        int dr = toRow - fromRow;
        int dc = toColumn - fromColumn;
        int adr = Math.abs(dr);
        int adc = Math.abs(dc);

        // Orthogonal one-cell movement.
        if (adr + adc == 1) {
            return true;
        }

        /*
         * The authoritative Monster Maze movement graph is cardinal: Maze's
         * movement waypoints are traversed north/south/east/west and the
         * original getTarget() logic never creates diagonal waypoint edges.
         *
         * Do not manufacture diagonal A* edges merely because two adjacent
         * floor cells touch at a corner. A continuous player can visually cut
         * that corner, but the one-cell corridor geometry does not guarantee
         * the 0.60m player footprint remains supported throughout the turn.
         * Keeping the planner cardinal also makes routeIndex, gap ownership,
         * and physical support agree on the same graph.
         */

        // One-block gap: supported endpoint, unsupported middle cell.
        if ((adr == 2 && dc == 0) || (adc == 2 && dr == 0)) {
            int middleRow = fromRow + Integer.signum(dr);
            int middleColumn = fromColumn + Integer.signum(dc);
            return !state.physicalFloor[middleRow][middleColumn];
        }

        return false;
    }

    private boolean isRouteEdgeTraversable(int fromRow, int fromColumn,
                                           int toRow, int toColumn) {
        int dr = Math.abs(toRow - fromRow);
        int dc = Math.abs(toColumn - fromColumn);
        return (dr <= 1 && dc <= 1 && dr + dc > 0)
                || (dr == 2 && dc == 0)
                || (dr == 0 && dc == 2);
    }

    /**
     * Signed progress along the current route edge, measured in block lengths
     * from the source cell centre. For a two-cell gap, the takeoff boundary is
     * exactly +0.5 block from the source centre.
     */
    /**
     * Accumulate actual forward movement along the current route edge. The
     * accumulator deliberately uses observed velocity rather than commanded
     * W input: a player who has been turned sideways, stopped, or knocked
     * around has not earned a gap-jump commitment merely by holding W.
     *
     * The direction is retained across route-index changes and mob replans when
     * the route continues straight. A meaningful route-direction change resets
     * the qualification, which prevents "turn sideways, turn back, jump" from
     * being treated as a three-block straight approach.
     */
    private void updateGapMomentum(LegacyWorldObservation state,
                                    float desiredYaw,
                                    float yawError) {
        if (routeRows == null || routeLength <= 1 || routeIndex >= routeLength - 1) {
            resetGapMomentum();
            return;
        }

        int fromRow = routeRows[routeIndex];
        int fromColumn = routeColumns[routeIndex];
        int toRow = routeRows[routeIndex + 1];
        int toColumn = routeColumns[routeIndex + 1];

        double dx = toRow - fromRow;
        double dz = toColumn - fromColumn;
        double length = Math.hypot(dx, dz);
        if (length <= 1.0E-9D) {
            resetGapMomentum();
            return;
        }
        dx /= length;
        dz /= length;

        if (!Double.isNaN(gapMomentumDirectionX)) {
            double directionDot = gapMomentumDirectionX * dx
                    + gapMomentumDirectionZ * dz;
            double minDirectionDot = Math.cos(Math.toRadians(GAP_MOMENTUM_DIRECTION_TOLERANCE));
            if (directionDot < minDirectionDot) {
                resetGapMomentum();
            }
        }

        gapMomentumDirectionX = dx;
        gapMomentumDirectionZ = dz;
        gapMomentumRouteIndex = routeIndex;

        double forwardVelocity = state.player.vx * dx + state.player.vz * dz;
        double lateralVelocity = Math.abs(state.player.vx * dz - state.player.vz * dx);
        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);

        boolean qualified = state.player.grounded
                && Math.abs(yawError) <= GAP_MOMENTUM_HEADING_TOLERANCE
                && forwardVelocity >= GAP_MOMENTUM_MIN_FORWARD_SPEED
                && lateralVelocity <= GAP_MOMENTUM_LATERAL_SPEED_LIMIT;

        if (qualified) {
            /*
             * Velocity is expressed in blocks/tick by the adapter, so one
             * observation contributes one tick of physical travel. Clamp the
             * contribution to the observed forward component; never count
             * sideways or reverse motion toward the qualification.
             */
            gapQualifiedMomentumDistance += forwardVelocity;
            if (gapQualifiedMomentumDistance > GAP_MOMENTUM_DISTANCE_REQUIRED) {
                gapQualifiedMomentumDistance = GAP_MOMENTUM_DISTANCE_REQUIRED;
            }
        } else if (horizontalSpeed < GAP_MOMENTUM_MIN_FORWARD_SPEED
                || Math.abs(yawError) > GAP_MOMENTUM_HEADING_TOLERANCE
                || lateralVelocity > GAP_MOMENTUM_LATERAL_SPEED_LIMIT
                || forwardVelocity < 0.0D) {
            /*
             * Any meaningful interruption starts the straight-run requirement
             * again. This is intentionally stricter than merely requiring
             * enough instantaneous speed at the jump edge.
             */
            gapQualifiedMomentumDistance = 0.0D;
        }

        if (state.worldTick % GAP_MOMENTUM_LOG_INTERVAL_TICKS == 0L
                && (gapQualifiedMomentumDistance > 0.0D
                || isCurrentEdgeGap(state))) {
            log(state.worldTick, "[MonsterMazeAI/1.8] GAP MOMENTUM"
                    + " tick=" + state.worldTick
                    + " routeIndex=" + routeIndex
                    + " distance=" + format(gapQualifiedMomentumDistance)
                    + "/" + format(GAP_MOMENTUM_DISTANCE_REQUIRED)
                    + " forwardSpeed=" + format(forwardVelocity)
                    + " lateralSpeed=" + format(lateralVelocity)
                    + " headingError=" + format(yawError)
                    + " qualified=" + (gapQualifiedMomentumDistance >= GAP_MOMENTUM_DISTANCE_REQUIRED));
        }
    }

    private boolean hasQualifiedGapMomentum() {
        return gapQualifiedMomentumDistance >= GAP_MOMENTUM_DISTANCE_REQUIRED;
    }

    private void resetGapMomentum() {
        gapQualifiedMomentumDistance = 0.0D;
        gapMomentumDirectionX = Double.NaN;
        gapMomentumDirectionZ = Double.NaN;
        gapMomentumRouteIndex = -1;
    }

    private double currentEdgeProgress(LegacyWorldObservation state) {
        int fromRow = routeRows[routeIndex];
        int fromColumn = routeColumns[routeIndex];
        int toRow = routeRows[routeIndex + 1];
        int toColumn = routeColumns[routeIndex + 1];

        double fromX = worldX(fromRow, state.center.x);
        double fromZ = worldZ(fromColumn, state.center.z);
        double edgeX = toRow - fromRow;
        double edgeZ = toColumn - fromColumn;
        double length = Math.hypot(edgeX, edgeZ);
        if (length <= 1.0E-9D) return 0.0D;

        edgeX /= length;
        edgeZ /= length;

        return (state.player.x - fromX) * edgeX
                + (state.player.z - fromZ) * edgeZ;
    }

    private boolean isGapJumpWindow(LegacyWorldObservation state) {
        /*
         * A genuine one-block gap is a deliberate airborne route edge. The
         * normal movement motor continuously holds W+sprint+Space; the safety
         * layer must therefore never reject the unsupported middle cell.
         * Landing is confirmed separately by advanceRouteIndex().
         */
        return isCurrentEdgeGap(state);
    }

    private boolean isGapTraversalActive(LegacyWorldObservation state) {
        if (!isCurrentEdgeGap(state) || !gapExecutionActive || gapExecutionRouteIndex != routeIndex) {
            return false;
        }
        double progress = currentEdgeProgress(state);
        return gapTakeoffStarted && (!state.player.grounded || progress < GAP_LANDING_PROGRESS);
    }

    private boolean isCurrentEdgeGap(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1 || routeIndex >= routeLength - 1) return false;
        return isGapRouteEdge(routeRows[routeIndex], routeColumns[routeIndex],
                routeRows[routeIndex + 1], routeColumns[routeIndex + 1], state);
    }

    private boolean isGapRouteEdge(int fromRow, int fromColumn,
                                   int toRow, int toColumn,
                                   LegacyWorldObservation state) {
        int dr = toRow - fromRow, dc = toColumn - fromColumn;
        if (!((Math.abs(dr) == 2 && dc == 0) || (Math.abs(dc) == 2 && dr == 0))) return false;
        int middleRow = fromRow + Integer.signum(dr);
        int middleColumn = fromColumn + Integer.signum(dc);
        return physicalFloorCell(state, fromRow, fromColumn)
                && !physicalFloorCell(state, middleRow, middleColumn)
                && physicalFloorCell(state, toRow, toColumn);
    }

    private LegacyAction prepareOrStartGap(LegacyWorldObservation state,
                                            float desiredYaw, float yawError) {
        /*
         * Gap traversal uses the same continuous jump policy as the normal
         * speedrun. There is no runway/momentum gate and no edge-timed IDLE
         * phase. The route edge itself proves that the missing middle cell is
         * intentional.
         */
        if (Math.abs(yawError) > GAP_HEADING_TOLERANCE && state.player.grounded) {
            float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
            return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
        }

        /*
         * Keep the gap policy continuous and gate-free. The route edge itself
         * identifies the intentional missing middle cell; ordinary route
         * control already supplies W+sprint+jump continuously across it.
         * A separate committed transaction is deliberately not entered here
         * until the controller has a physically validated takeoff state.
         */
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
        return new LegacyAction(1.0f, 0.0f, true, true, yawDelta, false);
    }

    private LegacyAction executeCommittedGap(LegacyWorldObservation state) {
        if (!gapExecutionActive || gapExecutionRouteIndex != routeIndex || routeIndex >= routeLength - 1) {
            gapExecutionActive = false; gapTakeoffStarted = false; gapExecutionRouteIndex = -1; gapLandingConfirmTicks = 0;
            return null;
        }
        int fromRow = routeRows[routeIndex], fromColumn = routeColumns[routeIndex];
        int toRow = routeRows[routeIndex + 1], toColumn = routeColumns[routeIndex + 1];
        if (!isGapRouteEdge(fromRow, fromColumn, toRow, toColumn, state)) {
            gapExecutionActive = false; gapTakeoffStarted = false; gapExecutionRouteIndex = -1; gapLandingConfirmTicks = 0;
            return null;
        }

        double progress = currentEdgeProgress(state);
        if (!gapTakeoffStarted && progress >= 0.15D) {
            gapTakeoffStarted = true;
            log(state.worldTick, "[MonsterMazeAI/1.8] GAP TAKEOFF"
                    + " tick=" + state.worldTick + " edge=" + fromRow + "," + fromColumn + "->" + toRow + "," + toColumn
                    + " progress=" + format(progress));
        }

        if (gapTakeoffStarted && state.player.grounded && progress > 0.90D
                && playerFootprintOverlapsCell(state, toRow, toColumn, 0.05D)) {
            gapLandingConfirmTicks++;
            if (gapLandingConfirmTicks >= GAP_LANDING_CONFIRM_TICKS) {
                log(state.worldTick, "[MonsterMazeAI/1.8] GAP LANDING CONFIRMED"
                        + " tick=" + state.worldTick + " edge=" + fromRow + "," + fromColumn + "->" + toRow + "," + toColumn
                        + " progress=" + format(progress));
                gapExecutionActive = false; gapTakeoffStarted = false; gapExecutionRouteIndex = -1; gapLandingConfirmTicks = 0;
                resetGapMomentum();
                routeIndex++;
                if (routeIndex > 0 && routeStartsOnPreviousPad) routeStartsOnPreviousPad = false;
                return new LegacyAction(1.0f, 0.0f, true, true, 0.0f, false);
            }
        } else {
            gapLandingConfirmTicks = 0;
        }

        /*
         * While committed to a gap, never fall back to ordinary route safety.
         * The action remains W+sprint+jump until the landing predicate above
         * succeeds or the edge is irrecoverably missed.
         */
        if (progress > 1.65D) {
            /*
             * Crossing the geometric endpoint while airborne is not a failed
             * gap. The player can still be descending toward the destination
             * block, and rebuilding A* in mid-flight destroys the very jump
             * trajectory that just crossed the gap.
             */
            if (!state.player.grounded) {
                return new LegacyAction(1.0f, 0.0f, true, true, 0.0f, false);
            }

            /*
             * Once grounded, accept the crossing if the player's footprint is
             * physically supported near the destination. Otherwise this is a
             * genuine miss and the normal grounded recovery/replan path may
             * take over.
             */
            double destinationX = worldX(toRow, state.center.x);
            double destinationZ = worldZ(toColumn, state.center.z);
            double destinationDistance = Math.hypot(
                    state.player.x - destinationX,
                    state.player.z - destinationZ);
            if (destinationDistance <= 1.25D
                    && physicalFloorSupportsFootprint(
                    state, state.player.x, state.player.z)) {
                log(state.worldTick, "[MonsterMazeAI/1.8] GAP LANDING CONFIRMED"
                        + " tick=" + state.worldTick
                        + " edge=" + fromRow + "," + fromColumn + "->" + toRow + "," + toColumn
                        + " progress=" + format(progress)
                        + " endpointDistance=" + format(destinationDistance));
                gapExecutionActive = false;
                gapTakeoffStarted = false;
                gapExecutionRouteIndex = -1;
                gapLandingConfirmTicks = 0;
                resetGapMomentum();
                routeIndex++;
                if (routeIndex > 0 && routeStartsOnPreviousPad) {
                    routeStartsOnPreviousPad = false;
                }
                return new LegacyAction(1.0f, 0.0f, true, true, 0.0f, false);
            }

            log(state.worldTick, "[MonsterMazeAI/1.8] GAP LANDING FAILED"
                    + " tick=" + state.worldTick + " edge=" + fromRow + "," + fromColumn + "->" + toRow + "," + toColumn
                    + " progress=" + format(progress) + " grounded=" + state.player.grounded);
            gapExecutionActive = false;
            gapTakeoffStarted = false;
            gapExecutionRouteIndex = -1;
            gapLandingConfirmTicks = 0;
            return null;
        }

        if (state.worldTick % 5L == 0L) log(state.worldTick, "[MonsterMazeAI/1.8] GAP EXECUTE"
                + " tick=" + state.worldTick + " edge=" + fromRow + "," + fromColumn + "->" + toRow + "," + toColumn
                + " progress=" + format(progress) + " grounded=" + state.player.grounded
                + " jumpSpam=" + gapTakeoffStarted);
        // Before takeoff: W+sprint only, preserving a straight grounded approach.
        // From a conservative pre-edge boundary onward: keep Space requested on
        // every grounded observation. At sprint speed a single tick is enough
        // to cross the source block edge, so waiting for exactly +0.50 progress
        // can miss the only grounded jump-input window.
        return new LegacyAction(1.0f, 0.0f, true, true, 0.0f, false);
    }

    private boolean shouldTriggerGapJump(LegacyWorldObservation state) {
        return gapExecutionActive && gapExecutionRouteIndex == routeIndex && gapTakeoffStarted;
    }

    private String movementSafetyFailureReason(LegacyWorldObservation state,
                                                int targetIndex,
                                                float desiredYaw,
                                                float yawError,
                                                float commandedYaw) {
        if (routeIndex >= routeLength - 1) return "route-end";
        /*
         * Heading error is not itself a safety failure. The movement command
         * can rotate and move in the same tick. The hard safety checks below
         * evaluate the direction that will actually be commanded.
         */
        if (Math.abs(state.player.y - state.center.y) > 3.50D) return "vertical";
        if (!state.player.grounded
                && state.player.y < state.center.y - 0.25D
                && !isGapJumpWindow(state)
                && !isGapTraversalActive(state)
                && !routePositionOnCommittedEnvelope(state)) {
            return "vertical";
        }

        int nextIndex = routeIndex + 1;
        int nextRow = routeRows[nextIndex], nextColumn = routeColumns[nextIndex];
        if (!routeCellSupported(state, nextIndex)) return "next-floor";
        if (!canTraverseEdge(state,
                routeRows[routeIndex], routeColumns[routeIndex],
                nextRow, nextColumn)) return "route-disconnected";

        for (int i = routeIndex; i < targetIndex; i++) {
            int r1 = routeRows[i], c1 = routeColumns[i], r2 = routeRows[i + 1], c2 = routeColumns[i + 1];
            if (!routeCellSupported(state, i) || !routeCellSupported(state, i + 1)) return "lookahead-floor";
            if (!canTraverseEdge(state, r1, c1, r2, c2)) return "lookahead-disconnected";
        }

        /*
         * A one-block gap is the one deliberate exception to the normal
         * short forward-floor probe. The middle cell is expected to be empty;
         * once the edge-timed jump is armed/triggered, the player is allowed to
         * cross that unsupported horizontal span until the landing endpoint.
         */
        if (isGapJumpWindow(state)) {
            return null;
        }

        int edgeRow = nextRow - routeRows[routeIndex], edgeColumn = nextColumn - routeColumns[routeIndex];
        double edgeX = edgeRow, edgeZ = edgeColumn;
        double edgeLength = Math.sqrt(edgeX * edgeX + edgeZ * edgeZ);
        edgeX /= edgeLength; edgeZ /= edgeLength;
        /*
         * Evaluate the forward-vector guard against the yaw that will be in
         * effect after this tick's turn command. A moving turn is therefore
         * safe when the post-turn direction is safe, without requiring a
         * full stop merely to rotate.
         */
        double rad = Math.toRadians(commandedYaw);
        double forwardX = -Math.sin(rad), forwardZ = Math.cos(rad);
        double forwardDot = forwardX * edgeX + forwardZ * edgeZ;
        if (forwardDot < EDGE_FORWARD_DOT_MIN) {
            /*
             * A 90-degree cardinal corner is physically traversable without
             * stopping: during the final block the player can safely rotate
             * through a diagonal heading provided the next edge is itself a
             * valid physical edge. Allow that bounded transition band instead
             * of dropping W/Space and destroying the speed-boost jump cycle.
             */
            boolean validCornerTurn = routeIndex + 2 < routeLength
                    && edgeType(routeIndex) != EdgeType.ONE_BLOCK_GAP
                    && edgeType(routeIndex + 1) != EdgeType.ONE_BLOCK_GAP
                    && Math.hypot(
                    state.player.x - worldX(nextRow, state.center.x),
                    state.player.z - worldZ(nextColumn, state.center.z))
                    <= 1.00D
                    && canTraverseEdge(state,
                    nextRow, nextColumn,
                    routeRows[routeIndex + 2], routeColumns[routeIndex + 2]);
            if (!validCornerTurn || forwardDot < 0.70D) {
                return "forward-vector";
            }
        }

        /*
         * Corner lead is allowed to bias the cursor toward the next edge, but
         * it must never redefine the current edge's safety direction. Validate
         * the post-turn heading against the raw immediate edge as well. This
         * prevents a lookahead turn from making an 80-120 degree heading error
         * appear "safe" merely because the desired yaw was smoothed toward the
         * following edge.
         */
        float rawEdgeYaw = desiredYawForEdge(
                routeRows[routeIndex], routeColumns[routeIndex],
                routeRows[nextIndex], routeColumns[nextIndex]);
        if (Math.abs(normalise(rawEdgeYaw - commandedYaw)) > 35.0F) {
            return "forward-vector";
        }

        /*
         * Safety is evaluated against the route surface, not against an
         * artificial four-corner player footprint. Minecraft's 1.8.9 player
         * collision box is continuous and may legitimately straddle logical
         * cell boundaries while the player remains on safe physical floor.
         *
         * The forward probe asks the useful question: if the player advances
         * a small distance in the commanded direction, does the destination
         * cell still contain physical floor? This also works while leaving a
         * Safe Pad, where the current cell may still be the previous pad but
         * the next cell is the first normal maze cell.
         */
        /*
         * The immediate route cell being physical is not sufficient at sprint
         * speed: the player's centre can be near a cell boundary while the
         * current footprint is supported, yet the next physics step can move
         * the complete 0.6m body beyond the corridor. Use a one-tick physical
         * prediction rather than a fixed 0.48m sweep.
         *
         * The probe distance is derived from the observed horizontal velocity
         * plus a small acceleration allowance and is capped just above the
         * simulator's measured 0.28 blocks/tick envelope. This makes the check
         * about the actual next body position, not an arbitrary future point.
         * Intentional one-block gaps have already returned above.
         */
        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        double probeDistance = Math.max(0.20D, Math.min(0.32D, horizontalSpeed + 0.04D));
        double predictedX = state.player.x + forwardX * probeDistance;
        double predictedZ = state.player.z + forwardZ * probeDistance;
        if (state.player.grounded
                && !physicalFloorSupportsFootprint(state, predictedX, predictedZ)
                && !routeSupportsFootprint(state, predictedX, predictedZ,
                Math.min(routeIndex + 2, routeLength - 1))) {
            return "predicted-floor";
        }

        return null;
    }

    /*
     * A Minecraft player is a continuous 0.6 x 0.6 body, not a point locked
     * to one logical maze cell. At a cell boundary the centre can already be
     * in the neighbouring logical cell while the collision box still overlaps
     * the safe block it is leaving.
     *
     * Treat a position as supported when a non-trivial area of the player's
     * horizontal footprint overlaps at least one physical-floor cell. This is
     * deliberately different from requiring every corner to be on floor:
     * every-corner checks reject legitimate boundary traversal and were the
     * source of the old "must be in the middle of the block" deadlock.
     */
    /*
     * SafePad is real physical support. A player can start at its boundary
     * rather than at the centre of the logical start cell; predictive probes
     * must not freeze the controller merely because the next probe has not yet
     * overlapped that one route cell. Geometry matches SafePad.isOn: +/-2.5.
     *
     * state.pad is the ACTIVE DESTINATION pad, not the spawn pad. The initial
     * spawn pad therefore needs its own recorded centre. Without that
     * distinction, a spawn at (1.00,1.00) with a diagonal first edge can probe
     * into z<1.00, miss the logical 50,50 cell, and be incorrectly held by
     * "predicted-floor" even though the real 5x5 spawn pad supports it.
     */
    private boolean safePadSupportsFootprint(int padRow, int padColumn,
                                              LegacyWorldObservation state,
                                              double x, double z) {
        if (padRow < 0 || padColumn < 0) return false;

        double padCenterX = (state.center.x - 49) + padRow + 0.5D;
        double padCenterZ = (state.center.z - 49) + padColumn + 0.5D;
        double minX = x - PLAYER_HALF_WIDTH, maxX = x + PLAYER_HALF_WIDTH;
        double minZ = z - PLAYER_HALF_WIDTH, maxZ = z + PLAYER_HALF_WIDTH;
        double overlapX = Math.min(maxX, padCenterX + 2.5D) - Math.max(minX, padCenterX - 2.5D);
        double overlapZ = Math.min(maxZ, padCenterZ + 2.5D) - Math.max(minZ, padCenterZ - 2.5D);
        return overlapX > 0.0D && overlapZ > 0.0D && overlapX * overlapZ >= 0.05D;
    }

    private boolean activeSafePadSupportsFootprint(LegacyWorldObservation state,
                                                    double x, double z) {
        if (state.pad != null && state.pad.row >= 0 && state.pad.column >= 0
                && safePadSupportsFootprint(state.pad.row, state.pad.column, state, x, z)) {
            return true;
        }

        if (initialStartPadAvailable
                && safePadSupportsFootprint(
                initialStartPadCenterRow, initialStartPadCenterColumn, state, x, z)) {
            return true;
        }

        return false;
    }

    private boolean routeSupportsFootprint(LegacyWorldObservation state,
                                            double x, double z,
                                            int maxRouteIndex) {
        double minX = x - PLAYER_HALF_WIDTH;
        double maxX = x + PLAYER_HALF_WIDTH;
        double minZ = z - PLAYER_HALF_WIDTH;
        double maxZ = z + PLAYER_HALF_WIDTH;

        if (activeSafePadSupportsFootprint(state, x, z)) return true;

        int minRow = row(minX, state.center.x);
        int maxRow = row(maxX - 1.0E-9D, state.center.x);
        int minColumn = row(minZ, state.center.z);
        int maxColumn = row(maxZ - 1.0E-9D, state.center.z);

        int end = Math.min(maxRouteIndex, routeLength - 1);
        for (int i = routeIndex; i <= end; i++) {
            if (!routeCellSupported(state, i)) continue;

            int r = routeRows[i];
            int c = routeColumns[i];
            double cellMinX = (state.center.x - 49) + r;
            double cellMaxX = cellMinX + 1.0D;
            double cellMinZ = (state.center.z - 49) + c;
            double cellMaxZ = cellMinZ + 1.0D;

            double overlapX = Math.min(maxX, cellMaxX) - Math.max(minX, cellMinX);
            double overlapZ = Math.min(maxZ, cellMaxZ) - Math.max(minZ, cellMinZ);
            double minimumArea = edgeType(routeIndex) == EdgeType.DIAGONAL
                    ? DIAGONAL_SUPPORT_MIN_AREA : 0.05D;
            if (overlapX > 0.0D && overlapZ > 0.0D
                    && overlapX * overlapZ >= minimumArea) {
                return true;
            }
        }
        return false;
    }

    private boolean routeCellSupported(LegacyWorldObservation state, int index) {
        if (index < 0 || index >= routeLength) return false;
        if (physicalFloorCell(state, routeRows[index], routeColumns[index])) return true;
        return index == 0
                && routeStartsOnPreviousPad
                && previousPadCenterRow >= 0
                && previousPadCenterColumn >= 0
                && Math.abs(routeRows[index] - previousPadCenterRow) <= PAD_RADIUS
                && Math.abs(routeColumns[index] - previousPadCenterColumn) <= PAD_RADIUS;
    }

    private boolean isPreviousPadSeedAvailable() {
        return routeStartsOnPreviousPad
                && previousPadCenterRow >= 0
                && previousPadCenterColumn >= 0;
    }

    private boolean physicalFloorSupportsFootprint(LegacyWorldObservation state,
                                                    double x, double z) {
        double minX = x - PLAYER_HALF_WIDTH;
        double maxX = x + PLAYER_HALF_WIDTH;
        double minZ = z - PLAYER_HALF_WIDTH;
        double maxZ = z + PLAYER_HALF_WIDTH;

        int minRow = row(minX, state.center.x);
        int maxRow = row(maxX - 1.0E-9D, state.center.x);
        int minColumn = row(minZ, state.center.z);
        int maxColumn = row(maxZ - 1.0E-9D, state.center.z);

        final double minimumSupportArea = 0.05D;

        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minColumn; c <= maxColumn; c++) {
                if (!physicalFloorCell(state, r, c)) continue;

                double cellMinX = (state.center.x - 49) + r;
                double cellMaxX = cellMinX + 1.0D;
                double cellMinZ = (state.center.z - 49) + c;
                double cellMaxZ = cellMinZ + 1.0D;

                double overlapX = Math.min(maxX, cellMaxX) - Math.max(minX, cellMinX);
                double overlapZ = Math.min(maxZ, cellMaxZ) - Math.max(minZ, cellMinZ);
                if (overlapX > 0.0D && overlapZ > 0.0D
                        && overlapX * overlapZ >= minimumSupportArea) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean movementSafetyAllowsForward(LegacyWorldObservation state,
                                                  int targetIndex,
                                                  float desiredYaw,
                                                  float yawError) {
        float yawDelta = clamp(normalise(desiredYaw - state.player.yaw), -MAX_YAW_STEP, MAX_YAW_STEP);
        float commandedYaw = normalise(state.player.yaw + yawDelta);
        return movementSafetyFailureReason(
                state, targetIndex, desiredYaw, yawError, commandedYaw) == null;
    }

    private int findBestRouteIndexForCurrentPosition(LegacyWorldObservation state,
                                                       int[] rows, int[] columns, int length) {
        if (rows == null || columns == null || length <= 0) return 0;
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < length; i++) {
            double d = Math.hypot(state.player.x - worldX(rows[i], state.center.x),
                    state.player.z - worldZ(columns[i], state.center.z));
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void advanceRouteIndex(LegacyWorldObservation state) {
        /*
         * A committed one-block gap owns the route edge until its landing is
         * confirmed. The generic waypoint capture logic must not advance the
         * route index across the missing cell: doing so invalidates
         * gapExecutionRouteIndex on the following tick and lets normal
         * replanning take over while the player is airborne.
         */
        if (gapExecutionActive && gapExecutionRouteIndex == routeIndex) {
            return;
        }

        /*
         * A genuine one-block gap owns its edge before commitment as well.
         * Generic waypoint/overshoot capture must not advance across the
         * unsupported middle block, otherwise the gap controller never gets a
         * chance to arm the jump and the route is re-indexed onto the landing
         * side while the player is still airborne.
         */
        if (isCurrentEdgeGap(state)) {
            /*
             * The gap edge is owned until its landing is actually observed.
             * While airborne, never advance the route index onto the landing
             * cell early; that would let ordinary route capture/replanning
             * outrun the physical jump.
             */
            if (state.player.grounded
                    && routeEdgeHasPhysicalCapture(state, routeIndex + 1)) {
                routeIndex++;
                if (routeStartsOnPreviousPad) routeStartsOnPreviousPad = false;
                resetGapMomentum();
            }
            return;
        }

        /*
         * The speedrun can move roughly half a block per client tick. A
         * one-cell waypoint can therefore be crossed between observations.
         * Route progress is edge-based: once the player has clearly passed a
         * waypoint along the committed edge, do not force the controller to
         * "recover" back to that waypoint.
         *
         * A normal capture uses the existing centre/footprint test. An
         * overshoot capture additionally accepts a small positive footprint
         * overlap when the player is beyond the waypoint along the same edge.
         * This is particularly important for diagonal corner-to-corner
         * traversal, where the overlap with the destination block can be
         * intentionally tiny.
         */
        while (routeIndex < routeLength - 1) {
            double ax = worldX(routeRows[routeIndex], state.center.x);
            double az = worldZ(routeColumns[routeIndex], state.center.z);
            double bx = worldX(routeRows[routeIndex + 1], state.center.x);
            double bz = worldZ(routeColumns[routeIndex + 1], state.center.z);
            double ex = bx - ax, ez = bz - az;
            double lengthSquared = ex * ex + ez * ez;
            if (lengthSquared <= 1.0E-9D) {
                routeIndex++;
                continue;
            }

            double px = state.player.x - ax, pz = state.player.z - az;
            double progress = (px * ex + pz * ez) / lengthSquared;
            double lateralX = px - ex * progress;
            double lateralZ = pz - ez * progress;
            double lateralDistance = Math.hypot(lateralX, lateralZ);
            double distanceToNext = Math.hypot(state.player.x - bx, state.player.z - bz);

            boolean normalCapture = (progress >= ROUTE_ADVANCE_PROGRESS
                    || distanceToNext <= ROUTE_WAYPOINT_CAPTURE_RADIUS)
                    && routeEdgeHasPhysicalCapture(state, routeIndex + 1);

            boolean overshootCapture = progress >= 1.0D
                    && lateralDistance <= ROUTE_EDGE_LATERAL_TOLERANCE
                    && (playerFootprintOverlapsCell(
                    state, routeRows[routeIndex + 1], routeColumns[routeIndex + 1],
                    edgeType(routeIndex) == EdgeType.DIAGONAL
                            ? DIAGONAL_SUPPORT_MIN_AREA : 0.01D)
                    || physicalFloorSupportsFootprint(state, state.player.x, state.player.z));

            if (normalCapture || overshootCapture) {
                routeIndex++;
                if (overshootCapture && !normalCapture) {
                    log(state.worldTick, "[MonsterMazeAI/1.8] EDGE OVERSHOOT CAPTURE"
                            + " tick=" + state.worldTick
                            + " edge=" + edgeType(routeIndex - 1)
                            + " routeIndex=" + routeIndex
                            + " player=" + format(state.player.x) + "," + format(state.player.z));
                }
                if (routeIndex > 0 && routeStartsOnPreviousPad) {
                    routeStartsOnPreviousPad = false;
                }
            } else {
                break;
            }
        }

        /*
         * Fast corner crossings can leave the player materially closer to a
         * later route waypoint than to the current one without ever producing
         * the exact edge projection required by the normal capture predicate.
         * Re-index forward in that case. This is deliberately bounded and
         * never skips a committed one-block gap; it only prevents the motor
         * from fighting a corner that the player has already physically passed.
         */
        if (!gapExecutionActive && routeIndex < routeLength - 1) {
            double currentDistance = Math.hypot(
                    state.player.x - worldX(routeRows[routeIndex], state.center.x),
                    state.player.z - worldZ(routeColumns[routeIndex], state.center.z));
            int bestIndex = routeIndex;
            double bestDistance = currentDistance;

            int end = Math.min(routeLength - 1,
                    routeIndex + ROUTE_NEAREST_CAPTURE_LOOKAHEAD);
            for (int candidate = routeIndex + 1; candidate <= end; candidate++) {
                boolean crossesGap = false;
                for (int edge = routeIndex; edge < candidate; edge++) {
                    if (edgeType(edge) == EdgeType.ONE_BLOCK_GAP) {
                        crossesGap = true;
                        break;
                    }
                }
                if (crossesGap || !routeCellSupported(state, candidate)) continue;

                double distance = Math.hypot(
                        state.player.x - worldX(routeRows[candidate], state.center.x),
                        state.player.z - worldZ(routeColumns[candidate], state.center.z));
                if (distance <= ROUTE_NEAREST_CAPTURE_RADIUS
                        && distance + 0.05D < bestDistance) {
                    bestDistance = distance;
                    bestIndex = candidate;
                }
            }

            /*
             * A nearest-cell match is not sufficient at sprint speed, but the
             * converse matters too: routeSupportsFootprint() intentionally
             * accepts ANY currently committed route cell, so using it here can
             * advance onto a future waypoint while the player's body is still
             * supported only by the previous cell. Require actual footprint
             * capture of the selected candidate itself.
             */
            if (bestIndex > routeIndex
                    && playerFootprintOverlapsCell(
                    state, routeRows[bestIndex], routeColumns[bestIndex], 0.05D)) {
                int oldIndex = routeIndex;
                routeIndex = bestIndex;
                if (routeStartsOnPreviousPad) routeStartsOnPreviousPad = false;
                log(state.worldTick, "[MonsterMazeAI/1.8] FORWARD ROUTE REINDEX"
                        + " tick=" + state.worldTick
                        + " oldIndex=" + oldIndex
                        + " newIndex=" + routeIndex
                        + " distance=" + format(bestDistance)
                        + " player=" + format(state.player.x) + "," + format(state.player.z));
            }
        }
    }

    private boolean routeEdgeHasPhysicalCapture(LegacyWorldObservation state, int nextIndex) {
        if (nextIndex < 0 || nextIndex >= routeLength) return false;

        double x = worldX(routeRows[nextIndex], state.center.x);
        double z = worldZ(routeColumns[nextIndex], state.center.z);
        double dx = state.player.x - x;
        double dz = state.player.z - z;

        /*
         * This predicate answers "has the PLAYER captured the next waypoint?",
         * not "does the waypoint itself have physical floor?". The old fallback
         * checked physicalFloorSupportsFootprint() at the waypoint coordinates,
         * which is true for every valid route cell regardless of where the
         * player actually is. That allowed routeIndex to advance while the
         * player was still a block short of the pad.
         */
        if (dx * dx + dz * dz <= ROUTE_WAYPOINT_CAPTURE_RADIUS * ROUTE_WAYPOINT_CAPTURE_RADIUS) {
            return true;
        }

        return playerFootprintOverlapsCell(
                state, routeRows[nextIndex], routeColumns[nextIndex], 0.05D);
    }

    private float routeTrackingYaw(LegacyWorldObservation state, float edgeYaw) {
        if (routeRows == null || routeColumns == null
                || routeIndex < 0 || routeIndex >= routeLength - 1
                || edgeType(routeIndex) == EdgeType.ONE_BLOCK_GAP) {
            return edgeYaw;
        }

        double ax = worldX(routeRows[routeIndex], state.center.x);
        double az = worldZ(routeColumns[routeIndex], state.center.z);
        double bx = worldX(routeRows[routeIndex + 1], state.center.x);
        double bz = worldZ(routeColumns[routeIndex + 1], state.center.z);
        double ex = bx - ax;
        double ez = bz - az;
        double length = Math.hypot(ex, ez);
        if (length <= 1.0E-9D) return edgeYaw;
        ex /= length;
        ez /= length;

        double px = state.player.x - ax;
        double pz = state.player.z - az;
        double progress = px * ex + pz * ez;
        double clampedProgress = clampDouble(progress, 0.0D, length);
        double closestX = ax + ex * clampedProgress;
        double closestZ = az + ez * clampedProgress;
        double lateralX = closestX - state.player.x;
        double lateralZ = closestZ - state.player.z;
        double lateralDistance = Math.hypot(lateralX, lateralZ);

        /*
         * Pure-pursuit style correction: keep a small forward lookahead while
         * biasing toward the route centreline. Limit the lateral correction so
         * it cannot turn a straight corridor into a diagonal cut.
         */
        float trackedEdgeYaw = edgeYaw;
        double distanceToWaypoint = Math.hypot(
                state.player.x - bx, state.player.z - bz);

        /*
         * The planner remains cardinal, but the continuous player can begin a
         * turn before the exact waypoint centre. Blend toward the NEXT cardinal
         * edge over the final ~1.75 blocks instead of issuing a discrete
         * +/-30-degree corner pulse. At the waypoint the blend reaches 50%
         * rather than demanding an instantaneous 90-degree turn; route capture
         * then completes the turn on the following observations.
         */
        if (routeIndex + 2 < routeLength
                && edgeType(routeIndex) != EdgeType.ONE_BLOCK_GAP
                && edgeType(routeIndex + 1) != EdgeType.ONE_BLOCK_GAP
                && distanceToWaypoint < 3.00D) {
            float nextEdgeYaw = desiredYawForEdge(
                    routeRows[routeIndex + 1], routeColumns[routeIndex + 1],
                    routeRows[routeIndex + 2], routeColumns[routeIndex + 2]);
            float turn = normalise(nextEdgeYaw - edgeYaw);
            double blend = clampDouble((3.00D - distanceToWaypoint) / 1.50D, 0.0D, 1.0D);
            trackedEdgeYaw = normalise(edgeYaw
                    + clamp((float) (turn * 0.85D * blend), -45.0F, 45.0F));
        }

        if (lateralDistance < 0.30D) return trackedEdgeYaw;

        double lookahead = Math.min(1.15D, Math.max(0.45D, length * 0.75D));
        double targetX = closestX + ex * lookahead;
        double targetZ = closestZ + ez * lookahead;
        double correctionWeight = Math.min(1.0D, lateralDistance / 0.85D);
        targetX += lateralX * correctionWeight;
        targetZ += lateralZ * correctionWeight;

        float targetYaw = (float) Math.toDegrees(
                Math.atan2(-(targetX - state.player.x),
                        targetZ - state.player.z));
        float correction = normalise(targetYaw - trackedEdgeYaw);
        correction = clamp(correction, -25.0F, 25.0F);
        return normalise(trackedEdgeYaw + correction);
    }

    private float airborneCornerYaw(LegacyWorldObservation state, float currentEdgeYaw) {
        if (routeRows == null || routeColumns == null
                || routeIndex < 0 || routeIndex >= routeLength - 1
                || gapExecutionActive) {
            return currentEdgeYaw;
        }

        double ax = worldX(routeRows[routeIndex], state.center.x);
        double az = worldZ(routeColumns[routeIndex], state.center.z);
        double bx = worldX(routeRows[routeIndex + 1], state.center.x);
        double bz = worldZ(routeColumns[routeIndex + 1], state.center.z);
        double ex = bx - ax;
        double ez = bz - az;
        double lengthSquared = ex * ex + ez * ez;
        if (lengthSquared <= 1.0E-9D) return currentEdgeYaw;

        double px = state.player.x - ax;
        double pz = state.player.z - az;
        double progress = (px * ex + pz * ez) / lengthSquared;
        double lateralX = px - ex * progress;
        double lateralZ = pz - ez * progress;
        double lateralDistance = Math.hypot(lateralX, lateralZ);
        double distanceToNext = Math.hypot(state.player.x - bx, state.player.z - bz);

        /*
         * When sprint-jump momentum carries the player laterally around a
         * corner, continuing to face the old discrete edge can make the motor
         * fly past the next corridor and then trigger recovery. During flight
         * the safest correction is the next waypoint itself: steer toward the
         * physical destination while preserving forward input.
         */
        if (lateralDistance <= 0.55D || distanceToNext > 2.50D) {
            return currentEdgeYaw;
        }

        float targetYaw = (float) Math.toDegrees(
                Math.atan2(-(bx - state.player.x), bz - state.player.z));
        float turn = normalise(targetYaw - currentEdgeYaw);
        if (Math.abs(turn) <= 5.0F) return currentEdgeYaw;

        return normalise(currentEdgeYaw
                + clamp(turn, -MAX_YAW_STEP, MAX_YAW_STEP));
    }

    private float cornerLeadYaw(LegacyWorldObservation state, float currentEdgeYaw) {
        if (routeRows == null || routeColumns == null
                || routeIndex < 0 || routeIndex + 2 >= routeLength) {
            return currentEdgeYaw;
        }
        if (edgeType(routeIndex) == EdgeType.ONE_BLOCK_GAP) {
            return currentEdgeYaw;
        }

        double ax = worldX(routeRows[routeIndex], state.center.x);
        double az = worldZ(routeColumns[routeIndex], state.center.z);
        double bx = worldX(routeRows[routeIndex + 1], state.center.x);
        double bz = worldZ(routeColumns[routeIndex + 1], state.center.z);
        double ex = bx - ax;
        double ez = bz - az;
        double lengthSquared = ex * ex + ez * ez;
        if (lengthSquared <= 1.0E-9D) return currentEdgeYaw;

        double px = state.player.x - ax;
        double pz = state.player.z - az;
        double progress = (px * ex + pz * ez) / lengthSquared;
        double distanceToWaypoint = Math.hypot(state.player.x - bx, state.player.z - bz);

        /*
         * Only begin the lead once the player is genuinely near the waypoint.
         * Starting at 45% edge progress let the 30-degree cursor budget fight
         * the current edge for several ticks at sprint speed. The resulting
         * +/-30-degree oscillation could push the continuous player body off a
         * one-cell corridor even though both discrete route edges were valid.
         * A near-waypoint lead preserves the speed advantage without making the
         * current edge compete with the following edge too early.
         */
        if (progress < 0.65D && distanceToWaypoint > 0.80D) {
            return currentEdgeYaw;
        }

        float nextYaw = desiredYawForEdge(
                routeRows[routeIndex + 1], routeColumns[routeIndex + 1],
                routeRows[routeIndex + 2], routeColumns[routeIndex + 2]);
        float turn = normalise(nextYaw - currentEdgeYaw);
        if (Math.abs(turn) < 5.0F) return currentEdgeYaw;

        float lead = clamp(turn, -MAX_YAW_STEP, MAX_YAW_STEP);
        return normalise(currentEdgeYaw + lead);
    }

    private EdgeType edgeType(int index) {
        if (index < 0 || index >= routeLength - 1) return EdgeType.ORTHOGONAL;
        int dr = Math.abs(routeRows[index + 1] - routeRows[index]);
        int dc = Math.abs(routeColumns[index + 1] - routeColumns[index]);
        if (dr == 2 || dc == 2) return EdgeType.ONE_BLOCK_GAP;
        if (dr == 1 && dc == 1) return EdgeType.DIAGONAL;
        return EdgeType.ORTHOGONAL;
    }

    private boolean routePositionOnCommittedEnvelope(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1) return false;
        int last = Math.min(routeLength - 2, routeIndex + 5);
        for (int i = routeIndex; i <= last; i++) {
            double ax = worldX(routeRows[i], state.center.x);
            double az = worldZ(routeColumns[i], state.center.z);
            double bx = worldX(routeRows[i + 1], state.center.x);
            double bz = worldZ(routeColumns[i + 1], state.center.z);
            double ex = bx - ax, ez = bz - az;
            double lengthSquared = ex * ex + ez * ez;
            if (lengthSquared <= 1.0E-9D) continue;
            double px = state.player.x - ax, pz = state.player.z - az;
            double progress = (px * ex + pz * ez) / lengthSquared;
            if (progress < -0.35D || progress > 1.75D) continue;
            double lateralX = px - ex * progress;
            double lateralZ = pz - ez * progress;
            if (Math.hypot(lateralX, lateralZ) > ROUTE_EDGE_LATERAL_TOLERANCE) continue;

            double minimumArea = edgeType(i) == EdgeType.DIAGONAL
                    ? DIAGONAL_SUPPORT_MIN_AREA : 0.01D;
            if (playerFootprintOverlapsCell(
                    state, routeRows[i], routeColumns[i], minimumArea)
                    || playerFootprintOverlapsCell(
                    state, routeRows[i + 1], routeColumns[i + 1], minimumArea)
                    || physicalFloorSupportsFootprint(state, state.player.x, state.player.z)) {
                return true;
            }
        }
        return false;
    }

    private boolean playerFootprintOverlapsCell(LegacyWorldObservation state,
                                                 int targetRow, int targetColumn,
                                                 double minimumArea) {
        double minX = state.player.x - PLAYER_HALF_WIDTH;
        double maxX = state.player.x + PLAYER_HALF_WIDTH;
        double minZ = state.player.z - PLAYER_HALF_WIDTH;
        double maxZ = state.player.z + PLAYER_HALF_WIDTH;

        double cellMinX = (state.center.x - 49) + targetRow;
        double cellMaxX = cellMinX + 1.0D;
        double cellMinZ = (state.center.z - 49) + targetColumn;
        double cellMaxZ = cellMinZ + 1.0D;

        double overlapX = Math.min(maxX, cellMaxX) - Math.max(minX, cellMinX);
        double overlapZ = Math.min(maxZ, cellMaxZ) - Math.max(minZ, cellMinZ);
        return overlapX > 0.0D && overlapZ > 0.0D
                && overlapX * overlapZ >= minimumArea;
    }

    private LegacyAction finalPadApproachAction(LegacyWorldObservation state,
                                                   double padCenterX,
                                                   double padCenterZ,
                                                   double padDistance) {
        if (state.pad.reached || isInsidePad(state)) {
            return LegacyAction.IDLE;
        }

        if (padDistance > 3.50D) {
            return LegacyAction.IDLE;
        }

        double dx = padCenterX - state.player.x;
        double dz = padCenterZ - state.player.z;
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = normalise(desiredYaw - state.player.yaw);

        if (Math.abs(yawError) > MOVING_YAW_TOLERANCE) {
            float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
            if (state.worldTick % 5L == 0L) {
                log(state.worldTick, "[MonsterMazeAI/1.8] PAD FINAL ALIGN"
                        + " tick=" + state.worldTick
                        + " pad=" + state.pad.row + "," + state.pad.column
                        + " distance=" + format(padDistance)
                        + " yawError=" + format(yawError));
            }
            return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
        }

        double rad = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(rad);
        double forwardZ = Math.cos(rad);
        if (!physicalFloorSupportsFootprint(
                state,
                state.player.x + forwardX * SAFETY_PROBE_DISTANCE,
                state.player.z + forwardZ * SAFETY_PROBE_DISTANCE)) {
            if (beginRecovery(state)) {
                return recoveryAction(state);
            }
            return LegacyAction.IDLE;
        }

        boolean jumpPulse = (state.worldTick & 1L) == 0L;
        if (state.worldTick % 5L == 0L) {
            log(state.worldTick, "[MonsterMazeAI/1.8] PAD FINAL APPROACH"
                    + " tick=" + state.worldTick
                    + " pad=" + state.pad.row + "," + state.pad.column
                    + " distance=" + format(padDistance)
                    + " yaw=" + format(state.player.yaw));
        }
        return new LegacyAction(1.0f, 0.0f, jumpPulse, true, 0.0f, false);
    }

    private boolean isInsidePad(LegacyWorldObservation state) {
        // MazeGenerator constructs SafePad from the centre of the target
        // cell (block coordinate + 0.5). Mirror SafePad.isOn() exactly; using
        // the integer block corner creates a half-block X/Z reachability error.
        double padCenterX = (state.center.x - 49) + state.pad.row + 0.5D;
        double padCenterZ = (state.center.z - 49) + state.pad.column + 0.5D;
        double dx = state.player.x - padCenterX;
        double dz = state.player.z - padCenterZ;
        double baseY = state.center.y - 1.0D;
        return dx > -2.5D && dx < 2.5D
                && dz > -2.5D && dz < 2.5D
                && state.player.y > baseY
                && state.player.y < baseY + 5.0D;
    }


    private int firstRouteHeadingIndex() {
        if (routeLength <= 1) {
            return 0;
        }

        int startRow = routeRows[0];
        int startColumn = routeColumns[0];

        for (int i = 1; i < routeLength; i++) {
            if (routeRows[i] != startRow || routeColumns[i] != startColumn) {
                return i;
            }
        }

        return Math.min(1, routeLength - 1);
    }

    private static float desiredYawForEdge(int fromRow, int fromColumn,
                                             int toRow, int toColumn) {
        double dx = toRow - fromRow;
        double dz = toColumn - fromColumn;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static int row(double world, int center) {
        return (int) Math.floor(world - (center - 49));
    }

    private static double worldX(int routeRow, int centerX) {
        return (centerX - 49) + routeRow + 0.5D;
    }

    private static double worldZ(int routeColumn, int centerZ) {
        return (centerZ - 49) + routeColumn + 0.5D;
    }

    private static int index(int r, int c) {
        return r * SIZE + c;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private static float normalise(float angle) {
        while (angle > 180.0F) angle -= 360.0F;
        while (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}