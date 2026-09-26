package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;

/**
 * Live-game objective layer above the physical movement controller.
 *
 * The source's phase timer is a deadline for the current Safe Pad, not a
 * validity flag for the live objective. In particular, zero can be observed
 * during the server's pad-transition tick, and an unavailable timer is also
 * represented separately as -1. The active pad itself is the authoritative
 * objective gate.
 */
public final class LiveObjectiveController {
    private final MazeAwareRecedingHorizonController movement;
    private String lastDecisionReason = "UNSET";
    private String lastDecisionDetail = "UNSET";

    public LiveObjectiveController(MazeAwareRecedingHorizonController movement) {
        if (movement == null) throw new IllegalArgumentException("movement");
        this.movement = movement;
    }

    public Action nextAction(GameState state, boolean allowJump) {
        if (!validObjective(state)) {
            lastDecisionReason = invalidReason(state);
            lastDecisionDetail = "objective invalid";
            return Action.IDLE;
        }

        boolean hasPreview = state.previewPadRow >= 0 && state.previewPadColumn >= 0;
        int goalRow = hasPreview ? state.previewPadRow : state.activePadRow;
        int goalColumn = hasPreview ? state.previewPadColumn : state.activePadColumn;

        // The source keeps the current pad as the survival checkpoint while a
        // next pad is built at phaseTimer == 2. Once the preview exists it is
        // the movement objective; remaining on the current pad would otherwise
        // make the bot stand still for almost the entire phase.
        if (!hasPreview && PadModel.isOn(state.player,
                state.activePadRow + 0.5, GameState.PAD_SURFACE_Y,
                state.activePadColumn + 0.5)) {
            Action evasive = prePreviewPadMovement(state, allowJump);
            lastDecisionReason = "PAD_HOLD_EVASION";
            lastDecisionDetail = "next Safe Pad not yet spawned; moving within current 5x5 pad to avoid stationary mob exposure | action=" + describe(evasive);
            return evasive;
        }

        if (state.padReached && hasPreview) {
            // Reaching the old pad is no longer an idle condition once the
            // source has revealed the next objective.
        }

        try {
            Action action = movement.nextActions(
                    state,
                    new Cell(goalRow, goalColumn),
                    allowJump)[0];
            lastDecisionReason = "MOVEMENT_PLANNER";
            lastDecisionDetail = movement.lastDecisionDetail()
                    + " | action=" + describe(action);
            return action;
        } catch (IllegalArgumentException noRoute) {
            lastDecisionReason = "NO_ROUTE";
            lastDecisionDetail = noRoute.getMessage() == null
                    ? "movement planner rejected route"
                    : noRoute.getMessage();
            return Action.IDLE;
        }
    }

    private Action prePreviewPadMovement(GameState state, boolean allowJump) {
        double cx = state.activePadRow + 0.5;
        double cz = state.activePadColumn + 0.5;

        // Stay inside the source's symmetric 5x5 pad while the next beacon is
        // unavailable. Prefer steering away from the nearest active monster;
        // otherwise make a small deterministic patrol around the pad centre.
        double targetX = cx;
        double targetZ = cz;
        double nearest = Double.POSITIVE_INFINITY;
        for (var monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick) || monster.frozen(state.tick)) continue;
            double dx = monster.x - state.player.x;
            double dz = monster.z - state.player.z;
            double d = Math.hypot(dx, dz);
            if (d < nearest) {
                nearest = d;
                if (d > 1.0E-6) {
                    targetX = state.player.x - dx / d * 1.25;
                    targetZ = state.player.z - dz / d * 1.25;
                }
            }
        }

        double rx = state.player.x - cx;
        double rz = state.player.z - cz;
        if (Math.abs(rx) > 1.8 || Math.abs(rz) > 1.8) {
            targetX = cx - rx * 0.75;
            targetZ = cz - rz * 0.75;
        } else if (nearest == Double.POSITIVE_INFINITY) {
            // A small deterministic orbit prevents a stationary target without
            // committing the player to an unknown future route.
            double yaw = Math.toRadians(state.player.yaw + 90.0);
            targetX = state.player.x + Math.cos(yaw) * 0.8;
            targetZ = state.player.z + Math.sin(yaw) * 0.8;
        }

        double dx = targetX - state.player.x;
        double dz = targetZ - state.player.z;
        if (Math.hypot(dx, dz) < 0.05) {
            targetX = cx;
            targetZ = cz;
            dx = targetX - state.player.x;
            dz = targetZ - state.player.z;
        }
        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double error = desiredYaw - state.player.yaw;
        while (error >= 180.0) error -= 360.0;
        while (error < -180.0) error += 360.0;
        double local = Math.toRadians(error);
        int forward = localForward(Math.cos(local));
        int strafe = localForward(Math.sin(local));
        if (forward == 0 && strafe == 0) forward = 1;
        return new Action(forward, strafe, false, true,
                (float)Math.max(-18.0, Math.min(18.0, error)), false);
    }

    private int localForward(double value) {
        if (value > 0.25) return 1;
        if (value < -0.25) return -1;
        return 0;
    }

    public String lastDecisionReason() { return lastDecisionReason; }
    public String lastDecisionDetail() { return lastDecisionDetail; }

    private String invalidReason(GameState state) {
        if (state == null) return "NULL_STATE";
        if (!state.inMonsterMaze) return "NOT_IN_MAZE";
        if (!state.alive) return "DEAD";
        if (state.completed) return "COMPLETED";
        if (state.maze == null) return "NO_MAZE";
        if (state.activePadRow < 0 || state.activePadColumn < 0
                || state.activePadRow >= MazeModel.SIZE
                || state.activePadColumn >= MazeModel.SIZE) return "INVALID_PAD";
        return "INVALID_OBJECTIVE";
    }

    private boolean validObjective(GameState state) {
        return state != null
                && state.inMonsterMaze
                && state.alive
                && !state.completed
                && state.maze != null
                && state.activePadRow >= 0
                && state.activePadColumn >= 0
                && state.activePadRow < MazeModel.SIZE
                && state.activePadColumn < MazeModel.SIZE;
    }

    private static String describe(Action action) {
        if (action == null) return "null";
        return "f=" + action.forward()
                + ",s=" + action.strafe()
                + ",jump=" + action.jump()
                + ",sprint=" + action.sprint()
                + ",yawDelta=" + action.yawDelta()
                + ",ability=" + action.useAbility();
    }
}
