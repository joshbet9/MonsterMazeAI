package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.AiProfile;

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
        this(executionTicks, AiProfile.BASELINE);
    }

    public MazeAwareRecedingHorizonController(int executionTicks, AiProfile profile) {
        if (executionTicks < 1) throw new IllegalArgumentException();
        if (profile == null) throw new IllegalArgumentException("profile");
        this.executionTicks = executionTicks;
        this.stableMovement = new StableLiveMovementController(profile);
    }

    public String lastDecisionDetail() {
        return lastDecisionDetail;
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump) {
        return nextActions(state, goal, allowJump, 0);
    }

    public Action[] nextActions(GameState state, Cell goal, boolean allowJump, int regionRadius) {
        if (state == null || goal == null) {
            lastDecisionDetail = "INVALID_INPUT";
            return new Action[]{Action.IDLE};
        }

        Action first = stableMovement.nextAction(state, goal, allowJump, regionRadius);
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
