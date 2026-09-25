package me.monstermazeai.runtime;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.planner.RobustLiveController;

/**
 * Complete common-core autonomous agent boundary.
 */
public final class AutonomousMonsterMazeAgent {
    private final RobustLiveController controller;
    private long lastMazeSignature = Long.MIN_VALUE;
    private String lastDecisionDetail = "UNSET";

    public AutonomousMonsterMazeAgent(RobustLiveController controller) {
        if (controller == null) throw new IllegalArgumentException("controller");
        this.controller = controller;
    }

    public Action decide(GameState state, boolean allowJump) {
        if (state == null) {
            lastDecisionDetail = "NULL_STATE -> RESET";
            controller.reset();
            lastMazeSignature = Long.MIN_VALUE;
            return Action.IDLE;
        }

        if (!state.inMonsterMaze || !state.alive || state.completed) {
            lastDecisionDetail = "STATE_GATE inMaze=" + state.inMonsterMaze
                    + " alive=" + state.alive + " completed=" + state.completed;
            controller.reset();
            lastMazeSignature = Long.MIN_VALUE;
            return Action.IDLE;
        }

        long signature = mazeSignature(state);
        if (lastMazeSignature != Long.MIN_VALUE && signature != lastMazeSignature) {
            lastDecisionDetail = "MAZE_SIGNATURE_CHANGED old=" + lastMazeSignature + " new=" + signature;
            controller.reset();
        } else {
            lastDecisionDetail = "SIGNATURE_STABLE=" + signature;
        }
        lastMazeSignature = signature;

        Action action = controller.nextAction(state, allowJump);
        lastDecisionDetail += " controller=" + controller.lastDecisionDetail()
                + " output=" + describe(action);
        return action;
    }

    public String lastDecisionDetail() { return lastDecisionDetail; }

    public void reset() {
        controller.reset();
        lastMazeSignature = Long.MIN_VALUE;
        lastDecisionDetail = "RESET";
    }

    private long mazeSignature(GameState state) {
        long h = 1469598103934665603L;
        h = mix(h, state.mazePattern);
        h = mix(h, state.activePadRow);
        h = mix(h, state.activePadColumn);
        if (state.maze != null) {
            h = mix(h, MazeModel.SIZE);
            for (int r = 0; r < MazeModel.SIZE; r++) {
                for (int c = 0; c < MazeModel.SIZE; c++) {
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

    private static String describe(Action action) {
        return "f=" + action.forward() + ",s=" + action.strafe()
                + ",jump=" + action.jump() + ",sprint=" + action.sprint()
                + ",yawDelta=" + action.yawDelta()
                + ",ability=" + action.useAbility();
    }
}
