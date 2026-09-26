package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;

/**
 * Live-game objective layer above the physical movement controller.
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

        if (PadModel.isOn(state.player, state.activePadRow + 0.5,
                GameState.PAD_SURFACE_Y, state.activePadColumn + 0.5)) {
            lastDecisionReason = "ON_PAD";
            lastDecisionDetail = "player is geometrically on active pad";
            return Action.IDLE;
        }

        if (state.padReached) {
            lastDecisionReason = "PAD_REACHED";
            lastDecisionDetail = "observation says active pad is reached";
            return Action.IDLE;
        }

        try {
            Action action = movement.nextActions(
                    state,
                    new Cell(state.activePadRow, state.activePadColumn),
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

    public String lastDecisionReason() { return lastDecisionReason; }
    public String lastDecisionDetail() { return lastDecisionDetail; }

    private String invalidReason(GameState state) {
        if (state == null) return "NULL_STATE";
        if (!state.inMonsterMaze) return "NOT_IN_MAZE";
        if (!state.alive) return "DEAD";
        if (state.completed) return "COMPLETED";
        if (state.maze == null) return "NO_MAZE";
        if (state.phaseTicksRemaining == 0) return "NO_PHASE_TIME";
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
                && state.phaseTicksRemaining != 0
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
