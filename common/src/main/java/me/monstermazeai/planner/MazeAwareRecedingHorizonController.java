package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.Action;

/**
 * Compatibility facade for the live movement layer.
 *
 * Live control now uses StableLiveMovementController. BeamSearchPlanner remains
 * available to the offline simulator/benchmark stack, but low-level live
 * movement must not choose between competing strafe/yaw controls every tick.
 */
public final class MazeAwareRecedingHorizonController {
    private final StableLiveMovementController stableMovement;
    private final int executionTicks;
    private String lastDecisionDetail = "UNSET";

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner, int executionTicks) {
        this(executionTicks);
    }

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,
                                               int executionTicks,
                                               double waypointTolerance) {
        this(executionTicks);
    }

    public MazeAwareRecedingHorizonController(int executionTicks) {
        if (executionTicks < 1) throw new IllegalArgumentException();
        this.executionTicks = executionTicks;
        this.stableMovement = new StableLiveMovementController();
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump) {
        if (state == null || goal == null) {
            lastDecisionDetail = "INVALID_INPUT";
            return new Action[]{Action.IDLE};
        }

        Action first = stableMovement.nextAction(state, goal, allowJump);
        lastDecisionDetail = stableMovement.lastDecisionDetail();

        /*
         * The live adapter consumes only the first action. Keep the public
         * multi-action API for existing callers, but never repeat a movement
         * command into the future: Minecraft state must be observed again
         * before the next motor command.
         */
        return new Action[]{first};
    }
}
