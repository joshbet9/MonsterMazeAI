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
    /* Testing-only safety mode: turn in place before forward input. Keep this
       isolated so the long-term moving controller can remove it cleanly. */
    private static final boolean TEST_STATIONARY_TURNING = true;
    private static final float MOVING_YAW_TOLERANCE = 12.0F;
    private static final int HEADING_STABLE_TICKS = 0;
    private static final double SAFETY_PROBE_DISTANCE = 0.48D;
    private static final double SAFETY_SWEEP_STEP = 0.10D;
    private static final double PLAYER_HALF_WIDTH = 0.30D;
    private static final double ROUTE_ADVANCE_PROGRESS = 0.80D;
    private static final double ROUTE_WAYPOINT_CAPTURE_RADIUS = 0.65D;
    private static final int MOB_REPLAN_RETRY_TICKS = 10;
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
    private static final int MOB_ROUTE_LOOKAHEAD_CELLS = 18;

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
    private int lastLoggedStage = -1;
    private long startedAtTick = Long.MIN_VALUE;
    private int headingStableTicks;
    private long lastFailedMobReplanTick = Long.MIN_VALUE;
    private boolean routeStartsOnPreviousPad;
    private int previousPadSeedRow = -1;
    private int previousPadSeedColumn = -1;
    private int previousPadCenterRow = -1;
    private int previousPadCenterColumn = -1;
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
    private boolean knockbackRecoveryPending;
    private long lastKnockbackRecoveryTick = Long.MIN_VALUE;
    private static final double KNOCKBACK_HORIZONTAL_SPEED = 0.35D;
    private static final double KNOCKBACK_TICK_DISPLACEMENT = 0.45D;
    private static final int KNOCKBACK_RECOVERY_COOLDOWN_TICKS = 8;

    public LegacyAction next(LegacyWorldObservation state) {
        if (state == null || !state.inMonsterMaze || !state.mazeDetected
                || !state.alive || state.completed || state.center == null
                || state.pad == null || state.pad.row < 0 || state.pad.column < 0) {
            reset();
            return LegacyAction.IDLE;
        }

        boolean atTarget = state.pad.reached || isInsidePad(state);
        boolean suddenHorizontalImpulse = detectSuddenHorizontalImpulse(state);

        /*
         * A mob can knock the player off an otherwise valid SafePad while the
         * server is still in the same phase. targetReached is not permission
         * to remain idle forever: if the player leaves the pad, the controller
         * must resume from the player's actual position. This is especially
         * important when the pad subsequently deteriorates and its physical
         * 5x5 surface is restored to the underlying maze.
         */
        if (targetReached && !atTarget) {
            targetReached = false;
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;
            System.out.println("[MonsterMazeAI/1.8] PAD EXIT RECOVERY"
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
                System.out.println("[MonsterMazeAI/1.8] PAD REACHED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " elapsedTicks=" + elapsed
                        + " routeLength=" + routeLength);
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
                return LegacyAction.IDLE;
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
        if (aligningForStage) {
            int headingIndex = firstRouteHeadingIndex();
            int fromIndex = Math.max(0, headingIndex - 1);
            float desiredYaw = desiredYawForEdge(
                    routeRows[fromIndex], routeColumns[fromIndex],
                    routeRows[headingIndex], routeColumns[headingIndex]);
            float yawError = normalise(desiredYaw - state.player.yaw);
            float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

            if (Math.abs(yawError) <= ALIGNMENT_TOLERANCE) {
                aligningForStage = false;
                System.out.println("[MonsterMazeAI/1.8] PAD ALIGNED"
                        + " stage=" + state.stage
                        + " tick=" + state.worldTick
                        + " heading=" + routeRows[headingIndex] + "," + routeColumns[headingIndex]
                        + " yaw=" + format(state.player.yaw)
                        + " desiredYaw=" + format(desiredYaw));
            } else {
                if (state.worldTick % 2L == 0L) {
                    System.out.println("[MonsterMazeAI/1.8] PAD ALIGN"
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
                && state.worldTick - lastKnockbackRecoveryTick >= KNOCKBACK_RECOVERY_COOLDOWN_TICKS) {
            knockbackRecoveryPending = true;
            lastKnockbackRecoveryTick = state.worldTick;
            System.out.println("[MonsterMazeAI/1.8] KNOCKBACK DETECTED"
                    + " tick=" + state.worldTick
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " motion=" + format(state.player.vx) + "," + format(state.player.vz));
        }

        if (knockbackRecoveryPending) {
            if (!state.player.grounded || Math.abs(state.player.y - state.center.y) > 1.50D) {
                return LegacyAction.IDLE;
            }
            knockbackRecoveryPending = false;
            routeLength = 0;
            routeIndex = 0;
            aligningForStage = false;
            if (beginRecovery(state)) {
                System.out.println("[MonsterMazeAI/1.8] KNOCKBACK REANCHOR"
                        + " tick=" + state.worldTick
                        + " player=" + format(state.player.x) + "," + format(state.player.z));
                return recoveryAction(state);
            }
            if (buildRoute(state)) {
                System.out.println("[MonsterMazeAI/1.8] KNOCKBACK REPLAN"
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
        if (!recovering && routePositionNeedsRecovery(state)) {
            if (beginRecovery(state)) {
                return recoveryAction(state);
            }
        }

        if (recovering) {
            if (isRecoveryComplete(state)) {
                recovering = false;
                recoveryRow = -1;
                recoveryColumn = -1;
                if (buildRoute(state)) {
                    System.out.println("[MonsterMazeAI/1.8] RECOVERY REANCHORED"
                            + " tick=" + state.worldTick
                            + " start=" + routeRows[0] + "," + routeColumns[0]
                            + " target=" + goalRow + "," + goalColumn);
                }
            }
            if (recovering) return recoveryAction(state);
        }

        if (routeIndex >= routeLength - 1) {
            return LegacyAction.IDLE;
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
        float yawError = normalise(desiredYaw - state.player.yaw);
        float yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);

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
            int oldLength = routeLength;
            int oldIndex = routeIndex;
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
                        System.out.println("[MonsterMazeAI/1.8] ROUTE REPLAN FAILED"
                                + " tick=" + state.worldTick
                                + " oldIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                                + " reason=" + (mobBlocked ? "dynamic-mob-block" : "physical-route")
                                + " action=RECOVER_TO_SAFE_CELL");
                    }
                    return recoveryAction(state);
                }
                if (mobBlocked) {
                    lastFailedMobReplanTick = state.worldTick;
                    if (state.worldTick % 5L == 0L) {
                        System.out.println("[MonsterMazeAI/1.8] MOB ROUTE BLOCKED"
                                + " tick=" + state.worldTick
                                + " routeIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                                + " action=RETRY_FROM_CURRENT_POSITION");
                    }
                } else if (state.worldTick % 5L == 0L) {
                    System.out.println("[MonsterMazeAI/1.8] PHYSICAL ROUTE REPLAN FAILED"
                            + " tick=" + state.worldTick
                            + " routeIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                            + " action=RETRY_FROM_CURRENT_POSITION");
                }
                return LegacyAction.IDLE;
            }
            lastFailedMobReplanTick = Long.MIN_VALUE;
            if (state.worldTick % 5L == 0L) {
                System.out.println("[MonsterMazeAI/1.8] MOB ROUTE REPLAN"
                        + " tick=" + state.worldTick
                        + " oldIndex=" + oldIndex + "/" + Math.max(0, oldLength - 1)
                        + " newLength=" + routeLength
                        + " newHeading=" + routeRows[Math.min(1, routeLength - 1)]
                        + "," + routeColumns[Math.min(1, routeLength - 1)]);
            }

            if (routeIndex >= routeLength - 1) {
                return LegacyAction.IDLE;
            }
            nextIndex = routeIndex + 1;
            targetIndex = safeLookaheadIndex();
            desiredYaw = desiredYawForEdge(
                    routeRows[routeIndex], routeColumns[routeIndex],
                    routeRows[nextIndex], routeColumns[nextIndex]);
            yawError = normalise(desiredYaw - state.player.yaw);
            yawDelta = clamp(yawError, -MAX_YAW_STEP, MAX_YAW_STEP);
        }

        /*
         * Hard movement safety invariant. During testing, large heading errors         * are resolved with stationary yaw only. Forward input is permitted
         * only when the player is aligned with the immediate route edge and
         * the forward vector agrees with that edge.
         */
        String safetyReason = movementSafetyFailureReason(state, targetIndex, desiredYaw, yawError);
        boolean safetyOk = safetyReason == null;
        if (!safetyOk || Math.abs(yawError) > MOVING_YAW_TOLERANCE) {
            headingStableTicks = 0;
            if (state.worldTick % 5L == 0L) {
                System.out.println("[MonsterMazeAI/1.8] MOVEMENT SAFETY HOLD"
                        + " tick=" + state.worldTick
                        + " routeIndex=" + routeIndex
                        + " next=" + routeRows[nextIndex] + "," + routeColumns[nextIndex]
                        + " target=" + routeRows[targetIndex] + "," + routeColumns[targetIndex]
                        + " pos=" + format(state.player.x) + "," + format(state.player.z)
                        + " yawError=" + format(yawError)
                        + " reason=" + (!safetyOk ? safetyReason : "heading"));
            }
            if (TEST_STATIONARY_TURNING && Math.abs(yawError) > MOVING_YAW_TOLERANCE) {
                return new LegacyAction(0.0f, 0.0f, false, false, yawDelta, false);
            }
            return LegacyAction.IDLE;
        }

        if (headingStableTicks < HEADING_STABLE_TICKS) {
            headingStableTicks++;
            return new LegacyAction(0.0f, 0.0f, false, false, 0.0f, false);
        }

        // Deliberately jump-spam: one tick pressed, one tick released.
        boolean jumpPulse = (state.worldTick & 1L) == 0L;

        if (state.worldTick % 10L == 0L) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN"
                    + " tick=" + state.worldTick
                    + " pos=" + format(state.player.x) + "," + format(state.player.z)
                    + " route=" + routeIndex + "/" + (routeLength - 1)
                    + " aim=" + targetIndex
                    + " next=" + routeRows[nextIndex] + "," + routeColumns[nextIndex]
                    + " target=" + routeRows[targetIndex] + "," + routeColumns[targetIndex]
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
        recovering = false;
        recoveryRow = -1;
        recoveryColumn = -1;
        lastRecoveryLogTick = Long.MIN_VALUE;
        previousPlayerX = Double.NaN;
        previousPlayerZ = Double.NaN;
        knockbackRecoveryPending = false;
        lastKnockbackRecoveryTick = Long.MIN_VALUE;
        routeStartsOnPreviousPad = false;
        previousPadSeedRow = -1;
        previousPadSeedColumn = -1;
        previousPadCenterRow = -1;
        previousPadCenterColumn = -1;
    }

    private boolean buildRoute(LegacyWorldObservation state) {
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

        int[] physicalStart = findNearestPhysicalStartCell(
                state, nominalStartRow, nominalStartColumn, standingOnPreviousPad);
        if (physicalStart == null) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + nominalStartRow + "," + nominalStartColumn
                    + " reason=no-physical-support-cell");
            return false;
        }
        int startRow = physicalStart[0];
        int startColumn = physicalStart[1];

        if (!state.physicalFloor[startRow][startColumn] && !standingOnPreviousPad) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn);
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

            goal = expandDynamicNeighbour(state, node, r - 1, c, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r + 1, c, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c - 1, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
            goal = expandDynamicNeighbour(state, node, r, c + 1, r, c,
                    targetRow, targetColumn, bestArrivalTicks, parent, open, goal);
        }

        if (goal < 0) {
            System.out.println("[MonsterMazeAI/1.8] FIRST_PAD_SPEEDRUN NO_ROUTE"
                    + " start=" + startRow + "," + startColumn
                    + " pad=" + targetRow + "," + targetColumn
                    + " reason=dynamic-mob-block");
            return false;
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

        boolean transitioningFromReachedPad = targetReached
                && !routeStartsOnPreviousPad;
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

        routeRows = newRouteRows;
        routeColumns = newRouteColumns;
        routeLength = count;
        routeIndex = 0;
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
        aligningForStage = transitioningFromReachedPad;

        if (startedAtTick == Long.MIN_VALUE) {
            startedAtTick = state.worldTick;
        }

        if (lastLoggedStage != state.stage || mobReplan) {
            System.out.println("[MonsterMazeAI/1.8] PAD ROUTE"
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
        if (!inBounds(r, c) || !state.physicalFloor[r][c]) {
            return currentGoal;
        }

        int next = index(r, c);
        double arrivalTicks = node.gTicks + ESTIMATED_TICKS_PER_CELL;

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

        for (int tick = 1; tick <= (int) Math.ceil(ESTIMATED_TICKS_PER_CELL); tick++) {
            double fraction = tick / ESTIMATED_TICKS_PER_CELL;
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
        return (Math.abs(row - targetRow) + Math.abs(column - targetColumn))
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
            if (i > routeIndex && Math.abs(routeRows[i] - routeRows[i - 1])
                    + Math.abs(routeColumns[i] - routeColumns[i - 1]) != 1) return true;
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
        previousPlayerX = state.player.x;
        previousPlayerZ = state.player.z;

        double horizontalSpeed = Math.hypot(state.player.vx, state.player.vz);
        double tickDisplacement = Math.hypot(dx, dz);
        return horizontalSpeed >= KNOCKBACK_HORIZONTAL_SPEED
                || tickDisplacement >= KNOCKBACK_TICK_DISPLACEMENT;
    }

    private boolean routePositionNeedsRecovery(LegacyWorldObservation state) {
        if (routeRows == null || routeLength <= 1 || routeIndex >= routeLength - 1) {
            return false;
        }

        if (routeSupportsFootprint(state, state.player.x, state.player.z,
                Math.min(routeIndex + 1, routeLength - 1))) {
            return false;
        }

        return true;
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
            System.out.println("[MonsterMazeAI/1.8] RECOVERY START"
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

    private String movementSafetyFailureReason(LegacyWorldObservation state,
                                                int targetIndex,
                                                float desiredYaw,
                                                float yawError) {
        if (routeIndex >= routeLength - 1) return "route-end";
        if (Math.abs(yawError) > MOVING_YAW_TOLERANCE) return "heading";
        if (Math.abs(state.player.y - state.center.y) > 1.50D) return "vertical";

        int nextIndex = routeIndex + 1;
        int nextRow = routeRows[nextIndex], nextColumn = routeColumns[nextIndex];
        if (!routeCellSupported(state, nextIndex)) return "next-floor";
        if (Math.abs(nextRow - routeRows[routeIndex]) + Math.abs(nextColumn - routeColumns[routeIndex]) != 1)
            return "route-disconnected";

        for (int i = routeIndex; i < targetIndex; i++) {
            int r1 = routeRows[i], c1 = routeColumns[i], r2 = routeRows[i + 1], c2 = routeColumns[i + 1];
            if (!routeCellSupported(state, i) || !routeCellSupported(state, i + 1)) return "lookahead-floor";
            if (Math.abs(r2 - r1) + Math.abs(c2 - c1) != 1) return "lookahead-disconnected";
        }

        int edgeRow = nextRow - routeRows[routeIndex], edgeColumn = nextColumn - routeColumns[routeIndex];
        double edgeX = edgeRow, edgeZ = edgeColumn;
        double edgeLength = Math.sqrt(edgeX * edgeX + edgeZ * edgeZ);
        edgeX /= edgeLength; edgeZ /= edgeLength;
        double rad = Math.toRadians(state.player.yaw);
        double forwardX = -Math.sin(rad), forwardZ = Math.cos(rad);
        if (forwardX * edgeX + forwardZ * edgeZ < EDGE_FORWARD_DOT_MIN) return "forward-vector";

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
         * Sweep the continuous player footprint through the short movement
         * envelope. This permits legitimate logical-cell boundary crossing
         * while preventing an endpoint-only check from skipping an unsupported
         * section of floor.
         */
        int lastSupportedRouteIndex = Math.min(targetIndex, routeLength - 1);
        for (double distance = SAFETY_SWEEP_STEP;
             distance <= SAFETY_PROBE_DISTANCE + 1.0E-9D;
             distance += SAFETY_SWEEP_STEP) {
            double probeX = state.player.x + forwardX * distance;
            double probeZ = state.player.z + forwardZ * distance;
            if (!routeSupportsFootprint(state, probeX, probeZ, lastSupportedRouteIndex)) {
                return "predicted-floor";
            }
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
    private boolean routeSupportsFootprint(LegacyWorldObservation state,
                                            double x, double z,
                                            int maxRouteIndex) {
        double minX = x - PLAYER_HALF_WIDTH;
        double maxX = x + PLAYER_HALF_WIDTH;
        double minZ = z - PLAYER_HALF_WIDTH;
        double maxZ = z + PLAYER_HALF_WIDTH;

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
            if (overlapX > 0.0D && overlapZ > 0.0D
                    && overlapX * overlapZ >= 0.05D) {
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
        return movementSafetyFailureReason(state, targetIndex, desiredYaw, yawError) == null;
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
        /* If the player is already materially closer to a later route waypoint,
           re-anchor the index before applying the normal edge-progress rule. */
        int nearest = findBestRouteIndexForCurrentPosition(state, routeRows, routeColumns, routeLength);
        if (nearest > routeIndex + 1) {
            routeIndex = nearest;
        }
        while (routeIndex < routeLength - 1) {
            double ax = worldX(routeRows[routeIndex], state.center.x);
            double az = worldZ(routeColumns[routeIndex], state.center.z);
            double bx = worldX(routeRows[routeIndex + 1], state.center.x);
            double bz = worldZ(routeColumns[routeIndex + 1], state.center.z);
            double ex = bx - ax, ez = bz - az;
            double lengthSquared = ex * ex + ez * ez;
            if (lengthSquared <= 1.0E-9D) { routeIndex++; continue; }

            double px = state.player.x - ax, pz = state.player.z - az;
            double progress = (px * ex + pz * ez) / lengthSquared;
            double distanceToNext = Math.hypot(state.player.x - bx, state.player.z - bz);
            if ((progress >= ROUTE_ADVANCE_PROGRESS || distanceToNext <= ROUTE_WAYPOINT_CAPTURE_RADIUS)
                    && routeEdgeHasPhysicalCapture(state, routeIndex + 1)) {
                routeIndex++;
            } else {
                break;
            }
        }
    }

    private boolean routeEdgeHasPhysicalCapture(LegacyWorldObservation state, int nextIndex) {
        if (nextIndex < 0 || nextIndex >= routeLength) return false;

        double x = worldX(routeRows[nextIndex], state.center.x);
        double z = worldZ(routeColumns[nextIndex], state.center.z);
        double dx = state.player.x - x;
        double dz = state.player.z - z;

        if (dx * dx + dz * dz <= ROUTE_WAYPOINT_CAPTURE_RADIUS * ROUTE_WAYPOINT_CAPTURE_RADIUS) {
            return true;
        }

        return physicalFloorSupportsFootprint(state, x, z);
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}