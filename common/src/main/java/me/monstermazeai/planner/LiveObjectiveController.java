package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.ability.AbilityDecision;
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
    /** SafePad is a symmetric 5x5 surface: any cell within +/-2 is a valid routing terminal. */
    private static final int SAFE_PAD_ROUTE_RADIUS = 2;

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

        /*
         * Do not latch completion from the observation boolean. The Minecraft
         * observer recomputes padReached each observation, but a phase
         * transition can replace the active pad while the previous completion
         * state is still present in an in-flight observation. Authoritative
         * geometry above is the only completion gate; the new active pad must
         * always become actionable immediately.
         */
        try {
            Action action = movement.nextActions(
                    state,
                    new Cell(state.activePadRow, state.activePadColumn),
                    allowJump,
                    SAFE_PAD_ROUTE_RADIUS)[0];
            lastDecisionReason = "MOVEMENT_PLANNER";
            lastDecisionDetail = movement.lastDecisionDetail()
                    + " | action=" + describe(action);

            if (AbilityDecision.shouldUse(state, lastDecisionReason, lastDecisionDetail,
                    movement.profile().tendencies)) {
                action = withAbility(action);
                lastDecisionDetail += " | ABILITY_USE";
            }
            return action;
        } catch (IllegalArgumentException noRoute) {
            lastDecisionReason = "NO_ROUTE";
            lastDecisionDetail = noRoute.getMessage() == null
                    ? "movement planner rejected route"
                    : noRoute.getMessage();
            if (AbilityDecision.shouldUse(state, lastDecisionReason, lastDecisionDetail)) {
                lastDecisionDetail += " | ABILITY_USE";
                return new Action(0.0, 0.0, false, false, 0.0F, true);
            }
            return Action.IDLE;
        }
    }

    private static Action withAbility(Action action) {
        if (action == null) return new Action(0.0, 0.0, false, false, 0.0F, true);
        return new Action(
                action.forward(),
                action.strafe(),
                action.jump(),
                action.sprint(),
                action.yawDelta(),
                true);
    }

    public me.monstermazeai.player.AiTendencies tendencies() {
        return movement.profile().tendencies;
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
