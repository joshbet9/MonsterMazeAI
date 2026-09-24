package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.Action;

/**
 * One-tick closed-loop bridge from the live world model to the movement
 * planner.
 *
 * The controller deliberately does not retain a multi-tick action plan. Each
 * observed GameState is planned from scratch and only the first action is
 * returned. This makes the live adapter authoritative: movement, monster
 * motion, damage, timer changes and pad changes are observed again before the
 * next decision.
 */
public final class LiveTickController {
    private final MazeAwareRecedingHorizonController controller;

    public LiveTickController(MazeAwareRecedingHorizonController controller) {
        if (controller == null) throw new IllegalArgumentException("controller");
        this.controller = controller;
    }

    /**
     * Decide the control for exactly one Minecraft tick.
     *
     * A live round has no meaningful movement target until an active Safe Pad
     * is observed. Invalid/lobby/dead/completed states fail closed to IDLE.
     */
    public Action nextAction(GameState state, boolean allowJump) {
        if (state == null
                || !state.inMonsterMaze
                || !state.alive
                || state.completed
                || state.maze == null
                || state.activePadRow < 0
                || state.activePadColumn < 0
                || state.padReached) {
            return Action.IDLE;
        }

        if (state.activePadRow >= me.monstermazeai.maze.MazeModel.SIZE
                || state.activePadColumn >= me.monstermazeai.maze.MazeModel.SIZE) {
            return Action.IDLE;
        }

        Action[] actions = controller.nextActions(
                state,
                new Cell(state.activePadRow, state.activePadColumn),
                allowJump);

        return actions.length == 0 ? Action.IDLE : actions[0];
    }
}
