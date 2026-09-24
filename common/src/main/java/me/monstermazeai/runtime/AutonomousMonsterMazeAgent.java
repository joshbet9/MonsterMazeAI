package me.monstermazeai.runtime;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.planner.RobustLiveController;

/**
 * Complete common-core autonomous agent boundary.
 *
 * Every live observation is converted into one fresh decision by the
 * persistent robust controller. Match/lobby transitions reset controller
 * state, and invalid observations fail closed to IDLE. No Minecraft APIs or
 * cached multi-tick commands cross this boundary.
 */
public final class AutonomousMonsterMazeAgent {
    private final RobustLiveController controller;
    private long lastMazeSignature = Long.MIN_VALUE;

    public AutonomousMonsterMazeAgent(RobustLiveController controller) {
        if (controller == null) throw new IllegalArgumentException("controller");
        this.controller = controller;
    }

    /**
     * Decide exactly one action from the newest observed state.
     *
     * A maze signature change resets movement history so stale stuck detection
     * from a previous match/layout cannot influence the new match.
     */
    public Action decide(GameState state, boolean allowJump) {
        if (state == null) {
            controller.reset();
            lastMazeSignature = Long.MIN_VALUE;
            return Action.IDLE;
        }

        if (!state.inMonsterMaze || !state.alive || state.completed) {
            controller.reset();
            lastMazeSignature = Long.MIN_VALUE;
            return Action.IDLE;
        }

        long signature = mazeSignature(state);
        if (lastMazeSignature != Long.MIN_VALUE && signature != lastMazeSignature) {
            controller.reset();
        }
        lastMazeSignature = signature;

        return controller.nextAction(state, allowJump);
    }

    public void reset() {
        controller.reset();
        lastMazeSignature = Long.MIN_VALUE;
    }

    private long mazeSignature(GameState state) {
        long h = 1469598103934665603L;
        h = mix(h, state.mazePattern);
        h = mix(h, state.activePadRow);
        h = mix(h, state.activePadColumn);
        if (state.maze != null) {
            h = mix(h, MazeModel.SIZE);
            for (int r = 0; r < state.maze.size(); r++) {
                for (int c = 0; c < state.maze.size(); c++) {
                    h = mix(h, state.maze.raw(r, c));
                }
            }
        }
        return h;
    }

    private long mix(long h, long value) {
        h ^= value;
        return h * 1099511628211L;
    }
}
