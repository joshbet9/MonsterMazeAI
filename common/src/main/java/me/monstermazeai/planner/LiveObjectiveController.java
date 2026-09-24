package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.Action;

/**
 * Live-game objective layer above the physical movement controller.
 *
 * The live adapter supplies the authoritative active Safe Pad. This layer
 * decides whether the AI should still pursue that objective before asking the
 * movement planner for a control. A newly promoted pad is therefore naturally
 * picked up on the next observation, while lobby/dead/completed/expired
 * objectives fail closed.
 */
public final class LiveObjectiveController {
    private final MazeAwareRecedingHorizonController movement;

    public LiveObjectiveController(MazeAwareRecedingHorizonController movement) {
        if (movement == null) throw new IllegalArgumentException("movement");
        this.movement = movement;
    }

    /**
     * Produce exactly one control for the current live objective.
     *
     * The active pad is the authoritative objective. We intentionally do not
     * predict a future/preview pad because the live 1.8 observation contract
     * only exposes the currently active Safe Pad.
     */
    public Action nextAction(GameState state, boolean allowJump) {
        if (!validObjective(state)) return Action.IDLE;

        if (PadModel.isOn(
                state.player,
                state.activePadRow + 0.5,
                GameState.PAD_SURFACE_Y,
                state.activePadColumn + 0.5)) {
            return Action.IDLE;
        }

        if (state.padReached) return Action.IDLE;

        try {
            return movement.nextActions(
                    state,
                    new Cell(state.activePadRow, state.activePadColumn),
                    allowJump)[0];
        } catch (IllegalArgumentException noRoute) {
            // An objective can become unreachable after live maze mutation.
            // Never turn a planning failure into uncontrolled movement.
            return Action.IDLE;
        }
    }

    private boolean validObjective(GameState state) {
        if (state == null
                || !state.inMonsterMaze
                || !state.alive
                || state.completed
                || state.maze == null
                || state.phaseTicksRemaining <= 0
                || state.activePadRow < 0
                || state.activePadColumn < 0
                || state.activePadRow >= MazeModel.SIZE
                || state.activePadColumn >= MazeModel.SIZE) {
            return false;
        }

        // The observer deliberately disables the active Safe Pad's 5x5 area
        // in the logical maze so pathfinding does not treat the temporary pad
        // replacement as a wall topology. The pad itself is still a valid
        // physical objective, so its raw cell must not be required to remain
        // traversable here.
        return true;
    }
}
